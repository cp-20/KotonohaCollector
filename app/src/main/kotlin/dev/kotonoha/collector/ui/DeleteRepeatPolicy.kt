package dev.kotonoha.collector.ui

import kotlin.math.max

/** Timing for accelerated backspace repeat, kept pure so it can be tested off-device. */
object DeleteRepeatPolicy {
    @JvmStatic
    fun initialDelayMs(platformLongPressTimeoutMs: Int): Long =
        max(320, platformLongPressTimeoutMs - 140).toLong()

    @JvmStatic
    fun intervalMs(completedDeletes: Int): Long = when {
        completedDeletes >= 20 -> 27L
        completedDeletes >= 7 -> 36L
        else -> 50L
    }

    @JvmStatic
    fun shouldHaptic(completedDeletes: Int): Boolean =
        completedDeletes == 1 || completedDeletes % 4 == 0
}
