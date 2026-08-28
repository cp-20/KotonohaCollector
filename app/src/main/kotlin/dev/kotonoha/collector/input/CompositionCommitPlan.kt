package dev.kotonoha.collector.input

/** Whether an editor action keeps an unread suffix composing or finishes the whole preedit. */
internal enum class CompositionCommitIntent {
    PARTIAL,
    FULL,
}

/** Pure description of one editor commit, created before any fallible InputConnection call. */
internal data class CompositionCommitPlan(
    val intent: CompositionCommitIntent,
    val text: String,
    val raw: String,
    val reading: String,
    val candidates: List<String>,
    val selectedIndex: Int,
    val compositionId: String,
    val candidateSource: String,
    val consumedReading: String,
    val remainingRaw: String,
    val remainingReading: String,
) {
    init {
        require(text.isNotEmpty()) { "A composition commit must contain text" }
        require(intent != CompositionCommitIntent.FULL || remainingRaw.isEmpty()) {
            "A full commit cannot retain raw composition"
        }
        require(intent != CompositionCommitIntent.FULL || remainingReading.isEmpty()) {
            "A full commit cannot retain reading composition"
        }
    }
}
