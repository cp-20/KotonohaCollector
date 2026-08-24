package dev.kotonoha.collector

import dev.kotonoha.collector.ui.EmojiCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class EmojiCatalogParserTest {
    @Test
    fun parsesOnlyFullyQualifiedEmojiByGroup() {
        val catalog = EmojiCatalog.parse(
            BufferedReader(
                StringReader(
                    """
                    # group: Smileys & Emotion
                    1F600 ; fully-qualified # 😀 E1.0 grinning face
                    263A ; unqualified # ☺ E0.6 smiling face
                    # group: Symbols
                    2764 FE0F ; fully-qualified # ❤️ E0.6 red heart
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(listOf("Smileys & Emotion", "Symbols"), catalog.keys.toList())
        assertEquals(listOf("😀"), catalog.getValue("Smileys & Emotion"))
        assertEquals(listOf("❤️"), catalog.getValue("Symbols"))
        assertFalse(catalog.values.flatten().contains("☺"))
    }
}
