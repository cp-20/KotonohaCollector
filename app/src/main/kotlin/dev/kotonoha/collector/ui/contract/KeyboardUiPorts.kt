package dev.kotonoha.collector.ui.contract

import dev.kotonoha.collector.editor.EditorDirection
import dev.kotonoha.collector.input.FlickGesture

internal enum class KeyboardMode {
    KANA_FLICK,
    LATIN_QWERTY,
    SYMBOL_QWERTY,
}

/** Read-only state rendered by keyboard components. */
internal interface KeyboardUiStateSource {
    val candidates: List<String>
    val selectedCandidateIndex: Int
    val canUndo: Boolean
    val collectionEnabled: Boolean
}

/** Text and conversion intents emitted by the character and candidate keyboards. */
internal interface TextInputActions {
    fun showConversionCandidates()
    fun enter()
    fun handleFlickInput(value: String, gesture: FlickGesture)
    fun commitHalfWidth(value: String, commitMethod: String)
    fun applyModifierCycle()
    fun commitCandidate(index: Int, commitMethod: String): Boolean
}

/** Editing intents independent from keyboard presentation. */
internal interface EditorActions {
    fun undoLastCommit()
    fun deleteOne()
    fun deleteWordBeforeCursor()
    fun moveCursor(direction: EditorDirection)
}

/** Content-picker intents. Pickers keep their own presentation state. */
internal interface ContentPickerActions {
    fun pasteClipboard(text: String): Boolean
    fun commitEmoji(emoji: String): Boolean
}

/** IME-level commands which are not text editing operations. */
internal interface ImeSystemActions {
    fun requestKeyboardModeChange(from: KeyboardMode, to: KeyboardMode): Boolean
    fun toggleCollection()
    fun openSettings()
    fun switchToNextIme()
}

/** Minimal rendering output used by IME orchestration after state changes. */
internal interface ImeUiOutput {
    val inputMode: String
    fun renderPanel()
    fun refreshCandidateViews()
    fun candidateCommitted()
}
