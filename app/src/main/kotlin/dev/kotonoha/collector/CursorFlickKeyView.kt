package dev.kotonoha.collector

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Four-way cursor key used by the two side columns of the Japanese 12-key layout.  */
internal class CursorFlickKeyView @JvmOverloads constructor(context:Context, private val defaultRight:Boolean = false, private val listener:Listener? = null):View(context) {
private val palette:GboardPalette
private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val triangle = Path()
private val directionThreshold:Float

private var downX:Float = 0.toFloat()
private var downY:Float = 0.toFloat()
private var tracking:Boolean = false
private var direction:Direction? = Direction.CENTER
private var guide:CursorGuideDrawable? = null
internal enum class Direction {
CENTER, 
LEFT, 
UP, 
RIGHT, 
DOWN
}

internal fun interface Listener {
 fun onMove(direction:Direction)
}

init{
palette = GboardPalette(context)
directionThreshold = dp(14f)
backgroundPaint.setColor(palette!!.sideKey)
trianglePaint.setColor(palette!!.secondaryText)
setClickable(true)
setFocusable(true)
setContentDescription(if (defaultRight)
"カーソルを右へ。上下左右にフリック可能"
else
"カーソルを左へ。上下左右にフリック可能")
}

override fun onDraw(canvas:Canvas) {
super.onDraw(canvas)
backgroundPaint.setColor(if (tracking) palette!!.pressed else palette!!.sideKey)
canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(3f), dp(3f), backgroundPaint)
drawTriangle(canvas, getWidth() * 0.5f, getHeight() * 0.5f, 
Math.min(getWidth(), getHeight()) * 0.18f, 
if (defaultRight) Direction.RIGHT else Direction.LEFT, 
palette!!.secondaryText)
}

override fun onTouchEvent(event:MotionEvent):Boolean {
when (event.getActionMasked()) {
MotionEvent.ACTION_DOWN -> {
tracking = true
direction = Direction.CENTER
downX = event.getX()
downY = event.getY()
getParent().requestDisallowInterceptTouchEvent(true)
performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
showGuide()
invalidate()
return true
}
MotionEvent.ACTION_MOVE -> {
val next = directionFor(event.getX() - downX, event.getY() - downY)
if (next != direction)
{
direction = next
performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
updateGuide()
}
return true
}
MotionEvent.ACTION_UP -> {
var completed:Direction = directionFor(event.getX() - downX, event.getY() - downY)
if (completed == Direction.CENTER)
{
completed = if (defaultRight) Direction.RIGHT else Direction.LEFT
}
stopTracking()
performClick()
if (listener != null)
{
listener!!.onMove(completed)
}
return true
}
MotionEvent.ACTION_CANCEL -> {
stopTracking()
return true
}
else -> return super.onTouchEvent(event)
}
}

override fun performClick():Boolean {
super.performClick()
return true
}

protected override fun onDetachedFromWindow() {
dismissGuide()
super.onDetachedFromWindow()
}

private fun directionFor(deltaX:Float, deltaY:Float):Direction {
if (kotlin.math.hypot(deltaX, deltaY) < directionThreshold)
{
return Direction.CENTER
}
if (Math.abs(deltaX) > Math.abs(deltaY))
{
return if (deltaX < 0) Direction.LEFT else Direction.RIGHT
}
return if (deltaY < 0) Direction.UP else Direction.DOWN
}

private fun showGuide() {
dismissGuide()
val overlayHost = getRootView()
if (overlayHost == null || getWidth() <= 0 || getHeight() <= 0)
{
return 
}
guide = CursorGuideDrawable()
guide!!.setDirection(if (defaultRight) Direction.RIGHT else Direction.LEFT)
val keyLocation = IntArray(2)
val rootLocation = IntArray(2)
getLocationOnScreen(keyLocation)
overlayHost!!.getLocationOnScreen(rootLocation)
val diameter = Math.round(dp(62f))
val centerX = keyLocation[0] - rootLocation[0] + getWidth() / 2
val left = Math.max(0, Math.min(centerX - diameter / 2, 
overlayHost!!.getWidth() - diameter))
val keyTop = keyLocation[1] - rootLocation[1]
val top = Math.max(0, keyTop - diameter - Math.round(dp(10f)))
guide!!.setBounds(left, top, left + diameter, top + diameter)
guide?.let(overlayHost.overlay::add)
}

private fun updateGuide() {
if (guide != null)
{
guide!!.setDirection(if (direction == Direction.CENTER)
(if (defaultRight) Direction.RIGHT else Direction.LEFT)
else
direction)
}
}

private fun stopTracking() {
tracking = false
direction = Direction.CENTER
dismissGuide()
invalidate()
}

private fun dismissGuide() {
if (guide != null)
{
val overlayHost = rootView
guide?.let(overlayHost.overlay::remove)
}
guide = null
}

private fun drawTriangle(
canvas:Canvas,
cx:Float, 
cy:Float, 
radius:Float, 
selected:Direction, 
color:Int) {
triangle.reset()
when (selected) {
CursorFlickKeyView.Direction.LEFT -> {
triangle.moveTo(cx - radius, cy)
triangle.lineTo(cx + radius * 0.45f, cy - radius * 0.72f)
triangle.lineTo(cx + radius * 0.45f, cy + radius * 0.72f)
}
CursorFlickKeyView.Direction.UP -> {
triangle.moveTo(cx, cy - radius)
triangle.lineTo(cx - radius * 0.72f, cy + radius * 0.45f)
triangle.lineTo(cx + radius * 0.72f, cy + radius * 0.45f)
}
CursorFlickKeyView.Direction.RIGHT -> {
triangle.moveTo(cx + radius, cy)
triangle.lineTo(cx - radius * 0.45f, cy - radius * 0.72f)
triangle.lineTo(cx - radius * 0.45f, cy + radius * 0.72f)
}
CursorFlickKeyView.Direction.DOWN -> {
triangle.moveTo(cx, cy + radius)
triangle.lineTo(cx - radius * 0.72f, cy - radius * 0.45f)
triangle.lineTo(cx + radius * 0.72f, cy - radius * 0.45f)
}
else -> return 
}
triangle.close()
trianglePaint.setColor(color)
canvas.drawPath(triangle, trianglePaint)
}

private fun dp(value:Float):Float {
return value * getResources().getDisplayMetrics().density
}

private inner class CursorGuideDrawable internal constructor():Drawable() {
private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
private var selected:Direction? = Direction.LEFT

@Deprecated("Required by Drawable")
override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

init{
bubble.setColor(palette!!.panelCard)
shadow.setColor(0x26000000)
}

internal fun setDirection(next:Direction?) {
selected = next
invalidateSelf()
}

override fun draw(canvas:Canvas) {
val bounds = getBounds()
val cx = bounds!!.exactCenterX()
val cy = bounds!!.exactCenterY()
val radius = bounds!!.width() * 0.47f
canvas.drawCircle(cx, cy + dp(1.5f), radius, shadow)
canvas.drawCircle(cx, cy, radius, bubble)
val offset = bounds!!.width() * 0.18f
val size = bounds!!.width() * 0.075f
drawTriangle(canvas, cx - offset, cy, size, Direction.LEFT, 
if (selected == Direction.LEFT) palette!!.text else palette!!.guideInactive)
drawTriangle(canvas, cx, cy - offset, size, Direction.UP, 
if (selected == Direction.UP) palette!!.text else palette!!.guideInactive)
drawTriangle(canvas, cx + offset, cy, size, Direction.RIGHT, 
if (selected == Direction.RIGHT) palette!!.text else palette!!.guideInactive)
drawTriangle(canvas, cx, cy + offset, size, Direction.DOWN, 
if (selected == Direction.DOWN) palette!!.text else palette!!.guideInactive)
}

override fun setAlpha(alpha:Int) {
bubble.setAlpha(alpha)
shadow.setAlpha(alpha)
}

override fun setColorFilter(colorFilter:ColorFilter?) {
bubble.setColorFilter(colorFilter)
}
}
}
