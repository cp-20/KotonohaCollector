package dev.kotonoha.collector

import android.content.ClipboardManager
import android.content.Context
import java.util.LinkedList

/** Session-only clipboard history. It never writes clipboard contents to disk. */
internal class ClipboardHistory {
    private val history = LinkedList<String>()

    fun capturePrimaryClip(context: Context) {
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip?.takeIf { manager.hasPrimaryClip() && it.itemCount > 0 }
            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::remember)
        } catch (_: SecurityException) {
            // Android may deny clipboard reads unless this IME is currently selected.
        }
    }

    fun remember(value: String?) {
        val bounded = value
            ?.takeUnless { it.isBlank() }
            ?.take(MAX_TEXT_LENGTH)
            ?: return
        history.remove(bounded)
        history.addFirst(bounded)
        while (history.size > MAX_ITEMS) history.removeLast()
    }

    fun items(): List<String> = history.toList()

    fun clear() = history.clear()

    private companion object {
        const val MAX_ITEMS = 12
        const val MAX_TEXT_LENGTH = 10_000
    }
}
