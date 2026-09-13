package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LetterToSoundRulesTest {

    @Test
    fun neverReturnsEmptyForAWordWithLetters() {
        listOf("ZJORVIK", "PLOTZ", "NGUYEN", "X").forEach { word ->
            assertTrue(LetterToSoundRules.guess(word).isNotEmpty(), "expected a guess for $word")
        }
    }

    @Test
    fun returnsEmptyForInputWithNoLetters() {
        assertEquals(emptyList(), LetterToSoundRules.guess("123"))
        assertEquals(emptyList(), LetterToSoundRules.guess(""))
    }

    @Test
    fun recognizesCommonDigraphs() {
        val guess = LetterToSoundRules.guess("PHIL").map { it.arpabet }
        assertEquals("F", guess.first())
    }

    @Test
    fun dropsSilentTrailingE() {
        // "MAKE" should guess three phonemes (M, A, K), not four — the trailing E is silent.
        val guess = LetterToSoundRules.guess("MAKE").map { it.arpabet }
        assertEquals(listOf("M", "AE1", "K"), guess)
    }
}
