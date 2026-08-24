package dev.kotonoha.collector.input

internal interface ConversionEngine {
    fun candidates(reading: String, contextBefore: String): List<String>

    fun predictions(reading: String, contextBefore: String): List<String> =
        candidates(reading, contextBefore)

    fun conversions(reading: String, contextBefore: String): List<String> =
        candidates(reading, contextBefore)

    /** Reading consumed by the candidate at [index], or null when it is unknown. */
    fun candidateReading(index: Int): String? = null

    fun candidateCommitted(index: Int) = Unit
    fun readingCommitted() = Unit
    fun discardComposition() = Unit
    fun resetSession() = Unit
    fun name(): String
}
