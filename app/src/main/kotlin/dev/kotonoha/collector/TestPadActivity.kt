package dev.kotonoha.collector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import dev.kotonoha.collector.ui.GboardPalette

import java.nio.charset.StandardCharsets

/** Simple local editor used to exercise the IME without installing another test app.  */
 class TestPadActivity:Activity() {

private var editor:EditText? = null
private val testStateReporter = object : Runnable {
override fun run() {
refreshTestState()
editor?.postDelayed(this, TEST_STATE_REFRESH_MS)
}
}

protected override fun onCreate(savedInstanceState:Bundle?) {
super.onCreate(savedInstanceState)
getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
setContentView(buildContent())
}

protected override fun onNewIntent(intent:Intent?) {
super.onNewIntent(intent)
setIntent(intent)
resetEditor(
initialText(intent), 
intent!!.getIntExtra(EXTRA_SELECTION_START, -1), 
intent!!.getIntExtra(EXTRA_SELECTION_END, -1), 
intent!!.getStringExtra(EXTRA_IME_TEST_COMMAND))
}

protected override fun onResume() {
super.onResume()
if (BuildConfig.DEBUG) editor?.post(testStateReporter)
}

protected override fun onPause() {
editor?.removeCallbacks(testStateReporter)
super.onPause()
}

private fun buildContent():View {
val palette = GboardPalette(this)
val root = LinearLayout(this)
root.setOrientation(LinearLayout.VERTICAL)
root.setPadding(dp(20), dp(24), dp(20), dp(16))
root.setBackgroundColor(palette.background)

val title = TextView(this)
title.setText(R.string.test_pad_title)
title.setTextSize(24f)
title.setTextColor(palette.text)
title.setTypeface(null, android.graphics.Typeface.BOLD)
root.addView(title)

val hint = TextView(this)
hint.setText("フリック表示、候補、クリップボード、絵文字をここで確認できます。")
hint.setTextSize(14f)
hint.setTextColor(palette.secondaryText)
hint.setPadding(0, dp(5), 0, dp(10))
root.addView(hint)

val sample = Button(this)
sample.setAllCaps(false)
sample.setText("テスト文をクリップボードへコピー")
sample.setOnClickListener({ view-> copySample() })
root.addView(sample, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

editor = EditText(this)
editor!!.setHint("ここに入力してください")
editor!!.setTextSize(19f)
editor!!.setTextColor(palette.text)
editor!!.setHintTextColor(palette.guideInactive)
editor!!.setGravity(Gravity.TOP or Gravity.START)
editor!!.setPadding(dp(12), dp(12), dp(12), dp(12))
editor!!.setInputType((InputType.TYPE_CLASS_TEXT 
or InputType.TYPE_TEXT_FLAG_MULTI_LINE 
or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
editor!!.setSingleLine(false)
editor!!.setContentDescription("test-editor:")
editor!!.addTextChangedListener(object : TextWatcher {
override fun beforeTextChanged(
value:CharSequence?, start:Int, count:Int, after:Int) {}

override fun onTextChanged(
value:CharSequence?, start:Int, before:Int, count:Int) {
refreshTestState()
}

override fun afterTextChanged(value:Editable?) {}
})
editor!!.setText(initialText(getIntent()))
applySelection(getIntent())
root.addView(editor, LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

editor!!.postDelayed({ editor!!.requestFocus()
val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
manager!!.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
dispatchImeTestCommand(
manager, getIntent().getStringExtra(EXTRA_IME_TEST_COMMAND)) }, 250)
return root
}

private fun refreshTestState() {
val editable = editor?.text ?: return
val composingStart = android.view.inputmethod.BaseInputConnection
.getComposingSpanStart(editable)
val composingEnd = android.view.inputmethod.BaseInputConnection
.getComposingSpanEnd(editable)
editor!!.contentDescription =
"test-editor:$editable;composing:$composingStart:$composingEnd"
}

private fun resetEditor(
value:String?, selectionStart:Int, selectionEnd:Int, testCommand:String?) {
if (editor == null)
{
return 
}
android.view.inputmethod.BaseInputConnection.removeComposingSpans(editor!!.getText())
editor!!.setText(value)
applySelection(selectionStart, selectionEnd)
editor!!.postDelayed({ editor!!.requestFocus()
val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
manager!!.restartInput(editor)
manager!!.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
dispatchImeTestCommand(manager, testCommand) }, 120)
}

private fun applySelection(intent:Intent?) {
applySelection(
if (intent == null) -1 else intent!!.getIntExtra(EXTRA_SELECTION_START, -1), 
if (intent == null) -1 else intent!!.getIntExtra(EXTRA_SELECTION_END, -1))
}

private fun applySelection(start:Int, end:Int) {
if (start >= 0 && end >= start && end <= editor!!.length())
{
editor!!.setSelection(start, end)
}
else
{
editor!!.setSelection(editor!!.length())
}
}

private fun dispatchImeTestCommand(manager:InputMethodManager?, command:String?) {
if (!BuildConfig.DEBUG || command == null || command!!.isEmpty())
{
return 
}
val testData = Bundle()
val intent = getIntent()
if ((intent != null 
&& intent!!.hasExtra(EXTRA_SELECTION_START) 
&& intent!!.hasExtra(EXTRA_SELECTION_END)))
{
testData.putInt(
EXTRA_SELECTION_START, intent!!.getIntExtra(EXTRA_SELECTION_START, -1))
testData.putInt(EXTRA_SELECTION_END, intent!!.getIntExtra(EXTRA_SELECTION_END, -1))
}
editor!!.postDelayed(
{ manager!!.sendAppPrivateCommand(editor, command, testData) }, 350)
}

private fun initialText(intent:Intent?):String {
if (intent == null)
{
return ""
}
val encoded = intent!!.getStringExtra(EXTRA_INITIAL_TEXT_BASE64)
if (encoded != null)
{
try
{
return String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
}
catch (invalidBase64:IllegalArgumentException) {
return ""
}

}
val plain = intent!!.getStringExtra(EXTRA_INITIAL_TEXT)
return if (plain == null) "" else plain
}

private fun copySample() {
val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
manager!!.setPrimaryClip(ClipData.newPlainText(
"ことのはテスト", 
"クリップボードから貼り付けるテスト文です。"))
Toast.makeText(this, "コピーしました。IME の 📋 を開いてください。", Toast.LENGTH_SHORT).show()
}

private fun dp(value:Int):Int {
return Math.round(value * getResources().getDisplayMetrics().density)
}

companion object {
internal val EXTRA_INITIAL_TEXT = "initial_text"
internal val EXTRA_INITIAL_TEXT_BASE64 = "initial_text_base64"
internal val EXTRA_IME_TEST_COMMAND = "ime_test_command"
internal val EXTRA_SELECTION_START = "selection_start"
internal val EXTRA_SELECTION_END = "selection_end"
private const val TEST_STATE_REFRESH_MS = 50L
}
}
