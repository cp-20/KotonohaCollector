package dev.kotonoha.collector

/** Pure candidate-state transitions shared by the IME and host-side tests. */
internal object CandidateFlow {
    fun shouldCommitBeforeInput(selectedIndex: Int, candidateCount: Int): Boolean =
        selectedIndex in 0 until candidateCount

    fun nextIndex(selectedIndex: Int, candidateCount: Int): Int = when {
        candidateCount <= 0 -> -1
        selectedIndex !in 0 until candidateCount -> 0
        else -> (selectedIndex + 1) % candidateCount
    }
}
