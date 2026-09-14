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
 * Feature layout for the stress-correction model — see [LtsModel]'s "Stress" section below.
 *
 * Unlike [FeatureLayout], this does not describe a sliding window over letters. It describes one
 * *vowel occurrence* in an already-decoded word: the vowel's own identity, where it sits among
 * the word's other vowels, and the word's own trailing letters. Position and suffix are exactly
 * the two things the per-letter model structurally cannot see — it only ever looks a fixed few
 * letters either side — and they are most of what decides English stress: penultimate/final
 * position, and morphology (`-ity`, `-ic`, `-tion` pull stress to a specific syllable regardless
 * of what the rest of the word looks like).
 */
class StressFeatureLayout(val suffixLength: Int, val positionCap: Int, val valueSpace: Int) {
    val featureCount: Int = FIXED_FEATURES + suffixLength

    /** Feature slot for the letter this many places from the end of the word (0 = last letter). */
    fun suffixSlot(offsetFromEnd: Int): Int = FIXED_FEATURES + offsetFromEnd

    companion object {
        /** The vowel's own identity; its index from the start and end among vowels; how many
         * vowels the word has in total; and the digit the letter forest already put there. */
        const val FIXED_FEATURES = 5
        const val VOWEL_FEATURE = 0
        const val INDEX_FROM_START = 1
        const val INDEX_FROM_END = 2
        const val TOTAL_VOWELS = 3

        /**
         * The stress digit already on this vowel before correction runs — the letter forest's own
         * guess, made from purely local context. Without this feature the correction forest
         * measurably made things *worse* (word-with-stress accuracy 58.4% to 55.3%) despite
         * scoring 87.8% per vowel in isolation given true phonemes: the letter forest's guess
         * turned out to already encode real signal (local phonotactic patterns a global,
         * suffix-and-position-only model has no access to), and discarding it lost more than the
         * global features won. Trained without leakage — see `Train.kt`'s bootstrap step, which
         * sources this feature from the letter forest's own predictions on training words, never
         * from the ground truth the label comes from.
         */
        const val PRIOR_STRESS_GUESS = 4
    }
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
 * ### Stress
 *
 * The per-letter forest gets the phonemes themselves mostly right but stress noticeably less
 * often — of words whose phonemes are entirely correct, close to one in seven still misplaces a
 * stress mark. That gap is structural, not a matter of more trees: every tree answers "what does
 * *this* letter say" from a fixed few letters of context, and English stress is a property of the
 * *whole word* (how many syllables it has, which one is last, what it ends in) that no local
 * window can see.
 *
 * So stress is corrected in a second pass, by an entirely separate, much smaller forest
 * ([StressFeatureLayout]) that runs once per vowel *after* the letter forest has decoded the
 * whole word. It only ever changes the stress digit already on a vowel phoneme — never which
 * phoneme it is — using exactly the features the letter forest lacks: the vowel's position among
 * the word's other vowels, and the word's own trailing letters.
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
 * stressSuffix     varint, [StressFeatureLayout.suffixLength]
 * stressPosCap     varint, [StressFeatureLayout.positionCap]
 * stressValueSpace varint, this section's own packing stride (independent of the letter
 *                  forest's — the two never share a node table)
 * stressRoots      varint count, then that many varints: a stress tree's node index plus one,
 *                  or 0 for a missing tree (never happens in practice, but the encoding allows it)
 * stressNodes      varint count, then that many nodes, same two-varint pre-order shape as
 *                  `nodes` above, except a leaf's second varint is not a label index — it *is*
 *                  the stress digit (0, 1 or 2) — there is no separate label table for this
 *                  section, since three digits need no interning
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
    /** Bare-phoneme name to id, the reverse of [phonemeBoundary]'s vocabulary — needed to turn a
     * decoded vowel's own identity back into the feature the stress model was trained on. */
    private val phonemeIds: Map<String, Int>,
    private val roots: IntArray,
    /**
     * Each node's question, or [LEAF]. Held as shorts rather than ints purely for memory: a tag
     * is at most `featureCount * valueSpace`, a few hundred, and at ensemble sizes worth shipping
     * there are over a million nodes, so the two bytes saved on each is several megabytes of
     * phone heap.
     */
    private val nodeTag: ShortArray,
    private val nodePayload: IntArray,
    private val stressLayout: StressFeatureLayout,
    private val stressRoots: IntArray,
    private val stressNodeTag: ShortArray,
    private val stressNodePayload: IntArray,
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
        return correctStress(result, ids)
    }

    /**
     * Overrides the stress digit on every vowel phoneme in [phonemes] using the stress forest,
     * leaving which phoneme it is untouched. [letterIds] is the same array [predict] already
     * built for the word, reused here for the suffix features.
     */
    private fun correctStress(phonemes: List<Phoneme>, letterIds: IntArray): List<Phoneme> {
        if (stressRoots.isEmpty()) return phonemes
        val vowelPositions = phonemes.indices.filter { phonemes[it].stress != null }
        if (vowelPositions.isEmpty()) return phonemes

        val total = vowelPositions.size
        val features = IntArray(stressLayout.featureCount)
        val corrected = phonemes.toMutableList()
        vowelPositions.forEachIndexed { index, position ->
            val phoneme = phonemes[position]
            features[StressFeatureLayout.VOWEL_FEATURE] = phonemeIds[phoneme.base] ?: phonemeBoundary
            features[StressFeatureLayout.INDEX_FROM_START] = minOf(index, stressLayout.positionCap)
            features[StressFeatureLayout.INDEX_FROM_END] = minOf(total - 1 - index, stressLayout.positionCap)
            features[StressFeatureLayout.TOTAL_VOWELS] = minOf(total, stressLayout.positionCap)
            // The letter forest's own guess for this vowel, before it gets overwritten below —
            // see StressFeatureLayout.PRIOR_STRESS_GUESS for why this feature exists at all.
            features[StressFeatureLayout.PRIOR_STRESS_GUESS] = phoneme.stress ?: 0
            for (slot in 0 until stressLayout.suffixLength) {
                val letterIndex = letterIds.size - 1 - slot
                features[stressLayout.suffixSlot(slot)] =
                    if (letterIndex in letterIds.indices) letterIds[letterIndex] else letterBoundary
            }
            corrected[position] = Phoneme(phoneme.base + voteStress(features))
        }
        return corrected
    }

    private fun voteStress(features: IntArray): Int {
        val votes = IntArray(3)
        for (root in stressRoots) {
            if (root == NO_TREE) continue
            votes[classifyByFeatures(root, features, stressNodeTag, stressNodePayload, stressLayout.valueSpace)]++
        }
        var winner = 0
        for (digit in 1..2) if (votes[digit] > votes[winner]) winner = digit
        return winner
    }

    /** Walks a tree whose questions read directly from a precomputed feature vector, rather than
     * from a sliding window over a word — the shape [stressRoots]' trees are, unlike [roots]'. */
    private fun classifyByFeatures(root: Int, features: IntArray, tag: ShortArray, payload: IntArray, valueSpace: Int): Int {
        var node = root
        while (true) {
            val t = tag[node].toInt()
            if (t == LEAF) return payload[node]
            val question = t - 1
            node = if (features[question / valueSpace] == question % valueSpace) node + 1 else payload[node]
        }
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

            val stressLayout = StressFeatureLayout(
                suffixLength = cursor.varint(),
                positionCap = cursor.varint(),
                valueSpace = cursor.varint(),
            )
            val stressRoots = IntArray(cursor.varint()) { cursor.varint() - 1 }
            val stressNodeCount = cursor.varint()
            val stressNodeTag = ShortArray(stressNodeCount)
            val stressNodePayload = IntArray(stressNodeCount)
            for (node in 0 until stressNodeCount) {
                val tag = cursor.varint()
                require(tag <= Short.MAX_VALUE) { "stress tag $tag does not fit the node table" }
                stressNodeTag[node] = tag.toShort()
                stressNodePayload[node] = if (tag == LEAF) cursor.varint() else node + cursor.varint()
            }

            return LtsModel(
                alphabet, ensembleSize, layout, labels, labelHistoryIds, phonemeCount, phonemeIds,
                roots, nodeTag, nodePayload,
                stressLayout, stressRoots, stressNodeTag, stressNodePayload,
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
