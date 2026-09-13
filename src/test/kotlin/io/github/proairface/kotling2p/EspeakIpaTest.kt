package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares G2P.toEspeakIpa against real `espeak-ng -v en-us --ipa` output, captured directly
 * (not assumed) while investigating Piper-voice compatibility. This is the actual phoneme
 * vocabulary and stress-placement convention a Piper-trained voice model expects, so this
 * test is the real compatibility bar — not textbook IPA correctness.
 *
 * Words that match exactly are asserted as exact matches. Two words are documented,
 * confirmed exceptions rather than silently skipped or forced to pass:
 *
 * - "telephone": espeak-ng uses ᵻ (a special unstressed I/schwa-merger vowel) for the
 *   second syllable; plain ARPAbet/CMUdict has no equivalent distinction to recover this
 *   from — CMUdict just has AH0 there, same as any other reduced vowel.
 * - "letter": espeak-ng's flapping rule is replicated (T -> ɾ intervocalically before an
 *   unstressed vowel), so this actually matches exactly. "murder" is the confirming
 *   counter-example that keeps the rule narrow: its D does *not* flap in real espeak-ng
 *   output ("mˈɜːdɚ", not "mˈɜːɾɚ"), which is why flapping only triggers on T, not D.
 */
class EspeakIpaTest {

    private val g2p = G2P()

    private fun assertMatchesEspeakNg(word: String, expectedFromEspeakNg: String) {
        assertEquals(expectedFromEspeakNg, g2p.toEspeakIpa(word), "mismatch for '$word'")
    }

    @Test fun hello() = assertMatchesEspeakNg("hello", "həlˈoʊ")
    @Test fun baker() = assertMatchesEspeakNg("baker", "bˈeɪkɚ")
    @Test fun street() = assertMatchesEspeakNg("street", "stɹˈiːt")
    @Test fun williams() = assertMatchesEspeakNg("williams", "wˈɪljəmz")
    @Test fun walked() = assertMatchesEspeakNg("walked", "wˈɔːkt")
    @Test fun twenty() = assertMatchesEspeakNg("twenty", "twˈɛnti")
    @Test fun first() = assertMatchesEspeakNg("first", "fˈɜːst")
    @Test fun sofa() = assertMatchesEspeakNg("sofa", "sˈoʊfə")
    @Test fun murder() = assertMatchesEspeakNg("murder", "mˈɜːdɚ")
    @Test fun letter() = assertMatchesEspeakNg("letter", "lˈɛɾɚ")
    @Test fun about() = assertMatchesEspeakNg("about", "ɐbˈaʊt")
    @Test fun again() = assertMatchesEspeakNg("again", "ɐɡˈɛn")
    @Test fun amused() = assertMatchesEspeakNg("amused", "ɐmjˈuːzd")

    @Test
    fun telephoneIsAKnownAndDocumentedGap() {
        // espeak-ng: "tˈɛlᵻfˌoʊn" -- the ᵻ can't be recovered from ARPAbet's plain AH0.
        // This asserts what we actually produce, so a future change to this specific
        // behavior is a deliberate decision, not a silent regression.
        assertEquals("tˈɛləfˌoʊn", g2p.toEspeakIpa("telephone"))
    }
}
