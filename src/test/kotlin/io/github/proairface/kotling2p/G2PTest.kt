package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class G2PTest {

    private val g2p = G2P()

    @Test
    fun usesTheDictionaryForKnownWords() {
        val result = g2p.toPhonemes("hello")
        assertEquals(1, result.size)
        assertEquals(PronunciationSource.DICTIONARY, result.single().source)
        assertTrue(result.single().phonemes.isNotEmpty())
    }

    @Test
    fun fallsBackToRulesForUnknownWordsRatherThanFailing() {
        val result = g2p.toPhonemes("zjorvik")
        assertEquals(1, result.size)
        assertEquals(PronunciationSource.RULES, result.single().source)
        assertTrue(result.single().phonemes.isNotEmpty())
    }

    @Test
    fun handlesAFullAddressLikeString() {
        val result = g2p.toPhonemes("221B Baker St")
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.phonemes.isNotEmpty() })
    }
}
