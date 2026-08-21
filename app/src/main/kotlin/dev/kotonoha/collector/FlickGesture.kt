package dev.kotonoha.collector

/** Normalized telemetry for one completed kana flick gesture. */
internal data class FlickGesture(
    val key: String,
    val direction: String,
    val deltaXDp: Float,
    val deltaYDp: Float,
    val durationMs: Long,
    val startXRatio: Float,
    val startYRatio: Float,
    val longPressGuideShown: Boolean,
)
