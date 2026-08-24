package dev.kotonoha.collector

import dev.kotonoha.collector.input.FlickGesture
import dev.kotonoha.collector.telemetry.CollectionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionEventTest {
    @Test
    fun schemaV3ContainsAnalysisAndGestureMetadata() {
        val gesture = FlickGesture("た", "UP", 1.5f, -28f, 143L, 0.48f, 0.61f, false)
        val payload = event(gesture).toPayload()

        assertEquals(3, payload["schema_version"])
        assertEquals("composition-1", payload["composition_id"])
        assertEquals("correction-1", payload["correction_id"])
        assertEquals("PREDICTION", payload["candidate_source"])
        assertEquals("INSERT", payload["edit_operation"])
        assertEquals("つ", payload["raw_after"])
        assertEquals("た", payload["gesture_key"])
        assertEquals("UP", payload["gesture_direction"])
        assertEquals(-28f, payload["gesture_dy_dp"])
        assertEquals(143L, payload["gesture_duration_ms"])
        assertEquals("Mozc 24.11.oss", payload["engine_version"])
        assertEquals("0.16.0", payload["app_version"])
        assertEquals("kotonoha-kana12-qwerty-v1", payload["layout_version"])
    }

    @Test
    fun absentGestureStillProducesStableTypedColumns() {
        val payload = event(null).toPayload()

        assertTrue(payload.containsKey("gesture_direction"))
        assertEquals("", payload["gesture_direction"])
        assertEquals(0L, payload["gesture_duration_ms"])
        assertEquals(-1f, payload["gesture_start_x_ratio"])
        assertFalse(payload["gesture_long_press"] as Boolean)
        assertEquals(listOf("今日", "きょう"), payload["candidates"])
    }

    private fun event(gesture: FlickGesture?) = CollectionEvent(
        sessionId = "session-1",
        sequence = 7,
        type = "COMPOSITION_EDIT",
        packageId = "hashed-app",
        inputType = 1,
        inputMode = "KANA_FLICK",
        rawInput = "つ",
        reading = "つ",
        committedText = "",
        candidates = listOf("今日", "きょう"),
        selectedIndex = -1,
        contextBefore = "前文",
        contextAfter = "後文",
        compositionId = "composition-1",
        correctionId = "correction-1",
        candidateSource = "PREDICTION",
        editOperation = "INSERT",
        rawBefore = "",
        rawAfter = "つ",
        cursorBefore = 3,
        cursorAfter = 4,
        engineVersion = "Mozc 24.11.oss",
        appVersion = "0.16.0",
        layoutVersion = "kotonoha-kana12-qwerty-v1",
        gesture = gesture,
        timestampMs = 1234L,
    )
}
