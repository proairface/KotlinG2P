package io.github.proairface.kotling2p

/**
 * Assembles a full espeak-ng-style IPA transcription from G2P output — the format a
 * Piper-trained voice model actually expects. Two placement rules, both confirmed by
 * running espeak-ng directly and comparing output (see EspeakIpaTest), not assumed from
 * textbook IPA:
 *
 * - Primary/secondary stress markers (ˈ, ˌ) go immediately before the stressed vowel's own
 *   symbol, not before the syllable's onset consonants ("street" -> "stɹˈiːt", mark after
 *   the "str" cluster).
 * - American-English flapping: an intervocalic T followed by an unstressed vowel becomes a
 *   tap (ɾ) — confirmed via "letter" -> "lˈɛɾɚ", not "lˈɛtɚ". D does NOT flap the same way in
 *   espeak-ng's output — "murder" stays "mˈɜːdɚ", not "mˈɜːɾɚ" — confirmed by testing it
 *   directly rather than assuming T and D behave alike.
 *
 * Words are joined with a literal space, matching Piper's phoneme_id_map, which has an
 * explicit space entry as a first-class token — Piper's own tokenizer treats the whole
 * transcription as a flat sequence of individual characters, this one included, not a
 * sequence of linguistic phoneme units.
 */
object EspeakIpa {
    private val flappableVowels = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW",
    )

    fun transcribe(pronunciations: List<WordPronunciation>): String =
        pronunciations.joinToString(" ") { transcribeWord(it.phonemes) }

    private fun transcribeWord(phonemes: List<Phoneme>): String {
        val symbols = phonemes.mapIndexed { index, phoneme ->
            ArpabetToIpa.toIpa(phoneme, isWordInitial = index == 0)
        }.toMutableList()

        for (i in phonemes.indices) {
            val base = phonemes[i].base
            if (base != "T") continue
            val prevIsVowel = phonemes.getOrNull(i - 1)?.base in flappableVowels
            val next = phonemes.getOrNull(i + 1)
            val nextIsUnstressedVowel = next != null &&
                next.base in flappableVowels &&
                (next.stress ?: 0) == 0
            if (prevIsVowel && nextIsUnstressedVowel) symbols[i] = "ɾ"
        }

        return phonemes.indices.joinToString("") { index ->
            val marker = when (phonemes[index].stress) {
                1 -> "ˈ"
                2 -> "ˌ"
                else -> ""
            }
            marker + symbols[index]
        }
    }
}
