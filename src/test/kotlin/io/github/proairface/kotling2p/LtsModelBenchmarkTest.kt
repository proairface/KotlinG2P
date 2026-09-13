package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures the shipped model against CMUdict words it was never trained on — a measurement, not a
 * claim, and the source of every accuracy number in the README.
 *
 * The scoring set is exactly [TrainingSplit.isHeldOut], the same predicate the trainer uses to
 * decide what to exclude. That matters more here than it did for the old hand-written rules,
 * which never looked at the dictionary and so could be scored against any of it: a model trained
 * on CMUdict reproduces CMUdict almost perfectly, so scoring it on words it has seen would
 * measure nothing but its own memory.
 *
 * Two numbers are reported:
 *
 * - word accuracy: the predicted phoneme sequence equals CMUdict's first-listed pronunciation,
 *   phoneme for phoneme. Reported both with stress digits and with them stripped, because they
 *   are different questions — a word can be perfectly intelligible with the wrong stress.
 * - phoneme error rate (PER): Levenshtein distance between predicted and actual sequences over
 *   the actual sequence's length, averaged. The standard TTS/ASR metric, and the more honest one
 *   for a long word, where exact match calls a single wrong vowel a total failure.
 */
class LtsModelBenchmarkTest {

    @Test
    fun reportAccuracyOnWordsTheModelNeverSaw() {
        val started = System.currentTimeMillis()
        val model = LtsModel.loadBundled()
        val loadMillis = System.currentTimeMillis() - started

        val heldOut = heldOutEntries()
        var exactWithStress = 0
        var exactIgnoringStress = 0
        var totalErrorRate = 0.0

        for ((word, actual) in heldOut) {
            val predicted = model.predict(word).map { it.arpabet }
            if (predicted == actual) exactWithStress++
            val predictedBase = predicted.map(::stripStress)
            val actualBase = actual.map(::stripStress)
            if (predictedBase == actualBase) exactIgnoringStress++
            totalErrorRate += editDistance(predictedBase, actualBase).toDouble() /
                actualBase.size.coerceAtLeast(1)
        }

        val withStress = exactWithStress.toDouble() / heldOut.size
        val ignoringStress = exactIgnoringStress.toDouble() / heldOut.size
        val errorRate = totalErrorRate / heldOut.size

        println("LtsModel benchmark over ${heldOut.size} held-out CMUdict words:")
        println("  word accuracy (with stress):     %.1f%%".format(withStress * 100))
        println("  word accuracy (ignoring stress): %.1f%%".format(ignoringStress * 100))
        println("  phoneme error rate:              %.1f%%".format(errorRate * 100))
        println("  model load time:                 ${loadMillis}ms")

        // Floors, not targets: calibrated below the measured 68.0% / 7.7% so this catches a real
        // regression — a broken model file, a decoder that drifts out of step with the trainer —
        // without failing on ordinary retraining noise, or on a deliberately smaller ensemble.
        assertTrue(ignoringStress > 0.62, "word accuracy fell to $ignoringStress")
        assertTrue(errorRate < 0.09, "phoneme error rate rose to $errorRate")
    }

    @Test
    fun predictsPlausiblePhonemesForNamesThatAreNotInTheDictionary() {
        val model = LtsModel.loadBundled()
        val dictionary = CmuDict.loadBundled()
        // Street-name-shaped words, the case this library exists for. The assertion is only that
        // every letter sequence produces a real, non-empty pronunciation containing a vowel —
        // there is no ground truth for an invented name, so this guards against the failure mode
        // that actually matters (silence, or an unpronounceable consonant run), not against being
        // subtly wrong.
        val names = listOf(
            "zjorvik", "haverbrook", "kensingcote", "oldenwaal", "brightmoor",
            "faulkstone", "quillingham", "thorsbury", "zellenbrook", "pendrimoor",
        )
        for (name in names) {
            require(dictionary.lookup(name) == null) { "$name is in the dictionary; pick another" }
            val phonemes = model.predict(name)
            assertTrue(phonemes.isNotEmpty(), "$name produced nothing")
            assertTrue(
                phonemes.any { it.stress != null },
                "$name produced no vowel at all: ${phonemes.joinToString(" ")}",
            )
        }
    }

    private fun heldOutEntries(): List<Pair<String, List<String>>> {
        val stream = javaClass.classLoader.getResourceAsStream("cmudict/cmudict.dict")
            ?: error("cmudict.dict not found on the classpath")
        val entries = mutableListOf<Pair<String, List<String>>>()
        stream.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith(";;;") || line.isBlank()) return@forEach
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@forEach
                val word = parts[0]
                // Skip alternate-pronunciation variants like "READ(1)", and keep only words made
                // of letters and apostrophes. This filter has to match the trainer's `Corpus`
                // exactly — including admitting the apostrophe — or the two disagree about which
                // words are in the held-out set and report different numbers for the same model.
                if (word.contains('(') || word.any { it !in 'a'..'z' && it != '\'' }) return@forEach
                if (!TrainingSplit.isHeldOut(word)) return@forEach
                entries += word to parts[1].split(" ")
            }
        }
        check(entries.size > 1000) { "held-out split looks wrong: only ${entries.size} words" }
        return entries
    }

    private fun stripStress(phoneme: String): String = phoneme.trimEnd { it.isDigit() }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                current[j] = if (a[i - 1] == b[j - 1]) {
                    previous[j - 1]
                } else {
                    1 + minOf(previous[j], current[j - 1], previous[j - 1])
                }
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }
}
