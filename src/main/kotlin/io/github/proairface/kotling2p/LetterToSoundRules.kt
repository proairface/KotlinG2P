package io.github.proairface.kotling2p

/**
 * A deliberately simple, original (not derived from espeak-ng or any other GPL source)
 * letter-to-sound fallback for words CMUdict doesn't know — mostly proper nouns and place
 * names, exactly what a routing app has to speak.
 *
 * Pipeline: strip a known suffix if the word ends with one (so "-ING" isn't guessed letter
 * by letter), collapse doubled consonants (so "LETTER" isn't read as two T's), then scan the
 * remaining letters left to right against a table of common English graphemes, falling back
 * to a per-letter default. Vowel stress is assigned afterward: the first vowel in the whole
 * guess gets primary stress, every other vowel gets none — approximating CMUdict's
 * convention rather than computing it linguistically.
 *
 * This is a greedy scanner, not a trained model or a full English-phonology engine. Known,
 * deliberate simplifications: soft C/G before E/I/Y is treated as a rule rather than the
 * lexical exception list English actually needs (so "GET"/"GIRL" guess wrong — hard G is
 * common after E/I too, there's no clean rule for it); Y's pronunciation is genuinely
 * context-dependent (consonant at the start of a word before a vowel, otherwise a vowel) and
 * only that one distinction is handled. See LetterToSoundRulesBenchmarkTest for measured
 * accuracy against real CMUdict entries, not a marketing claim.
 */
object LetterToSoundRules {

    private val vowelBases = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW",
    )
    private val vowelLetters = setOf('A', 'E', 'I', 'O', 'U')
    private val softCgTriggers = setOf('E', 'I', 'Y')

    // Checked only when the word actually ends with the pattern, longest first — safer than
    // treating these as ordinary mid-word graphemes, since a short one like "ER" or "ES"
    // would misfire constantly if matched anywhere in a word rather than anchored to the end.
    private val suffixRules: List<Pair<String, List<String>>> = listOf(
        "ABLE" to listOf("AH", "B", "AH", "L"),
        "IBLE" to listOf("AH", "B", "AH", "L"),
        "MENT" to listOf("M", "AH", "N", "T"),
        "NESS" to listOf("N", "AH", "S"),
        "LESS" to listOf("L", "AH", "S"),
        "TION" to listOf("SH", "AH", "N"),
        "SION" to listOf("ZH", "AH", "N"),
        "OUS" to listOf("AH", "S"),
        "IVE" to listOf("IH", "V"),
        "ING" to listOf("IH", "NG"),
        "EST" to listOf("AH", "S", "T"),
        "ER" to listOf("ER"),
        "LY" to listOf("L", "IY"),
        "ES" to listOf("IH", "Z"),
        "ED" to listOf("D"),
        "AL" to listOf("AH", "L"),
    ).sortedByDescending { it.first.length }

    // Tried longest-first, before falling back to single letters.
    private val multiLetterRules: List<Pair<String, List<String>>> = listOf(
        "OUGH" to listOf("AH", "F"),
        "AUGH" to listOf("AA", "F"),
        "EIGH" to listOf("EY"),
        "IGH" to listOf("AY"),
        "TCH" to listOf("CH"),
        "DGE" to listOf("JH"),
        "CIA" to listOf("SH", "AH"),
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
        "OO" to listOf("UW"),
        "EE" to listOf("IY"),
        "EA" to listOf("IY"),
        "AI" to listOf("EY"),
        "AY" to listOf("EY"),
        "OA" to listOf("OW"),
        "OW" to listOf("OW"),
        "OU" to listOf("AW"),
        "OI" to listOf("OY"),
        "OY" to listOf("OY"),
        "AU" to listOf("AO"),
        "AW" to listOf("AO"),
        "EW" to listOf("UW"),
        "IE" to listOf("IY"),
        "EY" to listOf("IY"),
        "PS" to listOf("S"),
    )

    private val singleLetterRules: Map<Char, List<String>> = mapOf(
        'A' to listOf("AE"), 'B' to listOf("B"), 'C' to listOf("K"), 'D' to listOf("D"),
        'E' to listOf("EH"), 'F' to listOf("F"), 'G' to listOf("G"), 'H' to listOf("HH"),
        'I' to listOf("IH"), 'J' to listOf("JH"), 'K' to listOf("K"), 'L' to listOf("L"),
        'M' to listOf("M"), 'N' to listOf("N"), 'O' to listOf("AA"), 'P' to listOf("P"),
        'Q' to listOf("K"), 'R' to listOf("R"), 'S' to listOf("S"), 'T' to listOf("T"),
        'U' to listOf("AH"), 'V' to listOf("V"), 'W' to listOf("W"), 'X' to listOf("K", "S"),
        'Y' to listOf("IY"), 'Z' to listOf("Z"),
    )

    /** Always returns at least one phoneme for any input with at least one letter. */
    fun guess(word: String): List<Phoneme> {
        val letters = word.uppercase().filter { it.isLetter() }
        if (letters.isEmpty()) return emptyList()
        return assignStress(guessLetters(letters)).map { Phoneme(it) }
    }

    private fun guessLetters(letters: String): List<String> {
        val suffix = suffixRules.firstOrNull { (pattern, _) ->
            letters.length > pattern.length && letters.endsWith(pattern)
        }
        val stem = if (suffix != null) letters.dropLast(suffix.first.length) else letters
        val stemPhonemes = scanGraphemes(collapseDoubledConsonants(stem))
        return if (suffix != null) stemPhonemes + suffix.second else stemPhonemes
    }

    private fun collapseDoubledConsonants(letters: String): String {
        val result = StringBuilder()
        for (c in letters) {
            if (result.isNotEmpty() && result.last() == c && c !in vowelLetters) continue
            result.append(c)
        }
        return result.toString()
    }

    private fun scanGraphemes(letters: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < letters.length) {
            val multi = multiLetterRules.firstOrNull { (grapheme, _) -> letters.startsWith(grapheme, i) }
            if (multi != null) {
                result += multi.second
                i += multi.first.length
                continue
            }
            val letter = letters[i]
            val nextLetter = letters.getOrNull(i + 1)
            val isSilentTrailingE = letter == 'E' && i == letters.length - 1 && i > 0
            when {
                isSilentTrailingE -> Unit
                letter == 'Y' && i == 0 && nextLetter != null && nextLetter in vowelLetters -> result += "Y"
                letter == 'C' && nextLetter != null && nextLetter in softCgTriggers -> result += "S"
                letter == 'G' && nextLetter != null && nextLetter in softCgTriggers -> result += "JH"
                else -> result += singleLetterRules[letter].orEmpty()
            }
            i += 1
        }
        return result
    }

    // Tried and measured: blanket-reducing every non-first vowel to schwa (real English's
    // actual dominant pattern for unstressed vowels) sounded right but benchmarked *worse*
    // (9.3% exact match vs. 14.3% keeping each vowel's guessed identity) — this sample skews
    // toward words where stress isn't on the first syllable, so "assume first vowel is
    // stressed, schwa everything else" was often wrong about *which* vowel to reduce. Kept
    // simple instead: every vowel keeps its guessed identity, only the digit marks stress.
    private fun assignStress(phones: List<String>): List<String> {
        var seenVowel = false
        return phones.map { base ->
            if (base !in vowelBases) return@map base
            if (!seenVowel) {
                seenVowel = true
                "${base}1"
            } else {
                "${base}0"
            }
        }
    }
}
