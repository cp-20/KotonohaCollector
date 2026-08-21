package dev.kotonoha.collector

import org.junit.Assert.assertEquals
import org.junit.Test

class RomajiConverterTest {
    @Test
    fun convertsBasicAndCompoundKana() {
        mapOf(
            null to "",
            "ka" to "か",
            "SHI" to "し",
            "kyou" to "きょう",
            "ja" to "じゃ",
            "fo" to "ふぉ",
        ).forEach { (input, expected) -> assertEquals(expected, RomajiConverter.convert(input)) }
    }

    @Test
    fun convertsDoubleConsonantsAndN() {
        assertEquals("がっこう", RomajiConverter.convert("gakkou"))
        assertEquals("かんぱい", RomajiConverter.convert("kanpai"))
        assertEquals("ん", RomajiConverter.convert("nn"))
    }

    @Test
    fun preservesKanaAndUnknownInput() {
        assertEquals("きょう", RomajiConverter.convert("きょう"))
        assertEquals("あbc!", RomajiConverter.convert("abc!"))
        assertEquals("!?", RomajiConverter.convert("!?"))
    }
}
