package app.watchaudio.core.summary

/**
 * The whole request as one text, for a model without guided generation or a separate system part
 * (ML Kit's Prompt API on Android, #205; LiteRT-LM later): the instructions, the answer's shape
 * written out ([schemaInstructions]), then the transcript lines. The answer is checked by
 * [parseSummaryOutput] as for every provider; one that is not of the shape is asked for once more
 * ([HourSummarizer]).
 */
fun LlmRequest.toSingleText(): String = buildString {
    append(instructions.trim())
    append("\n\n")
    append(schema.schemaInstructions())
    append("\n\n入力:\n")
    append(prompt)
}

/**
 * How to answer, in Japanese like the instructions: JSON only, an example of the shape, and what
 * each field is (with the list limits).
 */
fun OutputSchema.schemaInstructions(): String = buildString {
    append("答えは次の形の JSON だけを書く（前後に説明や ``` を付けない）:\n")
    append(exampleJson())
    val fields = fieldNotes(null)
    if (fields.isNotEmpty()) {
        append("\n")
        fields.forEach { append("\n- ").append(it) }
    }
}

/** A compact example of the shape: `"…"` for texts, `1` for integers, one item per list. */
fun OutputSchema.exampleJson(): String = when (this) {
    is OutputSchema.Text -> "\"…\""
    is OutputSchema.Integer -> "1"
    is OutputSchema.ListOf -> "[${items.exampleJson()}]"
    is OutputSchema.Object -> properties.joinToString(", ", "{", "}") { "\"${it.name}\": ${it.schema.exampleJson()}" }
}

/** One note per described field, e.g. `highlights: 大事な…（最大 3 件）`, nested names joined with `.`. */
private fun OutputSchema.fieldNotes(path: String?): List<String> {
    val notes = mutableListOf<String>()
    val description = description
    if (path != null && (description != null || this is OutputSchema.ListOf)) {
        val limit = (this as? OutputSchema.ListOf)?.let { "最大 ${it.maxItems} 件" }
        val text = listOfNotNull(description, limit).joinToString("、")
        notes += "$path: $text"
    }
    when (this) {
        is OutputSchema.Object -> properties.forEach { notes += it.schema.fieldNotes(if (path == null) it.name else "$path.${it.name}") }
        is OutputSchema.ListOf -> {
            // The items' own fields; a list of plain values was described above.
            if (items is OutputSchema.Object) notes += items.fieldNotes(path)
        }
        is OutputSchema.Text, is OutputSchema.Integer -> Unit
    }
    return notes
}
