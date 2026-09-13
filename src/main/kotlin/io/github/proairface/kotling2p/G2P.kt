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

    /** Espeak-ng-style IPA transcription, ready for a Piper-trained voice model's phoneme
     * vocabulary — see [EspeakIpa] for exactly what that means and how it was verified. */
    fun toEspeakIpa(text: String): String = EspeakIpa.transcribe(toPhonemes(text))

    private fun pronounce(word: String): WordPronunciation {
        dictionary.lookup(word)?.let { return WordPronunciation(word, it, PronunciationSource.DICTIONARY) }
        return WordPronunciation(word, letterToSound.predict(word), PronunciationSource.LETTER_TO_SOUND)
    }
}
