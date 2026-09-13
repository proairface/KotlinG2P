package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures LetterToSoundRules' accuracy against real CMUdict entries — a measurement, not a
 * claim. Takes a deterministic, evenly-spaced sample across the whole dictionary (this isn't
 * a held-out train/test split — the rules never look at the dictionary at all, so there's no
 * leakage to guard against) and reports two numbers:
 *
 * - exact match rate: guessed phoneme sequence (ignoring stress digits) equals CMUdict's
 *   first-listed pronunciation, phoneme-for-phoneme.
 * - phoneme error rate (PER): Levenshtein edit distance between guessed and actual base
 *   phoneme sequences, divided by the actual sequence's length, averaged over the sample —
 *   the standard ASR/TTS metric, forgiving of near-misses that exact-match isn't.
 */
class LetterToSoundRulesBenchmarkTest {

    @Test
    fun reportAccuracyAgainstASampleOfCmudict() {
        val sample = sampleCmudict(stride = 137)
        var exactMatches = 0
        var totalPer = 0.0

        sample.forEach { (word, actual) ->
            val guessed = LetterToSoundRules.guess(word).map { it.base }
            if (guessed == actual) exactMatches++
            totalPer += editDistance(guessed, actual).toDouble() / actual.size.coerceAtLeast(1)
        }

        val exactMatchRate = exactMatches.toDouble() / sample.size
        val averagePer = totalPer / sample.size

        println("LetterToSoundRules benchmark over ${sample.size} CMUdict words:")
        println("  exact match rate:           %.1f%%".format(exactMatchRate * 100))
        println("  average phoneme error rate: %.1f%%".format(averagePer * 100))

        // A loose floor, not a target: this is a simple heuristic, not a trained model.
        // Calibrated below the ~14% measured after the suffix-stripping/soft-C-G/doubled-
        // consonant improvements — this catches a real regression, it isn't an accuracy goal.
        assertTrue(exactMatchRate > 0.10, "exact match rate dropped to $exactMatchRate")
    }

    private fun sampleCmudict(stride: Int): List<Pair<String, List<String>>> {
        val stream = javaClass.classLoader.getResourceAsStream("cmudict/cmudict.dict")
            ?: error("cmudict.dict not found on the classpath")
        val words = mutableListOf<Pair<String, List<String>>>()
        var index = 0
        stream.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith(";;;") || line.isBlank()) return@forEach
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@forEach
                val word = parts[0]
                // Skip alternate-pronunciation variants like "READ(1)" and non-alphabetic
                // entries (punctuation, symbols) — the point is prose/address words.
                if (word.contains('(') || !word.all { it.isLetter() }) return@forEach
                if (index % stride == 0) {
                    val phonemes = parts[1].split(" ").map { it.trimEnd { c -> c.isDigit() } }
                    words += word to phonemes
                }
                index++
            }
        }
        return words
    }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) dp[i][0] = i
        for (j in 0..b.size) dp[0][j] = j
        for (i in 1..a.size) {
            for (j in 1..b.size) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.size][b.size]
    }
}
