package dev.kotonoha.collector.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.kotonoha.collector.ui.contract.KeyboardUiStateSource

/** Renders the expanded candidate grid and emits only candidate-selection intents. */
internal class CandidatePanelComponent(
    context: Context,
    private val palette: GboardPalette,
    private val state: KeyboardUiStateSource,
    private val onCandidateSelected: (Int) -> Unit,
) {
    private val views = KeyboardViewFactory(context, palette)
    private val context = context

    fun createView(): View {
        val panel = views.panelShell()
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(views.dp(5), views.dp(4), views.dp(5), views.dp(8))
        }
        val candidates = state.candidates.toList()
        var start = 0
        while (start < candidates.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(COLUMNS) { column ->
                val index = start + column
                val child = if (index < candidates.size) {
                    candidateButton(candidates[index], index)
                } else {
                    views.spacer()
                }
                row.addView(child, gridCell())
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    views.dp(54),
                ),
            )
            start += COLUMNS
        }
        scroll.addView(rows)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return panel
    }

    private fun candidateButton(candidate: String, index: Int): View = views.label(
        candidate,
        if (candidate.codePointCount(0, candidate.length) > 8) 14f else 17f,
        palette.text,
    ).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(views.dp(8), 0, views.dp(8), 0)
        contentDescription = "候補 ${index + 1}: $candidate"
        background = views.pressableBackground(
            if (index == state.selectedCandidateIndex) palette.accent else palette.key,
            palette.pressed,
            8,
        )
        setOnClickListener { onCandidateSelected(index) }
    }

    private fun gridCell(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(views.dp(3), views.dp(3), views.dp(3), views.dp(3))
        }

    private companion object {
        const val COLUMNS = 3
    }
}
