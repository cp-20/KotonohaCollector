package dev.kotonoha.collector

import dev.kotonoha.collector.input.TextDeletion
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDeletionTest {
    @Test
    fun deletesPlainAndJapaneseCharacters() {
        assertEquals("", TextDeletion.previousGrapheme(""))
        assertEquals("c", TextDeletion.previousGrapheme("abc"))
        assertEquals("日", TextDeletion.previousGrapheme("今日日"))
        assertEquals(1, TextDeletion.previousGraphemeCodePoints("今日"))
    }

    @Test
    fun keepsEmojiSequencesIntact() {
        assertEquals("👍🏽", TextDeletion.previousGrapheme("OK👍🏽"))
        assertEquals("🇯🇵", TextDeletion.previousGrapheme("旗🇯🇵"))
        assertEquals("👨‍👩‍👧‍👦", TextDeletion.previousGrapheme("家族👨‍👩‍👧‍👦"))
        assertEquals("1️⃣", TextDeletion.previousGrapheme("key1️⃣"))
        assertEquals("が", TextDeletion.previousGrapheme("かが"))
    }

    @Test
    fun keepsVariationSelectorsAndCombiningMarksIntact() {
        assertEquals("✈️", TextDeletion.previousGrapheme("便✈️"))
        assertEquals("é", TextDeletion.previousGrapheme("café"))
        assertEquals("#️⃣", TextDeletion.previousGrapheme("key#️⃣"))
    }

    @Test
    fun regionalIndicatorsArePairedFromTheStart() {
        assertEquals("🇯🇵", TextDeletion.previousGrapheme("🇺🇸🇯🇵"))
        assertEquals("🇯", TextDeletion.previousGrapheme("🇺🇸🇯"))
    }

    @Test
    fun previousWordIncludesTrailingSeparators() {
        assertEquals(0, TextDeletion.previousWordCodePoints(""))
        assertEquals(4, TextDeletion.previousWordCodePoints("abc test"))
        assertEquals(5, TextDeletion.previousWordCodePoints("abc test "))
        assertEquals(1, TextDeletion.previousWordCodePoints("abc。次"))
        assertEquals(4, TextDeletion.previousWordCodePoints("abc。"))
    }

    @Test
    fun previousWordHandlesWhitespaceNewlinesAndJapanesePunctuation() {
        assertEquals(6, TextDeletion.previousWordCodePoints("abc test  "))
        assertEquals(3, TextDeletion.previousWordCodePoints("abc\n次の語"))
        assertEquals(2, TextDeletion.previousWordCodePoints("前、後！"))
        assertEquals(1, TextDeletion.previousWordCodePoints("a\tb"))
        assertEquals(2, TextDeletion.previousWordCodePoints("a\t"))
    }
}
