package dev.kotonoha.collector

internal object KanaModifier {
    const val DAKUTEN = "゛"
    const val HANDAKUTEN = "゜"
    const val SMALL = "小"

    private val dakutenMap = buildMap {
        listOf(
            'う' to 'ゔ', 'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
            'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
            'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
            'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
        ).forEach { (first, second) -> addPair(first, second) }
        putAll(mapOf('ぱ' to 'ば', 'ぴ' to 'び', 'ぷ' to 'ぶ', 'ぺ' to 'べ', 'ぽ' to 'ぼ'))
    }

    private val handakutenMap = buildMap {
        addHandakuten('は', 'ば', 'ぱ')
        addHandakuten('ひ', 'び', 'ぴ')
        addHandakuten('ふ', 'ぶ', 'ぷ')
        addHandakuten('へ', 'べ', 'ぺ')
        addHandakuten('ほ', 'ぼ', 'ぽ')
    }

    private val smallMap = buildMap {
        listOf(
            'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
            'つ' to 'っ', 'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ', 'わ' to 'ゎ',
            'か' to 'ゕ', 'け' to 'ゖ',
        ).forEach { (first, second) -> addPair(first, second) }
    }

    private val cycleMap = buildMap {
        listOf(
            "あぁ", "いぃ", "うゔぅ", "えぇ", "おぉ", "かがゕ", "きぎ", "くぐ", "けげゖ", "こご",
            "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
            "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "やゃ", "ゆゅ", "よょ", "わゎ",
        ).forEach { addCycle(it) }
    }

    fun apply(composition: StringBuilder, modifier: String?): Boolean {
        if (composition.isEmpty()) return false
        val map = when (modifier) {
            DAKUTEN -> dakutenMap
            HANDAKUTEN -> handakutenMap
            SMALL -> smallMap
            else -> return false
        }
        return replaceLast(composition, map)
    }

    fun cycle(composition: StringBuilder): Boolean = replaceLast(composition, cycleMap)

    private fun replaceLast(composition: StringBuilder, map: Map<Char, Char>): Boolean {
        if (composition.isEmpty()) return false
        val index = composition.lastIndex
        val replacement = map[composition[index]] ?: return false
        composition.setCharAt(index, replacement)
        return true
    }

    private fun MutableMap<Char, Char>.addPair(first: Char, second: Char) {
        put(first, second)
        put(second, first)
    }

    private fun MutableMap<Char, Char>.addHandakuten(plain: Char, voiced: Char, halfVoiced: Char) {
        put(plain, halfVoiced)
        put(voiced, halfVoiced)
        put(halfVoiced, plain)
    }

    private fun MutableMap<Char, Char>.addCycle(values: String) {
        values.forEachIndexed { index, character ->
            put(character, values[(index + 1) % values.length])
        }
    }
}
