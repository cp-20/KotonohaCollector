package dev.kotonoha.collector

import kotlin.math.min

/** Caps legacy pill radii so keys and panels keep a crisp, restrained silhouette. */
object KeyboardShapePolicy {
    @JvmStatic
    fun cornerRadiusDp(requestedRadiusDp: Int): Int = when {
        requestedRadiusDp >= 20 -> 8
        requestedRadiusDp >= 10 -> 6
        else -> min(requestedRadiusDp, 3)
    }
}
