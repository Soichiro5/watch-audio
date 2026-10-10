package app.watchaudio.core.protocol

import kotlinx.serialization.json.Json

/** JSON settings shared by every peer. Unknown keys are ignored so newer watches can add fields. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun ChunkManifest.toJson(): String = ProtocolJson.encodeToString(ChunkManifest.serializer(), this)

fun ChunkAck.toJson(): String = ProtocolJson.encodeToString(ChunkAck.serializer(), this)

fun decodeManifest(json: String): ChunkManifest = ProtocolJson.decodeFromString(ChunkManifest.serializer(), json)

fun decodeAck(json: String): ChunkAck = ProtocolJson.decodeFromString(ChunkAck.serializer(), json)
