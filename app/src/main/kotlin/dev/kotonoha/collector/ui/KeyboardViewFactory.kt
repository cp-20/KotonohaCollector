package dev.kotonoha.collector.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Small shared factory for atomic keyboard visuals; it contains no keyboard behavior or state. */
internal class KeyboardViewFactory(
    private val context: Context,
    private val palette: GboardPalette,
) {
    fun label(value: CharSequence?, sizeSp: Float, color: Int): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun panelShell(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
    }

    fun pressableBackground(
        normalColor: Int,
        pressedColor: Int,
        radiusDp: Int,
    ): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            keyBackground(pressedColor, radiusDp),
        )
        addState(intArrayOf(), keyBackground(normalColor, radiusDp))
    }

    fun dp(value: Int): Int = Math.round(value * context.resources.displayMetrics.density)

    fun weightedCell(height: Int, weight: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, height, weight)

    fun spacer(): View = View(context)

    private fun keyBackground(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(KeyboardShapePolicy.cornerRadiusDp(radiusDp)).toFloat()
    }
}
