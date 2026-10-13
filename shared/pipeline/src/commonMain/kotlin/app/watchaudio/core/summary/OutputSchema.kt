package app.watchaudio.core.summary

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The shape of an LLM's answer, decided in shared code (#27 §4.1): objects of named properties,
 * lists with a maximum length, strings and integers. iOS builds Foundation Models'
 * `DynamicGenerationSchema` from [toJson] (guided generation); a provider without guided generation
 * can put [toJson] into the prompt. Either way the answer is checked by [parseSummaryOutput].
 */
sealed interface OutputSchema {
    val description: String?

    data class Text(override val description: String? = null) : OutputSchema

    data class Integer(override val description: String? = null) : OutputSchema

    data class ListOf(val items: OutputSchema, val maxItems: Int, override val description: String? = null) : OutputSchema

    data class Object(val name: String, val properties: List<Property>, override val description: String? = null) : OutputSchema

    data class Property(val name: String, val schema: OutputSchema)
}

/**
 * The schema as JSON, the contract with the Swift side (`apps/ios/App/Llm/OnDeviceLanguageModel.swift`):
 * `{"type": "object", "name", "description"?, "properties": [{"name", "schema"}]}`,
 * `{"type": "array", "items", "maxItems", "description"?}`, `{"type": "string" | "integer", "description"?}`.
 */
fun OutputSchema.toJson(): String = toJsonElement().toString()

private fun OutputSchema.toJsonElement(): JsonElement = buildJsonObject {
    when (val schema = this@toJsonElement) {
        is OutputSchema.Text -> put("type", "string")
        is OutputSchema.Integer -> put("type", "integer")
        is OutputSchema.ListOf -> {
            put("type", "array")
            put("items", schema.items.toJsonElement())
            put("maxItems", schema.maxItems)
        }
        is OutputSchema.Object -> {
            put("type", "object")
            put("name", schema.name)
            put(
                "properties",
                JsonArray(
                    schema.properties.map { property ->
                        JsonObject(mapOf("name" to JsonPrimitive(property.name), "schema" to property.schema.toJsonElement()))
                    },
                ),
            )
        }
    }
    description?.let { put("description", it) }
}
