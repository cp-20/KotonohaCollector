package dev.kotonoha.collector.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan

/** A single, consistent visual treatment for every uncommitted composition. */
object ComposingTextStyler {
    @JvmStatic
    fun style(text: String, foregroundColor: Int, backgroundColor: Int): CharSequence {
        if (text.isEmpty()) return text
        return SpannableString(text).apply {
            val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            setSpan(ForegroundColorSpan(foregroundColor), 0, length, flags)
            setSpan(BackgroundColorSpan(backgroundColor), 0, length, flags)
            setSpan(UnderlineSpan(), 0, length, flags)
        }
    }
}
