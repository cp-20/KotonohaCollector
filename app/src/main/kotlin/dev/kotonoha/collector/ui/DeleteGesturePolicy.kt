package dev.kotonoha.collector.ui

/** Pure gesture decisions used by the delete key and host-side tests. */
internal object DeleteGesturePolicy {
    fun isWordSwipe(startX: Float, currentX: Float, threshold: Float): Boolean =
        startX - currentX > threshold
}
