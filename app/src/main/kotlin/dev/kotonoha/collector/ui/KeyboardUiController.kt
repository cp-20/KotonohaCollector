package dev.kotonoha.collector.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kotonoha.collector.clipboard.ClipboardHistory
import dev.kotonoha.collector.editor.EditorDirection
import dev.kotonoha.collector.input.FlickGesture
import dev.kotonoha.collector.ui.contract.ContentPickerActions
import dev.kotonoha.collector.ui.contract.EditorActions
import dev.kotonoha.collector.ui.contract.ImeSystemActions
import dev.kotonoha.collector.ui.contract.ImeUiOutput
import dev.kotonoha.collector.ui.contract.KeyboardMode
import dev.kotonoha.collector.ui.contract.KeyboardUiStateSource
import dev.kotonoha.collector.ui.contract.TextInputActions

/**
 * Owns the keyboard view tree and its transient presentation state.
 *
 * Editor mutations are deliberately exposed only through narrow action ports. InputConnection
 * handling stays in EditorGateway and composition ordering stays in ImeCoordinator.
 */
internal class KeyboardUiController(
context:Context,
private val state:KeyboardUiStateSource,
private val textInput:TextInputActions,
private val editorActions:EditorActions,
private val contentPickerActions:ContentPickerActions,
private val systemActions:ImeSystemActions,
private val clipboardHistory:ClipboardHistory,
):ContextWrapper(context), ImeUiOutput {

private val palette = GboardPalette(this)
private val candidatePanel = CandidatePanelComponent(this, palette, state) {
commitCandidate(it, "CANDIDATE_TAP")
}
private val clipboardPanel = ClipboardPanelComponent(
this,
palette,
clipboardHistory,
contentPickerActions::pasteClipboard,
this::renderPanel,
)
private val emojiPanel = EmojiPanelComponent(
this,
palette,
contentPickerActions::commitEmoji,
this::renderPanel,
)
private val gestureBinder = KeyboardGestureBinder(
this,
this::deleteOne,
this::deleteWordBeforeCursor,
editorActions::moveCursor,
this::showInputMethodPicker,
)
private var toolbarContainer:LinearLayout? = null
private var candidateContainer:LinearLayout? = null
private var panelContainer:LinearLayout? = null
private var latinUppercase:Boolean = false
private var latinCapsLock:Boolean = false
private var symbolSecondPage:Boolean = false
private var lastShiftTapMs:Long = 0
private var keyboardMode:KeyboardMode = KeyboardMode.KANA_FLICK
private var panelMode:PanelMode = PanelMode.KEYBOARD

private enum class PanelMode {
KEYBOARD,
CANDIDATES,
CLIPBOARD,
EMOJI,
FEATURES
}

override val inputMode:String
get() = keyboardMode.name

fun createInputView():View {
val root = LinearLayout(this)
root.orientation = LinearLayout.VERTICAL
val contentBottomPadding = dp(14)
root.setPadding(0, 0, 0, contentBottomPadding)
root.setOnApplyWindowInsetsListener { view, insets ->
view.setPadding(
view.paddingLeft,
view.paddingTop,
view.paddingRight,
contentBottomPadding + systemBarBottomInset(insets),
)
insets
}
root.setBackgroundColor(palette.background)

root.addView(createToolbar(), LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

panelContainer = LinearLayout(this)
panelContainer!!.orientation = LinearLayout.VERTICAL
panelContainer!!.minimumHeight = dp(PANEL_HEIGHT_DP)
root.addView(panelContainer, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(PANEL_HEIGHT_DP)))

renderPanel()
refreshCandidateViews()
return root
}

@Suppress("DEPRECATION")
private fun systemBarBottomInset(insets:WindowInsets):Int {
// Android can expose the IME navigation strip as a caption bar, so include all system bars.
return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).bottom
else
insets.stableInsetBottom
}

fun startInput() {
panelMode = PanelMode.KEYBOARD
renderPanel()
refreshCandidateViews()
}

fun finishInput() {
// Clipboard history intentionally survives editor sessions for cross-application reuse.
panelMode = PanelMode.KEYBOARD
}

override fun candidateCommitted() {
if (panelMode == PanelMode.CANDIDATES)
{
panelMode = PanelMode.KEYBOARD
renderPanel()
}
refreshCandidateViews()
}

private fun createToolbar():View {
toolbarContainer = LinearLayout(this)
toolbarContainer!!.setGravity(Gravity.CENTER_VERTICAL)
toolbarContainer!!.setOrientation(LinearLayout.HORIZONTAL)
toolbarContainer!!.setPadding(dp(5), dp(2), dp(5), dp(2))
toolbarContainer!!.setBackgroundColor(palette.background)
return toolbarContainer!!
}

private fun toolbarIcon(
icon:KeyboardIconView.Icon?,
description:String?,
listener:View.OnClickListener?):KeyboardIconView {
val view = KeyboardIconView(this, icon)
view.setIconBoxDp(24)
view.setIconColor(palette.text)
view.setContentDescription(description)
view.setBackground(pressableBackground(
Color.TRANSPARENT,
palette.pressed,
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

override fun renderPanel() {
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
root.setBackgroundColor(palette.background)
val grid = LinearLayout(this)
grid.setOrientation(LinearLayout.HORIZONTAL)

val leftControls = keyColumn(palette.background)
val undo = controlIcon(
KeyboardIconView.Icon.UNDO, "元に戻す", palette.sideKey, false,
{ view-> undoLastCommit() })
undo.setIconColor(if (!state.canUndo)
palette.guideInactive
else
palette.text)
leftControls.addView(undo, controlCell(false))
leftControls.addView(cursorControl(false), controlCell(false))
leftControls.addView(controlIcon(
KeyboardIconView.Icon.EMOJI_SYMBOL, "絵文字と記号", palette.sideKey, false,
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

val rightControls = keyColumn(palette.background)
rightControls.addView(controlIcon(
KeyboardIconView.Icon.BACKSPACE, "削除", palette.sideKey, false,
{ view-> deleteOne() }), controlCell(false))
rightControls.addView(cursorControl(true), controlCell(false))
rightControls.addView(controlIcon(
KeyboardIconView.Icon.SPACE, "空白または変換", palette.sideKey, false,
{ view-> showConversionCandidates() }), controlCell(false))
rightControls.addView(controlIcon(
KeyboardIconView.Icon.ENTER, "確定または改行", palette.accent, true,
{ view-> enter() }), controlCell(true))
grid.addView(rightControls, weightedColumn())
root.addView(grid, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.MATCH_PARENT))
}

private fun addLatinQwertyKeyboard(root:LinearLayout) {
root.setBackgroundColor(palette.background)
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
root.setBackgroundColor(palette.background)
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
val primary = label(label, 19f, palette.text)
primary.setGravity(Gravity.CENTER)
key.addView(primary, FrameLayout.LayoutParams(
FrameLayout.LayoutParams.MATCH_PARENT,
FrameLayout.LayoutParams.MATCH_PARENT))
if (!alternate.isEmpty())
{
val hint = label(alternate, 8f, palette.secondaryText)
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
key.setBackground(pressableBackground(palette.key, palette.pressed, 7))
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
val key = label(title, (if (title!!.length >= 4) 12 else 16).toFloat(), palette.text)
key.setGravity(Gravity.CENTER)
key.setContentDescription(description)
key.setBackground(pressableBackground(palette.sideKey, palette.pressed, 7))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
listener!!.onClick(view) })
return key
}

private fun qwertyKanaKey():View {
val value = SpannableString("あa")
value.setSpan(ForegroundColorSpan(palette.guideInactive), 0, 1,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(ForegroundColorSpan(palette.text), 1, 2,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(RelativeSizeSpan(1.18f), 1, 2,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(StyleSpan(Typeface.BOLD), 1, 2,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
val key = label(value.toString(), 18f, palette.text)
key.setText(value)
key.setGravity(Gravity.CENTER)
key.setContentDescription("かな12キーに戻る")
key.setBackground(pressableBackground(palette.sideKey, palette.pressed, 28))
key.setOnClickListener({ view->
view!!.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
setKeyboardMode(KeyboardMode.KANA_FLICK) })
return key
}

private fun qwertyCursorKey(right:Boolean):View {
val cursor = cursorControl(right)
cursor.setBackground(pressableBackground(palette.sideKey, palette.pressed, 7))
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
if (latinUppercase) palette.accent else palette.sideKey,
palette.pressed,
7))
shift.setIconColor(if (latinUppercase) palette.accentText else palette.text)
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
key.setIconColor(palette.text)
key.setContentDescription(description)
key.setBackground(pressableBackground(palette.sideKey, palette.pressed, 7))
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
val space = label("日本語", 12f, palette.secondaryText)
space.setGravity(Gravity.CENTER)
space.setContentDescription("半角空白。横スワイプでカーソル移動、長押しでキーボード切り替え")
space.setBackground(pressableBackground(palette.key, palette.pressed, 7))
configureSpaceGesture(space) { commitHalfWidth(" ") }
return space
}

private fun qwertyEnterKey():KeyboardIconView {
val enter = qwertyIconKey(
KeyboardIconView.Icon.ENTER,
"確定または改行",
{ view-> enter() })
enter.setIconColor(palette.accentText)
enter.setBackground(pressableBackground(palette.accent, palette.pressed, 7))
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
val column = keyColumn(palette.key)
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
column.setBackgroundColor(palette.background)
return column
}

private fun weightedColumn():LinearLayout.LayoutParams {
return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
}

private fun verticalDivider():View {
val divider = View(this)
divider.setBackgroundColor(palette.divider)
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
key.setIconColor(if (accent) palette.accentText else palette.text)
key.setContentDescription(description)
key.setBackground(pressableBackground(
background,
if (accent) palette.pressed else palette.pressed,
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
key.setContentDescription("空白または変換。横スワイプでカーソル移動、長押しでキーボード切り替え")
configureSpaceGesture(key) { showConversionCandidates() }
}
return key
}

private fun emptySideCell():View {
val empty = View(this)
empty.setBackgroundColor(palette.background)
empty.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
return empty
}

private fun configureDeleteGesture(key:View) {
gestureBinder.bindDelete(key)
}

private fun configureSpaceGesture(key:View, tapAction:()->Unit) {
gestureBinder.bindSpace(key, tapAction)
}

private fun showInputMethodPicker() {
val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
manager.showInputMethodPicker()
}

private fun modeKey():View {
val value = SpannableString("あa1")
val activeIndex = if (keyboardMode == KeyboardMode.KANA_FLICK)
0
else if (keyboardMode == KeyboardMode.LATIN_QWERTY) 1 else 2
for (index in 0 until value.length)
{
value.setSpan(ForegroundColorSpan(
if (index == activeIndex) palette.text else palette.guideInactive),
index,
index + 1,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}
value.setSpan(RelativeSizeSpan(1.22f), activeIndex, activeIndex + 1,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
value.setSpan(StyleSpan(Typeface.BOLD), activeIndex, activeIndex + 1,
Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
val key = label(value.toString(), 18f, palette.secondaryText)
key.setText(value)
key.setGravity(Gravity.CENTER)
key.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
key.setContentDescription("文字種を切り替え")
key.setBackground(pressableBackground(palette.sideKey, palette.pressed, 32))
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
modifier.setBackground(pressableBackground(palette.key, palette.pressed, 7))
modifier.setOnClickListener({ view-> applyModifierCycle() })

val marks = label("゛  ゜", 15f, palette.text)
marks.setGravity(Gravity.CENTER)
marks.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
modifier.addView(marks, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(25)))

val size = label("大 ↔ 小", 8f, palette.secondaryText)
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
return candidatePanel.createView()
}

private fun createClipboardPanel():View {
return clipboardPanel.createView()
}

private fun createEmojiPanel():View {
return emojiPanel.createView()
}

private fun panelShell():LinearLayout {
val panel = LinearLayout(this)
panel.setOrientation(LinearLayout.VERTICAL)
panel.setBackgroundColor(palette.background)
return panel
}

private fun createFeaturePanel():View {
val panel = panelShell()
val instruction = label("長押ししてツールバーをカスタマイズ", 14f, palette.secondaryText)
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
if (state.collectionEnabled) "収集中" else "収集停止",
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
item.setBackground(pressableBackground(Color.TRANSPARENT, palette.pressed, 18))
val iconView = KeyboardIconView(this, icon)
iconView.setIconBoxDp(42)
iconView.setIconColor(palette.text)
item.addView(iconView, LinearLayout.LayoutParams(dp(54), dp(54)))
val label = label(title, 13f, palette.text)
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
systemActions.openSettings()
}

private fun toggleCollection() {
systemActions.toggleCollection()
renderPanel()
refreshCandidateViews()
}

private fun label(value:String?, size:Float, color:Int):TextView {
val view = TextView(this)
view.setText(value)
view.setTextSize(size)
view.setTextColor(color)
view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
return view
}

private fun cycleKeyboardMode() {
val next = when (keyboardMode) {
KeyboardMode.KANA_FLICK -> KeyboardMode.LATIN_QWERTY
KeyboardMode.LATIN_QWERTY -> KeyboardMode.SYMBOL_QWERTY
KeyboardMode.SYMBOL_QWERTY -> KeyboardMode.KANA_FLICK
}
setKeyboardMode(next)
}

private fun setKeyboardMode(mode:KeyboardMode) {
if (mode == keyboardMode || !systemActions.requestKeyboardModeChange(keyboardMode, mode)) return
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

private fun undoLastCommit() = editorActions.undoLastCommit()
private fun deleteOne() = editorActions.deleteOne()
private fun deleteWordBeforeCursor() = editorActions.deleteWordBeforeCursor()
private fun moveCursor(right:Boolean) = editorActions.moveCursor(
if (right) EditorDirection.RIGHT else EditorDirection.LEFT)
private fun moveCursorDirection(direction:CursorFlickKeyView.Direction) {
val editorDirection = when (direction) {
CursorFlickKeyView.Direction.LEFT -> EditorDirection.LEFT
CursorFlickKeyView.Direction.RIGHT -> EditorDirection.RIGHT
CursorFlickKeyView.Direction.UP -> EditorDirection.UP
CursorFlickKeyView.Direction.DOWN -> EditorDirection.DOWN
CursorFlickKeyView.Direction.CENTER -> return
}
editorActions.moveCursor(editorDirection)
}
private fun showConversionCandidates() = textInput.showConversionCandidates()
private fun enter() = textInput.enter()
private fun handleFlickInput(value:String, gesture:FlickGesture) =
textInput.handleFlickInput(value, gesture)
private fun commitHalfWidth(value:String?, commitMethod:String = "QWERTY_TAP") {
if (!value.isNullOrEmpty()) textInput.commitHalfWidth(value, commitMethod)
}
private fun applyModifierCycle() = textInput.applyModifierCycle()
private fun commitCandidate(index:Int, commitMethod:String):Boolean =
textInput.commitCandidate(index, commitMethod)
private fun switchToNextIme() = systemActions.switchToNextIme()

override fun refreshCandidateViews() {
if (toolbarContainer == null)
{
return
}
toolbarContainer!!.removeAllViews()
if (panelMode == PanelMode.CANDIDATES)
{
toolbarContainer!!.addView(toolbarBackButton(), toolbarFixed())
val title = label("候補一覧  " + state.candidates.size + "件", 17f, palette.text)
title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
title.setGravity(Gravity.CENTER_VERTICAL)
title.setPadding(dp(8), 0, 0, 0)
toolbarContainer!!.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
return
}
if (panelMode == PanelMode.CLIPBOARD)
{
toolbarContainer!!.addView(toolbarBackButton(), toolbarFixed())
val title = label("クリップボード", 17f, palette.text)
title.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
title.setGravity(Gravity.CENTER_VERTICAL)
toolbarContainer!!.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
val clear = label("消去", 14f, palette.text)
clear.setGravity(Gravity.CENTER)
clear.setBackground(pressableBackground(Color.TRANSPARENT, palette.pressed, 22))
clear.setOnClickListener({ view->
clipboardPanel.clear()
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
search.setBackground(pressableBackground(palette.panelCard, palette.pressed, 24))
val searchIcon = KeyboardIconView(this, KeyboardIconView.Icon.SEARCH)
searchIcon.setIconBoxDp(21)
searchIcon.setIconColor(palette.secondaryText)
search.addView(searchIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
val searchHint = label("絵文字を検索", 14f, palette.secondaryText)
searchHint.setPadding(dp(6), 0, 0, 0)
search.addView(searchHint, LinearLayout.LayoutParams(0, dp(42), 1f))
toolbarContainer!!.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f))
toolbarContainer!!.addView(toolbarIcon(
KeyboardIconView.Icon.BACKSPACE,
"削除",
{ view-> deleteOne() }), toolbarFixed())
return
}
if (state.candidates.isEmpty())
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
for (index in state.candidates.indices)
{
val candidateIndex = index
val candidate = state.candidates[index]
val button = label(candidate, 16f, palette.text)
button.setGravity(Gravity.CENTER)
button.setPadding(dp(15), 0, dp(15), 0)
button.setBackground(pressableBackground(
if (index == state.selectedCandidateIndex) palette.accent else Color.TRANSPARENT,
palette.pressed,
18))
button.setOnClickListener({ view-> commitCandidate(candidateIndex, "CANDIDATE_TAP") })
val parameters = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
button.setMinWidth(dp(68))
candidateContainer!!.addView(button, parameters)
}
val selectedIndex = state.selectedCandidateIndex
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

private fun dp(value:Int):Int =
Math.round(value * resources.displayMetrics.density)

private fun dp(value:Float):Int =
Math.round(value * resources.displayMetrics.density)

companion object {
private const val PANEL_HEIGHT_DP = 225
private const val QWERTY_PANEL_HEIGHT_DP = 270
}
}
