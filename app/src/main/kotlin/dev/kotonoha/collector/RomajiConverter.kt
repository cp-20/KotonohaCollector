package dev.kotonoha.collector

import java.util.Locale

internal object RomajiConverter {
private val TABLE: Map<String, String>

init{
val table = HashMap<String, String>()
val entries = arrayOf(arrayOf("a", "あ"), arrayOf("i", "い"), arrayOf("u", "う"), arrayOf("e", "え"), arrayOf("o", "お"), arrayOf("ka", "か"), arrayOf("ki", "き"), arrayOf("ku", "く"), arrayOf("ke", "け"), arrayOf("ko", "こ"), arrayOf("ga", "が"), arrayOf("gi", "ぎ"), arrayOf("gu", "ぐ"), arrayOf("ge", "げ"), arrayOf("go", "ご"), arrayOf("sa", "さ"), arrayOf("shi", "し"), arrayOf("si", "し"), arrayOf("su", "す"), arrayOf("se", "せ"), arrayOf("so", "そ"), arrayOf("za", "ざ"), arrayOf("ji", "じ"), arrayOf("zi", "じ"), arrayOf("zu", "ず"), arrayOf("ze", "ぜ"), arrayOf("zo", "ぞ"), arrayOf("ta", "た"), arrayOf("chi", "ち"), arrayOf("ti", "ち"), arrayOf("tsu", "つ"), arrayOf("tu", "つ"), arrayOf("te", "て"), arrayOf("to", "と"), arrayOf("da", "だ"), arrayOf("di", "ぢ"), arrayOf("du", "づ"), arrayOf("de", "で"), arrayOf("do", "ど"), arrayOf("na", "な"), arrayOf("ni", "に"), arrayOf("nu", "ぬ"), arrayOf("ne", "ね"), arrayOf("no", "の"), arrayOf("ha", "は"), arrayOf("hi", "ひ"), arrayOf("fu", "ふ"), arrayOf("hu", "ふ"), arrayOf("he", "へ"), arrayOf("ho", "ほ"), arrayOf("ba", "ば"), arrayOf("bi", "び"), arrayOf("bu", "ぶ"), arrayOf("be", "べ"), arrayOf("bo", "ぼ"), arrayOf("pa", "ぱ"), arrayOf("pi", "ぴ"), arrayOf("pu", "ぷ"), arrayOf("pe", "ぺ"), arrayOf("po", "ぽ"), arrayOf("ma", "ま"), arrayOf("mi", "み"), arrayOf("mu", "む"), arrayOf("me", "め"), arrayOf("mo", "も"), arrayOf("ya", "や"), arrayOf("yu", "ゆ"), arrayOf("yo", "よ"), arrayOf("ra", "ら"), arrayOf("ri", "り"), arrayOf("ru", "る"), arrayOf("re", "れ"), arrayOf("ro", "ろ"), arrayOf("wa", "わ"), arrayOf("wo", "を"), arrayOf("nn", "ん"), arrayOf("n'", "ん"), arrayOf("kya", "きゃ"), arrayOf("kyu", "きゅ"), arrayOf("kyo", "きょ"), arrayOf("gya", "ぎゃ"), arrayOf("gyu", "ぎゅ"), arrayOf("gyo", "ぎょ"), arrayOf("sha", "しゃ"), arrayOf("shu", "しゅ"), arrayOf("sho", "しょ"), arrayOf("sya", "しゃ"), arrayOf("syu", "しゅ"), arrayOf("syo", "しょ"), arrayOf("ja", "じゃ"), arrayOf("ju", "じゅ"), arrayOf("jo", "じょ"), arrayOf("cha", "ちゃ"), arrayOf("chu", "ちゅ"), arrayOf("cho", "ちょ"), arrayOf("tya", "ちゃ"), arrayOf("tyu", "ちゅ"), arrayOf("tyo", "ちょ"), arrayOf("nya", "にゃ"), arrayOf("nyu", "にゅ"), arrayOf("nyo", "にょ"), arrayOf("hya", "ひゃ"), arrayOf("hyu", "ひゅ"), arrayOf("hyo", "ひょ"), arrayOf("bya", "びゃ"), arrayOf("byu", "びゅ"), arrayOf("byo", "びょ"), arrayOf("pya", "ぴゃ"), arrayOf("pyu", "ぴゅ"), arrayOf("pyo", "ぴょ"), arrayOf("mya", "みゃ"), arrayOf("myu", "みゅ"), arrayOf("myo", "みょ"), arrayOf("rya", "りゃ"), arrayOf("ryu", "りゅ"), arrayOf("ryo", "りょ"), arrayOf("fa", "ふぁ"), arrayOf("fi", "ふぃ"), arrayOf("fe", "ふぇ"), arrayOf("fo", "ふぉ"), arrayOf("va", "ゔぁ"), arrayOf("vi", "ゔぃ"), arrayOf("vu", "ゔ"), arrayOf("ve", "ゔぇ"), arrayOf("vo", "ゔぉ"), arrayOf("xa", "ぁ"), arrayOf("xi", "ぃ"), arrayOf("xu", "ぅ"), arrayOf("xe", "ぇ"), arrayOf("xo", "ぉ"), arrayOf("la", "ぁ"), arrayOf("li", "ぃ"), arrayOf("lu", "ぅ"), arrayOf("le", "ぇ"), arrayOf("lo", "ぉ"), arrayOf("xtsu", "っ"), arrayOf("ltsu", "っ"), arrayOf("xya", "ゃ"), arrayOf("xyu", "ゅ"), arrayOf("xyo", "ょ"), arrayOf("-", "ー"))
for (entry in entries)
{
table[entry[0]] = entry[1]
}
TABLE = table.toMap()
}

fun convert(rawInput: String?): String {
val source = rawInput.orEmpty().lowercase(Locale.ROOT)
val result = StringBuilder()
var index = 0

while (index < source.length)
{
val current = source[index]

if ((index + 1 < source.length 
&& current == source[index + 1] 
&& isConsonant(current) 
&& current != 'n'))
{
result.append('っ')
index++
continue
}

if ((current == 'n' 
&& index + 1 < source.length 
&& source[index + 1] == 'n'))
{
result.append('ん')
index += if (index + 2 < source.length) 1 else 2
continue
}

var matched:String? = null
var matchedLength = 0
val maximum = Math.min(4, source.length - index)
for (length in maximum downTo 1)
{
val token = source.substring(index, index + length)
if (TABLE.containsKey(token))
{
matched = TABLE.get(token)
matchedLength = length
break
}
}

if (matched != null)
{
result.append(matched)
index += matchedLength
continue
}

if ((current == 'n' && index + 1 < source.length 
&& !isVowel(source[index + 1]) 
&& source[index + 1] != 'y'))
{
result.append('ん')
index++
continue
}

result.append(current)
index++
}
return result.toString()
}

private fun isConsonant(value:Char):Boolean {
return value >= 'a' && value <= 'z' && !isVowel(value)
}

private fun isVowel(value:Char):Boolean {
return value == 'a' || value == 'i' || value == 'u' || value == 'e' || value == 'o'
}
}
