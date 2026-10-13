package app.watchaudio.core.agent

import app.watchaudio.core.platform.Sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * What the QR code of `watch-audio-mcp pair` carries (docs/research/agent-access.md §4.3):
 * `watchaudio-mcp://pair?v=1&n=<PC name>&fp=<key fingerprint>&a=<host:port>[,<host:port>...]&t=<one-time value>&e=<expiry>`.
 * The phone pins [fingerprint] when it connects to one of [addresses] and sends [token] in its
 * [PairRequest].
 */
data class PairingUri(
    val version: Int,
    /** The PC's name (shown while pairing). */
    val name: String,
    /** [keyFingerprint] of the bridge's public key. */
    val fingerprint: String,
    /** `host:port` the bridge listens on for this pairing, IPv6 hosts in brackets. */
    val addresses: List<String>,
    /** One-time value (base64url of 16 random bytes). */
    val token: String,
    /** When [token] expires (epoch ms; the bridge enforces it, this is for the phone's UI). */
    val expiresAt: Long,
) {
    fun toUri(): String = buildString {
        append(SCHEME).append("?v=").append(version)
        append("&n=").append(percentEncode(name))
        append("&fp=").append(fingerprint)
        append("&a=").append(addresses.joinToString(",") { percentEncode(it) })
        append("&t=").append(token)
        append("&e=").append(expiresAt)
    }

    companion object {
        const val SCHEME = "watchaudio-mcp://pair"

        /** @throws IllegalArgumentException if [uri] is not a pairing URI of a known version. */
        fun parse(uri: String): PairingUri {
            val trimmed = uri.trim()
            require(trimmed.startsWith("$SCHEME?")) { "not a watch-audio-mcp pairing URI" }
            val params = trimmed.substringAfter('?').split('&').filter { it.isNotEmpty() }.associate { part ->
                val key = part.substringBefore('=')
                key to part.substringAfter('=', "")
            }
            fun param(key: String): String = requireNotNull(params[key]) { "pairing URI lacks '$key'" }
            val version = param("v").toIntOrNull()
            require(version == AgentWire.PROTOCOL_VERSION) { "unsupported pairing URI version ${param("v")}" }
            val fingerprint = param("fp")
            require(fingerprintBytes(fingerprint) != null) { "bad fingerprint" }
            val token = param("t")
            require(decodeBase64Url(token)?.size == TOKEN_BYTES) { "bad pairing value" }
            val addresses = param("a").split(',').filter { it.isNotEmpty() }.map { percentDecode(it) }
            require(addresses.isNotEmpty() && addresses.all { splitHostPort(it) != null }) { "bad address list" }
            return PairingUri(
                version = version!!,
                name = percentDecode(param("n")),
                fingerprint = fingerprint,
                addresses = addresses,
                token = token,
                expiresAt = requireNotNull(param("e").toLongOrNull()) { "bad expiry" },
            )
        }

        /** Size of the one-time value in bytes. */
        const val TOKEN_BYTES = 16

        /** How long a pairing value is valid. */
        const val VALIDITY_MS = 5 * 60_000L
    }
}

/** `host:port` → host (without IPv6 brackets) and port, or null if malformed. */
fun splitHostPort(address: String): Pair<String, Int>? {
    val host: String
    val portText: String
    if (address.startsWith("[")) {
        val end = address.indexOf("]:")
        if (end < 0) return null
        host = address.substring(1, end)
        portText = address.substring(end + 2)
    } else {
        host = address.substringBeforeLast(':', "")
        portText = address.substringAfterLast(':', "")
        if (':' in host) return null
    }
    val port = portText.toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return host to port
}

/**
 * The key fingerprint both sides pin: base64url (no padding) of the SHA-256 of the public key's
 * DER SubjectPublicKeyInfo.
 */
fun keyFingerprint(subjectPublicKeyInfo: ByteArray): String = encodeBase64Url(sha256(subjectPublicKeyInfo))

/** The 32 bytes of a [keyFingerprint], or null if it is not one. */
fun fingerprintBytes(fingerprint: String): ByteArray? = decodeBase64Url(fingerprint)?.takeIf { it.size == 32 }

/**
 * The 6-digit code both screens show while pairing, so the user can tell that the phone talks
 * to their PC and the PC to their phone: the first 4 bytes (big-endian) of
 * SHA-256(`watch-audio-mcp pairing v1` ‖ 0x00 ‖ token ‖ bridge fingerprint ‖ phone fingerprint)
 * modulo 1 000 000, zero-padded. Token and fingerprints enter as their decoded bytes.
 */
fun confirmationCode(token: String, bridgeFingerprint: String, phoneFingerprint: String): String {
    val tokenBytes = requireNotNull(decodeBase64Url(token)) { "bad pairing value" }
    val bridge = requireNotNull(fingerprintBytes(bridgeFingerprint)) { "bad bridge fingerprint" }
    val phone = requireNotNull(fingerprintBytes(phoneFingerprint)) { "bad phone fingerprint" }
    val digest = sha256(CODE_LABEL.encodeToByteArray() + byteArrayOf(0) + tokenBytes + bridge + phone)
    val value = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
        ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
    return (value % 1_000_000).toString().padStart(6, '0')
}

private const val CODE_LABEL = "watch-audio-mcp pairing v1"

internal fun sha256(bytes: ByteArray): ByteArray {
    val sha = Sha256()
    sha.update(bytes)
    return sha.digestHex().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@OptIn(ExperimentalEncodingApi::class)
private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@OptIn(ExperimentalEncodingApi::class)
fun encodeBase64Url(bytes: ByteArray): String = base64Url.encode(bytes)

@OptIn(ExperimentalEncodingApi::class)
fun decodeBase64Url(text: String): ByteArray? = try {
    if (text.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) null else base64Url.decode(text)
} catch (e: IllegalArgumentException) {
    null
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:[]"

/** RFC 3986 percent-encoding of UTF-8 (unreserved characters, `:` and brackets kept). */
internal fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c in UNRESERVED) append(c) else append('%').append((byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
    }
}

/** Inverse of [percentEncode]; `+` stays a plus. @throws IllegalArgumentException on a bad escape. */
internal fun percentDecode(text: String): String {
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            require(i + 3 <= text.length) { "bad escape" }
            val hex = text.substring(i + 1, i + 3)
            out += requireNotNull(hex.toIntOrNull(16)) { "bad escape %$hex" }.toByte()
            i += 3
        } else {
            out += c.toString().encodeToByteArray().toList()
            i += 1
        }
    }
    return out.toByteArray().decodeToString()
}
