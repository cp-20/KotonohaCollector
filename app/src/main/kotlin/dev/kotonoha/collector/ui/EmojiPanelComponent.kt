package dev.kotonoha.collector.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.LinkedList

/** Owns emoji catalog navigation and process-local recent-emoji presentation state. */
internal class EmojiPanelComponent(
    private val context: Context,
    private val palette: GboardPalette,
    private val onEmoji: (String) -> Boolean,
    private val onPanelInvalidated: () -> Unit,
) {
    private val views = KeyboardViewFactory(context, palette)
    private val recentEmojis = LinkedList<String>()
    private var selectedGroup: String? = null
    private var gridView: EmojiGridView? = null

    fun createView(): View {
        val panel = views.panelShell()
        val groups = EmojiCatalog.get(context).groups()
        if (selectedGroup == null || selectedGroup != RECENT_GROUP && selectedGroup !in groups) {
            selectedGroup = groups.firstOrNull() ?: RECENT_GROUP
        }

        val categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val categories = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(views.dp(4), views.dp(2), views.dp(4), views.dp(2))
        }
        if (recentEmojis.isNotEmpty()) {
            categories.addView(categoryButton(RECENT_GROUP, "◷"))
        }
        groups.forEach { group -> categories.addView(categoryButton(group, groupIcon(group))) }
        categoryScroll.addView(
            categories,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(
            categoryScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, views.dp(44)),
        )

        val emojiScroll = ScrollView(context).apply { isFillViewport = true }
        gridView = EmojiGridView(context) { commitEmoji(it) }.apply {
            setBackgroundColor(palette.background)
        }
        updateGrid()
        emojiScroll.addView(gridView)
        panel.addView(
            emojiScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return panel
    }

    private fun categoryButton(group: String, icon: String): TextView =
        views.label(icon, 22f, palette.text).apply {
            gravity = Gravity.CENTER
            contentDescription = group
            background = views.pressableBackground(
                if (group == selectedGroup) palette.accent else Color.TRANSPARENT,
                palette.pressed,
                18,
            )
            setOnClickListener {
                selectedGroup = group
                onPanelInvalidated()
            }
            layoutParams = LinearLayout.LayoutParams(views.dp(43), views.dp(38)).apply {
                setMargins(views.dp(1), 0, views.dp(1), 0)
            }
        }

    private fun commitEmoji(emoji: String) {
        if (!onEmoji(emoji)) return
        recentEmojis.remove(emoji)
        recentEmojis.addFirst(emoji)
        while (recentEmojis.size > MAX_RECENT) recentEmojis.removeLast()
        if (selectedGroup == RECENT_GROUP) updateGrid()
    }

    private fun updateGrid() {
        val values = if (selectedGroup == RECENT_GROUP) {
            recentEmojis
        } else {
            EmojiCatalog.get(context).emojis(selectedGroup)
        }
        gridView?.setEmojis(values)
    }

    private fun groupIcon(group: String): String = when {
        group.contains("Smileys") -> "😀"
        group.contains("People") -> "👋"
        group.contains("Component") -> "🧩"
        group.contains("Animals") -> "🐻"
        group.contains("Food") -> "🍙"
        group.contains("Travel") -> "🚗"
        group.contains("Activities") -> "⚽"
        group.contains("Objects") -> "💡"
        group.contains("Symbols") -> "🔣"
        group.contains("Flags") -> "🏳"
        else -> "•"
    }

    private companion object {
        const val RECENT_GROUP = "最近使った絵文字"
        const val MAX_RECENT = 40
    }
}
