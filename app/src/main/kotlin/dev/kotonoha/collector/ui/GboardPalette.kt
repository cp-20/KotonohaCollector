package dev.kotonoha.collector.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Keyboard colors that follow the system light/dark theme. */
internal class GboardPalette(context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val background: Int
    val key: Int
    val sideKey: Int
    val pressed: Int
    val accent: Int
    val accentText: Int
    val text: Int
    val secondaryText: Int
    val guideInactive: Int
    val flickHighlight: Int
    val divider: Int
    val panelCard: Int
    val composingHighlight: Int

    init {
        if (dark) {
            background = Color.rgb(29, 29, 33)
            key = Color.rgb(39, 39, 43)
            sideKey = Color.rgb(49, 51, 57)
            pressed = Color.rgb(69, 72, 79)
            accent = Color.rgb(168, 199, 250)
            accentText = Color.rgb(20, 42, 77)
            text = Color.rgb(232, 234, 237)
            secondaryText = Color.rgb(189, 193, 198)
            guideInactive = Color.rgb(145, 149, 154)
            flickHighlight = Color.rgb(168, 199, 250)
            divider = Color.rgb(58, 60, 66)
            panelCard = Color.rgb(45, 46, 51)
            composingHighlight = Color.rgb(55, 70, 94)
        } else {
            background = Color.rgb(245, 244, 252)
            key = Color.rgb(253, 252, 255)
            sideKey = Color.rgb(226, 232, 250)
            pressed = Color.rgb(204, 209, 225)
            accent = Color.rgb(178, 204, 250)
            accentText = Color.rgb(28, 55, 94)
            text = Color.rgb(31, 35, 40)
            secondaryText = Color.rgb(91, 98, 108)
            guideInactive = Color.rgb(167, 171, 180)
            flickHighlight = Color.rgb(75, 98, 155)
            divider = Color.rgb(222, 224, 233)
            panelCard = Color.WHITE
            composingHighlight = Color.rgb(215, 228, 250)
        }
    }
}
