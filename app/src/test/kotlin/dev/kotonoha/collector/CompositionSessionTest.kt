package dev.kotonoha.collector

import dev.kotonoha.collector.editor.CompositionCommitOutcome
import dev.kotonoha.collector.editor.CompositionEditor
import dev.kotonoha.collector.editor.RemainingTextOutcome
import dev.kotonoha.collector.ime.CompositionCommitter
import dev.kotonoha.collector.ime.CorrectionTracker
import dev.kotonoha.collector.ime.ImeEditHistory
import dev.kotonoha.collector.input.CompositionSession
import dev.kotonoha.collector.input.ConversionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionSessionTest {
    @Test
    fun compositionOwnsPredictionConversionAndCommitTransitions() {
        val engine = FakeConversionEngine()
        val controller = CompositionSession(engine) { "id-${engine.ids++}" }

        assertTrue(controller.append("きょう", "前文"))
        assertEquals("きょう", controller.raw)
        assertEquals(listOf("予測:きょう", "きょう"), controller.candidates)
        assertEquals("PREDICTION", controller.candidateSource)

        assertEquals("変換:きょう", controller.convertOrCycle("前文"))
        val preparedCommit = controller.planCurrentCommit()
        assertNotNull(preparedCommit)
        val commit = preparedCommit!!
        assertEquals("変換:きょう", commit.text)
        assertEquals(0, commit.selectedIndex)

        controller.completeCommit(commit)
        assertEquals(listOf(0), engine.committedCandidates)
        assertFalse(controller.hasComposition)
        assertTrue(controller.candidates.isEmpty())
    }

    @Test
    fun deletingLastCompositionDiscardsNativeComposition() {
        val engine = FakeConversionEngine()
        val controller = CompositionSession(engine) { "stable-id" }
        controller.append("あ", "")

        assertEquals("あ", controller.deletePreviousGrapheme(""))
        assertFalse(controller.hasComposition)
        assertEquals(1, engine.discardCount)
    }

    @Test
    fun correctionTrackerKeepsOneIdUntilTheSeriesFinishes() {
        val tracker = CorrectionTracker { "stable-id" }

        assertEquals("stable-id", tracker.ensure())
        assertEquals("stable-id", tracker.ensure())
        tracker.finish()

        assertEquals("", tracker.id)
    }

    @Test
    fun partialCandidateCommitKeepsUnreadSuffixAsComposition() {
        val engine = FakeConversionEngine().apply {
            conversionCandidates = listOf("大学", "だいがくきた")
            candidateReadings[0] = "だいがく"
        }
        val controller = CompositionSession(engine) { "id-${engine.ids++}" }
        controller.append("だいがくきた", "")

        assertEquals("大学きた", controller.convertOrCycle(""))
        val commit = controller.planCurrentCommit()!!
        assertEquals("大学", commit.text)
        assertEquals("だいがく", commit.consumedReading)
        assertEquals("きた", commit.remainingRaw)
        assertEquals("きた", commit.remainingReading)

        controller.completeCommit(commit, "大学")

        assertEquals(listOf(0), engine.committedCandidates)
        assertTrue(controller.hasComposition)
        assertEquals("きた", controller.raw)
        assertEquals("きた", controller.reading)
        assertEquals(listOf("予測:きた", "きた"), controller.candidates)
    }

    @Test
    fun planningCandidateCommitDoesNotMutateSelectionBeforeEditorAcceptsIt() {
        val engine = FakeConversionEngine()
        val controller = CompositionSession(engine) { "id-${engine.ids++}" }
        controller.append("きょう", "")

        val commit = controller.planCandidateCommit(0)

        assertNotNull(commit)
        assertEquals(-1, controller.selectedCandidateIndex)
        assertEquals("きょう", controller.raw)
        assertTrue(controller.hasComposition)
        assertTrue(engine.committedCandidates.isEmpty())
    }

    @Test
    fun acceptedPrefixCanDropRejectedRemainingCompositionWithoutKeepingStaleState() {
        val engine = FakeConversionEngine().apply {
            conversionCandidates = listOf("大学")
            candidateReadings[0] = "だいがく"
        }
        val controller = CompositionSession(engine) { "id-${engine.ids++}" }
        controller.append("だいがくきた", "")
        controller.convertOrCycle("")
        val commit = controller.planCurrentCommit()!!

        controller.completeCommit(
            commit,
            preserveRemainingComposition = false,
        )

        assertFalse(controller.hasComposition)
        assertTrue(controller.candidates.isEmpty())
        assertEquals(listOf(0), engine.committedCandidates)
    }

    @Test
    fun rejectedEditorCommitLeavesCompositionStateUntouched() {
        val engine = FakeConversionEngine()
        val controller = CompositionSession(engine) { "id-${engine.ids++}" }
        controller.append("きょう", "")
        controller.convertOrCycle("")
        val commit = controller.planCurrentCommit()!!
        val committer = CompositionCommitter(
            controller,
            FakeCompositionEditor(CompositionCommitOutcome(false, RemainingTextOutcome.REJECTED)),
            { it },
        )

        assertNull(committer.apply(commit, ""))

        assertTrue(controller.hasComposition)
        assertEquals("きょう", controller.raw)
        assertEquals(0, controller.selectedCandidateIndex)
        assertTrue(engine.committedCandidates.isEmpty())
    }

    @Test
    fun literalSuffixFallbackPreservesTextButDoesNotKeepStaleComposition() {
        val engine = FakeConversionEngine().apply {
            conversionCandidates = listOf("大学")
            candidateReadings[0] = "だいがく"
        }
        val controller = CompositionSession(engine) { "id-${engine.ids++}" }
        controller.append("だいがくきた", "")
        controller.convertOrCycle("")
        val commit = controller.planCurrentCommit()!!
        val committer = CompositionCommitter(
            controller,
            FakeCompositionEditor(
                CompositionCommitOutcome(true, RemainingTextOutcome.COMMITTED_LITERAL),
            ),
            { it },
        )

        val applied = committer.apply(commit, "")!!

        assertEquals("きた", applied.insertedSuffix)
        assertFalse(applied.preservesComposition)
        assertFalse(controller.hasComposition)
        assertEquals(listOf(0), engine.committedCandidates)
    }

    @Test
    fun editHistoryKeepsOperationKindAndCursorAnchor() {
        val history = ImeEditHistory()
        history.recordCommit("猫", 4)
        assertEquals(ImeEditHistory.Entry.Commit("猫", 4), history.entry)
        assertTrue(history.canApplyAt(4, "猫"))
        assertFalse(history.canApplyAt(3, "猫"))
        assertFalse(history.canApplyAt(4, "犬"))
        history.recordDelete("犬", 2)
        assertEquals(ImeEditHistory.Entry.Delete("犬", 2), history.entry)
        assertTrue(history.canApplyAt(2))
        history.clear()
        assertFalse(history.canUndo)
    }

    private class FakeConversionEngine : ConversionEngine {
        var ids = 1
        var discardCount = 0
        val committedCandidates = mutableListOf<Int>()
        var conversionCandidates: List<String>? = null
        val candidateReadings = mutableMapOf<Int, String>()

        override fun candidates(reading: String, contextBefore: String): List<String> =
            listOf("予測:$reading", reading)

        override fun conversions(reading: String, contextBefore: String): List<String> =
            conversionCandidates ?: listOf("変換:$reading", reading)

        override fun candidateReading(index: Int): String? = candidateReadings[index]

        override fun candidateCommitted(index: Int) {
            committedCandidates += index
        }

        override fun discardComposition() {
            discardCount++
        }

        override fun name(): String = "Fake"
    }

    private class FakeCompositionEditor(
        private val outcome: CompositionCommitOutcome,
    ) : CompositionEditor {
        override fun commitComposition(
            committedText: String,
            remainingStyledText: CharSequence?,
            remainingPlainText: String,
        ): CompositionCommitOutcome = outcome
    }
}
