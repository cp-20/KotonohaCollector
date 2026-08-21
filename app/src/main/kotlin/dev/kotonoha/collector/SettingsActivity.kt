package dev.kotonoha.collector

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

 class SettingsActivity:Activity() {

private var eventStore:EventStore? = null
private var eventCountView:TextView? = null
private var palette:GboardPalette? = null

protected override fun onCreate(savedInstanceState:Bundle?) {
super.onCreate(savedInstanceState)
palette = GboardPalette(this)
eventStore = EventStore.get(this)
setContentView(buildContent())
refreshCount()
}

protected override fun onResume() {
super.onResume()
refreshCount()
}

protected override fun onActivityResult(requestCode:Int, resultCode:Int, data:Intent?) {
super.onActivityResult(requestCode, resultCode, data)
if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null)
{
return 
}
val destination = data!!.getData()
if (destination == null)
{
return 
}
try
{
val output = getContentResolver().openOutputStream(destination, "wt")
if (output == null)
{
throw IllegalStateException("出力先を開けませんでした")
}
eventStore!!.exportJsonLines(output, { count, error-> if (error == null)
{
toast(count.toString() + "件をエクスポートしました")
}
else
{
toast("エクスポートに失敗しました: " + error.message)
} })
}
catch (error:Exception) {
toast("エクスポートに失敗しました: " + error.message)
}

}

private fun buildContent():View {
val scrollView = ScrollView(this)
scrollView.setBackgroundColor(palette!!.background)
val content = LinearLayout(this)
content.setOrientation(LinearLayout.VERTICAL)
content.setPadding(dp(24), dp(28), dp(24), dp(40))
scrollView.addView(content)

val title = text("ことのは収集IME", 28, palette!!.text)
title.setTypeface(null, android.graphics.Typeface.BOLD)
content.addView(title)

val subtitle = text(
"自分の変換・確定・修正履歴を端末内だけに保存する試作IMEです。", 
16, 
palette!!.secondaryText)
subtitle.setPadding(0, dp(8), 0, dp(18))
content.addView(subtitle)

content.addView(actionButton("1. Android設定でIMEを有効化", { view-> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }))
content.addView(actionButton("2. 使用するIMEを選択", { view->
val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
manager!!.showInputMethodPicker() }))
content.addView(actionButton("3. テストパッドで入力を試す", { view-> startActivity(Intent(this, TestPadActivity::class.java)) }))

content.addView(sectionTitle("データ収集"))

val collectionSwitch = Switch(this)
collectionSwitch.setText("入力イベントを収集する")
collectionSwitch.setTextSize(17f)
collectionSwitch.setChecked(ImePreferences.isCollectionEnabled(this))
collectionSwitch.setPadding(0, dp(10), 0, dp(10))
collectionSwitch.setOnCheckedChangeListener({ button, enabled-> ImePreferences.setCollectionEnabled(this, enabled) })
content.addView(collectionSwitch)

val contextSwitch = Switch(this)
contextSwitch.setText(R.string.context_collection_label)
contextSwitch.setTextSize(17f)
contextSwitch.setChecked(ImePreferences.isContextEnabled(this))
contextSwitch.setPadding(0, dp(10), 0, dp(10))
contextSwitch.setOnCheckedChangeListener({ button, enabled->
if (!enabled)
{
ImePreferences.setContextEnabled(this, false)
return@setOnCheckedChangeListener
}
AlertDialog.Builder(this)
.setTitle("文脈の保存")
.setMessage("確定前後の文章には個人情報が含まれる可能性があります。暗号化して端末内に保存しますが、必要な場合だけ有効にしてください。")
.setPositiveButton("有効にする", { dialog, which-> ImePreferences.setContextEnabled(this, true) })
.setNegativeButton("キャンセル", { dialog, which-> contextSwitch.setChecked(false) })
.setOnCancelListener({ dialog-> contextSwitch.setChecked(false) })
.show() })
content.addView(contextSwitch)

val privacy = text(
"schema v3では未確定編集、削除と置換の系列、候補の出所と確定方法、カーソル位置、フリック方向・移動量・時間、各バージョンを保存します。パスワード、PIN、および「学習禁止」が指定された入力欄では自動的に記録を停止します。アプリ名はSHA-256由来の短いIDに置換します。アプリにインターネット権限はありません。", 
14, 
palette!!.secondaryText)
privacy.setPadding(0, dp(8), 0, dp(18))
content.addView(privacy)

content.addView(sectionTitle("保存データ"))
eventCountView = text("保存イベント数: 読み込み中", 17, palette!!.text)
eventCountView!!.setPadding(0, dp(8), 0, dp(12))
content.addView(eventCountView)

content.addView(actionButton("JSONLでエクスポート", { view-> requestExport() }))
val deleteButton = actionButton("すべて削除", { view-> confirmDelete() })
deleteButton.setTextColor(if (palette!!.dark)
Color.rgb(248, 113, 113)
else
Color.rgb(185, 28, 28))
content.addView(deleteButton)

content.addView(sectionTitle("現在の変換エンジン"))
val engine = text(
"公式OSS Mozc（851c3fe）とOSS辞書を端末内で動かします。変換候補、予測候補、候補選択による端末内学習に対応し、通信は行いません。Mozcを読み込めない場合だけ小規模フォールバック辞書へ切り替わります。", 
15, 
palette!!.secondaryText)
content.addView(engine)

return scrollView
}

private fun requestExport() {
val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
intent.addCategory(Intent.CATEGORY_OPENABLE)
intent.setType("application/x-ndjson")
intent.putExtra(Intent.EXTRA_TITLE, "kotonoha-events-" + timestamp + ".jsonl")
startActivityForResult(intent, REQUEST_EXPORT)
}

private fun confirmDelete() {
AlertDialog.Builder(this)
.setTitle("保存データをすべて削除しますか？")
.setMessage("この操作は元に戻せません。必要なら先にエクスポートしてください。")
.setPositiveButton("削除", { dialog, which-> eventStore!!.deleteAll({ count, error-> if (error == null)
{
toast(count.toString() + "件を削除しました")
refreshCount()
}
else
{
toast("削除に失敗しました: " + error.message)
} }) })
.setNegativeButton("キャンセル", null)
.show()
}

private fun refreshCount() {
if (eventStore == null || eventCountView == null)
{
return 
}
eventStore!!.count({ count, error-> eventCountView!!.setText(if (error == null)
"保存イベント数: " + count + "件"
else
"保存イベント数を取得できません") })
}

private fun sectionTitle(value:String?):TextView {
val view = text(value, 19, palette!!.text)
view.setTypeface(null, android.graphics.Typeface.BOLD)
view.setPadding(0, dp(24), 0, dp(6))
return view
}

private fun text(value:String?, size:Int, color:Int):TextView {
val view = TextView(this)
view.setText(value)
view.setTextSize(size.toFloat())
view.setTextColor(color)
view.setLineSpacing(0f, 1.15f)
return view
}

private fun actionButton(label:String?, listener:View.OnClickListener?):Button {
val button = Button(this)
button.setAllCaps(false)
button.setText(label)
button.setTextSize(16f)
button.setGravity(Gravity.CENTER)
button.setOnClickListener(listener)
val parameters = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
parameters.setMargins(0, dp(5), 0, dp(5))
button.setLayoutParams(parameters)
return button
}

private fun toast(message:String?) {
Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private fun dp(value:Int):Int {
return Math.round(value * getResources().getDisplayMetrics().density)
}

companion object {
private val REQUEST_EXPORT = 1201
}
}
