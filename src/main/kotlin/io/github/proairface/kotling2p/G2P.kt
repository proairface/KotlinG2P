package io.github.proairface.kotling2p

/**
 * Public entry point: English text in, phonemes out.
 *
 * Every word gets *some* pronunciation. Words in CMUdict are looked up verbatim; everything else
 * goes to [LtsModel], which is trained on CMUdict rather than hand-written, and gets about two
 * words in three exactly right on words it has never seen. Unlike G2P engines that emit a
 * placeholder for out-of-dictionary words, this never fails a word — a routing app has no
 * acceptable way to skip a street name it doesn't recognize.
 *
 * Construction loads the bundled dictionary and model, which is the expensive part; hold on to
 * the instance rather than making one per call.
 */
class G2P(
    private val dictionary: CmuDict = CmuDict.loadBundled(),
    private val letterToSound: LtsModel = LtsModel.loadBundled(),
) {

    fun toPhonemes(text: String): List<WordPronunciation> =
        TextNormalizer.tokenize(text).map { word -> pronounce(word) }

    /**
     * Espeak-ng-style IPA transcription, ready for a Piper-trained voice model's phoneme
     * vocabulary — see [EspeakIpa] for exactly what that means and how it was verified.
     *
     * Clause-ending punctuation (`,.!?;:`) is preserved as a literal character attached to the
     * end of its clause, same convention [DutchG2P] already uses for sentence-enders — confirmed
     * directly against the real `piper_phonemize` Python package (the actual clause-phonemization
     * path Piper's own training data went through): `phonemize_espeak("...meters, turn right...")`
     * really does emit a literal `,` token mid-sequence, immediately after the preceding word with
     * no space before it, matching a Piper voice's own phoneme vocabulary, which has explicit
     * `,`/`.`/`!`/`?`/`;`/`:` entries alongside its IPA symbols (confirmed directly by inspecting
     * a real voice's `phoneme_id_map`, not assumed).
     * [TextNormalizer.tokenize] still strips punctuation from individual word tokens (a trailing
     * "St." still becomes "STREET" with no stray period) — this split happens on the raw text
     * first, before tokenization, so the two don't conflict.
     */
    fun toEspeakIpa(text: String): String {
        val clauses = CLAUSE_PUNCTUATION.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val marks = CLAUSE_PUNCTUATION.findAll(text).map { it.value }.toList()
        return clauses.mapIndexed { index, clause ->
            EspeakIpa.transcribe(toPhonemes(clause)) + (marks.getOrNull(index) ?: "")
        }.joinToString(" ")
    }

    private fun pronounce(word: String): WordPronunciation {
        dictionary.lookup(word)?.let { return WordPronunciation(word, it, PronunciationSource.DICTIONARY) }
        return WordPronunciation(word, letterToSound.predict(word), PronunciationSource.LETTER_TO_SOUND)
    }

    private companion object {
        val CLAUSE_PUNCTUATION = Regex("""([,.!?;:])""")
    }
}
