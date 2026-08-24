package dev.kotonoha.collector.ui

import android.content.Context

/** Builds the styled text sent to an editor for the active composing range. */
internal class CompositionPresentation(context: Context) {
    private val palette = GboardPalette(context)

    fun style(text: String): CharSequence = ComposingTextStyler.style(
        text,
        palette.text,
        palette.composingHighlight,
    )
}
