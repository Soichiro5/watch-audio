package app.watchaudio.core.db

/** Longest snippet (in characters, excluding markers) shown for a search hit. */
internal const val SNIPPET_CHARS = 40

/** Characters of context kept before the first match when the text has to be cut. */
private const val CONTEXT_BEFORE = 10

/**
 * Builds the snippet of a search hit: every case-insensitive occurrence of [query] in [text] is
 * wrapped in `[` `]`. Text longer than [maxChars] is cut to a window around the first match,
 * with `…` marking each cut; a match is never cut in half.
 *
 * Done in Kotlin rather than with FTS5 `snippet()`, which costs more than the search itself on
 * frequent words because SQLite computes it for every match before sorting (#23).
 */
internal fun highlightSnippet(text: String, query: String, maxChars: Int = SNIPPET_CHARS): String {
    if (query.isEmpty()) return text
    val matches = buildList {
        var at = text.indexOf(query, ignoreCase = true)
        while (at >= 0) {
            add(at)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
    }

    var start = 0
    var end = text.length
    if (text.length > maxChars) {
        start = ((matches.firstOrNull() ?: 0) - CONTEXT_BEFORE).coerceAtLeast(0)
        end = (start + maxChars).coerceAtMost(text.length)
        start = (end - maxChars).coerceAtLeast(0)
        matches.firstOrNull { it < end && it + query.length > end }?.let { end = it + query.length }
    }

    return buildString {
        if (start > 0) append('…')
        var position = start
        for (match in matches) {
            if (match < start || match + query.length > end) continue
            append(text, position, match)
            append('[').append(text, match, match + query.length).append(']')
            position = match + query.length
        }
        append(text, position, end)
        if (end < text.length) append('…')
    }
}
