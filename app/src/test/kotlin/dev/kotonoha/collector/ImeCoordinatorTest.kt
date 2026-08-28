package dev.kotonoha.collector

import android.view.inputmethod.EditorInfo
import dev.kotonoha.collector.editor.CompositionCommitOutcome
import dev.kotonoha.collector.editor.EditorDirection
import dev.kotonoha.collector.editor.EditorGateway
import dev.kotonoha.collector.editor.RemainingTextOutcome
import dev.kotonoha.collector.ime.ImeCoordinator
import dev.kotonoha.collector.input.ConversionEngine
import dev.kotonoha.collector.input.FlickGesture
import dev.kotonoha.collector.telemetry.ImeTelemetry
import dev.kotonoha.collector.telemetry.ImeTelemetryEvent
import dev.kotonoha.collector.ui.contract.KeyboardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeCoordinatorTest {
    @Test
    fun fullCommitActionsUseOneCommitAndNeverRecreateComposition() {
        val actions = listOf<Pair<String, (ImeCoordinator) -> Unit>>(
            "enter" to { it.enter() },
            "mode switch" to {
                assertTrue(
                    it.requestKeyboardModeChange(
                        KeyboardMode.KANA_FLICK,
                        KeyboardMode.LATIN_QWERTY,
                    ),
                )
            },
            "clipboard" to { assertTrue(it.pasteClipboard("貼")) },
            "emoji" to { assertTrue(it.commitEmoji("😀")) },
            "half width" to { it.commitHalfWidth("a", "TEST") },
        )

        actions.forEach { (label, action) ->
            val (coordinator, editor) = preparedPartialConversion()
            val composingUpdatesBefore = editor.composingUpdates.size

            action(coordinator)

            assertEquals(label, 1, editor.compositionCommits.size)
            assertEquals(
                label,
                RecordedCompositionCommit("大学きた", null, ""),
                editor.compositionCommits.single(),
            )
            assertEquals(label, composingUpdatesBefore, editor.composingUpdates.size)
            assertEquals(label, "", editor.composition)
            assertTrue(label, coordinator.candidates.isEmpty())
        }
    }

    @Test
    fun candidateTapStillUsesOnePartialCommitAndKeepsOnlyUnreadSuffixComposing() {
        val (coordinator, editor) = preparedPartialConversion()
        val composingUpdatesBefore = editor.composingUpdates.size

        assertTrue(coordinator.commitCandidate(0, "CANDIDATE_TAP"))

        assertEquals(
            listOf(RecordedCompositionCommit("大学", "きた", "きた")),
            editor.compositionCommits,
        )
        assertEquals(composingUpdatesBefore, editor.composingUpdates.size)
        assertEquals("大学きた", editor.text)
        assertEquals("きた", editor.composition)
        assertFalse(coordinator.candidates.isEmpty())
    }

    @Test
    fun debugPartialCommitCommandExercisesTheAcceptedEditorTransaction() {
        val (coordinator, editor) = coordinatorWithEditor()

        assertTrue(coordinator.commitPartialConversionTest())

        assertEquals(
            listOf(RecordedCompositionCommit("大学", "きた", "きた")),
            editor.compositionCommits,
        )
        assertEquals("大学きた", editor.text)
        assertEquals("きた", editor.composition)
        assertFalse(coordinator.candidates.isEmpty())
    }

    @Test
    fun rejectedFullCommitLeavesEditorAndSessionCompositionUntouched() {
        val (coordinator, editor) = preparedPartialConversion()
        editor.compositionCommitOutcomeOverride =
            CompositionCommitOutcome(false, RemainingTextOutcome.REJECTED)

        coordinator.enter()

        assertEquals(1, editor.compositionCommits.size)
        assertEquals("大学きた", editor.text)
        assertEquals("大学きた", editor.composition)
        assertFalse(coordinator.candidates.isEmpty())
    }

    private fun preparedPartialConversion(): Pair<ImeCoordinator, RecordingEditorGateway> {
        val (coordinator, editor) = coordinatorWithEditor()
        coordinator.handleFlickInput("だいがくきた", GESTURE)
        coordinator.showConversionCandidates()
        assertEquals("大学きた", editor.composition)
        return coordinator to editor
    }

    private fun coordinatorWithEditor(): Pair<ImeCoordinator, RecordingEditorGateway> {
        val editor = RecordingEditorGateway()
        val coordinator = ImeCoordinator(
            compositionEditor = editor,
            textMutations = editor,
            textQueries = editor,
            navigation = editor,
            conversionEngine = PartialConversionEngine(),
            telemetry = NoOpTelemetry(),
            styleComposition = { it },
        )
        return coordinator to editor
    }

    private data class RecordedCompositionCommit(
        val committedText: String,
        val remainingStyledText: String?,
        val remainingPlainText: String,
    )

    private class RecordingEditorGateway : EditorGateway {
        var text = ""
        var composition = ""
        var compositionCommitOutcomeOverride: CompositionCommitOutcome? = null
        val compositionCommits = mutableListOf<RecordedCompositionCommit>()
        val composingUpdates = mutableListOf<String>()

        override fun commitComposition(
            committedText: String,
            remainingStyledText: CharSequence?,
            remainingPlainText: String,
        ): CompositionCommitOutcome {
            compositionCommits += RecordedCompositionCommit(
                committedText,
                remainingStyledText?.toString(),
                remainingPlainText,
            )
            val outcome = compositionCommitOutcomeOverride ?: CompositionCommitOutcome(
                committed = true,
                remainingText = if (remainingPlainText.isEmpty()) {
                    RemainingTextOutcome.NONE
                } else {
                    RemainingTextOutcome.COMPOSING
                },
            )
            if (!outcome.committed) return outcome
            text = committedText + when (outcome.remainingText) {
                RemainingTextOutcome.COMPOSING,
                RemainingTextOutcome.COMMITTED_LITERAL,
                -> remainingPlainText
                RemainingTextOutcome.NONE,
                RemainingTextOutcome.REJECTED,
                -> ""
            }
            composition = if (outcome.remainingText == RemainingTextOutcome.COMPOSING) {
                remainingPlainText
            } else {
                ""
            }
            return outcome
        }

        override fun commitText(text: String): Boolean {
            this.text += text
            composition = ""
            return true
        }

        override fun updateComposition(text: CharSequence): Boolean {
            val value = text.toString()
            composingUpdates += value
            this.text = value
            composition = value
            return true
        }

        override fun clearComposition(): Boolean {
            text = text.removeSuffix(composition)
            composition = ""
            return true
        }

        override fun finishComposition(): Boolean {
            composition = ""
            return true
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean = true

        override fun selectedText(): String? = null
        override fun textBeforeCursor(length: Int): String = text.takeLast(length)
        override fun textAfterCursor(length: Int): String = ""
        override fun cursorPosition(): Int = text.length
        override fun selectionStart(): Int = text.length
        override fun selectedTextLength(): Int = 0
        override fun moveCursor(direction: EditorDirection): Boolean = true
        override fun performEditorAction(action: Int): Boolean = true

        override fun setSelection(start: Int, end: Int): Boolean = true
    }

    private class PartialConversionEngine : ConversionEngine {
        override fun candidates(reading: String, contextBefore: String): List<String> =
            listOf(reading)

        override fun conversions(reading: String, contextBefore: String): List<String> =
            listOf("大学")

        override fun candidateReading(index: Int): String = "だいがく"
        override fun name(): String = "Fake"
    }

    private class NoOpTelemetry : ImeTelemetry {
        override val collectionEnabled: Boolean = false
        override fun startInput(editorInfo: EditorInfo?) = Unit
        override fun finishInput() = Unit
        override fun toggleCollection() = Unit
        override fun shouldIncludeContext(): Boolean = false
        override fun record(event: ImeTelemetryEvent, inputMode: String?, engineVersion: String) = Unit
    }

    private companion object {
        val GESTURE = FlickGesture("あ", "CENTER", 0f, 0f, 1L, 0.5f, 0.5f, false)
    }
}
