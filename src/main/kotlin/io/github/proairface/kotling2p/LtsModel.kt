package io.github.proairface.kotling2p

import kotlin.math.ln

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
        val votes = VoteTally()
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
                    votes.add(label)
                    cast++
                }
                votes.forEachInOrder { label, count ->
                    val cost = VoteCosts.cost(count, cast)
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
            return read(BundledResources.bytes(RESOURCE_PATH))
        }

        /** A model from its serialized bytes. A stream overload for the JVM is in JvmStreams.kt. */
        fun read(bytes: ByteArray): LtsModel {
            require(bytes.size > 4 && bytes.decodeToString(0, 4) == "KG2P") {
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
            // ASCII by construction (the trainer writes only ASCII here), so UTF-8 decoding
            // reads it identically and also compiles for JavaScript.
            val text = bytes.decodeToString(position, position + length)
            position += length
            return text
        }
    }
}

/**
 * Per-letter vote counts, visited in **exactly the order `java.util.HashMap<Int, Int>` visits
 * them**, on every platform.
 *
 * Why that matters: two candidate phonemes with the same number of votes get the same cost, the
 * beam sort is stable, so whichever is visited first wins the tie. This used to be a plain
 * `HashMap`, whose visiting order is an implementation detail: the JavaScript build (Detour's web
 * version) visited ties in a different order and pronounced 6 of 6010 test inputs differently from
 * the JVM. Reproducing the JDK's order, rather than picking a new one, keeps the JVM output
 * byte-identical to every earlier release.
 *
 * The JDK order for small non-negative Int keys: buckets in ascending `key and (capacity - 1)`
 * (an Int's hash is itself, and the high-bit spreading is a no-op below 65536), insertion order
 * within a bucket (resizing splits buckets without reordering them). Capacity starts at 16 and
 * doubles whenever the entry count passes 3/4 of it; `clear()` keeps the grown table, so it is
 * tracked for the whole word, like the map it replaces, which [predict] created once per word.
 */
internal class VoteTally {
    private val labels = ArrayList<Int>()
    private val counts = ArrayList<Int>()
    private var capacity = 16

    fun clear() {
        labels.clear()
        counts.clear()
    }

    fun add(label: Int) {
        require(label in 0..0xFFFF) { "label $label outside the range the JDK-order emulation covers" }
        val index = labels.indexOf(label)
        if (index >= 0) {
            counts[index] = counts[index] + 1
            return
        }
        labels += label
        counts += 1
        if (labels.size > capacity * 3 / 4) capacity *= 2
    }

    inline fun forEachInOrder(action: (label: Int, count: Int) -> Unit) {
        val mask = capacity - 1
        // sortedBy is stable, so insertion order survives within a bucket.
        for (i in labels.indices.sortedBy { labels[it] and mask }) action(labels[i], counts[i])
    }
}

/**
 * `-ln(count / cast)` for every vote split an ensemble of up to [MAX_TABLE_CAST] trees can
 * produce, as exact bit patterns.
 *
 * `ln` is not bit-identical across platforms: for 31 of these 528 values the JavaScript engine's
 * logarithm differs from the desktop JVM's in the last bit, and Android's runtime has its own
 * again. Costs built from those values then break ties differently in the beam, and a word comes
 * out pronounced differently (2 of 40,000 test words did, in the JavaScript build). These are the
 * values the desktop JVM's `Math.log` gives, the ones every model in this repo was trained and
 * measured with, so the JVM output stays byte-identical to earlier releases and every other
 * platform now matches it exactly. Generated by computing `-Math.log(c.toDouble() / n)` on
 * HotSpot for 1 <= c <= n <= 32 and writing `doubleToRawLongBits`.
 */
internal object VoteCosts {
    const val MAX_TABLE_CAST = 32

    private val bits = longArrayOf(
        // cast = 1
        0x8000000000000000UL.toLong(),
        // cast = 2
        0x3fe62e42fefa39efL, 0x8000000000000000UL.toLong(),
        // cast = 3
        0x3ff193ea7aad030bL, 0x3fd9f323ecbf984dL, 0x8000000000000000UL.toLong(),
        // cast = 4
        0x3ff62e42fefa39efL, 0x3fe62e42fefa39efL, 0x3fd269621134db92L, 0x8000000000000000UL.toLong(),
        // cast = 5
        0x3ff9c041f7ed8d33L, 0x3fed5240f0e0e077L, 0x3fe058aefa811452L, 0x3fcc8ff7c79a9a20L, 0x8000000000000000UL.toLong(),
        // cast = 6
        0x3ffcab0bfa2a2002L, 0x3ff193ea7aad030bL, 0x3fe62e42fefa39efL, 0x3fd9f323ecbf984dL, 0x3fc7565011e49675L, 0x8000000000000000UL.toLong(),
        // cast = 7
        0x3fff2272ae325a58L, 0x3ff40b512eb53d60L, 0x3feb1d10670aae9aL, 0x3fe1e85f5e7040d1L, 0x3fd588c2d913348fL, 0x3fc3bb35a041d2abL, 0x8000000000000000UL.toLong(),
        // cast = 8
        0x4000a2b23f3bab73L, 0x3ff62e42fefa39efL, 0x3fef62f40794a7b8L, 0x3fe62e42fefa39efL, 0x3fde148a1a2726ceL, 0x3fd269621134db92L, 0x3fc1178e8227e47cL, 0x8000000000000000UL.toLong(),
        // cast = 9
        0x400193ea7aad030bL, 0x3ff810b375dce91eL, 0x3ff193ea7aad030bL, 0x3fe9f323ecbf984cL, 0x3fe2cf25fad8f1c3L, 0x3fd9f323ecbf984dL, 0x3fd015891c9eaef7L, 0x3fbe27076e2af2eaL, 0x8000000000000000UL.toLong(),
        // cast = 10
        0x40026bb1bbb55515L, 0x3ff9c041f7ed8d33L, 0x3ff34378fcbda721L, 0x3fed5240f0e0e077L, 0x3fe62e42fefa39efL, 0x3fe058aefa811452L, 0x3fd6d3c324e13f50L, 0x3fcc8ff7c79a9a20L, 0x3fbaf8e8210a415cL, 0x8000000000000000UL.toLong(),
        // cast = 11
        0x40032ee3b77f374cL, 0x3ffb46a5ef8151a0L, 0x3ff4c9dcf4516b8dL, 0x3ff02f84700434a8L, 0x3fe93b0aee21c2c8L, 0x3fe36576e9a89d2bL, 0x3fdced5303305101L, 0x3fd4618bc21c5ec2L, 0x3fc9af93cd234410L, 0x3fb8663f793c46c9L, 0x8000000000000000UL.toLong(),
        // cast = 12
        0x4003e116bcd39e7dL, 0x3ffcab0bfa2a2002L, 0x3ff62e42fefa39efL, 0x3ff193ea7aad030bL, 0x3fec03d703735f8cL, 0x3fe62e42fefa39efL, 0x3fe13f7596e9c544L, 0x3fd9f323ecbf984dL, 0x3fd269621134db92L, 0x3fc7565011e49675L, 0x3fb64660aa8ce629L, 0x8000000000000000UL.toLong(),
        // cast = 13
        0x400485042b318c51L, 0x3ffdf2e6d6e5fbaaL, 0x3ff7761ddbb61597L, 0x3ff2dbc55768deb3L, 0x3fee938cbceb16ddL, 0x3fe8bdf8b871f13fL, 0x3fe3cf2b50617c96L, 0x3fdf128f5faf06ecL, 0x3fd788cd84244a34L, 0x3fd0ca937be1b9dbL, 0x3fc562073b255055L, 0x3fb47dadcbbdba7fL, 0x8000000000000000UL.toLong(),
        // cast = 14
        0x40051cca16d7bba8L, 0x3fff2272ae325a58L, 0x3ff8a5a9b3027445L, 0x3ff40b512eb53d60L, 0x3ff0795235c1ea1bL, 0x3feb1d10670aae9aL, 0x3fe62e42fefa39efL, 0x3fe1e85f5e7040d1L, 0x3fdc46fce155c4e6L, 0x3fd588c2d913348fL, 0x3fcede65f58845bdL, 0x3fc3bb35a041d2abL, 0x3fb2f8bd74c5eacdL, 0x8000000000000000UL.toLong(),
        // cast = 15
        0x4005aa16394d481fL, 0x40001e85798eb9a3L, 0x3ff9c041f7ed8d33L, 0x3ff525e973a0564fL, 0x3ff193ea7aad030bL, 0x3fed5240f0e0e077L, 0x3fe8637388d06bcdL, 0x3fe41d8fe84672afL, 0x3fe058aefa811452L, 0x3fd9f323ecbf984dL, 0x3fd3d9940e70869cL, 0x3fcc8ff7c79a9a20L, 0x3fc25120e1bbbcdfL, 0x3fb1a9844eb18ef0L, 0x8000000000000000UL.toLong(),
        // cast = 16
        0x40062e42fefa39efL, 0x4000a2b23f3bab73L, 0x3ffac89b834770d4L, 0x3ff62e42fefa39efL, 0x3ff29c440606e6abL, 0x3fef62f40794a7b8L, 0x3fea74269f84330eL, 0x3fe62e42fefa39efL, 0x3fe269621134db92L, 0x3fde148a1a2726ceL, 0x3fd7fafa3bd8151cL, 0x3fd269621134db92L, 0x3fca93ed3c8ad9e3L, 0x3fc1178e8227e47cL, 0x3fb08598b59e3a07L, 0x8000000000000000UL.toLong(),
        // cast = 17
        0x4006aa6bc1fa7f7aL, 0x40011edb023bf0feL, 0x3ffbc0ed0947fbe9L, 0x3ff7269484fac504L, 0x3ff394958c0771c0L, 0x3ff0a9cb89cadef1L, 0x3fec64c9ab854939L, 0x3fe81ee60afb501aL, 0x3fe45a051d35f1bdL, 0x3fe0fae81914a991L, 0x3fdbdc4053da4170L, 0x3fd64aa8293707e6L, 0x3fd12b3cb6479948L, 0x3fc8da1ab22c3d27L, 0x3fc005588ad375aeL, 0x3faf0a30c01162a8L, 0x8000000000000000UL.toLong(),
        // cast = 18
        0x40071f7b3a6b9187L, 0x400193ea7aad030bL, 0x3ffcab0bfa2a2002L, 0x3ff810b375dce91eL, 0x3ff47eb47ce995d9L, 0x3ff193ea7aad030bL, 0x3fee39078d49916bL, 0x3fe9f323ecbf984cL, 0x3fe62e42fefa39efL, 0x3fe2cf25fad8f1c3L, 0x3fdf84bc1762d1d4L, 0x3fd9f323ecbf984dL, 0x3fd4d3b879d029acL, 0x3fd015891c9eaef7L, 0x3fc7565011e49675L, 0x3fbe27076e2af2eaL, 0x3fad43de1c448329L, 0x8000000000000000UL.toLong(),
        // cast = 19
        0x40078e360604b32dL, 0x400202a5464624b1L, 0x3ffd8881915c634fL, 0x3ff8ee290d0f2c6aL, 0x3ff55c2a141bd926L, 0x3ff2716011df4657L, 0x3feff3f2bbae1804L, 0x3febae0f1b241ee5L, 0x3fe7e92e2d5ec088L, 0x3fe48a11293d785cL, 0x3fe17d493a15ef83L, 0x3fdd68fa4988a57dL, 0x3fd8498ed69936dbL, 0x3fd38b5f7967bc29L, 0x3fce41fccb76b0d7L, 0x3fc5ff3070a793d6L, 0x3fbc794881467654L, 0x3fabaeb2e648698bL, 0x8000000000000000UL.toLong(),
        // cast = 20
        0x4007f7427b73e391L, 0x40026bb1bbb55515L, 0x3ffe5a9a7c3ac418L, 0x3ff9c041f7ed8d33L, 0x3ff62e42fefa39efL, 0x3ff34378fcbda721L, 0x3ff0cc1248b56cccL, 0x3fed5240f0e0e077L, 0x3fe98d60031b821bL, 0x3fe62e42fefa39efL, 0x3fe3217b0fd2b116L, 0x3fe058aefa811452L, 0x3fdb91f28212ba02L, 0x3fd6d3c324e13f50L, 0x3fd269621134db92L, 0x3fcc8ff7c79a9a20L, 0x3fc4cd6b97964179L, 0x3fbaf8e8210a415cL, 0x3faa431d5bcc193eL, 0x8000000000000000UL.toLong(),
        // cast = 21
        0x40085b2e946faeb1L, 0x4002cf9dd4b12035L, 0x3fff2272ae325a58L, 0x3ffa881a29e52373L, 0x3ff6f61b30f1d02fL, 0x3ff40b512eb53d60L, 0x3ff193ea7aad030bL, 0x3feee1f154d00cf7L, 0x3feb1d10670aae9aL, 0x3fe7bdf362e9666eL, 0x3fe4b12b73c1dd95L, 0x3fe1e85f5e7040d1L, 0x3fdeb15349f112ffL, 0x3fd9f323ecbf984dL, 0x3fd588c2d913348fL, 0x3fd1675cababa60fL, 0x3fcb0c2d2752f372L, 0x3fc3bb35a041d2abL, 0x3fb99f11cd5f7090L, 0x3fa8fb063ef2c7f1L, 0x8000000000000000UL.toLong(),
        // cast = 22
        0x4008ba74773dc5c7L, 0x40032ee3b77f374cL, 0x3fffe0fe73ce8885L, 0x3ffb46a5ef8151a0L, 0x3ff7b4a6f68dfe5cL, 0x3ff4c9dcf4516b8dL, 0x3ff2527640493138L, 0x3ff02f84700434a8L, 0x3fec9a27f2430af3L, 0x3fe93b0aee21c2c8L, 0x3fe62e42fefa39efL, 0x3fe36576e9a89d2bL, 0x3fe0d5c13030e5daL, 0x3fdced5303305101L, 0x3fd882f1ef83ed45L, 0x3fd4618bc21c5ec2L, 0x3fd08045aa1a326eL, 0x3fc9af93cd234410L, 0x3fc2c3e7139129b1L, 0x3fb8663f793c46c9L, 0x3fa7d178b385c59eL, 0x8000000000000000UL.toLong(),
        // cast = 23
        0x4009157dfdd1b3f0L, 0x400389ed3e132574L, 0x40004b88c07b326bL, 0x3ffbfcb8fca92df1L, 0x3ff86aba03b5daadL, 0x3ff57ff0017947deL, 0x3ff308894d710d88L, 0x3ff0e5977d2c10f9L, 0x3fee064e0c92c395L, 0x3feaa73108717b6aL, 0x3fe79a691949f291L, 0x3fe4d19d03f855cdL, 0x3fe241e74a809e7dL, 0x3fdfc59f37cfc243L, 0x3fdb5b3e24235e88L, 0x3fd739d7f6bbd007L, 0x3fd35891deb9a3b3L, 0x3fcf602c36622699L, 0x3fc8747f7cd00c39L, 0x3fc1e3b825dd05edL, 0x3fb749ed2c40a7e7L, 0x3fa6c261a4fb8a1dL, 0x8000000000000000UL.toLong(),
        // cast = 24
        0x40096ca77c922cf9L, 0x4003e116bcd39e7dL, 0x4000a2b23f3bab73L, 0x3ffcab0bfa2a2002L, 0x3ff9190d0136ccbeL, 0x3ff62e42fefa39efL, 0x3ff3b6dc4af1ff9aL, 0x3ff193ea7aad030bL, 0x3fef62f40794a7b8L, 0x3fec03d703735f8cL, 0x3fe8f70f144bd6b4L, 0x3fe62e42fefa39efL, 0x3fe39e8d4582829fL, 0x3fe13f7596e9c544L, 0x3fde148a1a2726ceL, 0x3fd9f323ecbf984dL, 0x3fd611ddd4bd6bf6L, 0x3fd269621134db92L, 0x3fcde71768d79cc6L, 0x3fc7565011e49675L, 0x3fc1178e8227e47cL, 0x3fb64660aa8ce629L, 0x3fa5ca5fb01e4225L, 0x8000000000000000UL.toLong(),
        // cast = 25
        0x4009c041f7ed8d33L, 0x400434b1382efeb8L, 0x4000f64cba970baeL, 0x3ffd5240f0e0e078L, 0x3ff9c041f7ed8d33L, 0x3ff6d577f5b0fa65L, 0x3ff45e1141a8c00fL, 0x3ff23b1f7163c380L, 0x3ff058aefa811452L, 0x3fed5240f0e0e077L, 0x3fea457901b9579fL, 0x3fe77cacec67badaL, 0x3fe4ecf732f00389L, 0x3fe28ddf8457462fL, 0x3fe058aefa811452L, 0x3fdc8ff7c79a9a21L, 0x3fd8aeb1af986dccL, 0x3fd50635ec0fdd69L, 0x3fd1905f8f46d038L, 0x3fcc8ff7c79a9a20L, 0x3fc6513637dde829L, 0x3fc05cd80afc76beL, 0x3fb5587f437b2869L, 0x3fa4e69ed6d80eb3L, 0x8000000000000000UL.toLong(),
        // cast = 26
        0x400a1094eaf01acdL, 0x400485042b318c51L, 0x4001469fad999947L, 0x3ffdf2e6d6e5fbaaL, 0x3ffa60e7ddf2a866L, 0x3ff7761ddbb61597L, 0x3ff4feb727addb42L, 0x3ff2dbc55768deb3L, 0x3ff0f954e0862f85L, 0x3fee938cbceb16ddL, 0x3feb86c4cdc38e04L, 0x3fe8bdf8b871f13fL, 0x3fe62e42fefa39efL, 0x3fe3cf2b50617c96L, 0x3fe199fac68b4ab8L, 0x3fdf128f5faf06ecL, 0x3fdb314947acda98L, 0x3fd788cd84244a34L, 0x3fd412f7275b3d04L, 0x3fd0ca937be1b9dbL, 0x3fcb56656806c1bdL, 0x3fc562073b255055L, 0x3fbf62dda3ccdb9bL, 0x3fb47dadcbbdba7fL, 0x3fa414bcc0a36654L, 0x8000000000000000UL.toLong(),
        // cast = 27
        0x400a5ddfb8038490L, 0x4004d24ef844f614L, 0x400193ea7aad030bL, 0x3ffe8d7c710ccf31L, 0x3ffafb7d78197bedL, 0x3ff810b375dce91eL, 0x3ff5994cc1d4aec9L, 0x3ff3765af18fb239L, 0x3ff193ea7aad030bL, 0x3fefc8b7f138bdeaL, 0x3fecbbf002113511L, 0x3fe9f323ecbf984cL, 0x3fe7636e3347e0fcL, 0x3fe5045684af23a2L, 0x3fe2cf25fad8f1c3L, 0x3fe0be72e4252a83L, 0x3fdd9b9fb04828b0L, 0x3fd9f323ecbf984dL, 0x3fd67d4d8ff68b1bL, 0x3fd334e9e47d07f6L, 0x3fd015891c9eaef7L, 0x3fca36b40c5bec88L, 0x3fc4861ba31d09fdL, 0x3fbe27076e2af2eaL, 0x3fb3b3b802beeb8fL, 0x3fa352b344da70ceL, 0x8000000000000000UL.toLong(),
        // cast = 28
        0x400aa85ad6964a23L, 0x40051cca16d7bba8L, 0x4001de65993fc89eL, 0x3fff2272ae325a58L, 0x3ffb9073b53f0713L, 0x3ff8a5a9b3027445L, 0x3ff62e42fefa39efL, 0x3ff40b512eb53d60L, 0x3ff228e0b7d28e31L, 0x3ff0795235c1ea1bL, 0x3fede5dc7c5c4b5eL, 0x3feb1d10670aae9aL, 0x3fe88d5aad92f749L, 0x3fe62e42fefa39efL, 0x3fe3f91275240811L, 0x3fe1e85f5e7040d1L, 0x3fdfef78a4de554dL, 0x3fdc46fce155c4e6L, 0x3fd8d126848cb7b6L, 0x3fd588c2d913348fL, 0x3fd269621134db92L, 0x3fcede65f58845bdL, 0x3fc92dcd8c496335L, 0x3fc3bb35a041d2abL, 0x3fbd031bd5179df7L, 0x3fb2f8bd74c5eacdL, 0x3fa29ec7a4b164d6L, 0x8000000000000000UL.toLong(),
        // cast = 29
        0x400af038cbcdfe1fL, 0x400564a80c0f6fa3L, 0x400226438e777c99L, 0x3fffb22e98a1c24eL, 0x3ffc202f9fae6f0aL, 0x3ff935659d71dc3bL, 0x3ff6bdfee969a1e6L, 0x3ff49b0d1924a557L, 0x3ff2b89ca241f628L, 0x3ff1090e20315212L, 0x3fef0554513b1b4dL, 0x3fec3c883be97e87L, 0x3fe9acd28271c737L, 0x3fe74dbad3d909ddL, 0x3fe5188a4a02d7feL, 0x3fe307d7334f10beL, 0x3fe11734274dfa94L, 0x3fde85ec8b1364c2L, 0x3fdb10162e4a5793L, 0x3fd7c7b282d0d46aL, 0x3fd4a851baf27b6eL, 0x3fd1ae22a481c2bbL, 0x3fcdabacdfc4a2eaL, 0x3fc83914f3bd1261L, 0x3fc2ff6d3e070eb7L, 0x3fbbf47c1bbc6a3dL, 0x3fb24b22794f31ddL, 0x3fa1f77d4decfed8L, 0x8000000000000000UL.toLong(),
        // cast = 30
        0x400b35a6f90bd69bL, 0x4005aa16394d481fL, 0x40026bb1bbb55515L, 0x40001e85798eb9a3L, 0x3ffcab0bfa2a2002L, 0x3ff9c041f7ed8d33L, 0x3ff748db43e552deL, 0x3ff525e973a0564fL, 0x3ff34378fcbda721L, 0x3ff193ea7aad030bL, 0x3ff00d8683193e9eL, 0x3fed5240f0e0e077L, 0x3feac28b37692927L, 0x3fe8637388d06bcdL, 0x3fe62e42fefa39efL, 0x3fe41d8fe84672afL, 0x3fe22cecdc455c84L, 0x3fe058aefa811452L, 0x3fdd3b8798391b74L, 0x3fd9f323ecbf984dL, 0x3fd6d3c324e13f50L, 0x3fd3d9940e70869cL, 0x3fd10147d9d11555L, 0x3fcc8ff7c79a9a20L, 0x3fc7565011e49675L, 0x3fc25120e1bbbcdfL, 0x3fbaf8e8210a415cL, 0x3fb1a9844eb18ef0L, 0x3fa15b8b4f761f03L, 0x8000000000000000UL.toLong(),
        // cast = 31
        0x400b78ce48912b5aL, 0x4005ed3d88d29cdeL, 0x4002aed90b3aa9d5L, 0x400061acc9140e62L, 0x3ffd315a9934c981L, 0x3ffa469096f836b2L, 0x3ff7cf29e2effc5dL, 0x3ff5ac3812aaffcdL, 0x3ff3c9c79bc8509eL, 0x3ff21a3919b7ac89L, 0x3ff093d52223e81cL, 0x3fee5ede2ef63374L, 0x3febcf28757e7c23L, 0x3fe97010c6e5becaL, 0x3fe73ae03d0f8cebL, 0x3fe52a2d265bc5abL, 0x3fe3398a1a5aaf81L, 0x3fe1654c3896674eL, 0x3fdf54c21463c16cL, 0x3fdc0c5e68ea3e46L, 0x3fd8ecfda10be549L, 0x3fd5f2ce8a9b2c92L, 0x3fd31a8255fbbb4fL, 0x3fd061365ff7f30aL, 0x3fcb88c50a39e26bL, 0x3fc68395da1108d1L, 0x3fc1aee908da6ca1L, 0x3fba0e6e3f5c26d8L, 0x3fb112af9865a76bL, 0x3fa0c9d3e1552fc4L, 0x8000000000000000UL.toLong(),
        // cast = 32
        0x400bb9d3beb8c86bL, 0x40062e42fefa39efL, 0x4002efde816246e6L, 0x4000a2b23f3bab73L, 0x3ffdb365858403a3L, 0x3ffac89b834770d4L, 0x3ff85134cf3f367fL, 0x3ff62e42fefa39efL, 0x3ff44bd288178ac1L, 0x3ff29c440606e6abL, 0x3ff115e00e73223fL, 0x3fef62f40794a7b8L, 0x3fecd33e4e1cf068L, 0x3fea74269f84330eL, 0x3fe83ef615ae0130L, 0x3fe62e42fefa39efL, 0x3fe43d9ff2f923c5L, 0x3fe269621134db92L, 0x3fe0ae76e2d054faL, 0x3fde148a1a2726ceL, 0x3fdaf5295248cdd0L, 0x3fd7fafa3bd8151cL, 0x3fd522ae0738a3d8L, 0x3fd269621134db92L, 0x3fcf991c6cb3b379L, 0x3fca93ed3c8ad9e3L, 0x3fc5bf406b543db2L, 0x3fc1178e8227e47cL, 0x3fb9335e5d594989L, 0x3fb08598b59e3a07L, 0x3fa0415d89e74444L, 0x8000000000000000UL.toLong(),
    )

    /** Same value as `-ln(count.toDouble() / cast)` on the desktop JVM, on every platform. */
    fun cost(count: Int, cast: Int): Double {
        if (cast !in 1..MAX_TABLE_CAST || count !in 1..cast) return -ln(count.toDouble() / cast)
        return Double.fromBits(bits[(cast - 1) * cast / 2 + (count - 1)])
    }
}
