package dev.kotonoha.collector.ui

/** Gboard-like cursor-key repeat timing, kept pure so it can be tested off-device. */
internal object CursorRepeatPolicy {
    fun initialDelayMs(platformLongPressTimeoutMs: Int): Long =
        platformLongPressTimeoutMs.toLong()

    fun intervalMs(): Long = 50L
}
