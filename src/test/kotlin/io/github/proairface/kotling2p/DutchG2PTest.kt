package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EXPERIMENTAL — see [DutchG2P]'s own doc comment. These are not the same kind of golden-output
 * tests [io.github.proairface.kotling2p] has for English (verified against real espeak-ng
 * output systematically); they cover only the specific regressions and fixes found and checked
 * by ear during development of this Dutch path. [DutchCompoundSegmenter] gives street/place-name
 * compounds first-constituent primary stress (see the tests below), but this covers only that
 * specific case — an arbitrary compound or proper name not in [DutchG2P.KNOWN_WORDS] or
 * [DutchG2P.NAME_STRESS_OVERRIDES] is still not expected to be reliably correct, and secondary
 * stress on trailing constituents is deliberately not attempted at all — see the class doc
 * comment.
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

    @Test
    fun `a plain street-name compound stresses only its first constituent`() {
        // "kerkstraat" = kerk + straat: primary stress belongs on "kerk", and "straat" should
        // carry none -- without the segmenter, the old default (first non-schwa vowel of the
        // whole unsplit word) got this right by accident; this exercises the real split.
        assertEquals("kˈɛrkstraːt", g2p.toEspeakIpa("kerkstraat"))
    }

    @Test
    fun `an opaque proper name keeps its own internal stress across every compound using it`() {
        // "juliana" needs a hand-verified override ([DutchG2P.NAME_STRESS_OVERRIDES]) since the
        // default heuristic gets its internal stress wrong; the same override must apply no
        // matter which street/place suffix follows it, and the suffix itself stays unstressed.
        assertEquals("jˌyliˈaːnaːlaːn", g2p.toEspeakIpa("julianalaan"))
        assertEquals("jˌyliˈaːnaːplɛɪn", g2p.toEspeakIpa("julianaplein"))
    }

    @Test
    fun `beatrix stresses its first syllable with full, unreduced vowels`() {
        // Regression test for a real bug: this override was originally verified against
        // espeak-ng's own output alone (bəˈɑtrɪks -- schwa-reduced first syllable, stress on the
        // second), which turned out to itself be wrong. Real Wiktionary IPA is
        // /ˈbeː.aː.trɪks/ -- stress on the FIRST syllable, with two full vowels, not one reduced
        // to schwa. espeak-ng's rule-based Dutch G2P mishandled this specific (Latinate) name.
        assertEquals("bˈeːaːtrɪks", g2p.toEspeakIpa("beatrix"))
        assertEquals("bˈeːaːtrɪksplɛɪn", g2p.toEspeakIpa("beatrixplein"))
    }

    @Test
    fun `a compound whose suffix could itself be mistaken for a dictionary word still splits`() {
        // "amstelveen" = amstel + veen: exercises the FINAL_STRESS_PLACE_SUFFIXES pre-pass, since
        // "amstelveen" is itself individually listed in the bundled wordlist (it's a real town),
        // which would otherwise make the general fewest-real-pieces segmenter treat it as one
        // atomic piece instead of finding the boundary. Stress lands on "veen", not "amstel" --
        // per ANS 1.6.5.1 (11a), place names ending in -veen (like -dam/-meer/-waard) are always
        // stressed on the second part, confirmed against real Wiktionary IPA for Amsterdam/
        // Rotterdam.
        assertEquals("ɑmstɛlvˈeːn", g2p.toEspeakIpa("amstelveen"))
    }

    @Test
    fun `-dam place names are stressed on the second part, not the first`() {
        // ANS 1.6.5.1 (11a), cross-checked against real Wiktionary IPA: Amsterdam
        // /ˌɑm.stərˈdɑm/, Rotterdam /ˌrɔ.tərˈdɑm/ -- both stress the final syllable. "amster" and
        // "rotter" aren't real standalone Dutch words, but the suffix match alone drives the
        // split (same as any other PLACE_SUFFIXES case), so that's not required.
        assertEquals("ɑmstərdˈɑm", g2p.toEspeakIpa("amsterdam"))
        assertEquals("rɔtərdˈɑm", g2p.toEspeakIpa("rotterdam"))
    }

    @Test
    fun `an ordinary non-compound word is not spuriously split`() {
        // "centrum" contains two real Dutch words ("cent", "rum"), which is exactly the kind of
        // wrong split the segmenter's dictionary approach has to avoid.
        val ipa = g2p.toEspeakIpa("centrum")
        assertEquals(1, ipa.count { it == 'ˈ' })
    }
}
