package io.github.proairface.kotling2p

/**
 * Public entry point: English text in, phonemes out. Every word gets *some* pronunciation —
 * unlike G2P engines that emit a placeholder for out-of-dictionary words, this always falls
 * back to [LetterToSoundRules] rather than failing silently, since a routing app has no
 * acceptable way to skip a street name it doesn't recognize.
 */
class G2P(private val dictionary: CmuDict = CmuDict.loadBundled()) {

    fun toPhonemes(text: String): List<WordPronunciation> =
        TextNormalizer.tokenize(text).map { word -> pronounce(word) }

    /** Espeak-ng-style IPA transcription, ready for a Piper-trained voice model's phoneme
     * vocabulary — see [EspeakIpa] for exactly what that means and how it was verified. */
    fun toEspeakIpa(text: String): String = EspeakIpa.transcribe(toPhonemes(text))

    private fun pronounce(word: String): WordPronunciation {
        dictionary.lookup(word)?.let { return WordPronunciation(word, it, PronunciationSource.DICTIONARY) }
        return WordPronunciation(word, LetterToSoundRules.guess(word), PronunciationSource.RULES)
    }
}
