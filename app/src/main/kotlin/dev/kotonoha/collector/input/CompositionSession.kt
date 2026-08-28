package dev.kotonoha.collector.input

import java.util.UUID

/**
 * Owns mutable Japanese composition and conversion state.
 *
 * Editor mutations, correction tracking, telemetry, and view rendering stay outside this class.
 * That keeps this state machine host-testable and its API limited to composition transitions.
 */
internal class CompositionSession(
    private val conversionEngine: ConversionEngine,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val rawBuffer = StringBuilder()
    private val candidateBuffer = mutableListOf<String>()

    val raw: String
        get() = rawBuffer.toString()

    val reading: String
        get() = RomajiConverter.convert(raw)

    val candidates: List<String>
        get() = candidateBuffer

    var selectedCandidateIndex: Int = -1
        private set

    var compositionId: String = ""
        private set

    var candidateSource: String = CANDIDATE_SOURCE_NONE
        private set

    val hasComposition: Boolean
        get() = rawBuffer.isNotEmpty()

    val engineName: String
        get() = conversionEngine.name()

    fun startInput() {
        conversionEngine.resetSession()
        clearState()
    }

    fun finishInput() {
        conversionEngine.resetSession()
        clearState()
    }

    fun requiresCommitBeforeInput(): Boolean = CandidateFlow.shouldCommitBeforeInput(
        selectedCandidateIndex,
        candidateBuffer.size,
    )

    fun append(character: String, contextBefore: String): Boolean {
        if (character.isEmpty() || requiresCommitBeforeInput()) return false
        ensureCompositionId()
        rawBuffer.append(character)
        refreshPredictions(contextBefore)
        return true
    }

    fun applyModifier(modifier: String?, contextBefore: String): Boolean {
        if (!KanaModifier.apply(rawBuffer, modifier)) return false
        ensureCompositionId()
        refreshPredictions(contextBefore)
        return true
    }

    fun cycleModifier(contextBefore: String): Boolean {
        if (!KanaModifier.cycle(rawBuffer)) return false
        ensureCompositionId()
        refreshPredictions(contextBefore)
        return true
    }

    fun deletePreviousGrapheme(contextBefore: String): String? {
        if (rawBuffer.isEmpty()) return null
        ensureCompositionId()
        val deleted = TextDeletion.previousGrapheme(raw)
        rawBuffer.delete(rawBuffer.length - deleted.length, rawBuffer.length)
        refreshPredictions(contextBefore)
        return deleted
    }

    fun deleteComposition(contextBefore: String): String? {
        if (rawBuffer.isEmpty()) return null
        ensureCompositionId()
        val deleted = raw
        rawBuffer.setLength(0)
        refreshPredictions(contextBefore)
        return deleted
    }

    /** Starts conversion or advances the currently selected conversion candidate. */
    fun convertOrCycle(contextBefore: String): String? {
        if (rawBuffer.isEmpty()) return null
        if (requiresCommitBeforeInput()) {
            selectedCandidateIndex = CandidateFlow.nextIndex(
                selectedCandidateIndex,
                candidateBuffer.size,
            )
        } else {
            candidateBuffer.clear()
            candidateBuffer.addAll(conversionEngine.conversions(reading, contextBefore))
            candidateSource = CANDIDATE_SOURCE_CONVERSION
            selectedCandidateIndex = if (candidateBuffer.isEmpty()) -1 else 0
        }
        return selectedCompositionText()
    }

    fun selectedCandidateText(): String? =
        candidateBuffer.getOrNull(selectedCandidateIndex)

    private fun selectedCompositionText(): String? {
        val candidate = selectedCandidateText() ?: return null
        return candidate + remainingForCandidate(selectedCandidateIndex).second
    }

    /** Creates a commit plan without changing selection or composition state. */
    fun planCandidateCommit(index: Int): CompositionCommitPlan? {
        val text = candidateBuffer.getOrNull(index) ?: return null
        return partialPlan(text, index)
    }

    /**
     * Creates one commit for everything currently shown in the composing range.
     *
     * A partial Mozc candidate normally leaves its unread suffix composing.  Actions such as
     * Enter and keyboard-mode changes instead promise to finish the whole composition, so they
     * must not expose that intermediate suffix to the editor as a new composing range.
     */
    fun planFullCurrentCommit(): CompositionCommitPlan? {
        val partial = when {
            requiresCommitBeforeInput() -> planCandidateCommit(selectedCandidateIndex)
            rawBuffer.isNotEmpty() -> partialPlan(reading, -1)
            else -> null
        } ?: return null
        return CompositionCommitPlan(
            intent = CompositionCommitIntent.FULL,
            text = partial.text + partial.remainingReading,
            raw = partial.raw,
            reading = partial.reading,
            candidates = partial.candidates,
            selectedIndex = partial.selectedIndex,
            compositionId = partial.compositionId,
            candidateSource = partial.candidateSource,
            consumedReading = partial.reading,
            remainingRaw = "",
            remainingReading = "",
        )
    }

    /** Call only after the editor accepted the text from [CompositionCommitPlan]. */
    fun completeCommit(
        commit: CompositionCommitPlan,
        contextBefore: String = "",
        preserveRemainingComposition: Boolean = true,
    ) {
        if (commit.selectedIndex >= 0) {
            conversionEngine.candidateCommitted(commit.selectedIndex)
        } else {
            conversionEngine.readingCommitted()
        }
        clearState()
        if (preserveRemainingComposition && commit.remainingRaw.isNotEmpty()) {
            rawBuffer.append(commit.remainingRaw)
            ensureCompositionId()
            refreshPredictions(contextBefore)
        }
    }

    /** Literals are outside Mozc composition and terminate composition metadata. */
    fun literalCommitted() {
        compositionId = ""
        candidateSource = CANDIDATE_SOURCE_NONE
    }

    fun clearComposition() {
        if (rawBuffer.isNotEmpty()) conversionEngine.discardComposition()
        clearState()
    }

    private fun refreshPredictions(contextBefore: String) {
        candidateBuffer.clear()
        if (rawBuffer.isNotEmpty()) {
            candidateBuffer.addAll(conversionEngine.predictions(reading, contextBefore))
            candidateSource = CANDIDATE_SOURCE_PREDICTION
        } else {
            conversionEngine.discardComposition()
            candidateSource = CANDIDATE_SOURCE_NONE
        }
        selectedCandidateIndex = -1
    }

    private fun partialPlan(text: String, selectedIndex: Int): CompositionCommitPlan {
        val originalRaw = raw
        val originalReading = reading
        val (remainingRaw, remainingReading) = if (selectedIndex >= 0) {
            remainingForCandidate(selectedIndex)
        } else {
            "" to ""
        }
        val consumedReading = originalReading.removeSuffix(remainingReading)
        return CompositionCommitPlan(
            intent = CompositionCommitIntent.PARTIAL,
            text = text,
            raw = originalRaw,
            reading = originalReading,
            candidates = candidateBuffer.toList(),
            selectedIndex = selectedIndex,
            compositionId = compositionId,
            candidateSource = candidateSource,
            consumedReading = consumedReading,
            remainingRaw = remainingRaw,
            remainingReading = remainingReading,
        )
    }

    private fun remainingForCandidate(index: Int): Pair<String, String> {
        val fullReading = reading
        val candidateReading = conversionEngine.candidateReading(index).orEmpty()
        val consumedReading = when {
            candidateReading.isEmpty() -> fullReading
            fullReading.startsWith(candidateReading) -> candidateReading
            // A completion candidate consumes all input even when its key extends the reading.
            candidateReading.startsWith(fullReading) -> fullReading
            else -> fullReading
        }
        if (consumedReading.length >= fullReading.length) return "" to ""

        val rawPrefixLength = rawPrefixLengthFor(consumedReading) ?: return "" to ""
        return raw.substring(rawPrefixLength) to fullReading.substring(consumedReading.length)
    }

    /** Finds the raw boundary corresponding to a converted-reading boundary. */
    private fun rawPrefixLengthFor(consumedReading: String): Int? {
        if (consumedReading.isEmpty()) return 0
        for (end in 1..rawBuffer.length) {
            if (RomajiConverter.convert(rawBuffer.substring(0, end)) == consumedReading) return end
        }
        return null
    }

    private fun ensureCompositionId(): String {
        if (compositionId.isEmpty()) compositionId = newId()
        return compositionId
    }

    private fun clearState() {
        rawBuffer.setLength(0)
        candidateBuffer.clear()
        selectedCandidateIndex = -1
        compositionId = ""
        candidateSource = CANDIDATE_SOURCE_NONE
    }

    private companion object {
        const val CANDIDATE_SOURCE_NONE = "NONE"
        const val CANDIDATE_SOURCE_PREDICTION = "PREDICTION"
        const val CANDIDATE_SOURCE_CONVERSION = "CONVERSION"
    }
}
