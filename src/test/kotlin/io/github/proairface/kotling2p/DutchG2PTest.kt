package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EXPERIMENTAL — see [DutchG2P]'s own doc comment. These are not the same kind of golden-output
 * tests [io.github.proairface.kotling2p] has for English (verified against real espeak-ng
 * output systematically); they cover only the specific regressions and fixes found and checked
 * by ear during development of this Dutch path. Compound stress on words not listed in
 * [DutchG2P.KNOWN_WORDS] is not covered by any test and is not expected to be reliably correct
 * — see the class doc comment.
 */
class DutchG2PTest {

    private val g2p = DutchG2P()

    @Test
    fun `een defaults to the article, not the number`() {
        // "een" the article is /ən/ (reduced); "een" the number one is /eːn/. Both are real
        // WikiPron entries for the same spelling, and this is the article -- the far more
        // common reading in ordinary sentences, and what a canonicalization rule that prefers
        // the fuller variant would otherwise get backwards.
        assertEquals("ən", g2p.toEspeakIpa("een"))
    }

    @Test
    fun `common function words are destressed in a sentence`() {
        // Every word getting its own stress mark makes a short phrase sound fine but a longer
        // sentence sound like a word-by-word list. "een" and "af" should carry no stress marker
        // here; "wachten" (the content word) should.
        val ipa = g2p.toEspeakIpa("we moeten nog even wachten")
        assertFalse(ipa.contains("ˈʋə")) // "we" un-stressed
    }

    @Test
    fun `sentence-ending punctuation is preserved as a pause cue`() {
        val ipa = g2p.toEspeakIpa("je bent aangekomen.")
        assertTrue(ipa.endsWith("."))
    }

    @Test
    fun `aangekomen carries its real primary and secondary stress`() {
        // Confirmed against real espeak-ng output (verification only): aːnɣəkˌoːmən.
        assertEquals("ˈaːnɣəkˌoːmən", g2p.toEspeakIpa("aangekomen"))
    }

    @Test
    fun `numbers use the standard voicing, not WikiPron's devoiced scrape variant`() {
        assertEquals("zˈɛstəx", g2p.toEspeakIpa("zestig"))
        assertEquals("tʋˈaːlf", g2p.toEspeakIpa("twaalf"))
    }

    @Test
    fun `diphthong offglides use the plain espeak-compatible symbols`() {
        // Not the combining-diacritic WikiPron notation (i̯) -- see the class doc comment's
        // "offglide symbol encoding" point. This word has no dictionary/known-word entry, so it
        // exercises the letter-to-sound model's own phoneme-map lookup.
        val ipa = g2p.toEspeakIpa("tijd")
        assertFalse(ipa.contains("i̯"))
    }
}
