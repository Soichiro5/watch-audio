package app.watchaudio.core.agent

/**
 * A minimal self-signed X.509 v3 certificate (DER) for an ECDSA P-256 key, for the mutual TLS
 * between the phone and the bridge. Nobody checks it against a certificate authority: each side
 * pins the other's [keyFingerprint], so the certificate only carries the public key in the form
 * TLS stacks need. Where the platform cannot make one itself (iOS; Android Keystore does), the
 * app builds it here and signs it with its own key ([sign] gets the DER TBSCertificate and
 * returns a DER ECDSA-SHA256 signature).
 *
 * Valid from 2020-01-01 until 9999-12-31 (RFC 5280's "no well-defined expiration"), no extensions.
 * The bridge builds the same shape in tools/agent-bridge/src/certificate.ts.
 */
object SelfSignedCertificate {
    fun build(
        subjectPublicKeyInfo: ByteArray,
        commonName: String,
        serial: ByteArray,
        sign: (tbsCertificate: ByteArray) -> ByteArray,
    ): ByteArray {
        require(serial.isNotEmpty() && serial.size <= 20) { "serial must be 1-20 bytes" }
        val name = sequence(set(sequence(OID_COMMON_NAME, tlv(0x0c, commonName.encodeToByteArray()))))
        val tbs = sequence(
            tlv(0xa0, integer(byteArrayOf(2))), // [0] version v3
            integer(serial),
            ECDSA_WITH_SHA256,
            name,
            sequence(tlv(0x17, NOT_BEFORE.encodeToByteArray()), tlv(0x18, NOT_AFTER.encodeToByteArray())),
            name,
            subjectPublicKeyInfo,
        )
        val signature = sign(tbs)
        return sequence(tbs, ECDSA_WITH_SHA256, tlv(0x03, byteArrayOf(0) + signature))
    }

    /** UTCTime 2020-01-01T00:00:00Z. */
    private const val NOT_BEFORE = "200101000000Z"

    /** GeneralizedTime 9999-12-31T23:59:59Z. */
    private const val NOT_AFTER = "99991231235959Z"

    /** AlgorithmIdentifier ecdsa-with-SHA256 (1.2.840.10045.4.3.2), no parameters. */
    private val ECDSA_WITH_SHA256 = sequence(byteArrayOf(0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x04, 0x03, 0x02))

    /** OID id-at-commonName (2.5.4.3). */
    private val OID_COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    private fun sequence(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    /** A DER INTEGER of the unsigned big-endian [value] (leading zeros dropped, sign byte added). */
    private fun integer(value: ByteArray): ByteArray {
        var start = 0
        while (start < value.size - 1 && value[start] == 0.toByte()) start++
        val trimmed = value.copyOfRange(start, value.size)
        val content = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        return tlv(0x02, content)
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        size < 0x10000 -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        else -> byteArrayOf(0x83.toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
    }
}
