package io.github.proairface.kotling2p

import java.io.InputStream

/**
 * Where each of a tree's features comes from, shared by the trainer and the decoder so the two
 * cannot disagree about what "feature 7" means.
 *
 * Features are one flat list of two blocks: the letters around the one being pronounced (from
 * furthest left to furthest right, skipping the letter itself), then the phonemes most recently
 * emitted to its left (most recent first). Both index into the same [valueSpace] so a tree node
 * needs only a single number to identify the question it asks.
 */
class FeatureLayout(val contextRadius: Int, val phonemeHistory: Int, val valueSpace: Int) {
    val letterFeatures: Int = contextRadius * 2
    val featureCount: Int = letterFeatures + phonemeHistory

    fun isLetterFeature(feature: Int): Boolean = feature < letterFeatures

    /** Position of a letter feature relative to the letter being pronounced; never zero. */
    fun letterOffset(feature: Int): Int =
        if (feature < contextRadius) feature - contextRadius else feature - contextRadius + 1

    /** How far back a phoneme-history feature looks; 0 is the phoneme just emitted. */
    fun historySlot(feature: Int): Int = feature - letterFeatures
}

/**
 * The trained letter-to-sound model: guesses a pronunciation for a word [CmuDict] doesn't have.
 *
 * That case is not an edge case for this library's intended use. A routing app reads out street
 * names, and street names are overwhelmingly proper nouns — exactly the words a fixed dictionary
 * is worst at. So the quality of *this* path, not the dictionary's coverage, is what a listener
 * actually notices.
 *
 * The model is a small forest of decision trees per letter, trained offline on CMUdict by the
 * `trainer` source set (`./gradlew trainLts`) and shipped as a compact binary resource.
 * Each tree predicts the phonemes one letter is responsible for, given the letters around it and
 * the phonemes already chosen to its left.
 *
 * Two things follow from that shape. Conditioning on its own output makes this a left-to-right
 * sequence model, so taking each letter's best answer in turn is not the same as finding the best
 * whole word — hence the beam search in [predict]. And having several trees vote turns a bare
 * answer into a distribution over answers, which is what gives that search anything to choose
 * between: where the evidence is thin the trees disagree, and the disagreement is the confidence
 * estimate a single tree grown to pure leaves can never produce.
 *
 * See the trainer's `Aligner` and `TreeTrainer` for how it is built, and `LtsModelBenchmarkTest`
 * for what it scores on words it was never trained on.
 *
 * ### File format
 *
 * Every number is an unsigned LEB128 varint, and every count comes before the thing it counts.
 *
 * ```
 * magic            4 bytes, "KG2P"
 * version          varint, currently 1
 * ensembleSize     varint, trees per letter
 * contextRadius    varint, letters of context each side
 * phonemeHistory   varint, how many already-emitted phonemes are visible
 * valueSpace       varint, the stride used to pack (feature, value) into one number
 * alphabet         varint length, then that many ASCII bytes (the letters, sorted)
 * phonemes         varint count, then each as varint length + ASCII: the bare ARPAbet symbols
 *                  used by the history features, whose id equals their index; the count itself
 *                  is the id meaning "nothing emitted yet"
 * labels           varint count, then each as varint length + ASCII: space-separated ARPAbet,
 *                  empty for a silent letter
 * roots            ensembleSize varints per alphabet letter, that letter's trees together: each
 *                  a node index plus one, or 0 for a letter with no tree
 * nodes            varint count, then that many nodes in pre-order, each two varints:
 *                    tag 0        a leaf; the second varint is its label index
 *                    tag non-zero a branch asking whether feature (tag-1)/valueSpace currently
 *                                 equals (tag-1)%valueSpace; the second varint is the distance
 *                                 forward to its "no" child
 *                  a branch's "yes" child is always the node immediately after it, which is why
 *                  only one child index is stored.
 * ```
 */
class LtsModel private constructor(
    private val alphabet: String,
    private val ensembleSize: Int,
    private val layout: FeatureLayout,
    private val labels: Array<List<Phoneme>>,
    /** Per label, the history ids of the phonemes it emits, so decoding needs no string work. */
    private val labelHistoryIds: Array<IntArray>,
    private val phonemeBoundary: Int,
    private val roots: IntArray,
    /**
     * Each node's question, or [LEAF]. Held as shorts rather than ints purely for memory: a tag
     * is at most `featureCount * valueSpace`, a few hundred, and at ensemble sizes worth shipping
     * there are over a million nodes, so the two bytes saved on each is several megabytes of
     * phone heap.
     */
    private val nodeTag: ShortArray,
    private val nodePayload: IntArray,
) {
    /** One past the last real letter id — the value a letter feature takes outside the word. */
    private val letterBoundary = alphabet.length

    /** Direct char-to-id lookup, so decoding never scans [alphabet]. -1 means "not a letter". */
    private val letterIds = IntArray(128) { -1 }.also {
        alphabet.forEachIndexed { id, char -> it[char.code] = id }
    }

    /**
     * Phonemes for [word], which is expected to be a single already-normalized word.
     *
     * Keeps [beamWidth] candidate pronunciations alive across the word rather than committing to
     * each letter's most popular answer as it goes. Because every tree can see the phonemes
     * chosen to its left, an early choice changes the questions later letters are asked, and the
     * locally best first choice is regularly not part of the best whole word.
     */
    fun predict(word: String, beamWidth: Int = DEFAULT_BEAM_WIDTH): List<Phoneme> {
        val letters = word.lowercase()
        val ids = IntArray(letters.length) {
            val code = letters[it].code
            if (code < 128) letterIds[code] else -1
        }

        var beam = listOf(Hypothesis(0.0, IntArray(0), IntArray(layout.phonemeHistory) { phonemeBoundary }))
        val votes = HashMap<Int, Int>()
        for (position in ids.indices) {
            val letter = ids[position]
            if (letter < 0) continue
            val first = letter * ensembleSize
            if (roots[first] == NO_TREE) continue

            val extended = ArrayList<Hypothesis>(beam.size * ensembleSize)
            for (hypothesis in beam) {
                votes.clear()
                var cast = 0
                for (tree in first until first + ensembleSize) {
                    if (roots[tree] == NO_TREE) continue
                    val label = classify(roots[tree], ids, position, hypothesis.history)
                    votes[label] = (votes[label] ?: 0) + 1
                    cast++
                }
                for ((label, count) in votes) {
                    val cost = -Math.log(count.toDouble() / cast)
                    extended += hypothesis.extend(label, cost, labelHistoryIds[label])
                }
            }
            beam = if (extended.size <= beamWidth) {
                extended
            } else {
                extended.sortedBy { it.cost }.subList(0, beamWidth)
            }
        }

        val best = beam.minByOrNull { it.cost } ?: return emptyList()
        val result = ArrayList<Phoneme>(best.labels.size + 2)
        for (label in best.labels) result += labels[label]
        return result
    }

    /** One candidate pronunciation of the word so far. [cost] is additive; lower is better. */
    private class Hypothesis(val cost: Double, val labels: IntArray, val history: IntArray) {
        fun extend(label: Int, addedCost: Double, emitted: IntArray): Hypothesis {
            val nextLabels = labels.copyOf(labels.size + 1)
            nextLabels[labels.size] = label
            val nextHistory = history.copyOf()
            if (nextHistory.isNotEmpty()) {
                for (id in emitted) {
                    for (slot in nextHistory.size - 1 downTo 1) nextHistory[slot] = nextHistory[slot - 1]
                    nextHistory[0] = id
                }
            }
            return Hypothesis(cost + addedCost, nextLabels, nextHistory)
        }
    }

    private fun classify(root: Int, ids: IntArray, position: Int, history: IntArray): Int {
        var node = root
        while (true) {
            val tag = nodeTag[node].toInt()
            if (tag == LEAF) return nodePayload[node]
            val question = tag - 1
            val feature = question / layout.valueSpace
            val current = if (layout.isLetterFeature(feature)) {
                letterAt(ids, position + layout.letterOffset(feature))
            } else {
                history[layout.historySlot(feature)]
            }
            node = if (current == question % layout.valueSpace) node + 1 else nodePayload[node]
        }
    }

    private fun letterAt(ids: IntArray, index: Int): Int {
        if (index < 0 || index >= ids.size) return letterBoundary
        val id = ids[index]
        return if (id < 0) letterBoundary else id
    }

    companion object {
        /**
         * Wide enough that widening it further stopped changing the held-out score, and narrow
         * enough that a word still decodes in microseconds.
         */
        const val DEFAULT_BEAM_WIDTH = 8

        private const val RESOURCE_PATH = "lts/model.bin"
        private const val LEAF = 0
        private const val NO_TREE = -1

        fun loadBundled(): LtsModel {
            val stream = LtsModel::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("$RESOURCE_PATH not found on the classpath")
            return stream.use { read(it) }
        }

        fun read(stream: InputStream): LtsModel {
            val bytes = stream.readBytes()
            require(bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "KG2P") {
                "not a KotlinG2P letter-to-sound model"
            }
            val cursor = Cursor(bytes)
            cursor.position = 4
            val version = cursor.varint()
            require(version == 1) { "unsupported model version $version" }

            val ensembleSize = cursor.varint()
            val layout = FeatureLayout(
                contextRadius = cursor.varint(),
                phonemeHistory = cursor.varint(),
                valueSpace = cursor.varint(),
            )
            val alphabet = cursor.ascii()
            val phonemeIds = HashMap<String, Int>()
            val phonemeCount = cursor.varint()
            for (id in 0 until phonemeCount) phonemeIds[cursor.ascii()] = id

            val labels = Array(cursor.varint()) {
                cursor.ascii().let { name ->
                    if (name.isEmpty()) emptyList() else name.split(' ').map(::Phoneme)
                }
            }
            val labelHistoryIds = Array(labels.size) { index ->
                IntArray(labels[index].size) { phoneme ->
                    phonemeIds[labels[index][phoneme].base] ?: phonemeCount
                }
            }

            // Stored one-based so that zero can mean "this letter never occurred in training".
            val roots = IntArray(alphabet.length * ensembleSize) { cursor.varint() - 1 }
            val nodeCount = cursor.varint()
            val nodeTag = ShortArray(nodeCount)
            val nodePayload = IntArray(nodeCount)
            for (node in 0 until nodeCount) {
                val tag = cursor.varint()
                require(tag <= Short.MAX_VALUE) { "tag $tag does not fit the node table" }
                nodeTag[node] = tag.toShort()
                // A leaf's second field is its label; a branch's is the distance to its "no"
                // child, a delta because pre-order guarantees the child comes later.
                nodePayload[node] = if (tag == LEAF) cursor.varint() else node + cursor.varint()
            }
            return LtsModel(
                alphabet, ensembleSize, layout, labels, labelHistoryIds, phonemeCount,
                roots, nodeTag, nodePayload,
            )
        }
    }

    private class Cursor(private val bytes: ByteArray) {
        var position = 0

        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = bytes[position++].toInt()
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun ascii(): String {
            val length = varint()
            val text = String(bytes, position, length, Charsets.US_ASCII)
            position += length
            return text
        }
    }
}
