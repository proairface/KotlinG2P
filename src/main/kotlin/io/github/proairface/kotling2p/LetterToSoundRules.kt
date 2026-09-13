package io.github.proairface.kotling2p

/**
 * A deliberately simple, original (not derived from espeak-ng or any other GPL source)
 * letter-to-sound fallback for words CMUdict doesn't know — mostly proper nouns and place
 * names, exactly what a routing app has to speak. It's a greedy longest-match grapheme
 * scanner, not a trained model: expect it to do reasonably on regular English spelling and
 * to guess roughly on genuinely unusual names.
 *
 * The goal is "always says something pronounceable," not phonetic accuracy — see the tests
 * for what it actually gets right today, not a marketing claim. A trained neural fallback
 * (the g2pE pattern: a small seq2seq model over CMUdict) is the natural next step if this
 * proves too rough in practice.
 */
object LetterToSoundRules {

    // Tried longest-first, before falling back to single letters.
    private val multiLetterRules: List<Pair<String, List<String>>> = listOf(
        "TION" to listOf("SH", "AH0", "N"),
        "SION" to listOf("ZH", "AH0", "N"),
        "OUGH" to listOf("AH1", "F"),
        "AUGH" to listOf("AA1", "F"),
        "EIGH" to listOf("EY1"),
        "IGH" to listOf("AY1"),
        "TCH" to listOf("CH"),
        "DGE" to listOf("JH"),
        "CIA" to listOf("SH", "AH0"),
        "PH" to listOf("F"),
        "GH" to listOf("F"),
        "CK" to listOf("K"),
        "NG" to listOf("NG"),
        "QU" to listOf("K", "W"),
        "TH" to listOf("TH"),
        "SH" to listOf("SH"),
        "CH" to listOf("CH"),
        "WH" to listOf("W"),
        "KN" to listOf("N"),
        "WR" to listOf("R"),
        "OO" to listOf("UW1"),
        "EE" to listOf("IY1"),
        "EA" to listOf("IY1"),
        "AI" to listOf("EY1"),
        "AY" to listOf("EY1"),
        "OA" to listOf("OW1"),
        "OW" to listOf("OW1"),
        "OU" to listOf("AW1"),
        "OI" to listOf("OY1"),
        "OY" to listOf("OY1"),
        "AU" to listOf("AO1"),
        "AW" to listOf("AO1"),
        "EW" to listOf("UW1"),
        "IE" to listOf("IY1"),
        "EY" to listOf("IY1"),
        "PS" to listOf("S"),
    )

    private val singleLetterRules: Map<Char, List<String>> = mapOf(
        'A' to listOf("AE1"), 'B' to listOf("B"), 'C' to listOf("K"), 'D' to listOf("D"),
        'E' to listOf("EH1"), 'F' to listOf("F"), 'G' to listOf("G"), 'H' to listOf("HH"),
        'I' to listOf("IH1"), 'J' to listOf("JH"), 'K' to listOf("K"), 'L' to listOf("L"),
        'M' to listOf("M"), 'N' to listOf("N"), 'O' to listOf("AA1"), 'P' to listOf("P"),
        'Q' to listOf("K"), 'R' to listOf("R"), 'S' to listOf("S"), 'T' to listOf("T"),
        'U' to listOf("AH1"), 'V' to listOf("V"), 'W' to listOf("W"), 'X' to listOf("K", "S"),
        'Y' to listOf("IY1"), 'Z' to listOf("Z"),
    )

    /** Always returns at least one phoneme for any input with at least one letter. */
    fun guess(word: String): List<Phoneme> {
        val letters = word.uppercase().filter { it.isLetter() }
        if (letters.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var i = 0
        while (i < letters.length) {
            val match = multiLetterRules.firstOrNull { (grapheme, _) -> letters.startsWith(grapheme, i) }
            if (match != null) {
                result += match.second
                i += match.first.length
                continue
            }
            val letter = letters[i]
            val isSilentTrailingE = letter == 'E' && i == letters.length - 1 && i > 0
            if (!isSilentTrailingE) {
                result += singleLetterRules[letter].orEmpty()
            }
            i += 1
        }
        return result.map { Phoneme(it) }
    }
}
