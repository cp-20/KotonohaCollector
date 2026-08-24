package dev.kotonoha.collector

import dev.kotonoha.collector.input.CandidateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateFlowTest {
    @Test
    fun selectedCandidateCommitsBeforeNextInput() {
        assertTrue(CandidateFlow.shouldCommitBeforeInput(0, 3))
        assertTrue(CandidateFlow.shouldCommitBeforeInput(2, 3))
        assertFalse(CandidateFlow.shouldCommitBeforeInput(-1, 3))
        assertFalse(CandidateFlow.shouldCommitBeforeInput(3, 3))
        assertFalse(CandidateFlow.shouldCommitBeforeInput(0, 0))
    }

    @Test
    fun conversionKeyCyclesAndWraps() {
        assertEquals(0, CandidateFlow.nextIndex(-1, 3))
        assertEquals(1, CandidateFlow.nextIndex(0, 3))
        assertEquals(2, CandidateFlow.nextIndex(1, 3))
        assertEquals(0, CandidateFlow.nextIndex(2, 3))
        assertEquals(-1, CandidateFlow.nextIndex(0, 0))
    }

    @Test
    fun invalidSelectionRestartsAtFirstCandidate() {
        assertEquals(0, CandidateFlow.nextIndex(9, 3))
        assertEquals(-1, CandidateFlow.nextIndex(-1, -1))
    }
}
