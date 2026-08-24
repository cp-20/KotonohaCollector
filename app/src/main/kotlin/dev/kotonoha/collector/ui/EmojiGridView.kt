package dev.kotonoha.collector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import dev.kotonoha.collector.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class EmojiGridView private constructor(
    context: Context,
    attributes: AttributeSet?,
    private val listener: Listener?,
) : View(context, attributes) {
    constructor(context: Context) : this(context, null, null)
    constructor(context: Context, attributes: AttributeSet?) : this(context, attributes, null)
    constructor(context: Context, listener: Listener?) : this(context, null, listener)
    fun interface Listener {
        fun onEmoji(emoji: String)
    }

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(27)
    }
    private val palette = GboardPalette(context)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.pressed }
    private val clipBounds = Rect()
    private val pressedBounds = RectF()
    private val cellHeight = dp(50)
    private var emojis: List<String> = emptyList()
    private var pressedIndex = -1

    init {
        isClickable = true
        isFocusable = true
    }

    fun setEmojis(values: List<String>?) {
        emojis = values.orEmpty().toList()
        contentDescription = resources.getQuantityString(
            R.plurals.emoji_count,
            emojis.size,
            emojis.size,
        )
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = (emojis.size + COLUMNS - 1) / COLUMNS
        setMeasuredDimension(width, max(cellHeight, rows * cellHeight))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellWidth = width.toFloat() / COLUMNS
        canvas.getClipBounds(clipBounds)
        val firstRow = max(0, clipBounds.top / cellHeight)
        val lastRow = min((emojis.size + COLUMNS - 1) / COLUMNS, clipBounds.bottom / cellHeight + 1)
        for (index in firstRow * COLUMNS until min(emojis.size, lastRow * COLUMNS)) {
            val row = index / COLUMNS
            val column = index % COLUMNS
            val centerX = (column + 0.5f) * cellWidth
            val centerY = (row + 0.5f) * cellHeight
            if (index == pressedIndex) {
                pressedBounds.set(
                    column * cellWidth + dp(3),
                    row * cellHeight.toFloat() + dp(3),
                    (column + 1) * cellWidth - dp(3),
                    (row + 1) * cellHeight.toFloat() - dp(3),
                )
                canvas.drawRoundRect(pressedBounds, dp(3).toFloat(), dp(3).toFloat(), pressedPaint)
            }
            drawCentered(canvas, emojis[index], centerX, centerY)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
            pressedIndex = indexAt(event.x, event.y)
            invalidate()
            true
        }
        MotionEvent.ACTION_UP -> {
            val selectedIndex = indexAt(event.x, event.y)
            pressedIndex = -1
            invalidate()
            performClick()
            emojis.getOrNull(selectedIndex)?.let { listener?.onEmoji(it) }
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            pressedIndex = -1
            invalidate()
            true
        }
        else -> super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x < 0 || y < 0 || x >= width) return -1
        val cellWidth = width.toFloat() / COLUMNS
        val column = min(COLUMNS - 1, (x / cellWidth).toInt())
        return (y / cellHeight).toInt().times(COLUMNS).plus(column).takeIf { it < emojis.size } ?: -1
    }

    private fun drawCentered(canvas: Canvas, text: String, centerX: Float, centerY: Float) {
        val metrics = emojiPaint.fontMetrics
        canvas.drawText(text, centerX, centerY - (metrics.ascent + metrics.descent) / 2f, emojiPaint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun sp(value: Int): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value.toFloat(),
        resources.displayMetrics,
    )

    private companion object {
        const val COLUMNS = 8
    }
}
