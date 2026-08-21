package dev.kotonoha.collector

import android.content.Context
import android.util.Log
import java.io.BufferedReader

internal class EmojiCatalog private constructor(
    private val byGroup: Map<String, List<String>>,
) {
    fun groups(): List<String> = byGroup.keys.toList()

    fun emojis(group: String?): List<String> = byGroup[group].orEmpty()

    companion object {
        private const val TAG = "KotonohaEmoji"
        private const val GROUP_PREFIX = "# group: "

        @Volatile
        private var instance: EmojiCatalog? = null

        fun get(context: Context): EmojiCatalog = instance ?: synchronized(this) {
            instance ?: EmojiCatalog(loadGroups(context)).also { instance = it }
        }

        internal fun parse(reader: BufferedReader): Map<String, List<String>> {
            val groups = linkedMapOf<String, MutableList<String>>()
            var currentGroup: String? = null
            reader.forEachLine { line ->
                if (line.startsWith(GROUP_PREFIX)) {
                    currentGroup = line.removePrefix(GROUP_PREFIX).trim()
                    groups.getOrPut(currentGroup!!) { mutableListOf() }
                } else if (currentGroup != null && "; fully-qualified" in line) {
                    val comment = line.substringAfter('#', "").trim()
                    val emoji = comment.substringBefore(' ')
                    if (emoji.isNotEmpty()) groups.getValue(currentGroup!!).add(emoji)
                }
            }
            return groups.mapValues { (_, emojis) -> emojis.toList() }
        }

        private fun loadGroups(context: Context): Map<String, List<String>> = try {
            context.assets.open("emoji-test.txt").bufferedReader(Charsets.UTF_8).use(::parse)
        } catch (error: Exception) {
            Log.e(TAG, "Could not load the Unicode emoji catalog", error)
            mapOf("Smileys & Emotion" to listOf("😀", "😂", "😊", "😍", "🥺", "👍", "🙏", "🎉", "❤️", "🔥"))
        }
    }
}
