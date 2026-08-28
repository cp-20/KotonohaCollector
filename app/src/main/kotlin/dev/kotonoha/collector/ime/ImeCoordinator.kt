package dev.kotonoha.collector.ime

import android.view.inputmethod.EditorInfo
import dev.kotonoha.collector.editor.CompositionEditor
import dev.kotonoha.collector.editor.EditorDirection
import dev.kotonoha.collector.editor.EditorNavigation
import dev.kotonoha.collector.editor.EditorTextMutations
import dev.kotonoha.collector.editor.EditorTextQueries
import dev.kotonoha.collector.input.CompositionCommitPlan
import dev.kotonoha.collector.input.CompositionSession
import dev.kotonoha.collector.input.ConversionEngine
import dev.kotonoha.collector.input.FlickGesture
import dev.kotonoha.collector.input.JapaneseInputPolicy
import dev.kotonoha.collector.input.KanaModifier
import dev.kotonoha.collector.input.TextDeletion
import dev.kotonoha.collector.telemetry.ImeTelemetry
import dev.kotonoha.collector.telemetry.ImeTelemetryEvent
import dev.kotonoha.collector.ui.contract.ContentPickerActions
import dev.kotonoha.collector.ui.contract.EditorActions
import dev.kotonoha.collector.ui.contract.ImeUiOutput
import dev.kotonoha.collector.ui.contract.KeyboardMode
import dev.kotonoha.collector.ui.contract.KeyboardUiStateSource
import dev.kotonoha.collector.ui.contract.TextInputActions

/**
 * Coordinates one Android editor session without depending on a concrete keyboard View.
 *
 * Android lifecycle forwarding and adapter construction stay at the application entrypoint.
 * This class sees editor, telemetry, and UI packages only through their narrow ports.
 */
internal class ImeCoordinator(
compositionEditor:CompositionEditor,
private val textMutations:EditorTextMutations,
private val textQueries:EditorTextQueries,
private val navigation:EditorNavigation,
conversionEngine:ConversionEngine,
private val telemetry:ImeTelemetry,
private val styleComposition:(String)->CharSequence,
):KeyboardUiStateSource, TextInputActions, EditorActions, ContentPickerActions {

private val editHistory = ImeEditHistory()
private val correctionTracker = CorrectionTracker()
private val compositionSession = CompositionSession(conversionEngine)
private val compositionCommitter = CompositionCommitter(
compositionSession,
compositionEditor,
styleComposition,
)
private var uiOutput:ImeUiOutput? = null
private var activeEditor:EditorInfo? = null
private var displayedComposition = ""

override val candidates:List<String>
get() = compositionSession.candidates
override val selectedCandidateIndex:Int
get() = compositionSession.selectedCandidateIndex
override val canUndo:Boolean
get() = editHistory.canUndo
override val collectionEnabled:Boolean
get() = telemetry.collectionEnabled

fun attachUi(output:ImeUiOutput) {
uiOutput = output
}

fun startInput(attribute:EditorInfo?) {
activeEditor = attribute
telemetry.startInput(attribute)
editHistory.clear()
correctionTracker.finish()
compositionSession.startInput()
displayedComposition = ""
}

fun finishInput() {
compositionSession.finishInput()
editHistory.clear()
correctionTracker.finish()
activeEditor = null
telemetry.finishInput()
displayedComposition = ""
}

fun onSelectionUpdated(
newSelStart:Int,
newSelEnd:Int,
candidatesEnd:Int,
) {
val undo = editHistory.entry
if (undo != null && undo.cursorAfter >= 0 &&
(newSelStart != newSelEnd || newSelEnd != undo.cursorAfter))
{
editHistory.clear()
renderPanel()
}
if (compositionSession.hasComposition && candidatesEnd >= 0 && newSelEnd != candidatesEnd)
{
compositionSession.clearComposition()
correctionTracker.finish()
displayedComposition = ""
textMutations.finishComposition()
refreshCandidateViews()
}
}

fun setSelection(start:Int, end:Int) {
navigation.setSelection(start, end)
}

fun requestKeyboardModeChange(from:KeyboardMode, to:KeyboardMode):Boolean =
from != KeyboardMode.KANA_FLICK ||
to == KeyboardMode.KANA_FLICK ||
!compositionSession.hasComposition ||
commitAllCurrent("MODE_SWITCH")

fun toggleCollection() {
telemetry.toggleCollection()
}

override fun pasteClipboard(text:String):Boolean {
if (text.isEmpty()) return false
if (compositionSession.hasComposition && !commitAllCurrent("CLIPBOARD_PASTE")) return false
val cursorBefore = cursorPosition()
val expectedCursorAfter = expectedCursorAfterCommit(cursorBefore, text, selectedTextLength())
if (!textMutations.commitText(text)) return false
editHistory.recordCommit(text, expectedCursorAfter)
compositionSession.literalCommitted()
correctionTracker.finish()
return true
}

override fun commitEmoji(emoji:String):Boolean {
if (emoji.isEmpty())
{
return false
}
if (compositionSession.hasComposition)
{
if (!commitAllCurrent("EMOJI_PICKER")) return false
}
return commitLiteral(emoji, "EMOJI_COMMIT", "EMOJI_PICKER")
}

override fun handleFlickInput(value:String, gesture:FlickGesture) {
if ((KanaModifier.DAKUTEN.equals(value)
|| KanaModifier.HANDAKUTEN.equals(value)
|| KanaModifier.SMALL.equals(value)))
{
val rawBefore = compositionSession.raw
val cursorBefore = cursorPosition()
val before = contextBefore()
if (compositionSession.applyModifier(value, conversionContextBefore()))
{
if (updateComposingText()) recordCompositionEdit(
"MODIFIER_" + when (value) {
KanaModifier.DAKUTEN -> "DAKUTEN"
KanaModifier.HANDAKUTEN -> "HANDAKUTEN"
else -> "SMALL"
}, rawBefore, cursorBefore, before, gesture, finalCodePoint(rawBefore).orEmpty())
}
return
}
if (JapaneseInputPolicy.isComposingPunctuation(value))
{
appendRaw(value, gesture)
return
}
appendRaw(value, gesture)
}

override fun commitHalfWidth(value:String, commitMethod:String) {
if (value.isEmpty())
{
return
}
if (compositionSession.hasComposition)
{
if (!commitAllCurrent(commitMethod)) return
}
commitLiteral(value, "HALFWIDTH_COMMIT", commitMethod)
}

private fun appendRaw(character:String?, gesture:FlickGesture?) {
if (compositionSession.requiresCommitBeforeInput())
{
if (!commitCandidate(compositionSession.selectedCandidateIndex, "NEXT_INPUT")) return
}
if (character.isNullOrEmpty()) return
val rawBefore = compositionSession.raw
val cursorBefore = cursorPosition()
val before = contextBefore()
if (!compositionSession.append(character, conversionContextBefore())) return
if (updateComposingText()) {
recordCompositionEdit("INSERT", rawBefore, cursorBefore, before, gesture)
}
}

private fun updateComposingText():Boolean {
val reading = compositionSession.reading
val accepted = if (reading.isEmpty()) {
// finishComposingText() alone commits the old composing text. Replace the composing range with an
// empty value first so backspace actually removes it.
textMutations.clearComposition()
} else {
textMutations.updateComposition(styledComposition(reading))
}
if (accepted) {
displayedComposition = reading
} else {
abortCompositionAfterEditorRejection()
}
refreshCandidateViews()
return accepted
}

override fun showConversionCandidates() {
if (!compositionSession.hasComposition)
{
commitLiteral(JapaneseInputPolicy.space(true), "SPACE_FULL_WIDTH", "SPACE_KEY")
return
}
val selected = compositionSession.convertOrCycle(conversionContextBefore())
if (selected != null && !showSelectedCandidateAsComposition(selected)) return
refreshCandidateViews()
}

private fun showSelectedCandidateAsComposition(candidate:String):Boolean {
if (textMutations.updateComposition(styledComposition(candidate))) {
displayedComposition = candidate
return true
}
abortCompositionAfterEditorRejection()
refreshCandidateViews()
return false
}

/** Fail closed when an editor rejects a composing update, avoiding editor/session divergence. */
private fun abortCompositionAfterEditorRejection() {
compositionSession.clearComposition()
correctionTracker.finish()
displayedComposition = ""
textMutations.finishComposition()
}

private fun styledComposition(text:String):CharSequence =
styleComposition(text)

private fun renderPanel() = uiOutput?.renderPanel()

private fun refreshCandidateViews() = uiOutput?.refreshCandidateViews()

override fun commitCandidate(index:Int, commitMethod:String):Boolean {
val commit = compositionSession.planCandidateCommit(index) ?: return false
return commitSessionText(commit, "CONVERSION_COMMIT", commitMethod)
}

private fun commitAllCurrent(commitMethod:String):Boolean {
val commit = compositionSession.planFullCurrentCommit() ?: return false
val eventType = if (commit.selectedIndex >= 0) "CONVERSION_COMMIT" else "READING_COMMIT"
return commitSessionText(commit, eventType, commitMethod)
}

private fun commitSessionText(
commit:CompositionCommitPlan,
eventType:String,
commitMethod:String):Boolean {
if (commit.text.isEmpty())
{
return false
}
val before = contextBefore()
val conversionBefore = conversionContextBefore()
val cursorBefore = cursorPosition()
val correctionId = correctionTracker.id
val replacedLength = displayedComposition.length
val applied = compositionCommitter.apply(commit, conversionBefore + commit.text) ?: return false
correctionTracker.finish()
val preserveRemaining = applied.preservesComposition
val insertedSuffix = applied.insertedSuffix
val expectedCursorAfter = expectedCursorAfterCommit(
cursorBefore,
commit.text + insertedSuffix,
replacedLength)
if (preserveRemaining)
{
displayedComposition = compositionSession.reading
editHistory.clear()
}
else
{
displayedComposition = ""
editHistory.recordCommit(commit.text + insertedSuffix, expectedCursorAfter)
}
record(ImeTelemetryEvent(
type = eventType,
rawInput = commit.raw,
reading = commit.reading,
committedText = commit.text,
candidates = commit.candidates,
selectedIndex = commit.selectedIndex,
contextBefore = before,
contextAfter = contextAfter(),
compositionId = commit.compositionId,
correctionId = correctionId,
candidateSource = commit.candidateSource,
commitMethod = commitMethod,
editOperation = if (preserveRemaining) "COMMIT_PARTIAL" else "COMMIT",
rawBefore = commit.raw,
rawAfter = if (preserveRemaining) commit.remainingRaw else "",
cursorBefore = cursorBefore,
cursorAfter = expectedCursorAfter,
))
uiOutput?.candidateCommitted()
return true
}

private fun commitLiteral(
text:String,
eventType:String,
commitMethod:String,
gesture:FlickGesture? = null):Boolean {
val before = contextBefore()
val cursorBefore = cursorPosition()
val correctionId = correctionTracker.id
val expectedCursorAfter = expectedCursorAfterCommit(
cursorBefore,
text,
selectedTextLength())
if (!textMutations.commitText(text)) return false
editHistory.recordCommit(text, expectedCursorAfter)
record(ImeTelemetryEvent(
type = eventType,
rawInput = text,
reading = text,
committedText = text,
candidates = emptyList(),
contextBefore = before,
contextAfter = contextAfter(),
compositionId = compositionSession.compositionId,
correctionId = correctionId,
candidateSource = compositionSession.candidateSource,
commitMethod = commitMethod,
editOperation = "COMMIT_LITERAL",
rawBefore = text,
cursorBefore = cursorBefore,
cursorAfter = expectedCursorAfter,
gesture = gesture,
))
compositionSession.literalCommitted()
correctionTracker.finish()
return true
}

override fun deleteOne() {
if (compositionSession.hasComposition)
{
val rawBefore = compositionSession.raw
val cursorBefore = cursorPosition()
val before = contextBefore()
val previous = compositionSession.deletePreviousGrapheme(conversionContextBefore()) ?: return
if (updateComposingText()) {
correctionTracker.ensure()
recordCompositionEdit("DELETE", rawBefore, cursorBefore, before, deletedText = previous)
}
return
}

if (deleteSelection())
{
return
}
val beforeCursor = textQueries.textBeforeCursor(64)
val deleted = TextDeletion.previousGrapheme(
beforeCursor.orEmpty())
val codePoints = deleted.codePointCount(0, deleted.length)
if (codePoints == 0)
{
return
}
val before = contextBefore()
val cursorBefore = cursorPosition()
val expectedCursorAfter = if (cursorBefore < 0) -1 else cursorBefore - deleted.length
if (!textMutations.deleteSurroundingTextInCodePoints(codePoints, 0)) return
correctionTracker.ensure()
editHistory.recordDelete(deleted, expectedCursorAfter)
record(ImeTelemetryEvent(
type = "DELETE_COMMITTED",
rawInput = "",
reading = "",
committedText = "",
candidates = emptyList(),
contextBefore = before,
contextAfter = contextAfter(),
correctionId = correctionTracker.id,
commitMethod = "BACKSPACE",
editOperation = "DELETE_COMMITTED",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = expectedCursorAfter,
))
}

private fun deleteSelection():Boolean {
val selection = textQueries.selectedText()
if (selection.isNullOrEmpty())
{
return false
}
val deleted = selection
val before = contextBefore()
val cursorBefore = cursorPosition()
val expectedCursorAfter = selectionStart()
if (!textMutations.commitText("")) return false
correctionTracker.ensure()
editHistory.recordDelete(deleted, expectedCursorAfter)
record(ImeTelemetryEvent(
type = "DELETE_COMMITTED",
rawInput = "",
reading = "",
committedText = "",
candidates = emptyList(),
contextBefore = before,
contextAfter = contextAfter(),
correctionId = correctionTracker.id,
commitMethod = "SELECTION_DELETE",
editOperation = "DELETE_SELECTION",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = expectedCursorAfter,
))
return true
}

override fun moveCursor(direction:EditorDirection) {
navigation.moveCursor(direction)
}

override fun deleteWordBeforeCursor() {
if (compositionSession.hasComposition)
{
val rawBefore = compositionSession.raw
val cursorBefore = cursorPosition()
val before = contextBefore()
compositionSession.deleteComposition(conversionContextBefore()) ?: return
if (updateComposingText()) {
correctionTracker.ensure()
recordCompositionEdit(
"DELETE_WORD", rawBefore, cursorBefore, before, deletedText = rawBefore)
}
return
}
if (deleteSelection())
{
return
}
val before = textQueries.textBeforeCursor(80)
if (before.isNullOrEmpty())
{
return
}
val value = before
val codePoints = TextDeletion.previousWordCodePoints(value)
if (codePoints > 0)
{
val deleted = value.substring(value.offsetByCodePoints(value.length, -codePoints))
val contextBeforeDelete = contextBefore()
val cursorBefore = cursorPosition()
val expectedCursorAfter = if (cursorBefore < 0) -1 else cursorBefore - deleted.length
if (!textMutations.deleteSurroundingTextInCodePoints(codePoints, 0)) return
correctionTracker.ensure()
editHistory.recordDelete(deleted, expectedCursorAfter)
record(ImeTelemetryEvent(
type = "DELETE_COMMITTED",
rawInput = "",
reading = "",
committedText = "",
candidates = emptyList(),
contextBefore = contextBeforeDelete,
contextAfter = contextAfter(),
correctionId = correctionTracker.id,
commitMethod = "BACKSPACE_SWIPE",
editOperation = "DELETE_WORD",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = expectedCursorAfter,
))
}
}

override fun undoLastCommit() {
val undo = editHistory.entry ?: return
val currentCursor = cursorPosition()
val textBeforeCursor = if (undo is ImeEditHistory.Entry.Commit)
textQueries.textBeforeCursor(undo.text.length)
else
null
if (!editHistory.canApplyAt(currentCursor, textBeforeCursor))
{
editHistory.clear()
renderPanel()
return
}
if (undo is ImeEditHistory.Entry.Delete)
{
val restored = undo.text
val before = contextBefore()
val cursorBefore = currentCursor
if (!textMutations.commitText(restored)) return
record(ImeTelemetryEvent(
type = "UNDO",
rawInput = "",
reading = "",
committedText = restored,
candidates = emptyList(),
contextBefore = before,
contextAfter = contextAfter(),
correctionId = correctionTracker.id,
commitMethod = "UNDO_KEY",
editOperation = "RESTORE_DELETED",
cursorBefore = cursorBefore,
cursorAfter = cursorPosition(),
))
editHistory.clear()
correctionTracker.finish()
renderPanel()
return
}
if (undo is ImeEditHistory.Entry.Commit)
{
val deleted = undo.text
val codePoints = deleted!!.codePointCount(0, deleted!!.length)
val before = contextBefore()
val cursorBefore = currentCursor
if (!textMutations.deleteSurroundingTextInCodePoints(codePoints, 0)) return
correctionTracker.ensure()
record(ImeTelemetryEvent(
type = "UNDO",
rawInput = "",
reading = "",
committedText = "",
candidates = emptyList(),
contextBefore = before,
contextAfter = contextAfter(),
correctionId = correctionTracker.id,
commitMethod = "UNDO_KEY",
editOperation = "UNDO_COMMIT",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition(),
))
editHistory.clear()
renderPanel()
}
}

override fun applyModifierCycle() {
val rawBefore = compositionSession.raw
val cursorBefore = cursorPosition()
val before = contextBefore()
if (!compositionSession.cycleModifier(conversionContextBefore()))
{
return
}
if (updateComposingText()) recordCompositionEdit(
"MODIFIER_CYCLE", rawBefore, cursorBefore, before,
deletedText = finalCodePoint(rawBefore).orEmpty())
}

private fun finalCodePoint(value:String):String? {
if (value.isEmpty())
{
return value
}
val start = value.offsetByCodePoints(value.length, -1)
return value.substring(start)
}

override fun enter() {
if (compositionSession.hasComposition)
{
commitAllCurrent("ENTER_KEY")
return
}
val action = if (activeEditor == null)
EditorInfo.IME_ACTION_NONE
else
activeEditor!!.imeOptions and EditorInfo.IME_MASK_ACTION
if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
{
navigation.performEditorAction(action)
}
else
{
commitLiteral("\n", "ENTER", "ENTER_KEY")
}
}

private fun record(event:ImeTelemetryEvent) {
telemetry.record(event, uiOutput?.inputMode, compositionSession.engineName)
}

private fun recordCompositionEdit(
operation:String,
rawBefore:String,
cursorBefore:Int,
before:String,
gesture:FlickGesture? = null,
deletedText:String = "") {
record(ImeTelemetryEvent(
type = "COMPOSITION_EDIT",
rawInput = compositionSession.raw,
reading = compositionSession.reading,
committedText = "",
candidates = compositionSession.candidates.toList(),
contextBefore = before,
contextAfter = contextAfter(),
compositionId = compositionSession.compositionId,
correctionId = correctionTracker.id,
candidateSource = compositionSession.candidateSource,
editOperation = operation,
rawBefore = rawBefore,
rawAfter = compositionSession.raw,
deletedText = deletedText,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition(),
gesture = gesture,
))
}

private fun cursorPosition():Int {
return textQueries.cursorPosition()
}

private fun selectionStart():Int {
return textQueries.selectionStart()
}

private fun selectedTextLength():Int {
return textQueries.selectedTextLength()
}

private fun expectedCursorAfterCommit(
cursorBefore:Int,
committedText:String,
replacedLength:Int):Int {
if (cursorBefore < 0) return -1
return Math.max(0, cursorBefore - replacedLength) + committedText.length
}

private fun contextBefore():String {
if (!telemetry.shouldIncludeContext())
{
return ""
}
return conversionContextBefore()
}

/** Context used ephemerally by Mozc; this value is never persisted by this method.  */
private fun conversionContextBefore():String {
val value = textQueries.textBeforeCursor(
CONTEXT_LIMIT + displayedComposition.length)
if (value == null)
{
return ""
}
var context = value!!.toString()
if (displayedComposition.isNotEmpty() &&
context.endsWith(displayedComposition))
{
context = context.substring(0, context.length - displayedComposition.length)
}
return if (context.length <= CONTEXT_LIMIT)
context
else
context.substring(context.length - CONTEXT_LIMIT)
}

private fun contextAfter():String {
if (!telemetry.shouldIncludeContext())
{
return ""
}
return textQueries.textAfterCursor(CONTEXT_LIMIT).orEmpty()
}

companion object {
private val CONTEXT_LIMIT = 64
}
}
