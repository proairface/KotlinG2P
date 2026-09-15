package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals

class DutchCompoundSegmenterTest {

    // A small dictionary standing in for the bundled OpenTaal wordlist, covering exactly the
    // pieces these tests need -- keeps the test independent of the real 391k-word resource.
    private val dictionary = setOf(
        "kerk", "hoofd", "amstel", "prinsen", "juliana", "beatrix", "wilhelmina", "nieuwen",
        "centrum", "cent", "rum", "verkeer", "informatie", "eindhoven", "benzine", "station",
        "bata",
    )

    @Test
    fun `a place-suffix compound splits on the suffix, stressed on the first constituent`() {
        assertEquals(
            DutchCompoundSegmenter.Segmentation(listOf("kerk", "straat"), 0),
            DutchCompoundSegmenter.segment("kerkstraat", dictionary),
        )
        assertEquals(
            DutchCompoundSegmenter.Segmentation(listOf("hoofd", "weg"), 0),
            DutchCompoundSegmenter.segment("hoofdweg", dictionary),
        )
    }

    @Test
    fun `a final-stress place suffix splits and stresses the LAST constituent`() {
        // ANS 1.6.5.1 (11a): place names ending in -dam/-meer/-veen/-waard are always stressed on
        // the second part -- the opposite of the regular compound rule tested above. "amster" and
        // "rotter" needn't be real dictionary words on their own; the suffix match alone drives
        // the split, same as the ordinary PLACE_SUFFIXES case.
        assertEquals(
            DutchCompoundSegmenter.Segmentation(listOf("amster", "dam"), 1),
            DutchCompoundSegmenter.segment("amsterdam", dictionary),
        )
        assertEquals(
            DutchCompoundSegmenter.Segmentation(listOf("rotter", "dam"), 1),
            DutchCompoundSegmenter.segment("rotterdam", dictionary),
        )
    }

    @Test
    fun `veen is no longer treated as a first-stress suffix`() {
        // Regression test for the real bug: "veen" used to be in the first-stress PLACE_SUFFIXES
        // list, giving "amstelveen" the wrong stress. ANS 1.6.5.1 (11a) confirms -veen names are
        // stressed on the second part, same class as -dam.
        assertEquals(
            DutchCompoundSegmenter.Segmentation(listOf("amstel", "veen"), 1),
            DutchCompoundSegmenter.segment("amstelveen", dictionary),
        )
    }

    @Test
    fun `a place-suffix compound whose whole form is itself in the dictionary still splits`() {
        // PLACE_SUFFIXES (and FINAL_STRESS_PLACE_SUFFIXES) are tried before the general
        // fewest-real-pieces DP, which would otherwise prefer the single atomic whole-word match.
        val withWholeWord = dictionary + "amstelveen"
        assertEquals(
            listOf("amstel", "veen"),
            DutchCompoundSegmenter.segment("amstelveen", withWholeWord).constituents,
        )
    }

    @Test
    fun `-dorp and -drecht place names split with REGULAR first-constituent stress`() {
        // ANS 1.6.5.1 (11b): unlike -dam/-meer/-veen/-waard, place names ending in -dorp and
        // -drecht take the ordinary first-constituent stress.
        assertEquals(
            DutchCompoundSegmenter.Segmentation(listOf("bata", "dorp"), 0),
            DutchCompoundSegmenter.segment("batadorp", dictionary),
        )
    }

    @Test
    fun `a linking segment between two real words is folded into the first constituent`() {
        assertEquals(
            listOf("prinsen", "gracht"),
            DutchCompoundSegmenter.segment("prinsengracht", dictionary).constituents,
        )
    }

    @Test
    fun `a word containing two real dictionary words is not spuriously split`() {
        // "centrum" = "cent" + "rum" are both real dictionary words, but the whole word already
        // wins as a single real piece -- this is exactly the false-split case the segmenter must
        // avoid.
        val withWholeWord = dictionary + "centrum"
        assertEquals(
            listOf("centrum"),
            DutchCompoundSegmenter.segment("centrum", withWholeWord).constituents,
        )
    }

    @Test
    fun `an ordinary word with no decomposition is returned unchanged`() {
        assertEquals(
            listOf("eindhoven"),
            DutchCompoundSegmenter.segment("eindhoven", dictionary).constituents,
        )
    }

    @Test
    fun `a multi-constituent compound with no place suffix still splits via the general dp`() {
        // The "s" linker (tussenklank) between "verkeer" and "informatie" is folded onto the
        // preceding constituent for display, same as the "en" linker in "nieuwen"+"dijk".
        assertEquals(
            listOf("verkeers", "informatie"),
            DutchCompoundSegmenter.segment("verkeersinformatie", dictionary).constituents,
        )
    }
}
