package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.FeatureLayout
import io.github.proairface.kotling2p.StressFeatureLayout
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Serializes the trained trees into the binary resource the library ships.
 *
 * The format is documented on [LtsModel], which reads it. Two details earn their complexity: the
 * trees are laid out in pre-order so a branch's "yes" child is always the next node and needs no
 * stored index, and every number is a varint. Together those roughly halve the file against the
 * obvious four-ints-per-node layout, which matters because this is an Android dependency — the
 * bytes are downloaded and held in memory on a phone.
 */
object ModelWriter {

    fun write(
        destination: File,
        alphabet: Alphabet,
        phonemes: PhonemeVocabulary,
        layout: FeatureLayout,
        forest: Map<Int, List<TreeTrainer.Node>>,
        chunks: ChunkTable,
        stressLayout: StressFeatureLayout,
        stressForest: List<TreeTrainer.Node>,
    ): Stats {
        val ensembleSize = forest.values.firstOrNull()?.size ?: 1
        val labelIds = LinkedHashMap<Int, Int>()
        val flat = FlatNodes { label -> labelIds.getOrPut(label) { labelIds.size } }
        // Roots are laid out letter-major: all of a letter's trees together, so the decoder walks
        // a contiguous run when it votes.
        val roots = IntArray(alphabet.characters.size * ensembleSize)
        for ((letter, trees) in forest.entries.sortedBy { it.key }) {
            require(trees.size == ensembleSize) { "every letter needs the same number of trees" }
            trees.forEachIndexed { index, tree ->
                roots[letter * ensembleSize + index] = flat.append(tree, layout.valueSpace) + 1
            }
        }

        // The stress forest gets its own, independent node table: a leaf's payload here is the
        // stress digit itself, not an index into `labelIds`, so the two tables cannot be merged
        // without a discriminant on every node. Small enough (see Stats below) that the
        // separation costs nothing worth avoiding.
        val stressFlat = FlatNodes { digit -> digit }
        val stressRoots = IntArray(stressForest.size) { stressFlat.append(stressForest[it], stressLayout.valueSpace) + 1 }

        val body = ByteArrayOutputStream()
        body.write("KG2P".toByteArray(Charsets.US_ASCII))
        body.varint(1)
        body.varint(ensembleSize)
        body.varint(layout.contextRadius)
        body.varint(layout.phonemeHistory)
        body.varint(layout.valueSpace)
        body.ascii(alphabet.characters.joinToString(""))
        body.varint(phonemes.names.size)
        for (phoneme in phonemes.names) body.ascii(phoneme)
        body.varint(labelIds.size)
        for (chunk in labelIds.keys) body.ascii(chunks.nameOf(chunk))
        for (root in roots) body.varint(root)
        body.writeFlat(flat)

        body.varint(stressLayout.suffixLength)
        body.varint(stressLayout.positionCap)
        body.varint(stressLayout.valueSpace)
        body.varint(stressRoots.size)
        for (root in stressRoots) body.varint(root)
        body.writeFlat(stressFlat)

        destination.parentFile.mkdirs()
        destination.writeBytes(body.toByteArray())
        return Stats(
            nodes = flat.size,
            leaves = flat.leaves,
            labels = labelIds.size,
            stressNodes = stressFlat.size,
            stressLeaves = stressFlat.leaves,
            bytes = body.size(),
        )
    }

    data class Stats(
        val nodes: Int,
        val leaves: Int,
        val labels: Int,
        val stressNodes: Int,
        val stressLeaves: Int,
        val bytes: Int,
    )

    private fun ByteArrayOutputStream.writeFlat(flat: FlatNodes) {
        varint(flat.size)
        for (node in 0 until flat.size) {
            varint(flat.tag[node])
            varint(if (flat.tag[node] == 0) flat.payload[node] else flat.payload[node] - node)
        }
    }

    /**
     * Pre-order flattening. Appending the node before recursing is what makes its "yes" child
     * land at `index + 1`; the "no" child's real index is only known once the whole "yes" subtree
     * has been laid out, which is why indices are resolved here and encoded afterwards.
     *
     * [leafPayload] maps a leaf's raw label to what gets stored: for the letter forest that
     * interns it into the shared ASCII label table, for the stress forest it is the identity
     * function, since a stress digit (0, 1 or 2) needs no table at all.
     */
    private class FlatNodes(private val leafPayload: (Int) -> Int) {
        var tag = IntArray(1024)
        var payload = IntArray(1024)
        var size = 0
        var leaves = 0

        fun append(node: TreeTrainer.Node, valueSpace: Int): Int {
            val index = size
            grow()
            size++
            when (node) {
                is TreeTrainer.Leaf -> {
                    tag[index] = 0
                    payload[index] = leafPayload(node.label)
                    leaves++
                }
                is TreeTrainer.Branch -> {
                    tag[index] = node.feature * valueSpace + node.value + 1
                    append(node.yes, valueSpace)
                    // Resolved into a local first: `payload[index] = append(...)` would bind
                    // `payload` to the array as it is *now*, and the recursion can replace it
                    // with a larger one, sending the write to an array that is then discarded.
                    val noChild = append(node.no, valueSpace)
                    payload[index] = noChild
                }
            }
            return index
        }

        private fun grow() {
            if (size < tag.size) return
            tag = tag.copyOf(tag.size * 2)
            payload = payload.copyOf(payload.size * 2)
        }
    }
}

private fun ByteArrayOutputStream.varint(value: Int) {
    require(value >= 0) { "varints are unsigned, got $value" }
    var remaining = value
    while (true) {
        val byte = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining == 0) {
            write(byte)
            return
        }
        write(byte or 0x80)
    }
}

private fun ByteArrayOutputStream.ascii(text: String) {
    val bytes = text.toByteArray(Charsets.US_ASCII)
    varint(bytes.size)
    write(bytes)
}
