package io.github.proairface.kotling2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CmuDictTest {

    private val dict = CmuDict.loadBundled()

    @Test
    fun looksUpACommonWordCaseInsensitively() {
        val expected = listOf("HH", "AH0", "L", "OW1")
        assertEquals(expected, dict.lookup("hello")?.map { it.arpabet })
        assertEquals(expected, dict.lookup("HELLO")?.map { it.arpabet })
    }

    @Test
    fun returnsNullForAWordNotInTheDictionary() {
        assertNull(dict.lookup("zjorvik"))
    }
}
