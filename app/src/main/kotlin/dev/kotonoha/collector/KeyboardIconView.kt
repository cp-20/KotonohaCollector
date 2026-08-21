package dev.kotonoha.collector

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView

/** Monochrome utility icon backed by the official Google Material Icons vectors.  */
internal class KeyboardIconView @JvmOverloads constructor(context:Context, icon:Icon? = Icon.CLIPBOARD):ImageView(context) {

private var iconBoxDp = 24f
internal enum class Icon {
MENU, 
STICKER, 
GIF, 
CLIPBOARD, 
SETTINGS, 
HANDWRITING, 
MICROPHONE, 
UNDO, 
BACKSPACE, 
LEFT, 
RIGHT, 
SPACE, 
ENTER, 
SHIFT, 
CAPS_LOCK, 
GLOBE, 
EMOJI_SYMBOL, 
BACK, 
SEARCH, 
EXPAND_MORE, 
DELETE
}

init{
setClickable(true)
setFocusable(true)
setScaleType(ScaleType.CENTER_INSIDE)
setIcon(icon)
}

 fun setIcon(icon:Icon?) {
val resource = resourceFor(icon!!)
if (resource == 0)
{
setImageDrawable(null)
}
else
{
setImageResource(resource)
}
}

 fun setIconColor(color:Int) {
setImageTintList(ColorStateList.valueOf(color))
}

fun setIconBoxDp(value:Int) = setIconBoxDp(value.toFloat())

fun setIconBoxDp(value:Float) {
iconBoxDp = value
updateIconPadding()
}

protected override fun onSizeChanged(width:Int, height:Int, oldWidth:Int, oldHeight:Int) {
super.onSizeChanged(width, height, oldWidth, oldHeight)
updateIconPadding()
}

private fun updateIconPadding() {
if (getWidth() <= 0 || getHeight() <= 0)
{
return 
}
val target = Math.round(iconBoxDp * getResources().getDisplayMetrics().density)
val horizontal = Math.max(0, (getWidth() - target) / 2)
val vertical = Math.max(0, (getHeight() - target) / 2)
setPadding(horizontal, vertical, horizontal, vertical)
}

private fun resourceFor(icon:Icon):Int {
when (icon) {
KeyboardIconView.Icon.CLIPBOARD -> return R.drawable.ic_material_content_paste
KeyboardIconView.Icon.EMOJI_SYMBOL, KeyboardIconView.Icon.STICKER -> return R.drawable.ic_material_emoji_emotions
KeyboardIconView.Icon.UNDO -> return R.drawable.ic_material_undo
KeyboardIconView.Icon.BACKSPACE, KeyboardIconView.Icon.DELETE -> return R.drawable.ic_material_backspace
KeyboardIconView.Icon.SPACE -> return R.drawable.ic_material_space_bar
KeyboardIconView.Icon.ENTER -> return R.drawable.ic_material_keyboard_return
KeyboardIconView.Icon.SHIFT -> return R.drawable.ic_material_shift
KeyboardIconView.Icon.CAPS_LOCK -> return R.drawable.ic_material_keyboard_capslock
KeyboardIconView.Icon.SEARCH -> return R.drawable.ic_material_search
KeyboardIconView.Icon.BACK -> return R.drawable.ic_material_arrow_back
KeyboardIconView.Icon.EXPAND_MORE -> return R.drawable.ic_material_expand_more
else -> return 0
}
}
}
