package dev.kotonoha.collector

import dev.kotonoha.collector.input.JapaneseInputPolicy
import dev.kotonoha.collector.ui.CursorRepeatPolicy
import dev.kotonoha.collector.ui.DeleteRepeatPolicy
import dev.kotonoha.collector.ui.KeyboardShapePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPoliciesTest {
    @Test
    fun japaneseModeUsesIdeographicSpace() {
        assertEquals("\u3000", JapaneseInputPolicy.space(kanaMode = true))
        assertEquals(" ", JapaneseInputPolicy.space(kanaMode = false))
    }

    @Test
    fun fullWidthPunctuationRemainsPartOfTheComposition() {
        "、。？！…「」（）〜".forEach {
            assertTrue(JapaneseInputPolicy.isComposingPunctuation(it.toString()))
        }
        assertFalse(JapaneseInputPolicy.isComposingPunctuation(","))
        assertFalse(JapaneseInputPolicy.isComposingPunctuation(null))
    }

    @Test
    fun deleteRepeatStartsSoonAndAccelerates() {
        assertEquals(360L, DeleteRepeatPolicy.initialDelayMs(500))
        assertEquals(50L, DeleteRepeatPolicy.intervalMs(1))
        assertEquals(36L, DeleteRepeatPolicy.intervalMs(7))
        assertEquals(27L, DeleteRepeatPolicy.intervalMs(20))
        assertTrue(DeleteRepeatPolicy.shouldHaptic(1))
        assertTrue(DeleteRepeatPolicy.shouldHaptic(4))
        assertFalse(DeleteRepeatPolicy.shouldHaptic(5))
    }

    @Test
    fun cursorRepeatUsesThePlatformLongPressDelay() {
        assertEquals(500L, CursorRepeatPolicy.initialDelayMs(500))
        assertEquals(50L, CursorRepeatPolicy.intervalMs())
    }

    @Test
    fun keyboardRadiiAreCappedForSharperShapes() {
        assertEquals(3, KeyboardShapePolicy.cornerRadiusDp(7))
        assertEquals(6, KeyboardShapePolicy.cornerRadiusDp(18))
        assertEquals(8, KeyboardShapePolicy.cornerRadiusDp(32))
    }
}
