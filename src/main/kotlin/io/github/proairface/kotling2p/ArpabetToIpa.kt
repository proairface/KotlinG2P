package io.github.proairface.kotling2p

/**
 * Static ARPAbet -> IPA mapping (the standard CMUdict phoneme inventory). Provided so
 * downstream consumers that need IPA (some neural TTS front-ends do) aren't stuck with
 * ARPAbet-only output. This table is documented linguistic convention, not derived from any
 * third-party codebase.
 */
object ArpabetToIpa {
    private val map = mapOf(
        "AA" to "ɑ", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔ", "AW" to "aʊ", "AY" to "aɪ",
        "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "EH" to "ɛ", "ER" to "ɝ",
        "EY" to "eɪ", "F" to "f", "G" to "ɡ", "HH" to "h", "IH" to "ɪ", "IY" to "i",
        "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ",
        "OW" to "oʊ", "OY" to "ɔɪ", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ",
        "T" to "t", "TH" to "θ", "UH" to "ʊ", "UW" to "u", "V" to "v", "W" to "w",
        "Y" to "j", "Z" to "z", "ZH" to "ʒ",
    )

    /** Converts one phoneme to IPA using its base symbol (stress digit stripped). */
    fun toIpa(phoneme: Phoneme): String = map[phoneme.base] ?: phoneme.base
}
