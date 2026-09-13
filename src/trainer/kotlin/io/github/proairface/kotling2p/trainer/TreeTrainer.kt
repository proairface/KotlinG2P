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
    private val random: kotlin.random.Random,
) {
    /** Knobs that trade model size against accuracy. */
    data class Settings(
        val minLeaf: Int,
        val minGain: Double,
        val maxDepth: Int,
        /**
         * How many features each split may choose between, drawn fresh at every node.
         *
         * Equal to the total feature count this is ordinary CART. Lower, it is a random forest's
         * tree: deliberately handicapped, so that trees grown on different samples make
         * *different* mistakes instead of all making the same one. Only worth anything when
         * several trees vote — see the ensemble handling in `Train.kt`.
         */
        val featuresPerSplit: Int,
    )

    sealed interface Node
    class Leaf(val label: Int) : Node
    class Branch(val feature: Int, val value: Int, val yes: Node, val no: Node) : Node

    private val keyCount = numFeatures * valueSpace
    private val histogram = IntArray(keyCount * numLabels)
    private val keyTotal = IntArray(keyCount)
    private val keyGeneration = IntArray(keyCount)
    private val touchedKeys = IntArray(keyCount)
    private val nodeCounts = IntArray(numLabels)
    private val featurePool = IntArray(numFeatures) { it }
    private var generation = 0

    fun train(rows: IntArray): Node = grow(rows, 0)

    private fun grow(rows: IntArray, depth: Int): Node {
        val present = countLabels(rows)

        if (present.size == 1 || depth >= settings.maxDepth || rows.size < settings.minLeaf * 2) {
            return leafOf(present)
        }

        val candidates = selectFeatures()
        val touched = buildHistogram(rows, candidates)
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
     * A leaf keeps only its winner, and needs to: grown to `minLeaf = 1` the tree is pure at every
     * leaf, so there are no runners-up to keep. An earlier version stored them anyway, to let the
     * decoder search over whole words, and it bought nothing for exactly that reason — the
     * distribution was always one-hot. Stopping the trees earlier to manufacture uncertainty cost
     * more accuracy than the search won back (62.0% at `minLeaf = 2`, against 64.2% for growing
     * out and taking each letter's answer as it comes).
     *
     * The uncertainty a search needs comes from the ensemble instead, where several differently
     * grown trees vote and their disagreement is the confidence estimate — which is also what
     * finally made the search worth having (66.8% to 67.7% at nine trees).
     */
    private fun leafOf(present: IntArray): Leaf {
        var majority = present[0]
        for (label in present) if (nodeCounts[label] > nodeCounts[majority]) majority = label
        clearNodeCounts(present)
        return Leaf(majority)
    }

    /**
     * Shuffles [settings.featuresPerSplit] features to the front of [featurePool] and returns how
     * many. A partial Fisher-Yates, so the pool stays a permutation of every feature and no node
     * is ever permanently denied one.
     */
    private fun selectFeatures(): Int {
        val count = minOf(settings.featuresPerSplit, numFeatures)
        if (count == numFeatures) return count
        for (i in 0 until count) {
            val j = i + random.nextInt(numFeatures - i)
            val swap = featurePool[i]
            featurePool[i] = featurePool[j]
            featurePool[j] = swap
        }
        return count
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
    private fun buildHistogram(rows: IntArray, candidates: Int): Int {
        generation++
        var touched = 0
        for (row in rows) {
            val label = labels[row]
            for (index in 0 until candidates) {
                val feature = featurePool[index]
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
