package app.watchaudio.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The wire format between the phone and the PC's bridge CLI (tools/agent-bridge), over mutual
 * TLS 1.3 (docs/architecture/02 §9). Both sides test against the JSON files in `shared/agent/testdata`.
 *
 * - A frame is a 4-byte big-endian length followed by that many bytes of UTF-8 JSON: one
 *   [AgentMessage], an object whose `type` names the kind. Frames longer than
 *   [AgentWire.MAX_FRAME_BYTES] end the connection.
 * - Pairing (`watch-audio-mcp pair`): the phone sends [PairRequest] with the one-time value from
 *   the QR code; the bridge answers [PairPending] (value accepted) or [ErrorMessage]. Both show
 *   the same confirmation code ([confirmationCode]); the phone sends [PairConfirm] once its user
 *   confirmed and the bridge answers [PairResult] once its user did too.
 * - Serving: the phone sends [Hello], the bridge answers [Hello]; then the bridge sends MCP
 *   JSON-RPC `tools/call` requests in [Rpc] and the phone answers each in an [Rpc] with the same
 *   JSON-RPC id. Either side may end with [Bye].
 *
 * The bridge answers MCP `initialize` and `tools/list` itself (the tool list is fixed,
 * `testdata/tools.json`), so only tool calls cross the network.
 */
object AgentWire {
    /** Version of this format; a [Hello] or [PairRequest] with another version is refused. */
    const val PROTOCOL_VERSION = 1

    /** Largest frame either side accepts (an audio clip of [AgentLimits.MAX_CLIP_MS] fits). */
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    /** JSON settings of the frames: unknown keys are ignored so newer peers can add fields. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun encode(message: AgentMessage): String = json.encodeToString(AgentMessage.serializer(), message)

    /** @throws IllegalArgumentException if [text] is not a known message. */
    fun decode(text: String): AgentMessage = json.decodeFromString(AgentMessage.serializer(), text)

    /** [message] as one frame: length prefix and UTF-8 JSON. */
    fun frame(message: AgentMessage): ByteArray {
        val body = encode(message).encodeToByteArray()
        require(body.size <= MAX_FRAME_BYTES) { "frame of ${body.size} bytes exceeds ${MAX_FRAME_BYTES}" }
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, 4)
        return out
    }
}

/** Who sends a [Hello]. */
@Serializable
enum class AgentRole {
    @SerialName("phone")
    PHONE,

    @SerialName("bridge")
    BRIDGE,
}

/** One frame's message (see [AgentWire]). */
@Serializable
sealed interface AgentMessage

/** First message of a serving connection, in both directions. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocol: Int,
    val role: AgentRole,
    /** Device or PC name, shown to the user. */
    val name: String,
    /** Sender software and version, for diagnostics (e.g. `watch-audio-mcp/0.1.0`). */
    val software: String,
) : AgentMessage

/** Phone → bridge: first message of a pairing connection. */
@Serializable
@SerialName("pair_request")
data class PairRequest(
    val protocol: Int,
    /** The one-time value from the QR code (base64url). */
    val token: String,
    /** The phone's name, shown in the bridge's list of paired phones. */
    val name: String,
) : AgentMessage

/** Bridge → phone: the one-time value is valid; both sides now show the confirmation code. */
@Serializable
@SerialName("pair_pending")
data object PairPending : AgentMessage

/** Phone → bridge: the user confirmed that both screens show the same code. */
@Serializable
@SerialName("pair_confirm")
data object PairConfirm : AgentMessage

/** Bridge → phone: pairing stored on the PC. */
@Serializable
@SerialName("pair_result")
data class PairResult(
    /** The PC's name, shown in the phone's list of pairings. */
    val name: String,
    /** TCP port the bridge listens on when an agent runs it; the phone keeps it with the QR's host. */
    val port: Int,
) : AgentMessage

/** An MCP JSON-RPC message: a `tools/call` request from the bridge or the phone's response. */
@Serializable
@SerialName("rpc")
data class Rpc(val message: JsonObject) : AgentMessage

/** A refusal; the sender closes the connection after it. [code] is one of [AgentErrorCode]. */
@Serializable
@SerialName("error")
data class ErrorMessage(val code: String, val message: String) : AgentMessage

/** Orderly end of a connection; [reason] is one of [ByeReason]. */
@Serializable
@SerialName("bye")
data class Bye(val reason: String) : AgentMessage

/** [ErrorMessage.code] values. */
object AgentErrorCode {
    const val UNSUPPORTED_PROTOCOL = "unsupported_protocol"

    /** The pairing value is unknown or was already used. */
    const val PAIRING_INVALID = "pairing_invalid"

    /** The pairing value is older than its validity (5 minutes). */
    const val PAIRING_EXPIRED = "pairing_expired"

    /** A user declined the confirmation code (or did not answer in time). */
    const val PAIRING_REJECTED = "pairing_rejected"

    /** A message that is not valid at this point of the conversation. */
    const val BAD_MESSAGE = "bad_message"
}

/** [Bye.reason] values. */
object ByeReason {
    /** The user turned agent access off on the phone (or it timed out). */
    const val ACCESS_OFF = "access_off"

    /** The pairing was revoked on the phone or on the PC. */
    const val REVOKED = "revoked"

    /** The bridge or the app is shutting down. */
    const val SHUTDOWN = "shutdown"
}

/**
 * Splits a byte stream into frames. Feed whatever the transport delivered; complete frames come
 * out in order.
 */
class FrameDecoder(private val maxFrameBytes: Int = AgentWire.MAX_FRAME_BYTES) {
    private var buffer = ByteArray(0)

    /**
     * @return the messages completed by these bytes.
     * @throws FrameException on a frame over the limit or one that is not a known message.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<AgentMessage> {
        buffer += bytes.copyOfRange(offset, offset + length)
        val messages = mutableListOf<AgentMessage>()
        while (buffer.size >= 4) {
            val size = ((buffer[0].toInt() and 0xff) shl 24) or ((buffer[1].toInt() and 0xff) shl 16) or
                ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
            if (size < 0 || size > maxFrameBytes) throw FrameException("frame of $size bytes exceeds $maxFrameBytes")
            if (buffer.size < 4 + size) break
            val body = buffer.copyOfRange(4, 4 + size)
            buffer = buffer.copyOfRange(4 + size, buffer.size)
            messages += try {
                AgentWire.decode(body.decodeToString(throwOnInvalidSequence = true))
            } catch (e: Exception) {
                throw FrameException("not a message: ${e.message}")
            }
        }
        return messages
    }
}

class FrameException(message: String) : Exception(message)
