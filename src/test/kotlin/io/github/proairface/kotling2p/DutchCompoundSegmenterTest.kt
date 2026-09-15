package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals

class DutchCompoundSegmenterTest {

    // A small dictionary standing in for the bundled OpenTaal wordlist, covering exactly the
    // pieces these tests need -- keeps the test independent of the real 391k-word resource.
    private val dictionary = setOf(
        "kerk", "hoofd", "amstel", "prinsen", "juliana", "beatrix", "wilhelmina", "nieuwen",
        "centrum", "cent", "rum", "verkeer", "informatie", "eindhoven", "benzine", "station",
    )

    @Test
    fun `a place-suffix compound splits on the suffix`() {
        assertEquals(listOf("kerk", "straat"), DutchCompoundSegmenter.constituents("kerkstraat", dictionary))
        assertEquals(listOf("hoofd", "weg"), DutchCompoundSegmenter.constituents("hoofdweg", dictionary))
    }

    @Test
    fun `a place-suffix compound whose whole form is itself in the dictionary still splits`() {
        // Mirrors the real "amstelveen" case: PLACE_SUFFIXES is tried before the general
        // fewest-real-pieces DP, which would otherwise prefer the single atomic whole-word match.
        val withWholeWord = dictionary + "amstelveen"
        assertEquals(listOf("amstel", "veen"), DutchCompoundSegmenter.constituents("amstelveen", withWholeWord))
    }

    @Test
    fun `a linking segment between two real words is folded into the first constituent`() {
        assertEquals(
            listOf("prinsen", "gracht"),
            DutchCompoundSegmenter.constituents("prinsengracht", dictionary),
        )
    }

    @Test
    fun `a word containing two real dictionary words is not spuriously split`() {
        // "centrum" = "cent" + "rum" are both real dictionary words, but the whole word already
        // wins as a single real piece -- this is exactly the false-split case the segmenter must
        // avoid.
        val withWholeWord = dictionary + "centrum"
        assertEquals(listOf("centrum"), DutchCompoundSegmenter.constituents("centrum", withWholeWord))
    }

    @Test
    fun `an ordinary word with no decomposition is returned unchanged`() {
        assertEquals(listOf("eindhoven"), DutchCompoundSegmenter.constituents("eindhoven", dictionary))
    }

    @Test
    fun `a multi-constituent compound with no place suffix still splits via the general dp`() {
        // The "s" linker (tussenklank) between "verkeer" and "informatie" is folded onto the
        // preceding constituent for display, same as the "en" linker in "nieuwen"+"dijk".
        assertEquals(
            listOf("verkeers", "informatie"),
            DutchCompoundSegmenter.constituents("verkeersinformatie", dictionary),
        )
    }
}
