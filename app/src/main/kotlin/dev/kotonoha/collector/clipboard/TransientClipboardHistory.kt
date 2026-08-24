package dev.kotonoha.collector.clipboard

import android.content.ClipboardManager
import android.content.Context
import java.util.LinkedList

/** Small UI-facing boundary for recent clipboard text. */
internal interface ClipboardHistory {
    fun capturePrimaryClip(context: Context)
    fun remember(value: String?)
    fun items(): List<String>
    fun clear()
}

/**
 * Process-scoped clipboard history shared across target applications.
 *
 * The history intentionally survives editor-session changes while this IME process is alive, but
 * is never written to disk and disappears when Android recreates the process.
 */
internal class TransientClipboardHistory : ClipboardHistory {
    private val history = LinkedList<String>()

    override fun capturePrimaryClip(context: Context) {
        try {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip?.takeIf { manager.hasPrimaryClip() && it.itemCount > 0 }
            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::remember)
        } catch (_: SecurityException) {
            // Android may deny clipboard reads unless this IME is currently selected.
        }
    }

    override fun remember(value: String?) {
        val bounded = value
            ?.takeUnless { it.isBlank() }
            ?.take(MAX_TEXT_LENGTH)
            ?: return
        history.remove(bounded)
        history.addFirst(bounded)
        while (history.size > MAX_ITEMS) history.removeLast()
    }

    override fun items(): List<String> = history.toList()

    override fun clear() = history.clear()

    private companion object {
        const val MAX_ITEMS = 12
        const val MAX_TEXT_LENGTH = 10_000
    }
}
