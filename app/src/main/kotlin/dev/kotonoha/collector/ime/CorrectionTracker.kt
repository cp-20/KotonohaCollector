package dev.kotonoha.collector.ime

import java.util.UUID

/** Tracks the telemetry identity of one contiguous correction series. */
internal class CorrectionTracker(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var id: String = ""
        private set

    fun ensure(): String {
        if (id.isEmpty()) id = newId()
        return id
    }

    fun finish() {
        id = ""
    }
}
