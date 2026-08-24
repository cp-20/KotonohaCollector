package dev.kotonoha.collector.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kotonoha.collector.clipboard.ClipboardHistory

/** Owns transient clipboard presentation and delegates the actual editor mutation. */
internal class ClipboardPanelComponent(
    private val context: Context,
    private val palette: GboardPalette,
    private val history: ClipboardHistory,
    private val onPaste: (String) -> Boolean,
    private val onHistoryChanged: () -> Unit,
) {
    private val views = KeyboardViewFactory(context, palette)

    fun createView(): View {
        history.capturePrimaryClip(context)
        val panel = views.panelShell()
        val privacy = views.label(
            "最近コピーしたテキスト  •  アプリ間で共有・端末保存なし",
            13f,
            palette.secondaryText,
        ).apply {
            setPadding(views.dp(14), views.dp(7), views.dp(14), views.dp(5))
        }
        panel.addView(
            privacy,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, views.dp(34)),
        )

        val scroll = ScrollView(context).apply { isFillViewport = true }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(views.dp(5), 0, views.dp(5), views.dp(6))
        }
        val items = history.items()
        if (items.isEmpty()) {
            val empty = views.label(
                "クリップボードにテキストをコピーすると、ここに表示されます。",
                15f,
                palette.secondaryText,
            ).apply { gravity = Gravity.CENTER }
            list.addView(
                empty,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, views.dp(170)),
            )
        } else {
            items.chunked(2).forEach { pair ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(clipboardCard(pair[0]), cardLayout())
                row.addView(
                    pair.getOrNull(1)?.let(::clipboardCard) ?: views.spacer(),
                    cardLayout(),
                )
                list.addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, views.dp(78)),
                )
            }
        }
        scroll.addView(list)
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return panel
    }

    fun clear() = history.clear()

    private fun clipboardCard(item: String): TextView {
        val preview = preview(item)
        return views.label(preview, 15f, palette.text).apply {
            gravity = Gravity.TOP or Gravity.START
            maxLines = 3
            setPadding(views.dp(12), views.dp(9), views.dp(12), views.dp(7))
            background = views.pressableBackground(palette.panelCard, palette.pressed, 12)
            contentDescription = "貼り付け: $preview"
            setOnClickListener {
                if (onPaste(item)) {
                    history.remember(item)
                    onHistoryChanged()
                }
            }
        }
    }

    private fun cardLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(views.dp(3), views.dp(3), views.dp(3), views.dp(3))
        }

    private fun preview(value: String): String {
        val compact = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (compact.length > 120) compact.substring(0, 120) + "…" else compact
    }
}
