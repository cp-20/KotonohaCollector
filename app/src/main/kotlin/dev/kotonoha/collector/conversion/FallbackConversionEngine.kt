package dev.kotonoha.collector.conversion

import dev.kotonoha.collector.input.ConversionEngine

/** Small deterministic fallback used only when Mozc cannot initialize. */
internal class FallbackConversionEngine : ConversionEngine {
    private val dictionary = mapOf(
        "きょう" to listOf("今日", "京", "きょう"),
        "あした" to listOf("明日", "あした"),
        "きのう" to listOf("昨日", "きのう"),
        "わたし" to listOf("私", "わたし"),
        "ぼく" to listOf("僕", "ボク", "ぼく"),
        "にほんご" to listOf("日本語", "にほんご"),
        "へんかん" to listOf("変換", "返還", "へんかん"),
        "にゅうりょく" to listOf("入力", "にゅうりょく"),
        "がくしゅう" to listOf("学習", "がくしゅう"),
        "ぶんみゃく" to listOf("文脈", "ぶんみゃく"),
        "せいど" to listOf("精度", "制度", "せいど"),
        "そくど" to listOf("速度", "そくど"),
        "ほぞん" to listOf("保存", "ほぞん"),
        "しゅうせい" to listOf("修正", "しゅうせい"),
        "ありがとう" to listOf("ありがとう", "有難う"),
        "よろしく" to listOf("よろしく", "宜しく"),
    )

    override fun candidates(reading: String, contextBefore: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        return buildSet {
            dictionary[reading]?.let(::addAll)
            add(reading)
            add(reading.toKatakana())
        }.toList()
    }

    override fun name(): String = "Fallback"

    private fun String.toKatakana(): String = buildString(length) {
        this@toKatakana.forEach { character ->
            append(if (character in 'ぁ'..'ゖ') character + 0x60 else character)
        }
    }
}
