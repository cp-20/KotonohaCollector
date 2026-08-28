package dev.kotonoha.collector.editor.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import dev.kotonoha.collector.editor.CompositionCommitOutcome
import dev.kotonoha.collector.editor.EditorDirection
import dev.kotonoha.collector.editor.EditorGateway
import dev.kotonoha.collector.editor.RemainingTextOutcome

/** Contains every fallible [InputConnection] call behind the editor ports. */
internal class AndroidEditorGateway(
    private val connectionProvider: () -> InputConnection?,
) : EditorGateway {
    override fun commitText(text: String): Boolean = withConnection(false) {
        it.commitText(text, 1)
    }

    override fun commitComposition(
        committedText: String,
        remainingStyledText: CharSequence?,
        remainingPlainText: String,
    ): CompositionCommitOutcome {
        val connection = connectionProvider()
            ?: return CompositionCommitOutcome(false, RemainingTextOutcome.REJECTED)
        runCatching { connection.beginBatchEdit() }
        return try {
            val committed = if (remainingPlainText.isEmpty()) {
                runCatching { connection.commitText(committedText, 1) }.getOrDefault(false)
            } else {
                // Replacing a styled preedit via commitText can leave old SPAN_COMPOSING flags
                // attached to the committed prefix in EditText. Replace it as composition first,
                // then close that composition so the suffix starts with a clean span boundary.
                runCatching {
                    connection.setComposingText(committedText, 1) &&
                        connection.finishComposingText()
                }.getOrDefault(false)
            }
            if (!committed) {
                CompositionCommitOutcome(false, RemainingTextOutcome.REJECTED)
            } else if (remainingPlainText.isEmpty()) {
                CompositionCommitOutcome(true, RemainingTextOutcome.NONE)
            } else if (
                remainingStyledText != null &&
                runCatching {
                    connection.setComposingText(remainingStyledText, 1)
                }.getOrDefault(false)
            ) {
                CompositionCommitOutcome(true, RemainingTextOutcome.COMPOSING)
            } else if (
                runCatching { connection.commitText(remainingPlainText, 1) }.getOrDefault(false)
            ) {
                // The prefix has already been committed. Preserve the user's suffix even when the
                // target editor rejects a new composing range.
                CompositionCommitOutcome(true, RemainingTextOutcome.COMMITTED_LITERAL)
            } else {
                CompositionCommitOutcome(true, RemainingTextOutcome.REJECTED)
            }
        } finally {
            runCatching { connection.endBatchEdit() }
        }
    }

    override fun updateComposition(text: CharSequence): Boolean = withConnection(false) {
        it.setComposingText(text, 1)
    }

    override fun clearComposition(): Boolean = withConnection(false) { connection ->
        val cleared = connection.setComposingText("", 1)
        val finished = connection.finishComposingText()
        cleared && finished
    }

    override fun finishComposition(): Boolean = withConnection(false) {
        it.finishComposingText()
    }

    override fun deleteSurroundingTextInCodePoints(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean = withConnection(false) {
        it.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun selectedText(): String? = withConnection<String?>(null) {
        it.getSelectedText(0)?.toString()
    }

    override fun textBeforeCursor(length: Int): String? = withConnection<String?>(null) {
        it.getTextBeforeCursor(length, 0)?.toString()
    }

    override fun textAfterCursor(length: Int): String? = withConnection<String?>(null) {
        it.getTextAfterCursor(length, 0)?.toString()
    }

    override fun cursorPosition(): Int = extractedSelection()?.second ?: -1

    override fun selectionStart(): Int = extractedSelection()?.let { (start, end) ->
        minOf(start, end)
    } ?: -1

    override fun selectedTextLength(): Int = extractedSelection()?.let { (start, end) ->
        kotlin.math.abs(end - start)
    } ?: 0

    override fun moveCursor(direction: EditorDirection): Boolean {
        val connection = connectionProvider() ?: return false
        val keyCode = when (direction) {
            EditorDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            EditorDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            EditorDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
            EditorDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        }
        val extracted = runCatching {
            connection.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        if (extracted != null && extracted.selectionStart == extracted.selectionEnd) {
            val adjacentText = runCatching {
                when (direction) {
                    EditorDirection.LEFT, EditorDirection.UP ->
                        connection.getTextBeforeCursor(1, 0)
                    EditorDirection.RIGHT, EditorDirection.DOWN ->
                        connection.getTextAfterCursor(1, 0)
                }
            }.getOrNull()
            if (adjacentText != null && adjacentText.isEmpty()) return false
        }

        val downTime = SystemClock.uptimeMillis()
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val downAccepted = connection.sendKeyEvent(
            KeyEvent(
                downTime,
                downTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
        val upAccepted = connection.sendKeyEvent(
            KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                flags,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
        return downAccepted && upAccepted
    }

    override fun performEditorAction(action: Int): Boolean = withConnection(false) {
        it.performEditorAction(action)
    }

    override fun setSelection(start: Int, end: Int): Boolean = withConnection(false) {
        it.setSelection(start, end)
    }

    private fun extractedSelection(): Pair<Int, Int>? = withConnection<Pair<Int, Int>?>(null) {
        val extracted = it.getExtractedText(ExtractedTextRequest(), 0) ?: return@withConnection null
        extracted.selectionStart to extracted.selectionEnd
    }

    private inline fun <T> withConnection(fallback: T, operation: (InputConnection) -> T): T {
        val connection = connectionProvider() ?: return fallback
        return try {
            operation(connection)
        } catch (_: RuntimeException) {
            fallback
        }
    }
}
