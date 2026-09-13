package io.github.proairface.kotling2p.trainer

/**
 * Learns, from CMUdict alone, which phonemes each letter of a word is responsible for.
 *
 * A dictionary entry says only that "box" is pronounced `B AA1 K S` — not that the x is the part
 * saying `K S`. Every downstream step needs that missing detail, so it has to be inferred. This
 * is the standard expectation-maximization treatment: start by considering *every* way the
 * letters could divide up the phonemes as equally likely, score each division by how well its
 * letter-to-phoneme pairings agree with the rest of the dictionary, re-estimate, repeat. Pairings
 * that recur across thousands of words (`ph` → `F`, silent `e`) reinforce; coincidental ones
 * starve.
 *
 * Each letter is allowed to produce zero, one or two phonemes ([MAX_PHONEMES_PER_LETTER]). Zero
 * covers silent letters and the second half of a digraph (the h of "sh"); two covers the handful
 * of letters that genuinely carry a cluster (the x of "box", the u of "cute" → `Y UW1`). Letters
 * are never merged into multi-letter chunks, which keeps the output directly usable: exactly one
 * label per letter, so the next stage is a plain per-letter classification problem.
 */
class Aligner(private val chunks: ChunkTable, private val alphabetSize: Int) {

    /** A word's letters paired with every phoneme chunk it could possibly emit at each position. */
    class Word(val letters: IntArray, val phonemeCount: Int, private val chunkIds: IntArray) {
        /** Id of the chunk covering `phonemes[start until start + length]`, or -1 if out of range. */
        fun chunkAt(start: Int, length: Int): Int = chunkIds[start * STRIDE + length]
    }

    private var probability = DoubleArray(0)
    private var supported = BooleanArray(0)

    /**
     * Runs [iterations] rounds of expectation-maximization, then returns the single best
     * alignment for each word.
     *
     * @return for each input word, the chunk id emitted by each of its letters, or null if the
     *   word cannot be aligned at all (its pronunciation needs more than two phonemes from some
     *   letter — "xylophone"-style entries where a single letter carries a whole cluster).
     */
    fun align(words: List<Word>, iterations: Int, onIteration: (Int, Double) -> Unit): List<IntArray?> {
        probability = DoubleArray(alphabetSize * chunks.size) { 1.0 }
        supported = BooleanArray(probability.size) { true }

        val buffers = Buffers(words)
        repeat(iterations) { iteration ->
            val counts = DoubleArray(probability.size)
            var alignable = 0
            var logLikelihood = 0.0
            for (word in words) {
                val z = accumulate(word, counts, buffers)
                if (z > 0.0) {
                    alignable++
                    logLikelihood += Math.log(z)
                }
            }
            if (iteration == 0) {
                // Whatever had no posterior mass under the uniform start is genuinely impossible
                // (that letter never sits next to that chunk anywhere), so it stays at zero.
                // Everything else keeps a floor in later rounds: EM driving a pairing to exactly
                // zero would make words that depend on it unalignable, losing them from training.
                for (i in counts.indices) supported[i] = counts[i] > 0.0
            }
            maximize(counts)
            onIteration(iteration, logLikelihood / alignable.coerceAtLeast(1))
        }

        return words.map { viterbi(it, buffers) }
    }

    /** Adds one word's expected letter-to-chunk counts; returns the word's total probability. */
    private fun accumulate(word: Word, counts: DoubleArray, buffers: Buffers): Double {
        val n = word.letters.size
        val m = word.phonemeCount
        val stride = m + 1
        val forward = buffers.forward
        val backward = buffers.backward

        java.util.Arrays.fill(forward, 0, (n + 1) * stride, 0.0)
        forward[0] = 1.0
        for (i in 1..n) {
            val letter = word.letters[i - 1] * chunks.size
            for (j in 0..m) {
                var sum = 0.0
                for (k in 0..minOf(MAX_PHONEMES_PER_LETTER, j)) {
                    val previous = forward[(i - 1) * stride + (j - k)]
                    if (previous == 0.0) continue
                    sum += previous * probability[letter + word.chunkAt(j - k, k)]
                }
                forward[i * stride + j] = sum
            }
        }

        val total = forward[n * stride + m]
        if (total <= 0.0) return 0.0

        java.util.Arrays.fill(backward, 0, (n + 1) * stride, 0.0)
        backward[n * stride + m] = 1.0
        for (i in n - 1 downTo 0) {
            val letter = word.letters[i] * chunks.size
            for (j in 0..m) {
                var sum = 0.0
                for (k in 0..minOf(MAX_PHONEMES_PER_LETTER, m - j)) {
                    val next = backward[(i + 1) * stride + (j + k)]
                    if (next == 0.0) continue
                    sum += probability[letter + word.chunkAt(j, k)] * next
                }
                backward[i * stride + j] = sum
            }
        }

        for (i in 0 until n) {
            val letter = word.letters[i] * chunks.size
            for (j in 0..m) {
                val prefix = forward[i * stride + j]
                if (prefix == 0.0) continue
                for (k in 0..minOf(MAX_PHONEMES_PER_LETTER, m - j)) {
                    val suffix = backward[(i + 1) * stride + (j + k)]
                    if (suffix == 0.0) continue
                    val chunk = word.chunkAt(j, k)
                    counts[letter + chunk] += prefix * probability[letter + chunk] * suffix / total
                }
            }
        }
        return total
    }

    /** Re-estimates the per-letter distribution over chunks from the accumulated counts. */
    private fun maximize(counts: DoubleArray) {
        val next = DoubleArray(counts.size)
        for (letter in 0 until alphabetSize) {
            val base = letter * chunks.size
            var sum = 0.0
            for (c in 0 until chunks.size) {
                val floored = if (supported[base + c]) counts[base + c] + FLOOR else 0.0
                next[base + c] = floored
                sum += floored
            }
            if (sum <= 0.0) continue
            for (c in 0 until chunks.size) next[base + c] /= sum
        }
        probability = next
    }

    /** The single most likely alignment for one word, under the trained distribution. */
    private fun viterbi(word: Word, buffers: Buffers): IntArray? {
        val n = word.letters.size
        val m = word.phonemeCount
        val stride = m + 1
        val best = buffers.forward
        val backPointer = buffers.backPointer

        java.util.Arrays.fill(best, 0, (n + 1) * stride, 0.0)
        best[0] = 1.0
        for (i in 1..n) {
            val letter = word.letters[i - 1] * chunks.size
            for (j in 0..m) {
                var top = 0.0
                var argument = -1
                for (k in 0..minOf(MAX_PHONEMES_PER_LETTER, j)) {
                    val previous = best[(i - 1) * stride + (j - k)]
                    if (previous == 0.0) continue
                    val score = previous * probability[letter + word.chunkAt(j - k, k)]
                    if (score > top) {
                        top = score
                        argument = k
                    }
                }
                best[i * stride + j] = top
                backPointer[i * stride + j] = argument
            }
        }
        if (best[n * stride + m] <= 0.0) return null

        val labels = IntArray(n)
        var j = m
        for (i in n downTo 1) {
            val k = backPointer[i * stride + j]
            labels[i - 1] = word.chunkAt(j - k, k)
            j -= k
        }
        return labels
    }

    /** Scratch space sized once to the largest word, rather than reallocated per word per round. */
    private class Buffers(words: List<Word>) {
        private val cells = words.maxOf { (it.letters.size + 1) * (it.phonemeCount + 1) }
        val forward = DoubleArray(cells)
        val backward = DoubleArray(cells)
        val backPointer = IntArray(cells)
    }

    companion object {
        const val MAX_PHONEMES_PER_LETTER = 2
        private const val STRIDE = MAX_PHONEMES_PER_LETTER + 1

        /** Keeps a once-seen pairing reachable forever; far below any real posterior. */
        private const val FLOOR = 1e-9

        /** Prepares a dictionary entry for alignment, interning every chunk it could emit. */
        fun prepare(entry: Entry, alphabet: Alphabet, chunks: ChunkTable): Word? {
            val m = entry.phonemes.size
            val n = entry.word.length
            if (m > n * MAX_PHONEMES_PER_LETTER) return null

            val chunkIds = IntArray((m + 1) * STRIDE) { -1 }
            for (start in 0..m) {
                for (length in 0..MAX_PHONEMES_PER_LETTER) {
                    if (start + length > m) continue
                    val name = entry.phonemes.subList(start, start + length).joinToString(" ")
                    chunkIds[start * STRIDE + length] = chunks.idOf(name)
                }
            }
            return Word(IntArray(n) { alphabet.idOf(entry.word[it]) }, m, chunkIds)
        }
    }
}
