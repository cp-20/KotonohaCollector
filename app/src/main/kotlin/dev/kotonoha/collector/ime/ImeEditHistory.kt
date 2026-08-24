package dev.kotonoha.collector.ime

/** The single reversible editor operation exposed by the keyboard's Undo key. */
internal class ImeEditHistory {
    sealed interface Entry {
        val text: String
        val cursorAfter: Int

        data class Commit(
            override val text: String,
            override val cursorAfter: Int,
        ) : Entry

        data class Delete(
            override val text: String,
            override val cursorAfter: Int,
        ) : Entry
    }

    var entry: Entry? = null
        private set

    val canUndo: Boolean
        get() = entry != null

    fun recordCommit(text: String, cursorAfter: Int) {
        entry = text.takeIf(String::isNotEmpty)?.let { Entry.Commit(it, cursorAfter) }
    }

    fun recordDelete(text: String, cursorAfter: Int) {
        entry = text.takeIf(String::isNotEmpty)?.let { Entry.Delete(it, cursorAfter) }
    }

    fun canApplyAt(cursor: Int, textBeforeCursor: String? = null): Boolean {
        val current = entry ?: return false
        if (current.cursorAfter >= 0 && cursor >= 0 && current.cursorAfter != cursor) return false
        return current !is Entry.Commit || textBeforeCursor == current.text
    }

    fun clear() {
        entry = null
    }
}
