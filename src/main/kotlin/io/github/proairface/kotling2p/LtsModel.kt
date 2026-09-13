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
 * The model is one decision tree per letter, trained offline on CMUdict by the `trainer`
 * subproject (`./gradlew :trainer:trainLts`) and shipped as a compact binary resource. Each tree
 * predicts the phonemes one letter is responsible for, given the letters around it and the
 * phonemes already chosen to its left. Decoding is a pointer chase down one tree per letter — no
 * floating point, and no allocation beyond the result.
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
 * contextRadius    varint, letters of context each side
 * phonemeHistory   varint, how many already-emitted phonemes are visible
 * valueSpace       varint, the stride used to pack (feature, value) into one number
 * alphabet         varint length, then that many ASCII bytes (the letters, sorted)
 * phonemes         varint count, then each as varint length + ASCII: the bare ARPAbet symbols
 *                  used by the history features, whose id equals their index; the count itself
 *                  is the id meaning "nothing emitted yet"
 * labels           varint count, then each as varint length + ASCII: space-separated ARPAbet,
 *                  empty for a silent letter
 * roots            one varint per alphabet letter: its tree's node index plus one, or 0 for a
 *                  letter with no tree
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
    private val layout: FeatureLayout,
    private val labels: Array<List<Phoneme>>,
    /** Per label, the history ids of the phonemes it emits, so decoding needs no string work. */
    private val labelHistoryIds: Array<IntArray>,
    private val phonemeBoundary: Int,
    private val roots: IntArray,
    private val nodeTag: IntArray,
    private val nodePayload: IntArray,
) {
    /** One past the last real letter id — the value a letter feature takes outside the word. */
    private val letterBoundary = alphabet.length

    /** Direct char-to-id lookup, so decoding never scans [alphabet]. -1 means "not a letter". */
    private val letterIds = IntArray(128) { -1 }.also {
        alphabet.forEachIndexed { id, char -> it[char.code] = id }
    }

    /** Phonemes for [word], which is expected to be a single already-normalized word. */
    fun predict(word: String): List<Phoneme> {
        val letters = word.lowercase()
        val ids = IntArray(letters.length) {
            val code = letters[it].code
            if (code < 128) letterIds[code] else -1
        }
        val history = IntArray(layout.phonemeHistory) { phonemeBoundary }
        val result = ArrayList<Phoneme>(letters.length + 2)
        for (position in ids.indices) {
            val letter = ids[position]
            if (letter < 0) continue
            val root = roots[letter]
            if (root == NO_TREE) continue
            val label = classify(root, ids, position, history)
            result += labels[label]
            if (history.isNotEmpty()) {
                for (id in labelHistoryIds[label]) {
                    for (slot in history.size - 1 downTo 1) history[slot] = history[slot - 1]
                    history[0] = id
                }
            }
        }
        return result
    }

    private fun classify(root: Int, ids: IntArray, position: Int, history: IntArray): Int {
        var node = root
        while (true) {
            val tag = nodeTag[node]
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
            val roots = IntArray(alphabet.length) { cursor.varint() - 1 }
            val nodeCount = cursor.varint()
            val nodeTag = IntArray(nodeCount)
            val nodePayload = IntArray(nodeCount)
            for (node in 0 until nodeCount) {
                val tag = cursor.varint()
                nodeTag[node] = tag
                // A leaf's second field is its label; a branch's is the distance to its "no"
                // child, a delta because pre-order guarantees the child comes later.
                nodePayload[node] = if (tag == LEAF) cursor.varint() else node + cursor.varint()
            }
            return LtsModel(
                alphabet, layout, labels, labelHistoryIds, phonemeCount, roots, nodeTag, nodePayload,
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
