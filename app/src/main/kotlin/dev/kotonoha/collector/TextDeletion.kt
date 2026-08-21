package dev.kotonoha.collector

/** Unicode-aware deletion boundaries that do not split common visible characters. */
internal object TextDeletion {
    fun previousGrapheme(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val end = text.length
        val finalCodePoint = text.codePointBefore(end)
        if (isRegionalIndicator(finalCodePoint)) {
            var runCount = 0
            var cursor = end
            while (cursor > 0 && isRegionalIndicator(text.codePointBefore(cursor))) {
                cursor -= Character.charCount(text.codePointBefore(cursor))
                runCount++
            }
            var start = end
            repeat(if (runCount % 2 == 0) 2 else 1) {
                start = text.offsetByCodePoints(start, -1)
            }
            return text.substring(start, end)
        }

        var start = text.offsetByCodePoints(end, -1)
        while (start > 0 && isExtension(text.codePointAt(start))) {
            start = text.offsetByCodePoints(start, -1)
        }
        while (start > 0 && text.codePointBefore(start) == ZERO_WIDTH_JOINER) {
            start -= Character.charCount(ZERO_WIDTH_JOINER)
            if (start == 0) break
            start = text.offsetByCodePoints(start, -1)
            while (start > 0 && isExtension(text.codePointAt(start))) {
                start = text.offsetByCodePoints(start, -1)
            }
        }
        return text.substring(start, end)
    }

    fun previousGraphemeCodePoints(text: String?): Int {
        val grapheme = previousGrapheme(text)
        return grapheme.codePointCount(0, grapheme.length)
    }

    fun previousWordCodePoints(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var deleteFrom = text.length
        while (deleteFrom > 0) {
            val codePoint = text.codePointBefore(deleteFrom)
            if (!isSeparator(codePoint)) break
            deleteFrom -= Character.charCount(codePoint)
        }
        while (deleteFrom > 0) {
            val codePoint = text.codePointBefore(deleteFrom)
            if (isSeparator(codePoint)) break
            deleteFrom -= Character.charCount(codePoint)
        }
        return text.codePointCount(deleteFrom, text.length)
    }

    private fun isSeparator(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || "、。,.！？!?\n".indexOf(codePoint.toChar()) >= 0

    private fun isExtension(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint == KEYCAP || codePoint == 0xFE0E || codePoint == 0xFE0F ||
            codePoint in 0x1F3FB..0x1F3FF || codePoint in 0xE0020..0xE007F
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val KEYCAP = 0x20E3
}
