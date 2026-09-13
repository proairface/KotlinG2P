package io.github.proairface.kotling2p

/**
 * ARPAbet -> IPA mapping, tuned to match real espeak-ng output (the phonemizer Piper voices
 * were trained against) rather than textbook IPA — verified by running espeak-ng directly
 * and comparing outputs (see EspeakIpaTest). A few things a naive ARPAbet->IPA table gets
 * wrong for this purpose:
 *
 * - Stress changes which SYMBOL is used, not just whether it's marked, for AH (ʌ stressed /
 *   ə unstressed) and ER (ɜː stressed / ɚ unstressed — note ɜː, not the textbook "ɝ", which
 *   doesn't even exist in Piper's phoneme vocabulary; feeding it a real Piper voice would
 *   produce an out-of-vocabulary token).
 * - Word-initial unstressed AH specifically comes out as ɐ, not ə — confirmed across
 *   "about", "again", "amused"; this is one specific, confirmed case, not a general theory
 *   of every reduction context (espeak-ng also uses ɐ/ə/ᵻ for other unstressed vowels in
 *   ways plain ARPAbet has no way to distinguish — see EspeakIpaTest's "telephone" case).
 * - Tense vowels (IY/UW/AO/AA) get a trailing length mark (ː) when stressed, dropped
 *   otherwise.
 */
object ArpabetToIpa {
    private val consonants = mapOf(
        "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "ɡ", "HH" to "h",
        "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "P" to "p",
        "R" to "ɹ", "S" to "s", "SH" to "ʃ", "T" to "t", "TH" to "θ", "V" to "v", "W" to "w",
        "Y" to "j", "Z" to "z", "ZH" to "ʒ",
    )

    // Vowels whose symbol doesn't depend on stress.
    private val plainVowels = mapOf(
        "AE" to "æ", "AW" to "aʊ", "AY" to "aɪ", "EH" to "ɛ", "EY" to "eɪ",
        "OW" to "oʊ", "OY" to "ɔɪ", "UH" to "ʊ", "IH" to "ɪ",
    )

    // Tense vowels: length mark when stressed, dropped otherwise. IY/UW/AO confirmed
    // directly against espeak-ng output; AA follows the same pattern by inference only —
    // espeak-ng wasn't run against an AA-specific test word during verification.
    private val tenseStressed = mapOf("IY" to "iː", "UW" to "uː", "AO" to "ɔː", "AA" to "ɑː")
    private val tenseUnstressed = mapOf("IY" to "i", "UW" to "u", "AO" to "ɔ", "AA" to "ɑ")

    /**
     * Converts one phoneme to IPA. [isWordInitial] only affects unstressed AH (ɐ at the
     * start of a word, ə elsewhere) — pass true for a word's first phoneme; see [EspeakIpa],
     * which handles this automatically for full-word/sentence transcription.
     */
    fun toIpa(phoneme: Phoneme, isWordInitial: Boolean = false): String {
        val base = phoneme.base
        val stressed = (phoneme.stress ?: 0) >= 1

        consonants[base]?.let { return it }
        plainVowels[base]?.let { return it }
        tenseStressed[base]?.let { return if (stressed) it else tenseUnstressed.getValue(base) }
        if (base == "AH") return if (stressed) "ʌ" else if (isWordInitial) "ɐ" else "ə"
        if (base == "ER") return if (stressed) "ɜː" else "ɚ"
        return base
    }
}
