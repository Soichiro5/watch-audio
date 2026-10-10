package app.watchaudio.app.logic

data class SnippetPart(val text: String, val highlighted: Boolean)

/**
 * Splits a search snippet whose matches are wrapped in `[` `]` (see `StoreTransaction.search`)
 * into plain and highlighted parts. An unclosed `[` highlights to the end; a stray `]` is
 * kept as text. Empty parts are dropped and neighbours of the same kind are joined.
 */
fun parseSnippet(snippet: String): List<SnippetPart> {
    val parts = mutableListOf<SnippetPart>()
    val current = StringBuilder()
    var highlighted = false

    fun flush() {
        if (current.isEmpty()) return
        val last = parts.lastOrNull()
        if (last != null && last.highlighted == highlighted) {
            parts[parts.lastIndex] = last.copy(text = last.text + current)
        } else {
            parts += SnippetPart(current.toString(), highlighted)
        }
        current.clear()
    }

    for (c in snippet) {
        when {
            c == '[' && !highlighted -> {
                flush()
                highlighted = true
            }
            c == ']' && highlighted -> {
                flush()
                highlighted = false
            }
            else -> current.append(c)
        }
    }
    flush()
    return parts
}
