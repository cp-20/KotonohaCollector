package dev.kotonoha.collector.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.kotonoha.collector.editor.EditorDirection

/** Binds compound delete and space gestures without owning any keyboard view state. */
internal class KeyboardGestureBinder(
    private val context: Context,
    private val deleteOne: () -> Unit,
    private val deleteWord: () -> Unit,
    private val moveCursor: (EditorDirection) -> Unit,
    private val showInputMethodPicker: () -> Unit,
) {
    fun bindDelete(key: View) {
        key.setOnClickListener(null)
        var startX = 0f
        var wordDeleted = false
        var repeating = false
        var repeatCount = 0
        val repeatDelete = object : Runnable {
            override fun run() {
                if (!key.isPressed || wordDeleted) return
                repeating = true
                deleteOne()
                repeatCount++
                if (DeleteRepeatPolicy.shouldHaptic(repeatCount)) {
                    key.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                key.postDelayed(this, DeleteRepeatPolicy.intervalMs(repeatCount))
            }
        }
        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    wordDeleted = false
                    repeating = false
                    repeatCount = 0
                    view.isPressed = true
                    view.postDelayed(
                        repeatDelete,
                        DeleteRepeatPolicy.initialDelayMs(ViewConfiguration.getLongPressTimeout()),
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!wordDeleted && isDeleteWordSwipe(startX, event.x)) {
                        view.removeCallbacks(repeatDelete)
                        deleteWord()
                        wordDeleted = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(repeatDelete)
                    view.isPressed = false
                    view.performClick()
                    // Very fast swipes may arrive without ACTION_MOVE.
                    if (!wordDeleted && isDeleteWordSwipe(startX, event.x)) {
                        deleteWord()
                        wordDeleted = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (!wordDeleted && !repeating) deleteOne()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(repeatDelete)
                    view.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    fun bindSpace(key: View, tapAction: () -> Unit) {
        key.setOnClickListener(null)
        var startX = 0f
        var appliedSteps = 0
        var cursorGesture = false
        var longPressTriggered = false
        val showPicker = Runnable {
            if (key.isPressed && appliedSteps == 0) {
                longPressTriggered = true
                key.performLongClick()
            }
        }
        key.setOnLongClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showInputMethodPicker()
            true
        }

        fun applyCursorSteps(view: View, currentX: Float) {
            if (longPressTriggered) return
            val steps = Math.round((currentX - startX) / dp(26f))
            if (steps != 0) {
                cursorGesture = true
                view.removeCallbacks(showPicker)
            }
            while (appliedSteps < steps) {
                moveCursor(EditorDirection.RIGHT)
                appliedSteps++
            }
            while (appliedSteps > steps) {
                moveCursor(EditorDirection.LEFT)
                appliedSteps--
            }
        }

        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    appliedSteps = 0
                    cursorGesture = false
                    longPressTriggered = false
                    view.isPressed = true
                    view.postDelayed(showPicker, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    applyCursorSteps(view, event.x)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    applyCursorSteps(view, event.x)
                    view.removeCallbacks(showPicker)
                    view.isPressed = false
                    if (!longPressTriggered) {
                        view.performClick()
                        if (!cursorGesture) tapAction()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(showPicker)
                    view.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun isDeleteWordSwipe(startX: Float, currentX: Float): Boolean =
        DeleteGesturePolicy.isWordSwipe(startX, currentX, dp(26).toFloat())

    private fun dp(value: Int): Int = Math.round(value * context.resources.displayMetrics.density)
    private fun dp(value: Float): Int = Math.round(value * context.resources.displayMetrics.density)
}
