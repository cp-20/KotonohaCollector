package dev.kotonoha.collector.input

/** Locale-sensitive literals that must not leak into gesture/UI code. */
object JapaneseInputPolicy {
    const val IDEOGRAPHIC_SPACE: String = "\u3000"

    @JvmStatic
    fun space(kanaMode: Boolean): String = if (kanaMode) IDEOGRAPHIC_SPACE else " "

    @JvmStatic
    fun isComposingPunctuation(value: String?): Boolean =
        value != null && value in "、。？！…「」（）〜"
}
