package dev.kotonoha.collector.telemetry

import dev.kotonoha.collector.input.FlickGesture
import org.json.JSONArray
import org.json.JSONObject

internal data class CollectionEvent(
    val sessionId: String,
    val sequence: Long,
    val type: String,
    val packageId: String,
    val inputType: Int,
    val inputMode: String?,
    val rawInput: String?,
    val reading: String?,
    val committedText: String?,
    val candidates: List<String>?,
    val selectedIndex: Int,
    val contextBefore: String?,
    val contextAfter: String?,
    val compositionId: String = "",
    val correctionId: String = "",
    val candidateSource: String = "NONE",
    val commitMethod: String = "",
    val editOperation: String = "",
    val rawBefore: String = "",
    val rawAfter: String = "",
    val deletedText: String = "",
    val cursorBefore: Int = -1,
    val cursorAfter: Int = -1,
    val engineVersion: String = "",
    val appVersion: String = "",
    val layoutVersion: String = "",
    val gesture: FlickGesture? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    internal fun toPayload(): Map<String, Any> = linkedMapOf(
        "schema_version" to 3,
        "timestamp_ms" to timestampMs,
        "session_id" to sessionId,
        "sequence" to sequence,
        "type" to type,
        "app_id" to packageId,
        "input_type" to inputType,
        "input_mode" to inputMode.orEmpty(),
        "raw_input" to rawInput.orEmpty(),
        "reading" to reading.orEmpty(),
        "committed_text" to committedText.orEmpty(),
        "candidates" to candidates.orEmpty(),
        "selected_index" to selectedIndex,
        "context_before" to contextBefore.orEmpty(),
        "context_after" to contextAfter.orEmpty(),
        "composition_id" to compositionId,
        "correction_id" to correctionId,
        "candidate_source" to candidateSource,
        "commit_method" to commitMethod,
        "edit_operation" to editOperation,
        "raw_before" to rawBefore,
        "raw_after" to rawAfter,
        "deleted_text" to deletedText,
        "cursor_before" to cursorBefore,
        "cursor_after" to cursorAfter,
        "engine_version" to engineVersion,
        "app_version" to appVersion,
        "layout_version" to layoutVersion,
        "gesture_key" to gesture?.key.orEmpty(),
        "gesture_direction" to gesture?.direction.orEmpty(),
        "gesture_dx_dp" to (gesture?.deltaXDp ?: 0f),
        "gesture_dy_dp" to (gesture?.deltaYDp ?: 0f),
        "gesture_duration_ms" to (gesture?.durationMs ?: 0L),
        "gesture_start_x_ratio" to (gesture?.startXRatio ?: -1f),
        "gesture_start_y_ratio" to (gesture?.startYRatio ?: -1f),
        "gesture_long_press" to (gesture?.longPressGuideShown ?: false),
    )

    fun toJson(): JSONObject = JSONObject().apply {
        toPayload().forEach { (key, value) ->
            put(key, if (value is List<*>) JSONArray(value) else value)
        }
    }
}
