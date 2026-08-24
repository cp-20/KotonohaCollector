package dev.kotonoha.collector.editor

/** Direction requested by the keyboard, independent from any concrete View implementation. */
internal enum class EditorDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

internal enum class RemainingTextOutcome {
    NONE,
    COMPOSING,
    COMMITTED_LITERAL,
    REJECTED,
}

/** Result of replacing the current composing range with committed text and an optional suffix. */
internal data class CompositionCommitOutcome(
    val committed: Boolean,
    val remainingText: RemainingTextOutcome,
)

/** Commit-only editor port used by composition transactions. */
internal interface CompositionEditor {
    fun commitComposition(
        committedText: String,
        remainingStyledText: CharSequence?,
        remainingPlainText: String,
    ): CompositionCommitOutcome
}

internal interface EditorTextMutations {
    fun commitText(text: String): Boolean
    fun updateComposition(text: CharSequence): Boolean
    fun clearComposition(): Boolean
    fun finishComposition(): Boolean
    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean
}

internal interface EditorTextQueries {
    fun selectedText(): String?
    fun textBeforeCursor(length: Int): String?
    fun textAfterCursor(length: Int): String?
    fun cursorPosition(): Int
    fun selectionStart(): Int
    fun selectedTextLength(): Int
}

internal interface EditorNavigation {
    fun moveCursor(direction: EditorDirection): Boolean
    fun performEditorAction(action: Int): Boolean
    fun setSelection(start: Int, end: Int): Boolean
}

/** Convenience composite implemented by the Android adapter and used only during wiring. */
internal interface EditorGateway :
    CompositionEditor,
    EditorTextMutations,
    EditorTextQueries,
    EditorNavigation
