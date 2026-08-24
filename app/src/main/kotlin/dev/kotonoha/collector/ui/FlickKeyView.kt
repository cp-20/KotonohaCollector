package dev.kotonoha.collector.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.util.TypedValue
import android.widget.PopupWindow
import dev.kotonoha.collector.input.FlickGesture

/** Borderless Japanese flick key with Gboard-like wedge feedback and character bubble.  */
internal class FlickKeyView @JvmOverloads constructor(
context:Context,
center:String? = "", 
left:String? = "", 
up:String? = "", 
right:String? = "", 
down:String? = "", 
private val listener:Listener? = null):View(context) {

private val center:String
private val left:String
private val up:String
private val right:String
private val down:String
private val palette:GboardPalette
private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val wedge = Path()
private val textBounds = Rect()
private val keyBounds = RectF()
private val directionThreshold:Float

private var downX:Float = 0.toFloat()
private var downY:Float = 0.toFloat()
private var tracking:Boolean = false
private var longPressGuide:Boolean = false
private var direction:Direction? = Direction.CENTER
private var characterBubble:CharacterBubbleDrawable? = null
private var characterBubbleWindow:PopupWindow? = null
private var displayLabel:String = ""
private var secondaryLabel:String = ""
private val showLongPressGuide = Runnable {
    if (!tracking || direction != Direction.CENTER || characterBubble == null) return@Runnable
    longPressGuide = true
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    characterBubble?.setGuideMode(true)
}
internal fun interface Listener {
 fun onInput(value:String, gesture:FlickGesture)
}

private enum class Direction {
CENTER, 
LEFT, 
UP, 
RIGHT, 
DOWN
}

init{
this.center = valueOrEmpty(center)
this.left = valueOrEmpty(left)
this.up = valueOrEmpty(up)
this.right = valueOrEmpty(right)
this.down = valueOrEmpty(down)
this.palette = GboardPalette(context)
this.directionThreshold = dp(16f)
this.displayLabel = this.center

setClickable(true)
setFocusable(true)
setContentDescription(buildDescription())
backgroundPaint.setColor(palette!!.key)
textPaint.setColor(palette!!.text)
textPaint.setTextAlign(Paint.Align.CENTER)
textPaint.setTextSize(sp(23))
textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
hintPaint.setColor(palette!!.secondaryText)
hintPaint.setTextAlign(Paint.Align.LEFT)
hintPaint.setTextSize(sp(10))
hintPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
}

 fun setKeyLabel(primary:String?, secondary:String?):FlickKeyView {
displayLabel = valueOrEmpty(primary)
secondaryLabel = valueOrEmpty(secondary)
invalidate()
return this
}

override fun onDraw(canvas:Canvas) {
super.onDraw(canvas)
backgroundPaint.setColor(if (tracking) palette!!.pressed else palette!!.key)
keyBounds.set(0f, 0f, width.toFloat(), height.toFloat())
canvas.drawRoundRect(keyBounds, dp(3f), dp(3f), backgroundPaint)
if (tracking && direction != Direction.CENTER)
{
drawWedge(canvas, direction!!)
}
if (!tracking && "、".equals(center) && secondaryLabel!!.isEmpty())
{
drawPunctuationHints(canvas)
}
else
{
drawKeyLabel(canvas)
}
}

override fun onTouchEvent(event:MotionEvent):Boolean {
when (event.getActionMasked()) {
MotionEvent.ACTION_DOWN -> {
tracking = true
longPressGuide = false
direction = Direction.CENTER
downX = event.getX()
downY = event.getY()
getParent().requestDisallowInterceptTouchEvent(true)
performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
showCharacterBubble()
postDelayed(showLongPressGuide, ViewConfiguration.getLongPressTimeout().toLong())
invalidate()
return true
}
MotionEvent.ACTION_MOVE -> {
val next = directionFor(event.getX() - downX, event.getY() - downY)
if (next != direction)
{
if (!longPressGuide)
{
removeCallbacks(showLongPressGuide)
}
direction = next
performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
updateCharacterBubble()
invalidate()
}
return true
}
MotionEvent.ACTION_UP -> {
removeCallbacks(showLongPressGuide)
val deltaX = event.getX() - downX
val deltaY = event.getY() - downY
val completed = directionFor(deltaX, deltaY)
val value = valueFor(completed)
val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
val gesture = FlickGesture(
center,
completed.name,
deltaX / density,
deltaY / density,
event.eventTime - event.downTime,
(downX / width.coerceAtLeast(1)).coerceIn(0f, 1f),
(downY / height.coerceAtLeast(1)).coerceIn(0f, 1f),
longPressGuide)
stopTracking()
performClick()
if (!value!!.isEmpty() && listener != null)
{
listener!!.onInput(value, gesture)
}
return true
}
MotionEvent.ACTION_CANCEL -> {
removeCallbacks(showLongPressGuide)
stopTracking()
return true
}
else -> return super.onTouchEvent(event)
}
}

private fun drawKeyLabel(canvas:Canvas) {
if (secondaryLabel!!.isEmpty())
{
drawCentered(canvas, displayLabel!!, getWidth() * 0.5f, getHeight() * 0.5f, textPaint)
return 
}
val originalSize = textPaint.getTextSize()
if (displayLabel!!.length >= 4)
{
textPaint.setTextSize(sp(17))
}
else if (displayLabel!!.length >= 3)
{
textPaint.setTextSize(sp(20))
}
drawCentered(canvas, displayLabel!!, getWidth() * 0.5f, getHeight() * 0.43f, textPaint)
textPaint.setTextSize(originalSize)
hintPaint.setTextAlign(Paint.Align.CENTER)
drawCentered(canvas, secondaryLabel!!, getWidth() * 0.5f, getHeight() * 0.76f, hintPaint)
hintPaint.setTextAlign(Paint.Align.LEFT)
}

override fun performClick():Boolean {
super.performClick()
return true
}

protected override fun onDetachedFromWindow() {
dismissCharacterBubble()
super.onDetachedFromWindow()
}

private fun drawWedge(canvas:Canvas, wedgeDirection:Direction) {
val cx = getWidth() * 0.5f
val cy = getHeight() * 0.5f
wedge.reset()
wedge.moveTo(cx, cy)
when (wedgeDirection) {
FlickKeyView.Direction.LEFT -> {
wedge.lineTo(0f, 0f)
wedge.lineTo(0f, height.toFloat())
}
FlickKeyView.Direction.UP -> {
wedge.lineTo(0f, 0f)
wedge.lineTo(width.toFloat(), 0f)
}
FlickKeyView.Direction.RIGHT -> {
wedge.lineTo(width.toFloat(), 0f)
wedge.lineTo(width.toFloat(), height.toFloat())
}
FlickKeyView.Direction.DOWN -> {
wedge.lineTo(0f, height.toFloat())
wedge.lineTo(width.toFloat(), height.toFloat())
}
else -> return 
}
wedge.close()
wedgePaint.setColor(palette!!.flickHighlight)
canvas.drawPath(wedge, wedgePaint)
}

private fun drawPunctuationHints(canvas:Canvas) {
drawOpticallyCentered(canvas, up!!, getWidth() * 0.50f, getHeight() * 0.19f, hintPaint)
drawOpticallyCentered(canvas, left!!, getWidth() * 0.27f, getHeight() * 0.53f, hintPaint)
drawOpticallyCentered(canvas, center!!, getWidth() * 0.50f, getHeight() * 0.53f, hintPaint)
drawOpticallyCentered(canvas, right!!, getWidth() * 0.73f, getHeight() * 0.53f, hintPaint)
drawOpticallyCentered(canvas, down!!, getWidth() * 0.50f, getHeight() * 0.82f, hintPaint)
}

private fun showCharacterBubble() {
dismissCharacterBubble()
if (getWidth() <= 0 || getHeight() <= 0)
{
return 
}
characterBubble = CharacterBubbleDrawable()
characterBubble!!.setValue(valueFor(direction!!))

val keyLocation = IntArray(2)
val windowLocation = IntArray(2)
getLocationOnScreen(keyLocation)
val windowRoot = getRootView()
windowRoot!!.getLocationOnScreen(windowLocation)
val diameter = Math.round(dp(72f))
val centerX = keyLocation[0] + getWidth() / 2
val desiredLeft = centerX - diameter / 2
val screenWidth = getResources().getDisplayMetrics().widthPixels
val leftBound = Math.max(0, Math.min(desiredLeft, screenWidth - diameter))
val topBound = Math.max(0, keyLocation[1] - diameter - Math.round(dp(18f)))

val preview = View(getContext())
preview.setBackground(characterBubble)
characterBubbleWindow = PopupWindow(preview, diameter, diameter, false)
characterBubbleWindow!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
characterBubbleWindow!!.setTouchable(false)
characterBubbleWindow!!.setOutsideTouchable(false)
characterBubbleWindow!!.setClippingEnabled(false)
characterBubbleWindow!!.setElevation(dp(8f))
try
{
characterBubbleWindow!!.showAtLocation(
this, 
android.view.Gravity.TOP or android.view.Gravity.START, 
leftBound - windowLocation[0], 
topBound - windowLocation[1])
}
catch (unavailableWindowToken:RuntimeException) {
characterBubbleWindow = null
characterBubble = null
}

}

private fun updateCharacterBubble() {
if (characterBubble != null)
{
val value = valueFor(direction!!)
characterBubble!!.setValue(if (value!!.isEmpty()) center else value)
characterBubble!!.setDirection(direction)
}
}

private fun stopTracking() {
tracking = false
longPressGuide = false
direction = Direction.CENTER
removeCallbacks(showLongPressGuide)
dismissCharacterBubble()
invalidate()
}

private fun dismissCharacterBubble() {
if (characterBubbleWindow != null)
{
characterBubbleWindow!!.dismiss()
}
characterBubbleWindow = null
characterBubble = null
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

private fun valueFor(selectedDirection:Direction):String {
when (selectedDirection) {
FlickKeyView.Direction.LEFT -> return left
FlickKeyView.Direction.UP -> return up
FlickKeyView.Direction.RIGHT -> return right
FlickKeyView.Direction.DOWN -> return down
FlickKeyView.Direction.CENTER -> return center
else -> return center
}
}

private fun drawCentered(canvas:Canvas, value:String, cx:Float, cy:Float, paint:Paint) {
if (value.isEmpty())
{
return 
}
val metrics = paint.fontMetrics
canvas.drawText(value, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)
}

private fun drawOpticallyCentered(canvas:Canvas, value:String, cx:Float, cy:Float, paint:Paint) {
if (value.isEmpty())
{
return 
}
paint.getTextBounds(value, 0, value.length, textBounds)
canvas.drawText(
value, 
cx - textBounds.exactCenterX(), 
cy - textBounds.exactCenterY(), 
paint)
}

private fun buildDescription():String {
return center + "、左 " + left + "、上 " + up + "、右 " + right + "、下 " + down
}

private fun valueOrEmpty(value:String?):String {
return if (value == null) "" else value
}

private fun dp(value:Float):Float {
return value * getResources().getDisplayMetrics().density
}

private fun sp(value:Int):Float {
return TypedValue.applyDimension(
TypedValue.COMPLEX_UNIT_SP,
value.toFloat(),
resources.displayMetrics,
)
}

private inner class CharacterBubbleDrawable internal constructor():Drawable() {
private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG)
private val guideText = Paint(Paint.ANTI_ALIAS_FLAG)
private var value: String = center
private var guideMode:Boolean = false
private var selected:Direction? = Direction.CENTER

@Deprecated("Required by Drawable")
override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

init{
bubblePaint.setColor(if (palette!!.dark) -0xc3bfbd else palette!!.panelCard)
shadowPaint.setColor(0x38000000)
bubbleText.setColor(palette!!.text)
bubbleText.setTextAlign(Paint.Align.CENTER)
bubbleText.setTextSize(sp(42))
bubbleText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
guideText.setColor(palette!!.secondaryText)
guideText.setTextAlign(Paint.Align.CENTER)
guideText.setTextSize(sp(16))
guideText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
}

internal fun setValue(next:String?) {
value = next.orEmpty()
invalidateSelf()
}

internal fun setGuideMode(enabled:Boolean) {
guideMode = enabled
invalidateSelf()
}

internal fun setDirection(next:Direction?) {
selected = next
invalidateSelf()
}

override fun draw(canvas:Canvas) {
val bounds = getBounds()
val cx = bounds!!.exactCenterX()
val cy = bounds!!.exactCenterY()
if (guideMode)
{
drawGuide(canvas, bounds, cx, cy)
}
else
{
val radius = bounds!!.width() * 0.47f
canvas.drawCircle(cx, cy + dp(2f), radius, shadowPaint)
canvas.drawCircle(cx, cy, radius, bubblePaint)
drawCentered(canvas, value!!, cx, cy, bubbleText)
}
}

private fun drawGuide(canvas:Canvas, bounds:Rect, cx:Float, cy:Float) {
val guideBounds = RectF(bounds)
canvas.drawRoundRect(
guideBounds.left, 
guideBounds.top + dp(2f), 
guideBounds.right, 
guideBounds.bottom + dp(2f), 
dp(12f), 
dp(12f), 
shadowPaint)
canvas.drawRoundRect(guideBounds, dp(5f), dp(5f), bubblePaint)
val horizontal = bounds!!.width() * 0.29f
val vertical = bounds!!.height() * 0.29f
drawGuideValue(canvas, center!!, cx, cy, Direction.CENTER, true)
drawGuideValue(canvas, left!!, cx - horizontal, cy, Direction.LEFT, false)
drawGuideValue(canvas, up!!, cx, cy - vertical, Direction.UP, false)
drawGuideValue(canvas, right!!, cx + horizontal, cy, Direction.RIGHT, false)
drawGuideValue(canvas, down!!, cx, cy + vertical, Direction.DOWN, false)
}

private fun drawGuideValue(
canvas:Canvas,
guideValue:String, 
x:Float, 
y:Float, 
guideDirection:Direction?, 
centerValue:Boolean) {
if (guideValue.isEmpty())
{
return 
}
val highlighted = selected == guideDirection
val paint = if (centerValue) bubbleText else guideText
val originalSize = paint.getTextSize()
val originalColor = paint.getColor()
if (centerValue)
{
paint.setTextSize(sp(30))
}
else if (highlighted)
{
paint.setTextSize(sp(20))
}
paint.setColor(if (highlighted) palette!!.text else palette!!.secondaryText)
drawCentered(canvas, guideValue, x, y, paint)
paint.setTextSize(originalSize)
paint.setColor(originalColor)
}

override fun setAlpha(alpha:Int) {
bubblePaint.setAlpha(alpha)
shadowPaint.setAlpha(alpha)
bubbleText.setAlpha(alpha)
guideText.setAlpha(alpha)
}

override fun setColorFilter(colorFilter:ColorFilter?) {
bubblePaint.setColorFilter(colorFilter)
shadowPaint.setColorFilter(colorFilter)
bubbleText.setColorFilter(colorFilter)
guideText.setColorFilter(colorFilter)
}
}
}
