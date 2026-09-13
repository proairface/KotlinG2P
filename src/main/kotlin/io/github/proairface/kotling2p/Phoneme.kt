package io.github.proairface.kotling2p

/**
 * A single ARPAbet phoneme, e.g. "T", "AH0", "ER1". The trailing digit on vowel phonemes is
 * CMUdict's stress marker: 0 = no stress, 1 = primary, 2 = secondary. Consonants carry no digit.
 */
data class Phoneme(val arpabet: String) {
    val base: String get() = arpabet.trimEnd { it.isDigit() }
    val stress: Int? get() = arpabet.lastOrNull { it.isDigit() }?.digitToIntOrNull()
    override fun toString(): String = arpabet
}

/** Where a word's pronunciation came from — useful for callers that want to know when the
 * fallback rules (rather than the dictionary) had to guess. */
enum class PronunciationSource { DICTIONARY, RULES }

/** The phonemes produced for one input word, and which path produced them. */
data class WordPronunciation(
    val word: String,
    val phonemes: List<Phoneme>,
    val source: PronunciationSource,
)
