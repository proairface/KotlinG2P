package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {

    @Test
    fun expandsStreetAbbreviations() {
        assertEquals(listOf("MAIN", "STREET"), TextNormalizer.tokenize("Main St"))
    }

    @Test
    fun expandsDirectionalAbbreviations() {
        assertEquals(listOf("NORTHEAST", "MAIN", "STREET"), TextNormalizer.tokenize("NE Main St"))
    }

    @Test
    fun spellsOutCardinalNumbers() {
        assertEquals(listOf("ONE", "HUNDRED", "TWENTY", "THREE"), TextNormalizer.tokenize("123"))
    }

    @Test
    fun spellsOutOrdinalNumbers() {
        assertEquals(listOf("TWENTY", "FIRST"), TextNormalizer.tokenize("21st"))
        assertEquals(listOf("THIRD"), TextNormalizer.tokenize("3rd"))
    }

    @Test
    fun expandsUnitNumberSigns() {
        assertEquals(listOf("APARTMENT", "NUMBER", "FOUR"), TextNormalizer.tokenize("Apt #4"))
    }

    @Test
    fun splitsNumberAndLetterSuffix() {
        assertEquals(listOf("TWO", "HUNDRED", "TWENTY", "ONE", "B"), TextNormalizer.tokenize("221B"))
    }

    @Test
    fun stripsSurroundingPunctuation() {
        assertEquals(listOf("MAIN", "STREET"), TextNormalizer.tokenize("Main St."))
    }
}
