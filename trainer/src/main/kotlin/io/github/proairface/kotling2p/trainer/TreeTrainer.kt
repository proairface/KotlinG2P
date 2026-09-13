package io.github.proairface.kotling2p.trainer

/**
 * Grows one classification tree per letter over the aligned corpus.
 *
 * Once [Aligner] has decided which phonemes each letter is responsible for, predicting a
 * pronunciation becomes a per-letter classification problem: given the letters around it, which
 * phoneme chunk does *this* letter produce? A tree answers that by asking a series of yes/no
 * questions about the neighbours ("is the next letter an h?", "is the one after that an e?"),
 * which is a good fit for English spelling — the conditioning really is a handful of nearby
 * letters, and the exceptions are nested (c is `K`, unless followed by e/i/y, unless it is part
 * of ch, ...), which is exactly the shape a tree represents naturally.
 *
 * Splits are chosen greedily by information gain (CART). Every split tests one feature against
 * one value, so the decoder is a pointer chase with no arithmetic. Features are the surrounding
 * letters plus the phonemes already emitted to the left, which is what lets the tree avoid
 * producing sequences English does not have.
 */
class TreeTrainer(
    private val features: ByteArray,
    private val labels: IntArray,
    private val numFeatures: Int,
    private val valueSpace: Int,
    private val numLabels: Int,
    private val settings: Settings,
) {
    /** Knobs that trade model size against accuracy. */
    data class Settings(val minLeaf: Int, val minGain: Double, val maxDepth: Int)

    sealed interface Node
    class Leaf(val label: Int) : Node
    class Branch(val feature: Int, val value: Int, val yes: Node, val no: Node) : Node

    private val keyCount = numFeatures * valueSpace
    private val histogram = IntArray(keyCount * numLabels)
    private val keyTotal = IntArray(keyCount)
    private val keyGeneration = IntArray(keyCount)
    private val touchedKeys = IntArray(keyCount)
    private val nodeCounts = IntArray(numLabels)
    private var generation = 0

    fun train(rows: IntArray): Node = grow(rows, 0)

    private fun grow(rows: IntArray, depth: Int): Node {
        val present = countLabels(rows)

        if (present.size == 1 || depth >= settings.maxDepth || rows.size < settings.minLeaf * 2) {
            return leafOf(present)
        }

        val touched = buildHistogram(rows)
        var bestKey = -1
        var bestScore = negativeEntropy(present, rows.size)

        for (t in 0 until touched) {
            val key = touchedKeys[t]
            val leftSize = keyTotal[key]
            val rightSize = rows.size - leftSize
            if (leftSize < settings.minLeaf || rightSize < settings.minLeaf) continue

            val base = key * numLabels
            var score = -(weight(leftSize) + weight(rightSize))
            for (label in present) {
                val left = histogram[base + label]
                score += weight(left) + weight(nodeCounts[label] - left)
            }
            if (score > bestScore + settings.minGain) {
                bestScore = score
                bestKey = key
            }
        }
        if (bestKey < 0) return leafOf(present)
        clearNodeCounts(present)

        val feature = bestKey / valueSpace
        val value = bestKey % valueSpace
        val left = IntArray(keyTotal[bestKey])
        val right = IntArray(rows.size - left.size)
        var l = 0
        var r = 0
        for (row in rows) {
            if (featureAt(row, feature) == value) left[l++] = row else right[r++] = row
        }
        // The children overwrite every shared buffer, so nothing derived from this node may be
        // read past this point.
        return Branch(feature, value, grow(left, depth + 1), grow(right, depth + 1))
    }

    /**
     * Turns the node's label counts into a leaf holding the most frequent one, and resets them.
     *
     * Only the winner is kept. An earlier version stored the runners-up so the decoder could beam
     * search over whole words, which sounds like it should help a model that conditions on its own
     * output — but measurement said otherwise, and the reason is worth recording. With
     * `minLeaf = 1` the trees grow until every leaf is pure, so there are no runners-up to search
     * over; stopping earlier to create some cost more accuracy than the search recovered, at every
     * stopping point tried (61.7% to 62.0% at `minLeaf = 2`, against 64.2% for growing out and
     * taking each letter's answer as it comes).
     */
    private fun leafOf(present: IntArray): Leaf {
        var majority = present[0]
        for (label in present) if (nodeCounts[label] > nodeCounts[majority]) majority = label
        clearNodeCounts(present)
        return Leaf(majority)
    }

    private fun featureAt(row: Int, feature: Int): Int =
        features[row * numFeatures + feature].toInt() and 0xFF

    /** Tallies [rows] into [nodeCounts] and returns the labels that actually occur. */
    private fun countLabels(rows: IntArray): IntArray {
        val present = IntArray(minOf(rows.size, numLabels))
        var distinct = 0
        for (row in rows) {
            val label = labels[row]
            if (nodeCounts[label] == 0) present[distinct++] = label
            nodeCounts[label]++
        }
        return present.copyOf(distinct)
    }

    private fun clearNodeCounts(present: IntArray) {
        for (label in present) nodeCounts[label] = 0
    }

    /**
     * Counts, for every (position, letter) question, how the rows' labels fall on its yes side.
     * A generation stamp retires the previous node's counts in O(1) rather than clearing an
     * array that is mostly untouched at depth.
     */
    private fun buildHistogram(rows: IntArray): Int {
        generation++
        var touched = 0
        for (row in rows) {
            val label = labels[row]
            for (feature in 0 until numFeatures) {
                val key = feature * valueSpace + featureAt(row, feature)
                if (keyGeneration[key] != generation) {
                    keyGeneration[key] = generation
                    keyTotal[key] = 0
                    touchedKeys[touched++] = key
                    java.util.Arrays.fill(histogram, key * numLabels, (key + 1) * numLabels, 0)
                }
                histogram[key * numLabels + label]++
                keyTotal[key]++
            }
        }
        return touched
    }

    /**
     * Entropy of the node, scaled by its size and negated, so splits can be compared by summing
     * the same quantity over their two sides — the constant `N log N` terms then cancel.
     */
    private fun negativeEntropy(present: IntArray, total: Int): Double {
        var score = -weight(total)
        for (label in present) score += weight(nodeCounts[label])
        return score
    }

    private fun weight(n: Int): Double = if (n <= 1) 0.0 else n * Math.log(n.toDouble())
}
