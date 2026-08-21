package dev.kotonoha.collector

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.InputType
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedList
import java.util.UUID
import java.io.File

 class CollectorImeService:InputMethodService() {

private val rawComposition = StringBuilder()
private val currentCandidates = mutableListOf<String>()
private val clipboardHistory = ClipboardHistory()
private val recentEmojis = LinkedList<String>()

private var conversionEngine:ConversionEngine? = null
private var eventStore:EventStore? = null
private var palette:GboardPalette? = null
private var toolbarContainer:LinearLayout? = null
private var candidateContainer:LinearLayout? = null
private var panelContainer:LinearLayout? = null
private val menuButton:KeyboardIconView? = null
private var emojiGridView:EmojiGridView? = null
private var activeEditor:EditorInfo? = null
private var sessionId = UUID.randomUUID().toString()
private var selectedEmojiGroup:String? = null
private var lastComposedText:String = ""
private var lastCommittedText:String = ""
private var lastDeletedText:String = ""
private var sequence:Long = 0
private var selectedCandidateIndex = -1
private var compositionId = ""
private var activeCorrectionId = ""
private var currentCandidateSource = "NONE"
private var sensitiveField = true
private var latinUppercase:Boolean = false
private var latinCapsLock:Boolean = false
private var symbolSecondPage:Boolean = false
private var lastShiftTapMs:Long = 0
private var keyboardMode:KeyboardMode = KeyboardMode.KANA_FLICK
private var panelMode:PanelMode = PanelMode.KEYBOARD

private enum class KeyboardMode {
KANA_FLICK, 
LATIN_QWERTY, 
SYMBOL_QWERTY
}

private enum class PanelMode {
KEYBOARD, 
CANDIDATES, 
CLIPBOARD, 
EMOJI, 
FEATURES
}

override fun onCreate() {
super.onCreate()
palette = GboardPalette(this)
conversionEngine = MozcConversionEngine.createOrFallback(this)
eventStore = EventStore.get(this)
}

override fun onCreateInputView():View {
val root = LinearLayout(this)
root.setOrientation(LinearLayout.VERTICAL)
root.setPadding(0, 0, 0, dp(14))
root.setBackgroundColor(palette!!.background)

root.addView(createToolbar(), LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

panelContainer = LinearLayout(this)
panelContainer!!.setOrientation(LinearLayout.VERTICAL)
panelContainer!!.setMinimumHeight(dp(PANEL_HEIGHT_DP))
root.addView(panelContainer, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(PANEL_HEIGHT_DP)))

renderPanel()
refreshStatus()
refreshCandidateViews()
return root
}

override fun onStartInput(attribute:EditorInfo?, restarting:Boolean) {
super.onStartInput(attribute, restarting)
activeEditor = attribute
sensitiveField = PrivacyGuard.isSensitive(attribute)
sessionId = UUID.randomUUID().toString()
sequence = 0
compositionId = ""
activeCorrectionId = ""
currentCandidateSource = "NONE"
lastCommittedText = ""
lastDeletedText = ""
panelMode = PanelMode.KEYBOARD
if (conversionEngine != null)
{
conversionEngine!!.resetSession()
}
clearComposition(false)
renderPanel()
refreshStatus()
}

override fun onFinishInput() {
clearComposition(false)
if (conversionEngine != null)
{
conversionEngine!!.resetSession()
}
activeEditor = null
sensitiveField = true
panelMode = PanelMode.KEYBOARD
super.onFinishInput()
}

private fun createToolbar():View? {
toolbarContainer = LinearLayout(this)
toolbarContainer!!.setGravity(Gravity.CENTER_VERTICAL)
toolbarContainer!!.setOrientation(LinearLayout.HORIZONTAL)
toolbarContainer!!.setPadding(dp(5), dp(2), dp(5), dp(2))
toolbarContainer!!.setBackgroundColor(palette!!.background)
return toolbarContainer
}

private fun toolbarIcon(
icon:KeyboardIconView.Icon?, 
description:String?, 
listener:View.OnClickListener?):KeyboardIconView {
val view = KeyboardIconView(this, icon)
view.setIconBoxDp(24)
view.setIconColor(palette!!.text)
view.setContentDescription(description)
view.setBackground(pressableBackground(
Color.TRANSPARENT, 
palette!!.pressed, 
24))
view.setOnClickListener({ clicked->
clicked!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
listener!!.onClick(clicked) })
return view
}

private fun toolbarBackButton():View {
return toolbarIcon(
KeyboardIconView.Icon.BACK, 
"キーボードに戻る", 
{ view-> setPanelMode(PanelMode.KEYBOARD) })
}

private fun setPanelMode(mode:PanelMode) {
panelMode = mode
renderPanel()
refreshCandidateViews()
}

private fun renderPanel() {
if (panelContainer == null)
{
return 
}
val containerLayout = panelContainer!!.getLayoutParams() as LinearLayout.LayoutParams
containerLayout.height = dp(currentPanelHeightDp())
panelContainer!!.setLayoutParams(containerLayout)
panelContainer!!.removeAllViews()
if (panelMode == PanelMode.CANDIDATES)
{
panelContainer!!.addView(createCandidatePanel(), panelLayout())
}
else if (panelMode == PanelMode.CLIPBOARD)
{
panelContainer!!.addView(createClipboardPanel(), panelLayout())
}
else if (panelMode == PanelMode.EMOJI)
{
panelContainer!!.addView(createEmojiPanel(), panelLayout())
}
else if (panelMode == PanelMode.FEATURES)
{
panelContainer!!.addView(createFeaturePanel(), panelLayout())
}
else
{
val keyboard = LinearLayout(this)
keyboard.setOrientation(LinearLayout.VERTICAL)
if (keyboardMode == KeyboardMode.KANA_FLICK)
{
addFlickKeyboard(keyboard)
}
else if (keyboardMode == KeyboardMode.LATIN_QWERTY)
{
addLatinQwertyKeyboard(keyboard)
}
else if (keyboardMode == KeyboardMode.SYMBOL_QWERTY)
{
addSymbolQwertyKeyboard(keyboard)
}
panelContainer!!.addView(keyboard, panelLayout())
}
}

private fun panelLayout():LinearLayout.LayoutParams {
return LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 
dp(currentPanelHeightDp()))
}

private fun currentPanelHeightDp():Int {
return if ((panelMode == PanelMode.CANDIDATES || panelMode == PanelMode.KEYBOARD && keyboardMode == KeyboardMode.LATIN_QWERTY))
QWERTY_PANEL_HEIGHT_DP
else
PANEL_HEIGHT_DP
}

private fun addFlickKeyboard(root:LinearLayout) {
root.setBackgroundColor(palette!!.background)
val grid = LinearLayout(this)
grid.setOrientation(LinearLayout.HORIZONTAL)

val leftControls = keyColumn(palette!!.background)
val undo = controlIcon(
KeyboardIconView.Icon.UNDO, "元に戻す", palette!!.sideKey, false, 
{ view-> undoLastCommit() })
undo.setIconColor(if (lastCommittedText!!.isEmpty() && lastDeletedText!!.isEmpty())
palette!!.guideInactive
else
palette!!.text)
leftControls.addView(undo, controlCell(false))
leftControls.addView(cursorControl(false), controlCell(false))
leftControls.addView(controlIcon(
KeyboardIconView.Icon.EMOJI_SYMBOL, "絵文字と記号", palette!!.sideKey, false, 
{ view-> setPanelMode(PanelMode.EMOJI) }), controlCell(false))
leftControls.addView(modeKey(), controlCell(true))
grid.addView(leftControls, weightedColumn())

grid.addView(flickColumn(
flick("あ", "い", "う", "え", "お"), 
flick("た", "ち", "つ", "て", "と"), 
flick("ま", "み", "む", "め", "も"), null), weightedColumn())
grid.addView(flickColumn(
flick("か", "き", "く", "け", "こ"), 
flick("な", "に", "ぬ", "ね", "の"), 
flick("や", "（", "ゆ", "）", "よ"), 
flick("わ", "を", "ん", "ー", "〜")), weightedColumn())
grid.addView(flickColumn(
flick("さ", "し", "す", "せ", "そ"), 
flick("は", "ひ", "ふ", "へ", "ほ"), 
flick("ら", "り", "る", "れ", "ろ"), 
flick("、", "。", "？", "！", "…")), weightedColumn())

val rightControls = keyColumn(palette!!.background)
rightControls.addView(controlIcon(
KeyboardIconView.Icon.BACKSPACE, "削除", palette!!.sideKey, false, 
{ view-> deleteOne() }), controlCell(false))
rightControls.addView(cursorControl(true), controlCell(false))
rightControls.addView(controlIcon(
KeyboardIconView.Icon.SPACE, "空白または変換", palette!!.sideKey, false, 
{ view-> showConversionCandidates() }), controlCell(false))
rightControls.addView(controlIcon(
KeyboardIconView.Icon.ENTER, "確定または改行", palette!!.accent, true, 
{ view-> enter() }), controlCell(true))
grid.addView(rightControls, weightedColumn())
root.addView(grid, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 
LinearLayout.LayoutParams.MATCH_PARENT))
}

private fun addLatinQwertyKeyboard(root:LinearLayout) {
root.setBackgroundColor(palette!!.background)
root.addView(qwertyLiteralRow(arrayOf<String?>("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")), qwertyRowLayout())
root.addView(qwertyLetterRow("qwertyuiop", "", 0f), qwertyRowLayout())
root.addView(qwertyLetterRow("asdfghjkl", "@#¥_&-+()", 0.45f), qwertyRowLayout())

val fourthRow = qwertyRow()
fourthRow.addView(shiftKey(), qwertyCell(1.35f))
addQwertyLetters(fourthRow, "zxcvbnm", "*\"':;!?")
fourthRow.addView(qwertyIconKey(
KeyboardIconView.Icon.BACKSPACE, 
"削除", 
{ view-> deleteOne() }), qwertyCell(1.35f))
root.addView(fourthRow, qwertyRowLayout())

val bottomRow = qwertyRow()
bottomRow.addView(qwertyKanaKey(), qwertyCell(1.25f))
bottomRow.addView(qwertyActionKey("?123", "数字と記号", { view-> setKeyboardMode(KeyboardMode.SYMBOL_QWERTY) }), qwertyCell(1f))
bottomRow.addView(qwertyLiteralKey(","), qwertyCell(0.72f))
bottomRow.addView(qwertySpaceKey(), qwertyCell(2.6f))
bottomRow.addView(qwertyLiteralKey("."), qwertyCell(0.72f))
bottomRow.addView(qwertyCursorKey(false), qwertyCell(0.76f))
bottomRow.addView(qwertyCursorKey(true), qwertyCell(0.76f))
bottomRow.addView(qwertyEnterKey(), qwertyCell(1.25f))
root.addView(bottomRow, qwertyRowLayout())
}

private fun addSymbolQwertyKeyboard(root:LinearLayout) {
root.setBackgroundColor(palette!!.background)
if (symbolSecondPage)
{
root.addView(qwertyLiteralRow(arrayOf<String?>("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")), qwertyRowLayout())
root.addView(qwertyLiteralRow(arrayOf<String?>("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")), qwertyRowLayout())
val alternateRow = qwertyRow()
alternateRow.addView(qwertyActionKey("?123", "基本記号", { view->
symbolSecondPage = false
renderPanel() }), qwertyCell(1.35f))
addQwertyLiterals(alternateRow, arrayOf<String?>("%", "©", "®", "™", "✓", "[", "]"))
alternateRow.addView(qwertyIconKey(
KeyboardIconView.Icon.BACKSPACE, 
"削除", 
{ view-> deleteOne() }), qwertyCell(1.35f))
root.addView(alternateRow, qwertyRowLayout())
}
else
{
root.addView(qwertyLiteralRow(arrayOf<String?>("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")), qwertyRowLayout())
root.addView(qwertyLiteralRow(arrayOf<String?>("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")), qwertyRowLayout())
val primaryRow = qwertyRow()
primaryRow.addView(qwertyActionKey("=\\<", "その他の記号", { view->
symbolSecondPage = true
renderPanel() }), qwertyCell(1.35f))
addQwertyLiterals(primaryRow, arrayOf<String?>("*", "\"", "'", ":", ";", "!", "?"))
primaryRow.addView(qwertyIconKey(
KeyboardIconView.Icon.BACKSPACE, 
"削除", 
{ view-> deleteOne() }), qwertyCell(1.35f))
root.addView(primaryRow, qwertyRowLayout())
}

val bottomRow = qwertyRow()
bottomRow.addView(qwertyKanaKey(), qwertyCell(1.25f))
bottomRow.addView(qwertyActionKey("ABC", "半角英字", { view-> setKeyboardMode(KeyboardMode.LATIN_QWERTY) }), qwertyCell(1f))
bottomRow.addView(qwertyLiteralKey(","), qwertyCell(0.72f))
bottomRow.addView(qwertySpaceKey(), qwertyCell(2.6f))
bottomRow.addView(qwertyLiteralKey("."), qwertyCell(0.72f))
bottomRow.addView(qwertyCursorKey(false), qwertyCell(0.76f))
bottomRow.addView(qwertyCursorKey(true), qwertyCell(0.76f))
bottomRow.addView(qwertyEnterKey(), qwertyCell(1.25f))
root.addView(bottomRow, qwertyRowLayout())
}

private fun qwertyLiteralRow(values:Array<String?>?):LinearLayout {
val row = qwertyRow()
addQwertyLiterals(row, values!!)
return row
}

private fun addQwertyLiterals(row:LinearLayout?, values:Array<String?>) {
for (value in values)
{
row!!.addView(qwertyLiteralKey(value), qwertyCell(1f))
}
}

private fun qwertyLetterRow(letters:String?, alternates:String?, edgeWeight:Float):LinearLayout {
val row = qwertyRow()
if (edgeWeight > 0f)
{
row.addView(View(this), qwertyCell(edgeWeight))
}
addQwertyLetters(row, letters!!, alternates)
if (edgeWeight > 0f)
{
row.addView(View(this), qwertyCell(edgeWeight))
}
return row
}

private fun addQwertyLetters(row:LinearLayout?, letters:String, alternates:String?) {
for (index in 0 until letters.length)
{
val letter = letters[index].toString()
val alternate = if (index < alternates!!.length)
alternates!![index].toString()
else
""
val display = if (latinUppercase)
letter.uppercase(java.util.Locale.ROOT)
else
letter
row!!.addView(qwertyTextKey(display, alternate!!, letter, display), qwertyCell(1f))
}
}

private fun qwertyRow():LinearLayout {
val row = LinearLayout(this)
row.setOrientation(LinearLayout.HORIZONTAL)
row.setGravity(Gravity.CENTER)
row.setPadding(dp(3), 0, dp(3), 0)
return row
}

private fun qwertyRowLayout():LinearLayout.LayoutParams {
return LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 
0, 
1f)
}

private fun qwertyCell(weight:Float):LinearLayout.LayoutParams {
val parameters = LinearLayout.LayoutParams(
0, 
LinearLayout.LayoutParams.MATCH_PARENT, 
weight)
parameters.setMargins(dp(2), dp(2), dp(2), dp(2))
return parameters
}

private fun qwertyTextKey(
label:String?, 
alternate:String, 
committedValue:String?, 
description:String?):View {
val key = FrameLayout(this)
val primary = label(label, 19f, palette!!.text)
primary.setGravity(Gravity.CENTER)
key.addView(primary, FrameLayout.LayoutParams(
FrameLayout.LayoutParams.MATCH_PARENT, 
FrameLayout.LayoutParams.MATCH_PARENT))
if (!alternate.isEmpty())
{
val hint = label(alternate, 8f, palette!!.secondaryText)
hint.setGravity(Gravity.TOP or Gravity.END)
val hintLayout = FrameLayout.LayoutParams(
dp(18), 
dp(18), 
Gravity.TOP or Gravity.END)
hintLayout.setMargins(0, dp(3), dp(5), 0)
key.addView(hint, hintLayout)
}
key.setContentDescription((description!! + (if (alternate.isEmpty())
""
else
"、長押し " + alternate)))
key.setBackground(pressableBackground(palette!!.key, palette!!.pressed, 7))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
if (committedValue!!.length == 1 && Character.isLetter(committedValue[0]))
{
commitQwertyLetter(committedValue)
}
else
{
commitHalfWidth(committedValue)
} })
if (!alternate.isEmpty())
{
key.setOnLongClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
commitHalfWidth(alternate, "QWERTY_LONG_PRESS")
true })
}
return key
}

private fun qwertyLiteralKey(value:String?):View {
return qwertyTextKey(value, "", value, value)
}

private fun qwertyActionKey(
title:String?, 
description:String?, 
listener:View.OnClickListener?):TextView {
val key = label(title, (if (title!!.length >= 4) 12 else 16).toFloat(), palette!!.text)
key.setGravity(Gravity.CENTER)
key.setContentDescription(description)
key.setBackground(pressableBackground(palette!!.sideKey, palette!!.pressed, 7))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
listener!!.onClick(view) })
return key
}

private fun qwertyKanaKey():View {
val value = SpannableString("あa")
value.setSpan(ForegroundColorSpan(palette!!.guideInactive), 0, 1, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(ForegroundColorSpan(palette!!.text), 1, 2, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(RelativeSizeSpan(1.18f), 1, 2, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(StyleSpan(Typeface.BOLD), 1, 2, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
val key = label(value.toString(), 18f, palette!!.text)
key.setText(value)
key.setGravity(Gravity.CENTER)
key.setContentDescription("かな12キーに戻る")
key.setBackground(pressableBackground(palette!!.sideKey, palette!!.pressed, 28))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
setKeyboardMode(KeyboardMode.KANA_FLICK) })
return key
}

private fun qwertyCursorKey(right:Boolean):View {
val cursor = cursorControl(right)
cursor.setBackground(pressableBackground(palette!!.sideKey, palette!!.pressed, 7))
return cursor
}

private fun shiftKey():KeyboardIconView {
val shift = qwertyIconKey(
if (latinCapsLock) KeyboardIconView.Icon.CAPS_LOCK else KeyboardIconView.Icon.SHIFT, 
if (latinCapsLock)
"Caps Lockを解除"
else if (latinUppercase)
"大文字入力を解除、2回タップでCaps Lock"
else
"次の1文字を大文字で入力、2回タップでCaps Lock", 
{ view-> handleShiftTap() })
shift.setBackground(pressableBackground(
if (latinUppercase) palette!!.accent else palette!!.sideKey, 
palette!!.pressed, 
7))
shift.setIconColor(if (latinUppercase) palette!!.accentText else palette!!.text)
return shift
}

private fun handleShiftTap() {
val now = SystemClock.uptimeMillis()
if (latinCapsLock)
{
latinCapsLock = false
latinUppercase = false
}
else if (latinUppercase && now - lastShiftTapMs <= 350)
{
latinCapsLock = true
latinUppercase = true
}
else
{
latinUppercase = !latinUppercase
}
lastShiftTapMs = now
renderPanel()
}

private fun qwertyIconKey(
icon:KeyboardIconView.Icon?, 
description:String?, 
listener:View.OnClickListener?):KeyboardIconView {
val key = KeyboardIconView(this, icon)
key.setIconBoxDp(22)
key.setIconColor(palette!!.text)
key.setContentDescription(description)
key.setBackground(pressableBackground(palette!!.sideKey, palette!!.pressed, 7))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
listener!!.onClick(view) })
if (icon == KeyboardIconView.Icon.BACKSPACE)
{
configureDeleteGesture(key)
}
return key
}

private fun qwertySpaceKey():TextView {
val space = label("日本語", 12f, palette!!.secondaryText)
space.setGravity(Gravity.CENTER)
space.setContentDescription("半角空白、横スワイプでカーソル移動")
space.setBackground(pressableBackground(palette!!.key, palette!!.pressed, 7))
configureQwertySpaceGesture(space)
return space
}

private fun configureQwertySpaceGesture(key:View) {
val startX = FloatArray(1)
val appliedSteps = IntArray(1)
key.setOnTouchListener({ view, event-> when (event!!.getActionMasked()) {
MotionEvent.ACTION_DOWN -> {
startX[0] = event!!.getX()
appliedSteps[0] = 0
view!!.setPressed(true)
return@setOnTouchListener true
}
MotionEvent.ACTION_MOVE -> {
val steps = Math.round((event!!.getX() - startX[0]) / dp(26f))
while (appliedSteps[0] < steps)
{
moveCursor(true)
appliedSteps[0]++
}
while (appliedSteps[0] > steps)
{
moveCursor(false)
appliedSteps[0]--
}
return@setOnTouchListener true
}
MotionEvent.ACTION_UP -> {
view!!.setPressed(false)
view!!.performClick()
if (appliedSteps[0] == 0)
{
commitHalfWidth(" ")
}
return@setOnTouchListener true
}
MotionEvent.ACTION_CANCEL -> {
view!!.setPressed(false)
return@setOnTouchListener true
}
else -> return@setOnTouchListener false
} })
}

private fun qwertyEnterKey():KeyboardIconView {
val enter = qwertyIconKey(
KeyboardIconView.Icon.ENTER, 
"確定または改行", 
{ view-> enter() })
enter.setIconColor(palette!!.accentText)
enter.setBackground(pressableBackground(palette!!.accent, palette!!.pressed, 7))
return enter
}

private fun commitQwertyLetter(letter:String?) {
val value = if (latinUppercase)
letter!!.uppercase(java.util.Locale.ROOT)
else
letter
commitHalfWidth(value)
if (latinUppercase && !latinCapsLock)
{
latinUppercase = false
renderPanel()
}
}

private fun flickColumn(vararg mappings:Array<String?>?):LinearLayout {
val column = keyColumn(palette!!.key)
for (mapping in mappings)
{
if (mapping == null)
{
val parameters = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
parameters.setMargins(dp(2), dp(2), dp(2), dp(2))
column.addView(bottomLeftFlickCell(), parameters)
}
else
{
val key = FlickKeyView(
this, 
mapping!![0], 
mapping!![1], 
mapping!![2], 
mapping!![3], 
mapping!![4], 
{ value, gesture-> handleFlickInput(value, gesture) })
val parameters = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
parameters.setMargins(dp(2), dp(2), dp(2), dp(2))
column.addView(key, parameters)
}
}
return column
}

private fun keyColumn(color:Int):LinearLayout {
val column = LinearLayout(this)
column.setOrientation(LinearLayout.VERTICAL)
column.setBackgroundColor(palette!!.background)
return column
}

private fun weightedColumn():LinearLayout.LayoutParams {
return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
}

private fun verticalDivider():View {
val divider = View(this)
divider.setBackgroundColor(palette!!.divider)
divider.setLayoutParams(LinearLayout.LayoutParams(dp(1), 
LinearLayout.LayoutParams.MATCH_PARENT))
return divider
}

private fun controlCell(inset:Boolean):LinearLayout.LayoutParams {
val parameters = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
parameters.setMargins(dp(2), dp(2), dp(2), dp(2))
return parameters
}

private fun cursorControl(defaultRight:Boolean):CursorFlickKeyView {
return CursorFlickKeyView(this, defaultRight) { moveCursorDirection(it) }
}

private fun controlIcon(
icon:KeyboardIconView.Icon?, 
description:String?, 
background:Int, 
accent:Boolean, 
listener:View.OnClickListener?):KeyboardIconView {
val key = KeyboardIconView(this, icon)
key.setIconBoxDp(if (icon == KeyboardIconView.Icon.BACKSPACE) 27 else 24)
key.setIconColor(if (accent) palette!!.accentText else palette!!.text)
key.setContentDescription(description)
key.setBackground(pressableBackground(
background, 
if (accent) palette!!.pressed else palette!!.pressed, 
if (accent) 30 else 7))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
listener!!.onClick(view) })
if (icon == KeyboardIconView.Icon.BACKSPACE)
{
configureDeleteGesture(key)
}
else if (icon == KeyboardIconView.Icon.SPACE)
{
configureSpaceGesture(key)
}
return key
}

private fun emptySideCell():View {
val empty = View(this)
empty.setBackgroundColor(palette!!.background)
empty.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
return empty
}

private fun configureDeleteGesture(key:View) {
key.setOnClickListener(null)
val startX = FloatArray(1)
val wordDeleted = BooleanArray(1)
val repeating = BooleanArray(1)
val repeatCount = IntArray(1)
val repeatDelete = arrayOfNulls<Runnable?>(1)
repeatDelete[0] = object : Runnable {
override fun run() {
if (!key.isPressed() || wordDeleted[0])
{
return 
}
repeating[0] = true
deleteOne()
repeatCount[0]++
if (DeleteRepeatPolicy.shouldHaptic(repeatCount[0])) {
key.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
key.postDelayed(this, DeleteRepeatPolicy.intervalMs(repeatCount[0]))
}
}
key.setOnTouchListener({ view, event-> when (event!!.getActionMasked()) {
MotionEvent.ACTION_DOWN -> {
startX[0] = event!!.getX()
wordDeleted[0] = false
repeating[0] = false
repeatCount[0] = 0
view!!.setPressed(true)
view!!.postDelayed(repeatDelete[0], DeleteRepeatPolicy.initialDelayMs(
ViewConfiguration.getLongPressTimeout()))
return@setOnTouchListener true
}
MotionEvent.ACTION_MOVE -> {
if ((!wordDeleted[0] && DeleteGesturePolicy.isWordSwipe(
startX[0], event!!.getX(), dp(26).toFloat())))
{
view!!.removeCallbacks(repeatDelete[0])
deleteWordBeforeCursor()
wordDeleted[0] = true
view!!.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
return@setOnTouchListener true
}
MotionEvent.ACTION_UP -> {
view!!.removeCallbacks(repeatDelete[0])
view!!.setPressed(false)
view!!.performClick()
 // Very fast swipes may arrive without an ACTION_MOVE. Re-check the final
                    // displacement on release so they do not degrade into a one-character tap.
                    if ((!wordDeleted[0] && DeleteGesturePolicy.isWordSwipe(
startX[0], event!!.getX(), dp(26).toFloat())))
{
deleteWordBeforeCursor()
wordDeleted[0] = true
view!!.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
if (!wordDeleted[0] && !repeating[0])
{
deleteOne()
}
return@setOnTouchListener true
}
MotionEvent.ACTION_CANCEL -> {
view!!.removeCallbacks(repeatDelete[0])
view!!.setPressed(false)
return@setOnTouchListener true
}
else -> return@setOnTouchListener false
} })
}

private fun configureSpaceGesture(key:View) {
key.setOnClickListener(null)
val startX = FloatArray(1)
val appliedSteps = IntArray(1)
key.setOnTouchListener({ view, event-> when (event!!.getActionMasked()) {
MotionEvent.ACTION_DOWN -> {
startX[0] = event!!.getX()
appliedSteps[0] = 0
view!!.setPressed(true)
return@setOnTouchListener true
}
MotionEvent.ACTION_MOVE -> {
val steps = Math.round((event!!.getX() - startX[0]) / dp(26f))
while (appliedSteps[0] < steps)
{
moveCursor(true)
appliedSteps[0]++
}
while (appliedSteps[0] > steps)
{
moveCursor(false)
appliedSteps[0]--
}
return@setOnTouchListener true
}
MotionEvent.ACTION_UP -> {
view!!.setPressed(false)
view!!.performClick()
if (appliedSteps[0] == 0)
{
showConversionCandidates()
}
return@setOnTouchListener true
}
MotionEvent.ACTION_CANCEL -> {
view!!.setPressed(false)
return@setOnTouchListener true
}
else -> return@setOnTouchListener false
} })
}

private fun modeKey():View {
val value = SpannableString("あa1")
val activeIndex = if (keyboardMode == KeyboardMode.KANA_FLICK)
0
else if (keyboardMode == KeyboardMode.LATIN_QWERTY) 1 else 2
for (index in 0 until value.length)
{
value.setSpan(ForegroundColorSpan(
if (index == activeIndex) palette!!.text else palette!!.guideInactive), 
index, 
index + 1, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}
value.setSpan(RelativeSizeSpan(1.22f), activeIndex, activeIndex + 1, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(StyleSpan(Typeface.BOLD), activeIndex, activeIndex + 1, 
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
val key = label(value.toString(), 18f, palette!!.secondaryText)
key.setText(value)
key.setGravity(Gravity.CENTER)
key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
key.setContentDescription("文字種を切り替え")
key.setBackground(pressableBackground(palette!!.sideKey, palette!!.pressed, 32))
key.setOnClickListener({ view-> cycleKeyboardMode() })
key.setOnLongClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
openSettings()
true })
return key
}

private fun bottomLeftFlickCell():View {
val modifier = LinearLayout(this)
modifier.setOrientation(LinearLayout.VERTICAL)
modifier.setGravity(Gravity.CENTER)
modifier.setContentDescription("濁点、半濁点、小文字")
modifier.setBackground(pressableBackground(palette!!.key, palette!!.pressed, 7))
modifier.setOnClickListener({ view-> applyModifierCycle() })

val marks = label("゛  ゜", 15f, palette!!.text)
marks.setGravity(Gravity.CENTER)
marks.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
modifier.addView(marks, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(25)))

val size = label("大 ↔ 小", 8f, palette!!.secondaryText)
size.setGravity(Gravity.CENTER)
size.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
modifier.addView(size, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
return modifier
}

private fun pressableBackground(
normalColor:Int, 
pressedColor:Int, 
radiusDp:Int):StateListDrawable {
val states = StateListDrawable()
states.addState(intArrayOf(android.R.attr.state_pressed), keyBackground(pressedColor, radiusDp))
states.addState(intArrayOf(), keyBackground(normalColor, radiusDp))
return states
}

private fun keyBackground(color:Int, radiusDp:Int):GradientDrawable {
val drawable = GradientDrawable()
drawable.setColor(color)
val sharpRadiusDp = KeyboardShapePolicy.cornerRadiusDp(radiusDp)
drawable.setCornerRadius(dp(sharpRadiusDp).toFloat())
return drawable
}

private fun flick(center:String?, left:String?, up:String?, right:String?, down:String?):Array<String?> {
return arrayOf<String?>(center, left, up, right, down)
}

private fun createCandidatePanel():View {
val panel = panelShell()
val scroll = ScrollView(this)
scroll.setFillViewport(true)
val rows = LinearLayout(this)
rows.setOrientation(LinearLayout.VERTICAL)
rows.setPadding(dp(5), dp(4), dp(5), dp(8))
var start = 0
while (start < currentCandidates.size)
{
val row = LinearLayout(this)
row.setOrientation(LinearLayout.HORIZONTAL)
for (column in 0..2)
{
val index = start + column
if (index < currentCandidates.size)
{
val candidate = currentCandidates.get(index)
val button = label(
candidate, 
(if (candidate.codePointCount(0, candidate.length) > 8) 14 else 17).toFloat(),
palette!!.text)
button.setGravity(Gravity.CENTER)
button.setSingleLine(true)
button.setEllipsize(TextUtils.TruncateAt.END)
button.setPadding(dp(8), 0, dp(8), 0)
button.setContentDescription("候補 " + (index + 1) + ": " + candidate)
button.setBackground(pressableBackground(
if (index == selectedCandidateIndex) palette!!.accent else palette!!.key, 
palette!!.pressed, 
8))
button.setOnClickListener({ view-> commitCandidate(index, "CANDIDATE_TAP") })
row.addView(button, candidateGridCell())
}
else
{
row.addView(View(this), candidateGridCell())
}
}
rows.addView(row, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
start += 3
}
scroll.addView(rows)
panel.addView(scroll, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
return panel
}

private fun candidateGridCell():LinearLayout.LayoutParams {
val parameters = LinearLayout.LayoutParams(
0, 
LinearLayout.LayoutParams.MATCH_PARENT, 
1f)
parameters.setMargins(dp(3), dp(3), dp(3), dp(3))
return parameters
}

private fun createClipboardPanel():View {
clipboardHistory.capturePrimaryClip(this)
val panel = panelShell()

val privacy = label(
"最近コピーしたテキスト  •  このセッションのみ", 
13f, 
palette!!.secondaryText)
privacy.setPadding(dp(14), dp(7), dp(14), dp(5))
panel.addView(privacy, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))

val scroll = ScrollView(this)
scroll.setFillViewport(true)
val list = LinearLayout(this)
list.setOrientation(LinearLayout.VERTICAL)
list.setPadding(dp(5), 0, dp(5), dp(6))
val items = clipboardHistory.items()
if (items!!.isEmpty())
{
val empty = label(
"クリップボードにテキストをコピーすると、ここに表示されます。", 
15f, 
palette!!.secondaryText)
empty.setGravity(Gravity.CENTER)
list.addView(empty, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(170)))
}
else
{
var index = 0
while (index < items!!.size)
{
val row = LinearLayout(this)
row.setOrientation(LinearLayout.HORIZONTAL)
row.addView(clipboardCard(items!!.get(index)), clipboardCardLayout())
if (index + 1 < items!!.size)
{
row.addView(clipboardCard(items!!.get(index + 1)), clipboardCardLayout())
}
else
{
row.addView(View(this), clipboardCardLayout())
}
list.addView(row, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(78)))
index += 2
}
}
scroll.addView(list)
panel.addView(scroll, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
return panel
}

private fun clipboardCard(item:String?):TextView {
val card = label(previewClipboard(item!!), 15f, palette!!.text)
card.setGravity(Gravity.TOP or Gravity.START)
card.setMaxLines(3)
card.setPadding(dp(12), dp(9), dp(12), dp(7))
card.setBackground(pressableBackground(
palette!!.panelCard, 
palette!!.pressed, 
12))
card.setContentDescription("貼り付け: " + previewClipboard(item!!)!!)
card.setOnClickListener({ view-> pasteClipboard(item) })
return card
}

private fun clipboardCardLayout():LinearLayout.LayoutParams {
val parameters = LinearLayout.LayoutParams(0, 
LinearLayout.LayoutParams.MATCH_PARENT, 1f)
parameters.setMargins(dp(3), dp(3), dp(3), dp(3))
return parameters
}

private fun createEmojiPanel():View {
val panel = panelShell()
val catalog = EmojiCatalog.get(this)
val groups = catalog!!.groups()
if ((selectedEmojiGroup == null || ((!RECENT_EMOJI_GROUP.equals(selectedEmojiGroup) && !groups!!.contains(selectedEmojiGroup)))))
{
selectedEmojiGroup = if (groups!!.isEmpty()) RECENT_EMOJI_GROUP else groups!!.get(0)
}

val categoryScroll = HorizontalScrollView(this)
categoryScroll.setHorizontalScrollBarEnabled(false)
val categories = LinearLayout(this)
categories.setGravity(Gravity.CENTER_VERTICAL)
categories.setOrientation(LinearLayout.HORIZONTAL)
categories.setPadding(dp(4), dp(2), dp(4), dp(2))
if (!recentEmojis.isEmpty())
{
categories.addView(emojiCategoryButton(RECENT_EMOJI_GROUP, "◷"))
}
for (group in groups!!)
{
categories.addView(emojiCategoryButton(group!!, emojiGroupIcon(group!!)))
}
categoryScroll.addView(categories, FrameLayout.LayoutParams(
FrameLayout.LayoutParams.WRAP_CONTENT,
FrameLayout.LayoutParams.MATCH_PARENT))
panel.addView(categoryScroll, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

val emojiScroll = ScrollView(this)
emojiScroll.setFillViewport(true)
emojiGridView = EmojiGridView(this) { commitEmoji(it) }
emojiGridView!!.setBackgroundColor(palette!!.background)
updateEmojiGrid()
emojiScroll.addView(emojiGridView)
panel.addView(emojiScroll, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
return panel
}

private fun createEmojiFooter():View {
val footer = LinearLayout(this)
footer.setGravity(Gravity.CENTER)
footer.setOrientation(LinearLayout.HORIZONTAL)
footer.setBackgroundColor(palette!!.background)
val labels = arrayOf<String?>("😊", "GIF", "▱", ":-)", "Ω", "⌫")
for (index in labels!!.indices)
{
val tab = label(labels!![index], (if (index == 1) 12 else 18).toFloat(), 
if (index == 0) palette!!.accentText else palette!!.secondaryText)
tab.setGravity(Gravity.CENTER)
tab.setBackground(pressableBackground(
if (index == 0) palette!!.accent else Color.TRANSPARENT, 
palette!!.pressed, 
20))
if (index == labels!!.size - 1)
{
tab.setOnClickListener({ view-> deleteOne() })
}
footer.addView(tab, LinearLayout.LayoutParams(0, dp(38), 1f))
}
return footer
}

private fun emojiCategoryButton(group:String, icon:String?):TextView {
val selected = group.equals(selectedEmojiGroup)
val button = label(icon, 22f, palette!!.text)
button.setGravity(Gravity.CENTER)
button.setContentDescription(group)
button.setBackground(pressableBackground(
if (selected) palette!!.accent else Color.TRANSPARENT, 
palette!!.pressed, 
18))
button.setOnClickListener({ view->
selectedEmojiGroup = group
renderPanel() })
val parameters = LinearLayout.LayoutParams(dp(43), dp(38))
parameters.setMargins(dp(1), 0, dp(1), 0)
button.setLayoutParams(parameters)
return button
}

private fun updateEmojiGrid() {
if (emojiGridView == null)
{
return 
}
if (RECENT_EMOJI_GROUP.equals(selectedEmojiGroup))
{
emojiGridView!!.setEmojis(recentEmojis)
}
else
{
emojiGridView!!.setEmojis(EmojiCatalog.get(this).emojis(selectedEmojiGroup))
}
}

private fun panelShell():LinearLayout {
val panel = LinearLayout(this)
panel.setOrientation(LinearLayout.VERTICAL)
panel.setBackgroundColor(palette!!.background)
return panel
}

private fun createFeaturePanel():View {
val panel = panelShell()
val instruction = label("長押ししてツールバーをカスタマイズ", 14f, palette!!.secondaryText)
instruction.setGravity(Gravity.CENTER_VERTICAL)
instruction.setPadding(dp(16), 0, dp(16), 0)
panel.addView(instruction, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))

val firstRow = LinearLayout(this)
firstRow.setOrientation(LinearLayout.HORIZONTAL)
firstRow.addView(featureItem(
KeyboardIconView.Icon.CLIPBOARD, 
"クリップボード", 
{ view-> setPanelMode(PanelMode.CLIPBOARD) }), featureCell())
firstRow.addView(featureItem(
KeyboardIconView.Icon.STICKER, 
"絵文字", 
{ view-> setPanelMode(PanelMode.EMOJI) }), featureCell())
firstRow.addView(featureItem(
KeyboardIconView.Icon.SETTINGS, 
"設定", 
{ view-> openSettings() }), featureCell())
panel.addView(firstRow, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

val secondRow = LinearLayout(this)
secondRow.setOrientation(LinearLayout.HORIZONTAL)
secondRow.addView(featureItem(
KeyboardIconView.Icon.MENU, 
if (ImePreferences.isCollectionEnabled(this)) "収集中" else "収集停止", 
{ view-> toggleCollection() }), featureCell())
secondRow.addView(featureItem(
KeyboardIconView.Icon.HANDWRITING, 
if (keyboardMode == KeyboardMode.LATIN_QWERTY) "12キー" else "QWERTY", 
{ view-> setKeyboardMode(if (keyboardMode == KeyboardMode.LATIN_QWERTY)
KeyboardMode.KANA_FLICK
else
KeyboardMode.LATIN_QWERTY) }), featureCell())
secondRow.addView(featureItem(
KeyboardIconView.Icon.GLOBE, 
"次の言語", 
{ view-> switchToNextIme() }), featureCell())
panel.addView(secondRow, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
return panel
}

private fun featureItem(
icon:KeyboardIconView.Icon?, 
title:String?, 
listener:View.OnClickListener?):View {
val item = LinearLayout(this)
item.setOrientation(LinearLayout.VERTICAL)
item.setGravity(Gravity.CENTER)
item.setPadding(dp(6), dp(4), dp(6), dp(4))
item.setBackground(pressableBackground(Color.TRANSPARENT, palette!!.pressed, 18))
val iconView = KeyboardIconView(this, icon)
iconView.setIconBoxDp(42)
iconView.setIconColor(palette!!.text)
item.addView(iconView, LinearLayout.LayoutParams(dp(54), dp(54)))
val label = label(title, 13f, palette!!.text)
label.setGravity(Gravity.CENTER)
label.setSingleLine(true)
item.addView(label, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
item.setContentDescription(title)
item.setOnClickListener(listener)
return item
}

private fun featureCell():LinearLayout.LayoutParams {
val parameters = LinearLayout.LayoutParams(0, 
LinearLayout.LayoutParams.MATCH_PARENT, 1f)
parameters.setMargins(dp(6), dp(3), dp(6), dp(3))
return parameters
}

private fun openSettings() {
val intent = Intent(this, SettingsActivity::class.java)
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
startActivity(intent)
}

private fun toggleCollection() {
if (!sensitiveField)
{
ImePreferences.setCollectionEnabled(
this, 
!ImePreferences.isCollectionEnabled(this))
}
renderPanel()
refreshStatus()
}

private fun label(value:String?, size:Float, color:Int):TextView {
val view = TextView(this)
view.setText(value)
view.setTextSize(size)
view.setTextColor(color)
view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
return view
}

private fun previewClipboard(value:String):String? {
val compact = value.replace('\n', ' ').replace('\r', ' ').trim()
return if (compact!!.length > 120) compact!!.substring(0, 120) + "…" else compact
}

private fun emojiGroupIcon(group:String):String {
if (group.contains("Smileys")) return "😀"
if (group.contains("People")) return "👋"
if (group.contains("Component")) return "🧩"
if (group.contains("Animals")) return "🐻"
if (group.contains("Food")) return "🍙"
if (group.contains("Travel")) return "🚗"
if (group.contains("Activities")) return "⚽"
if (group.contains("Objects")) return "💡"
if (group.contains("Symbols")) return "🔣"
if (group.contains("Flags")) return "🏳"
return "•"
}

private fun pasteClipboard(text:String?) {
val connection = getCurrentInputConnection()
if (connection == null || text == null || text!!.isEmpty())
{
return 
}
clearComposition(true)
connection!!.commitText(text, 1)
clipboardHistory.remember(text)
renderPanel()
}

private fun commitEmoji(emoji:String?) {
if (emoji == null || emoji!!.isEmpty())
{
return 
}
if (rawComposition.length > 0)
{
commitCurrent("EMOJI_PICKER")
}
commitLiteral(emoji, "EMOJI_COMMIT", "EMOJI_PICKER")
recentEmojis.remove(emoji)
recentEmojis.addFirst(emoji)
while (recentEmojis.size > 40)
{
recentEmojis.removeLast()
}
if (RECENT_EMOJI_GROUP.equals(selectedEmojiGroup))
{
updateEmojiGrid()
}
}

private fun handleFlickInput(value:String, gesture:FlickGesture) {
if ((KanaModifier.DAKUTEN.equals(value) 
|| KanaModifier.HANDAKUTEN.equals(value) 
|| KanaModifier.SMALL.equals(value)))
{
val rawBefore = rawComposition.toString()
val cursorBefore = cursorPosition()
val before = contextBefore()
if (KanaModifier.apply(rawComposition, value))
{
ensureCompositionId()
refreshSuggestions()
updateComposingText()
recordCompositionEdit(
"MODIFIER_" + when (value) {
KanaModifier.DAKUTEN -> "DAKUTEN"
KanaModifier.HANDAKUTEN -> "HANDAKUTEN"
else -> "SMALL"
}, rawBefore, cursorBefore, before, gesture, finalCodePoint(rawBefore).orEmpty())
}
return 
}
if (JapaneseInputPolicy.isComposingPunctuation(value))
{
appendRaw(value, gesture)
return 
}
appendRaw(value, gesture)
}

private fun commitHalfWidth(value:String?, commitMethod:String = "QWERTY_TAP") {
if (value == null || value!!.isEmpty())
{
return 
}
if (rawComposition.length > 0)
{
commitCurrent(commitMethod)
}
commitLiteral(value, "HALFWIDTH_COMMIT", commitMethod)
}

private fun cycleKeyboardMode() {
val next:KeyboardMode?
if (keyboardMode == KeyboardMode.KANA_FLICK)
{
next = KeyboardMode.LATIN_QWERTY
}
else if (keyboardMode == KeyboardMode.LATIN_QWERTY)
{
next = KeyboardMode.SYMBOL_QWERTY
}
else
{
next = KeyboardMode.KANA_FLICK
}
setKeyboardMode(next)
}

private fun setKeyboardMode(mode:KeyboardMode) {
if ((keyboardMode == KeyboardMode.KANA_FLICK 
&& mode != KeyboardMode.KANA_FLICK 
&& rawComposition.length > 0))
{
commitCurrent("MODE_SWITCH")
}
if (mode != KeyboardMode.SYMBOL_QWERTY)
{
symbolSecondPage = false
}
if (mode != KeyboardMode.LATIN_QWERTY)
{
latinUppercase = false
latinCapsLock = false
}
keyboardMode = mode
panelMode = PanelMode.KEYBOARD
renderPanel()
refreshCandidateViews()
}

private fun appendRaw(character:String?, gesture:FlickGesture?) {
if (CandidateFlow.shouldCommitBeforeInput(
selectedCandidateIndex, currentCandidates.size))
{
commitCandidate(selectedCandidateIndex, "NEXT_INPUT")
}
val rawBefore = rawComposition.toString()
val cursorBefore = cursorPosition()
val before = contextBefore()
ensureCompositionId()
rawComposition.append(character)
selectedCandidateIndex = -1
refreshSuggestions()
updateComposingText()
recordCompositionEdit("INSERT", rawBefore, cursorBefore, before, gesture)
}

private fun refreshSuggestions() {
currentCandidates.clear()
if (rawComposition.length > 0 && conversionEngine != null)
{
currentCandidates.addAll(conversionEngine!!.predictions(
currentReading(), 
conversionContextBefore()))
currentCandidateSource = "PREDICTION"
}
else
{
currentCandidateSource = "NONE"
}
selectedCandidateIndex = -1
}

private fun updateComposingText() {
val reading = currentReading()
val connection = getCurrentInputConnection()
if (connection != null)
{
if (reading.isEmpty())
{
 // finishComposingText() alone commits the old composing text. Replace the
                // composing range with an empty value first so backspace actually removes it.
                connection!!.setComposingText("", 1)
connection!!.finishComposingText()
}
else
{
connection!!.setComposingText(styledComposition(reading), 1)
}
}
lastComposedText = reading
refreshCandidateViews()
}

private fun showConversionCandidates() {
if (rawComposition.isEmpty())
{
commitLiteral(JapaneseInputPolicy.space(true), "SPACE_FULL_WIDTH", "SPACE_KEY")
return 
}
if (CandidateFlow.shouldCommitBeforeInput(
selectedCandidateIndex, currentCandidates.size))
{
selectedCandidateIndex = CandidateFlow.nextIndex(
selectedCandidateIndex, currentCandidates.size)
showSelectedCandidateAsComposition()
refreshCandidateViews()
return 
}
val context = conversionContextBefore()
currentCandidates.clear()
currentCandidates.addAll(conversionEngine!!.conversions(currentReading(), context))
currentCandidateSource = "CONVERSION"
selectedCandidateIndex = if (currentCandidates.isEmpty()) -1 else 0
showSelectedCandidateAsComposition()
refreshCandidateViews()
}

private fun showSelectedCandidateAsComposition() {
if (selectedCandidateIndex < 0 || selectedCandidateIndex >= currentCandidates.size)
{
return 
}
val candidate = currentCandidates.get(selectedCandidateIndex)
val connection = getCurrentInputConnection()
if (connection != null)
{
connection!!.setComposingText(styledComposition(candidate), 1)
}
lastComposedText = candidate
}

private fun styledComposition(text: String): CharSequence = ComposingTextStyler.style(
text,
palette!!.text,
palette!!.composingHighlight,
)

private fun refreshCandidateViews() {
if (toolbarContainer == null)
{
return 
}
toolbarContainer!!.removeAllViews()
if (panelMode == PanelMode.CANDIDATES)
{
toolbarContainer!!.addView(toolbarBackButton(), toolbarFixed())
val title = label("候補一覧  " + currentCandidates.size + "件", 17f, palette!!.text)
title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
title.setGravity(Gravity.CENTER_VERTICAL)
title.setPadding(dp(8), 0, 0, 0)
toolbarContainer!!.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
return 
}
if (panelMode == PanelMode.CLIPBOARD)
{
toolbarContainer!!.addView(toolbarBackButton(), toolbarFixed())
val title = label("クリップボード", 17f, palette!!.text)
title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
title.setGravity(Gravity.CENTER_VERTICAL)
toolbarContainer!!.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
val clear = label("消去", 14f, palette!!.text)
clear.setGravity(Gravity.CENTER)
clear.setBackground(pressableBackground(Color.TRANSPARENT, palette!!.pressed, 22))
clear.setOnClickListener({ view->
clipboardHistory.clear()
renderPanel() })
toolbarContainer!!.addView(clear, LinearLayout.LayoutParams(dp(58), dp(44)))
return 
}
if (panelMode == PanelMode.EMOJI)
{
toolbarContainer!!.addView(toolbarBackButton(), toolbarFixed())
val search = LinearLayout(this)
search.setGravity(Gravity.CENTER_VERTICAL)
search.setOrientation(LinearLayout.HORIZONTAL)
search.setPadding(dp(12), 0, dp(12), 0)
search.setBackground(pressableBackground(palette!!.panelCard, palette!!.pressed, 24))
val searchIcon = KeyboardIconView(this, KeyboardIconView.Icon.SEARCH)
searchIcon.setIconBoxDp(21)
searchIcon.setIconColor(palette!!.secondaryText)
search.addView(searchIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
val searchHint = label("絵文字を検索", 14f, palette!!.secondaryText)
searchHint.setPadding(dp(6), 0, 0, 0)
search.addView(searchHint, LinearLayout.LayoutParams(0, dp(42), 1f))
toolbarContainer!!.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f))
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.BACKSPACE, 
"削除", 
{ view-> deleteOne() }), toolbarFixed())
return 
}
if (currentCandidates.isEmpty())
{
toolbarContainer!!.addView(View(this), LinearLayout.LayoutParams(
0, dp(44), 1f))
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.EMOJI_SYMBOL, 
"絵文字", 
{ view-> setPanelMode(PanelMode.EMOJI) }), toolbarFixed())
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.CLIPBOARD, 
"クリップボード", 
{ view-> setPanelMode(PanelMode.CLIPBOARD) }), toolbarFixed())
return 
}

val candidateScroller = HorizontalScrollView(this)
candidateScroller.setHorizontalScrollBarEnabled(false)
candidateScroller.setFillViewport(true)
candidateContainer = LinearLayout(this)
candidateContainer!!.setGravity(Gravity.CENTER_VERTICAL)
candidateContainer!!.setOrientation(LinearLayout.HORIZONTAL)
candidateScroller.addView(candidateContainer, FrameLayout.LayoutParams(
FrameLayout.LayoutParams.WRAP_CONTENT,
FrameLayout.LayoutParams.MATCH_PARENT))
toolbarContainer!!.addView(candidateScroller, LinearLayout.LayoutParams(0, dp(46), 1f))
for (index in 0 until currentCandidates.size)
{
val candidateIndex = index
val candidate = currentCandidates.get(index)
val button = label(candidate, 16f, palette!!.text)
button.setGravity(Gravity.CENTER)
button.setPadding(dp(15), 0, dp(15), 0)
button.setBackground(pressableBackground(
if (index == selectedCandidateIndex) palette!!.accent else Color.TRANSPARENT, 
palette!!.pressed, 
18))
button.setOnClickListener({ view-> commitCandidate(candidateIndex, "CANDIDATE_TAP") })
val parameters = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
button.setMinWidth(dp(68))
candidateContainer!!.addView(button, parameters)
}
val selectedIndex = selectedCandidateIndex
if (selectedIndex >= 0)
{
candidateScroller.post({ val selected = if (candidateContainer == null)
null
else
candidateContainer!!.getChildAt(selectedIndex)
if (selected != null)
{
candidateScroller.smoothScrollTo(
Math.max(0, selected!!.getLeft() - dp(12)), 0)
} })
}
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.EXPAND_MORE, 
"候補一覧を開く", 
{ view-> setPanelMode(PanelMode.CANDIDATES) }), toolbarFixed())
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.EMOJI_SYMBOL, 
"絵文字", 
{ view-> setPanelMode(PanelMode.EMOJI) }), toolbarFixed())
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.CLIPBOARD, 
"クリップボード", 
{ view-> setPanelMode(PanelMode.CLIPBOARD) }), toolbarFixed())
}

private fun addToolbarIcon(
icon:KeyboardIconView.Icon?, 
description:String?, 
listener:View.OnClickListener?) {
toolbarContainer!!.addView(toolbarIcon(icon, description, listener), toolbarWeighted())
}

private fun toolbarWeighted():LinearLayout.LayoutParams {
return LinearLayout.LayoutParams(0, dp(44), 1f)
}

private fun toolbarFixed():LinearLayout.LayoutParams {
return LinearLayout.LayoutParams(dp(48), dp(44))
}

private fun collectionDescription():String {
if (sensitiveField)
{
return "機能メニュー。この欄では記録しません"
}
return if (ImePreferences.isCollectionEnabled(this))
"機能メニュー。入力データを収集中"
else
"機能メニュー。入力データの収集停止中"
}

private fun commitCandidate(index:Int, commitMethod:String) {
if (index < 0 || index >= currentCandidates.size)
{
return 
}
selectedCandidateIndex = index
if (conversionEngine != null)
{
conversionEngine!!.candidateCommitted(index)
}
if (panelMode == PanelMode.CANDIDATES)
{
panelMode = PanelMode.KEYBOARD
renderPanel()
}
commitText(currentCandidates.get(index), "CONVERSION_COMMIT", index, commitMethod)
}

private fun commitCurrent(commitMethod:String) {
if (!currentCandidates.isEmpty() && selectedCandidateIndex >= 0)
{
commitCandidate(selectedCandidateIndex, commitMethod)
}
else if (rawComposition.length > 0)
{
if (conversionEngine != null)
{
conversionEngine!!.readingCommitted()
}
commitText(currentReading(), "READING_COMMIT", -1, commitMethod)
}
}

private fun commitText(text:String, eventType:String, candidateIndex:Int, commitMethod:String) {
val connection = getCurrentInputConnection()
if (connection == null || text.isEmpty())
{
return 
}
val raw = rawComposition.toString()
val reading = currentReading()
val before = contextBefore()
val cursorBefore = cursorPosition()
val candidatesSnapshot = ArrayList(currentCandidates)
connection!!.commitText(text, 1)
lastCommittedText = text
lastDeletedText = ""
record(
eventType, raw, reading, text, candidatesSnapshot, candidateIndex, before, contextAfter(),
compositionId = compositionId,
correctionId = activeCorrectionId,
candidateSource = currentCandidateSource,
commitMethod = commitMethod,
editOperation = "COMMIT",
rawBefore = raw,
rawAfter = "",
cursorBefore = cursorBefore,
cursorAfter = cursorPosition())
clearComposition(false)
}

private fun commitLiteral(
text:String,
eventType:String,
commitMethod:String,
gesture:FlickGesture? = null) {
val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
val before = contextBefore()
val cursorBefore = cursorPosition()
connection!!.commitText(text, 1)
lastCommittedText = text
lastDeletedText = ""
record(
eventType, text, text, text, Collections.emptyList(), -1, before, contextAfter(),
compositionId = compositionId,
correctionId = activeCorrectionId,
candidateSource = currentCandidateSource,
commitMethod = commitMethod,
editOperation = "COMMIT_LITERAL",
rawBefore = text,
rawAfter = "",
cursorBefore = cursorBefore,
cursorAfter = cursorPosition(),
gesture = gesture)
compositionId = ""
activeCorrectionId = ""
currentCandidateSource = "NONE"
}

private fun deleteOne() {
if (rawComposition.length > 0)
{
val rawBefore = rawComposition.toString()
val cursorBefore = cursorPosition()
val before = contextBefore()
ensureCompositionId()
ensureCorrectionId()
val previous = TextDeletion.previousGrapheme(rawComposition.toString())
rawComposition.delete(
rawComposition.length - previous!!.length, rawComposition.length)
selectedCandidateIndex = -1
refreshSuggestions()
updateComposingText()
recordCompositionEdit("DELETE", rawBefore, cursorBefore, before, deletedText = previous)
return 
}

val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
if (deleteSelection(connection!!))
{
return 
}
val beforeCursor = connection!!.getTextBeforeCursor(64, 0)
val deleted = TextDeletion.previousGrapheme(
if (beforeCursor == null) "" else beforeCursor!!.toString())
val codePoints = deleted!!.codePointCount(0, deleted!!.length)
if (codePoints == 0)
{
return 
}
val before = contextBefore()
val cursorBefore = cursorPosition()
ensureCorrectionId()
connection!!.deleteSurroundingTextInCodePoints(codePoints, 0)
lastDeletedText = deleted
lastCommittedText = ""
record(
"DELETE_COMMITTED", 
"", 
"", 
"", 
Collections.emptyList(), 
-1, 
before, 
contextAfter(),
compositionId = "",
correctionId = activeCorrectionId,
candidateSource = "NONE",
commitMethod = "BACKSPACE",
editOperation = "DELETE_COMMITTED",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition())
}

private fun deleteSelection(connection:InputConnection):Boolean {
val selection = connection.getSelectedText(0)
if (selection == null || selection.isEmpty())
{
return false
}
val deleted = selection!!.toString()
val before = contextBefore()
val cursorBefore = cursorPosition()
ensureCorrectionId()
connection.commitText("", 1)
lastDeletedText = deleted
lastCommittedText = ""
record(
"DELETE_COMMITTED", 
"", 
"", 
"", 
Collections.emptyList(), 
-1, 
before, 
contextAfter(),
compositionId = "",
correctionId = activeCorrectionId,
candidateSource = "NONE",
commitMethod = "SELECTION_DELETE",
editOperation = "DELETE_SELECTION",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition())
return true
}

private fun moveCursor(right:Boolean) {
val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
val keyCode = if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
connection!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
connection!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
}

private fun moveCursorVertical(down:Boolean) {
val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
val keyCode = if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
connection!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
connection!!.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
}

private fun moveCursorDirection(direction:CursorFlickKeyView.Direction) {
when (direction) {
CursorFlickKeyView.Direction.LEFT -> moveCursor(false)
CursorFlickKeyView.Direction.RIGHT -> moveCursor(true)
CursorFlickKeyView.Direction.UP -> moveCursorVertical(false)
CursorFlickKeyView.Direction.DOWN -> moveCursorVertical(true)
CursorFlickKeyView.Direction.CENTER -> {}
else -> {}
}
}

private fun deleteWordBeforeCursor() {
if (rawComposition.length > 0)
{
val rawBefore = rawComposition.toString()
val cursorBefore = cursorPosition()
val before = contextBefore()
ensureCompositionId()
ensureCorrectionId()
rawComposition.setLength(0)
refreshSuggestions()
updateComposingText()
recordCompositionEdit(
"DELETE_WORD", rawBefore, cursorBefore, before, deletedText = rawBefore)
return 
}
val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
if (deleteSelection(connection!!))
{
return 
}
val before = connection!!.getTextBeforeCursor(80, 0)
if (before == null || before.isEmpty())
{
return 
}
val value = before!!.toString()
val codePoints = TextDeletion.previousWordCodePoints(value)
if (codePoints > 0)
{
val deleted = value.substring(value.offsetByCodePoints(value.length, -codePoints))
val contextBeforeDelete = contextBefore()
val cursorBefore = cursorPosition()
ensureCorrectionId()
connection!!.deleteSurroundingTextInCodePoints(codePoints, 0)
lastDeletedText = deleted
lastCommittedText = ""
record(
"DELETE_COMMITTED", "", "", "", Collections.emptyList(), -1,
contextBeforeDelete, contextAfter(),
compositionId = "",
correctionId = activeCorrectionId,
candidateSource = "NONE",
commitMethod = "BACKSPACE_SWIPE",
editOperation = "DELETE_WORD",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition())
}
}

override fun onAppPrivateCommand(action:String?, data:Bundle?) {
super.onAppPrivateCommand(action, data)
if (!BuildConfig.DEBUG || action == null)
{
return 
}
if ((data != null 
&& data!!.containsKey(TEST_SELECTION_START) 
&& data!!.containsKey(TEST_SELECTION_END)))
{
val connection = getCurrentInputConnection()
if (connection != null)
{
connection!!.setSelection(
data!!.getInt(TEST_SELECTION_START), data!!.getInt(TEST_SELECTION_END))
}
}
if (TEST_PREPARE_TELEMETRY.equals(action))
{
prepareTelemetryTest()
}
else if (TEST_EXPORT_TELEMETRY.equals(action))
{
exportTelemetryTest()
}
else if (TEST_DELETE_WORD.equals(action))
{
deleteWordBeforeCursor()
}
else if (TEST_REPEAT_DELETE.equals(action))
{
for (count in 0..4)
{
deleteOne()
}
}
else if (TEST_DELETE_ONE.equals(action))
{
deleteOne()
}
}

private fun prepareTelemetryTest() {
ImePreferences.setCollectionEnabled(this, true)
ImePreferences.setContextEnabled(this, true)
val status = File(cacheDir, TEST_TELEMETRY_STATUS_FILE)
status.delete()
File(cacheDir, TEST_TELEMETRY_EXPORT_FILE).delete()
eventStore?.deleteAll { _, error ->
status.writeText(if (error == null) "prepared" else "error:${error.message}")
}
}

private fun exportTelemetryTest() {
val status = File(cacheDir, TEST_TELEMETRY_STATUS_FILE)
status.delete()
val destination = File(cacheDir, TEST_TELEMETRY_EXPORT_FILE)
val output = destination.outputStream()
eventStore?.exportJsonLines(output) { count, error ->
status.writeText(if (error == null) "exported:$count" else "error:${error.message}")
}
}

private fun undoLastCommit() {
val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
if (!lastDeletedText!!.isEmpty())
{
val restored = lastDeletedText
val before = contextBefore()
val cursorBefore = cursorPosition()
connection!!.commitText(restored, 1)
record(
"UNDO", "", "", restored, Collections.emptyList(), -1, before, contextAfter(),
compositionId = "",
correctionId = activeCorrectionId,
candidateSource = "NONE",
commitMethod = "UNDO_KEY",
editOperation = "RESTORE_DELETED",
cursorBefore = cursorBefore,
cursorAfter = cursorPosition())
lastDeletedText = ""
activeCorrectionId = ""
return 
}
if (!lastCommittedText!!.isEmpty())
{
val deleted = lastCommittedText
val codePoints = deleted!!.codePointCount(0, deleted!!.length)
val before = contextBefore()
val cursorBefore = cursorPosition()
ensureCorrectionId()
connection!!.deleteSurroundingTextInCodePoints(codePoints, 0)
record(
"UNDO", "", "", "", Collections.emptyList(), -1, before, contextAfter(),
compositionId = "",
correctionId = activeCorrectionId,
candidateSource = "NONE",
commitMethod = "UNDO_KEY",
editOperation = "UNDO_COMMIT",
deletedText = deleted,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition())
lastCommittedText = ""
}
}

private fun applyModifierCycle() {
val rawBefore = rawComposition.toString()
val cursorBefore = cursorPosition()
val before = contextBefore()
if (!KanaModifier.cycle(rawComposition))
{
return
}
ensureCompositionId()
refreshSuggestions()
updateComposingText()
recordCompositionEdit(
"MODIFIER_CYCLE", rawBefore, cursorBefore, before,
deletedText = finalCodePoint(rawBefore).orEmpty())
}

private fun finalCodePoint(value:String):String? {
if (value.isEmpty())
{
return value
}
val start = value.offsetByCodePoints(value.length, -1)
return value.substring(start)
}

private fun enter() {
if (rawComposition.length > 0)
{
commitCurrent("ENTER_KEY")
return 
}
val connection = getCurrentInputConnection()
if (connection == null)
{
return 
}
val action = if (activeEditor == null)
EditorInfo.IME_ACTION_NONE
else
activeEditor!!.imeOptions and EditorInfo.IME_MASK_ACTION
if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
{
connection!!.performEditorAction(action)
}
else
{
commitLiteral("\n", "ENTER", "ENTER_KEY")
}
}

private fun record(
type:String,
raw:String?, 
reading:String?, 
committed:String?, 
candidates:List<String>?,
selectedIndex:Int, 
before:String?, 
after:String?,
compositionId:String = this.compositionId,
correctionId:String = activeCorrectionId,
candidateSource:String = currentCandidateSource,
commitMethod:String = "",
editOperation:String = "",
rawBefore:String = "",
rawAfter:String = "",
deletedText:String = "",
cursorBefore:Int = -1,
cursorAfter:Int = -1,
gesture:FlickGesture? = null) {
if (!canCollect())
{
return 
}
eventStore!!.append(CollectionEvent(
sessionId = sessionId,
sequence = ++sequence,
type = type,
packageId = PrivacyGuard.packageId(if (activeEditor == null) null else activeEditor!!.packageName),
inputType = if (activeEditor == null) InputType.TYPE_NULL else activeEditor!!.inputType,
inputMode = keyboardMode.name,
rawInput = raw,
reading = reading,
committedText = committed,
candidates = candidates,
selectedIndex = selectedIndex,
contextBefore = before,
contextAfter = after,
compositionId = compositionId,
correctionId = correctionId,
candidateSource = candidateSource,
commitMethod = commitMethod,
editOperation = editOperation,
rawBefore = rawBefore,
rawAfter = rawAfter,
deletedText = deletedText,
cursorBefore = cursorBefore,
cursorAfter = cursorAfter,
engineVersion = conversionEngine?.name().orEmpty(),
appVersion = BuildConfig.VERSION_NAME,
layoutVersion = LAYOUT_VERSION,
gesture = gesture))
}

private fun recordCompositionEdit(
operation:String,
rawBefore:String,
cursorBefore:Int,
before:String,
gesture:FlickGesture? = null,
deletedText:String = "") {
record(
"COMPOSITION_EDIT",
rawComposition.toString(),
currentReading(),
"",
ArrayList(currentCandidates),
-1,
before,
contextAfter(),
compositionId = compositionId,
correctionId = activeCorrectionId,
candidateSource = currentCandidateSource,
editOperation = operation,
rawBefore = rawBefore,
rawAfter = rawComposition.toString(),
deletedText = deletedText,
cursorBefore = cursorBefore,
cursorAfter = cursorPosition(),
gesture = gesture)
}

private fun ensureCompositionId():String {
if (compositionId.isEmpty())
{
compositionId = UUID.randomUUID().toString()
}
return compositionId
}

private fun ensureCorrectionId():String {
if (activeCorrectionId.isEmpty())
{
activeCorrectionId = UUID.randomUUID().toString()
}
return activeCorrectionId
}

private fun cursorPosition():Int {
val connection = getCurrentInputConnection() ?: return -1
return try
{
connection.getExtractedText(ExtractedTextRequest(), 0)?.selectionEnd ?: -1
}
catch (error:RuntimeException)
{
-1
}
}

private fun contextBefore():String {
if (!canCollect() || !ImePreferences.isContextEnabled(this))
{
return ""
}
return conversionContextBefore()
}

/** Context used ephemerally by Mozc; this value is never persisted by this method.  */
private fun conversionContextBefore():String {
val connection = getCurrentInputConnection()
val value = if (connection == null)
null
else
connection!!.getTextBeforeCursor(
CONTEXT_LIMIT + lastComposedText.length,
0)
if (value == null)
{
return ""
}
var context = value!!.toString()
if (lastComposedText.isNotEmpty() && context.endsWith(lastComposedText))
{
context = context.substring(0, context.length - lastComposedText.length)
}
return if (context.length <= CONTEXT_LIMIT)
context
else
context.substring(context.length - CONTEXT_LIMIT)
}

private fun contextAfter():String {
if (!canCollect() || !ImePreferences.isContextEnabled(this))
{
return ""
}
val connection = getCurrentInputConnection()
val value = if (connection == null) null else connection!!.getTextAfterCursor(CONTEXT_LIMIT, 0)
return if (value == null) "" else value!!.toString()
}

private fun canCollect():Boolean {
return (activeEditor != null 
&& !sensitiveField 
&& ImePreferences.isCollectionEnabled(this))
}

private fun clearComposition(finishConnection:Boolean) {
if (rawComposition.length > 0 && conversionEngine != null)
{
conversionEngine!!.discardComposition()
}
rawComposition.setLength(0)
lastComposedText = ""
currentCandidates.clear()
selectedCandidateIndex = -1
compositionId = ""
activeCorrectionId = ""
currentCandidateSource = "NONE"
if (finishConnection)
{
val connection = getCurrentInputConnection()
if (connection != null)
{
connection!!.finishComposingText()
}
}
refreshCandidateViews()
}

private fun refreshStatus() {
if (menuButton != null)
{
menuButton!!.setContentDescription(collectionDescription())
}
}

private fun currentReading():String {
return RomajiConverter.convert(rawComposition.toString())
}

@Suppress("DEPRECATION")
private fun switchToNextIme() {
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
switchToNextInputMethod(false)
return
}
val token = window?.window?.attributes?.token ?: return
val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
manager.switchToNextInputMethod(token, false)
}

private fun dp(value:Int):Int {
return Math.round(value * getResources().getDisplayMetrics().density)
}

private fun dp(value:Float):Int {
return Math.round(value * getResources().getDisplayMetrics().density)
}

companion object {
internal val TEST_DELETE_WORD = "dev.kotonoha.collector.TEST_DELETE_WORD"
internal val TEST_REPEAT_DELETE = "dev.kotonoha.collector.TEST_REPEAT_DELETE"
internal val TEST_DELETE_ONE = "dev.kotonoha.collector.TEST_DELETE_ONE"
internal val TEST_SELECTION_START = "selection_start"
internal val TEST_SELECTION_END = "selection_end"
internal val TEST_PREPARE_TELEMETRY = "dev.kotonoha.collector.TEST_PREPARE_TELEMETRY"
internal val TEST_EXPORT_TELEMETRY = "dev.kotonoha.collector.TEST_EXPORT_TELEMETRY"
internal val TEST_TELEMETRY_EXPORT_FILE = "kotonoha-telemetry-test.jsonl"
internal val TEST_TELEMETRY_STATUS_FILE = "kotonoha-telemetry-status.txt"
private val CONTEXT_LIMIT = 64
private val PANEL_HEIGHT_DP = 225
private val QWERTY_PANEL_HEIGHT_DP = 270
private val LAYOUT_VERSION = "kotonoha-kana12-qwerty-v1"
private val RECENT_EMOJI_GROUP = "最近使った絵文字"
}
}
