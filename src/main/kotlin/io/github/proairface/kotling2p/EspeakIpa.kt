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
 *
 * [UNSTRESSED_FUNCTION_WORDS] destresses closed-class words the same way [DutchG2P] already
 * does for Dutch — CMUdict gives each word a single citation-form stress digit (e.g. "at" is
 * "AE1 T", "on" is "AA1 N"), but real espeak-ng contextually destresses these in connected
 * speech; confirmed directly against the real `piper_phonemize` Python package (the same
 * clause-phonemization path Piper's own training data went through, not the plain `espeak-ng`
 * CLI, which turned out to *not* match: its default `--ipa` mode drops punctuation entirely,
 * while `piper_phonemize.phonemize_espeak` emits it as a literal character) — e.g. "arrived at
 * your destination" -> "...æt..." with no stress mark, "sat on the mat" -> "...ɔnðə..." with
 * none either, both of which this class used to mark `ˈ`. This is a fixed set, not a full
 * prosody model — real espeak-ng is context-sensitive (`ˌɔn` sometimes DOES carry stress, e.g.
 * "it is on your left"), so this is a directional improvement, same acknowledged imperfection
 * as [DutchG2P]'s own destress list, not a claim of perfect accuracy.
 */
object EspeakIpa {
    private val flappableVowels = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW",
    )

    /** See the class doc comment. Checked individually against real `piper_phonemize` output —
     * each entry here was confirmed to actually lose its stress mark in a real sentence, not
     * just assumed from closed-class part of speech. */
    private val UNSTRESSED_FUNCTION_WORDS = setOf(
        "A", "AN", "THE",
        "AND", "OR", "BUT",
        "OF", "TO", "IN", "ON", "AT", "FOR", "WITH", "BY", "FROM", "AS",
        "IS", "IT",
    )

    fun transcribe(pronunciations: List<WordPronunciation>): String =
        pronunciations.joinToString(" ") { pronunciation ->
            transcribeWord(pronunciation.phonemes, destress = pronunciation.word in UNSTRESSED_FUNCTION_WORDS)
        }

    private fun transcribeWord(phonemes: List<Phoneme>, destress: Boolean): String {
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
            val marker = if (destress) "" else when (phonemes[index].stress) {
                1 -> "ˈ"
                2 -> "ˌ"
                else -> ""
            }
            marker + symbols[index]
        }
    }
}
