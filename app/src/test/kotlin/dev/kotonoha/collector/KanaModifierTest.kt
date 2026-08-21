package dev.kotonoha.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaModifierTest {
    @Test
    fun cyclesVoicingAndSmallKana() {
        val ha = StringBuilder("は")
        assertTrue(KanaModifier.cycle(ha))
        assertEquals("ば", ha.toString())
        assertTrue(KanaModifier.cycle(ha))
        assertEquals("ぱ", ha.toString())
        assertTrue(KanaModifier.cycle(ha))
        assertEquals("は", ha.toString())

        val yo = StringBuilder("よ")
        assertTrue(KanaModifier.cycle(yo))
        assertEquals("ょ", yo.toString())

        val tsu = StringBuilder("つ")
        assertTrue(KanaModifier.cycle(tsu))
        assertEquals("っ", tsu.toString())
        assertTrue(KanaModifier.cycle(tsu))
        assertEquals("づ", tsu.toString())
        assertTrue(KanaModifier.cycle(tsu))
        assertEquals("つ", tsu.toString())
    }

    @Test
    fun appliesExplicitModifiers() {
        assertModified("か", KanaModifier.DAKUTEN, "が")
        assertModified("ひ", KanaModifier.HANDAKUTEN, "ぴ")
        assertModified("つ", KanaModifier.SMALL, "っ")
    }

    @Test
    fun ignoresEmptyAndUnsupportedCharacters() {
        assertFalse(KanaModifier.cycle(StringBuilder()))
        assertFalse(KanaModifier.cycle(StringBuilder("ん")))
        assertFalse(KanaModifier.apply(StringBuilder("。"), KanaModifier.DAKUTEN))
    }

    private fun assertModified(input: String, modifier: String, expected: String) {
        val composition = StringBuilder(input)
        assertTrue(KanaModifier.apply(composition, modifier))
        assertEquals(expected, composition.toString())
    }
}
