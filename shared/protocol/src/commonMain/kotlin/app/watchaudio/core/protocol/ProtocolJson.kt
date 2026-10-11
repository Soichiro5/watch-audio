package app.watchaudio.core.protocol

import kotlinx.serialization.json.Json

/** JSON settings shared by every peer. Unknown keys are ignored so newer watches can add fields. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Encodes this manifest with [ProtocolJson]. */
fun ChunkManifest.toJson(): String = ProtocolJson.encodeToString(ChunkManifest.serializer(), this)

/** Encodes this ACK with [ProtocolJson]. */
fun ChunkAck.toJson(): String = ProtocolJson.encodeToString(ChunkAck.serializer(), this)

/** Decodes a manifest; throws [IllegalArgumentException] when [json] is malformed. */
fun decodeManifest(json: String): ChunkManifest = ProtocolJson.decodeFromString(ChunkManifest.serializer(), json)

/** Decodes an ACK; throws [IllegalArgumentException] when [json] is malformed. */
fun decodeAck(json: String): ChunkAck = ProtocolJson.decodeFromString(ChunkAck.serializer(), json)
