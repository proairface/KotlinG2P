package io.github.proairface.kotling2p.trainer

import io.github.proairface.kotling2p.LtsModel

/** How a model scored on words it was never trained on. */
data class Score(
    val words: Int,
    val exactWithStress: Double,
    val exactIgnoringStress: Double,
    val phonemeErrorRate: Double,
) {
    override fun toString(): String = buildString {
        appendLine("  words scored:                    $words")
        appendLine("  word accuracy (with stress):     %.1f%%".format(exactWithStress * 100))
        appendLine("  word accuracy (ignoring stress): %.1f%%".format(exactIgnoringStress * 100))
        append("  phoneme error rate:              %.1f%%".format(phonemeErrorRate * 100))
    }
}

object Evaluation {

    /**
     * Scores [model] against [entries], which must be the held-out split — a model trained on
     * CMUdict scores near-perfectly on CMUdict words it has already seen, so measuring on
     * anything else would only measure memorization.
     */
    fun score(model: LtsModel, entries: List<Entry>): Score {
        var exact = 0
        var exactBase = 0
        var errorRate = 0.0
        for (entry in entries) {
            val predicted = model.predict(entry.word).map { it.arpabet }
            if (predicted == entry.phonemes) exact++
            val predictedBase = predicted.map(::stripStress)
            val actualBase = entry.phonemes.map(::stripStress)
            if (predictedBase == actualBase) exactBase++
            errorRate += editDistance(predictedBase, actualBase).toDouble() / actualBase.size.coerceAtLeast(1)
        }
        val total = entries.size.coerceAtLeast(1)
        return Score(entries.size, exact.toDouble() / total, exactBase.toDouble() / total, errorRate / total)
    }

    /** Prints the words the model gets wrong, which is the only way to see *how* it is wrong. */
    fun printMistakes(model: LtsModel, entries: List<Entry>, limit: Int) {
        println("sample held-out mistakes:")
        entries.asSequence()
            .map { it to model.predict(it.word).map { phoneme -> phoneme.arpabet } }
            .filter { (entry, predicted) ->
                predicted.map(::stripStress) != entry.phonemes.map(::stripStress)
            }
            .take(limit)
            .forEach { (entry, predicted) ->
                println("  %-18s want %-34s got %s".format(
                    entry.word, entry.phonemes.joinToString(" "), predicted.joinToString(" "),
                ))
            }
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
