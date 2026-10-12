package app.watchaudio.core.backup

import app.watchaudio.core.platform.Aes256Ctr
import app.watchaudio.core.platform.HmacSha256
import app.watchaudio.core.platform.pbkdf2HmacSha256
import app.watchaudio.core.platform.secureRandomBytes
import app.watchaudio.core.platform.toNfc
import app.watchaudio.core.platform.wipe
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readTo

// The encrypted container of a backup file (#40); docs/architecture/02 §6.7 has the reasoning.
//
// header  = magic(9) version(u16) kdf(u8) iterations(u32) saltLength(u8) salt cipher(u8)
//           chunkSize(u32) fileNonce(16) headerMac(32)          — integers big-endian
// master  = PBKDF2-HMAC-SHA256(NFC(passphrase) as UTF-8, salt, iterations, 32 bytes)
// keys    = HKDF-SHA256(master, salt = fileNonce, info = "watch-audio backup v1 keys", 64 bytes)
//           -> encryption key (first 32), MAC key (last 32)
// headerMac = HMAC(macKey, header bytes before it)             — a wrong passphrase fails here
// chunk i = AES-256-CTR(encKey, iv = i (u64) || 0 (u64), plaintext) || HMAC(macKey, headerMac ||
//           i (u64) || final (u8) || ciphertext)
//
// Every chunk but the last carries exactly chunkSize plaintext bytes; the last one fewer (possibly
// none) and final = 1. Reordered, dropped, truncated or appended chunks therefore fail their MAC
// or leave the stream without a final chunk.

/** Why a backup file cannot be read. The UI shows a fixed text per type, never [message]. */
sealed class BackupException(message: String) : Exception(message) {
    /** Not a backup file of this app (wrong magic, or an unknown algorithm id). */
    class NotABackup(message: String) : BackupException(message)

    /** Written by a newer version of the app. */
    class UnsupportedVersion(message: String) : BackupException(message)

    /** The header's MAC does not match: the passphrase is wrong (or the header was damaged). */
    class WrongPassphrase : BackupException("wrong passphrase")

    /** Damaged, truncated or modified after it was written. */
    class Corrupted(message: String) : BackupException(message)
}

/** The cleartext header of a backup file: how to derive its keys. */
class BackupHeader internal constructor(
    val formatVersion: Int,
    /** PBKDF2 rounds; recorded so that later versions can raise them and still read older files. */
    val iterations: Int,
    internal val salt: ByteArray,
    val chunkSize: Int,
    internal val fileNonce: ByteArray,
    /** The header bytes before [mac]. */
    internal val authenticated: ByteArray,
    internal val mac: ByteArray,
) {
    /** True if [other] is byte for byte the same header (a file read twice did not change). */
    internal fun sameAs(other: BackupHeader): Boolean =
        authenticated.contentEquals(other.authenticated) && mac.contentEquals(other.mac)
}

/** Encryption and MAC keys of one backup file. Call [wipe] when done. */
class BackupKeys internal constructor(internal val encryption: ByteArray, internal val mac: ByteArray) {
    fun wipe() {
        encryption.wipe()
        mac.wipe()
    }
}

/** Reads and writes the encrypted container; see the format at the top of this file. */
object BackupCipher {
    /** PBKDF2 rounds of new backups (OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256). */
    const val DEFAULT_ITERATIONS = 600_000

    /** Plaintext bytes per authenticated chunk of new backups. */
    const val DEFAULT_CHUNK_SIZE = 64 * 1024

    const val FORMAT_VERSION = 1

    /** Shortest passphrase the app accepts (the UI also shows a strength hint). */
    const val MIN_PASSPHRASE_LENGTH = 8

    internal const val TAG_SIZE = 32
    internal val MAGIC = byteArrayOf(0x89.toByte(), 'W'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private const val KDF_PBKDF2_SHA256 = 1
    private const val CIPHER_AES256CTR_HMACSHA256 = 1
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 16
    private val KEY_INFO = "watch-audio backup v1 keys".encodeToByteArray()

    // Bounds for headers read from a file, so a crafted header cannot stall or exhaust the app.
    private const val MAX_ITERATIONS = 10_000_000
    private const val MIN_CHUNK_SIZE = 1024
    private const val MAX_CHUNK_SIZE = 4 * 1024 * 1024

    /**
     * Writes the header of a new backup to [out] and returns the sink that encrypts what follows.
     * Call [EncryptingSink.finish] after the last byte; without it the file has no final chunk
     * and can never be restored.
     *
     * @param random source of the salt and nonce; tests pass fixed bytes for reproducible files.
     */
    fun encrypt(
        out: RawSink,
        passphrase: String,
        iterations: Int = DEFAULT_ITERATIONS,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        random: (Int) -> ByteArray = ::secureRandomBytes,
    ): EncryptingSink {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) { "chunk size out of range" }
        val salt = random(SALT_SIZE)
        val nonce = random(NONCE_SIZE)
        val authenticated = Buffer().apply {
            write(MAGIC)
            writeShort(FORMAT_VERSION.toShort())
            writeByte(KDF_PBKDF2_SHA256.toByte())
            writeInt(iterations)
            writeByte(SALT_SIZE.toByte())
            write(salt)
            writeByte(CIPHER_AES256CTR_HMACSHA256.toByte())
            writeInt(chunkSize)
            write(nonce)
        }.readByteArray()
        val provisional = BackupHeader(FORMAT_VERSION, iterations, salt, chunkSize, nonce, authenticated, ByteArray(0))
        val keys = deriveKeys(provisional, passphrase)
        val mac = HmacSha256(keys.mac).apply { update(authenticated) }.doFinal()
        val header = BackupHeader(FORMAT_VERSION, iterations, salt, chunkSize, nonce, authenticated, mac)
        Buffer().apply {
            write(authenticated)
            write(mac)
            out.write(this, size)
        }
        return EncryptingSink(out, header, keys)
    }

    /**
     * Reads the header at the start of [source].
     * @throws BackupException.NotABackup, [BackupException.UnsupportedVersion] or
     *   [BackupException.Corrupted] (too short).
     */
    fun readHeader(source: Source): BackupHeader {
        val authenticated = Buffer()
        fun take(n: Int): ByteArray {
            if (!source.request(n.toLong())) {
                throw if (authenticated.size == 0L && source.exhausted()) {
                    BackupException.NotABackup("empty file")
                } else {
                    BackupException.Corrupted("header truncated")
                }
            }
            return source.readByteArray(n).also { authenticated.write(it) }
        }
        val magic = take(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw BackupException.NotABackup("magic")
        val version = take(2).u16()
        if (version > FORMAT_VERSION || version < 1) throw BackupException.UnsupportedVersion("version $version")
        if (take(1)[0].toInt() != KDF_PBKDF2_SHA256) throw BackupException.NotABackup("unknown KDF")
        val iterations = take(4).u32()
        if (iterations !in 1..MAX_ITERATIONS) throw BackupException.Corrupted("iterations $iterations")
        val saltSize = take(1)[0].toInt() and 0xFF
        if (saltSize !in SALT_SIZE..64) throw BackupException.Corrupted("salt size $saltSize")
        val salt = take(saltSize)
        if (take(1)[0].toInt() != CIPHER_AES256CTR_HMACSHA256) throw BackupException.NotABackup("unknown cipher")
        val chunkSize = take(4).u32()
        if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) throw BackupException.Corrupted("chunk size $chunkSize")
        val nonce = take(NONCE_SIZE)
        val bytes = authenticated.readByteArray()
        if (!source.request(TAG_SIZE.toLong())) throw BackupException.Corrupted("header truncated")
        val mac = source.readByteArray(TAG_SIZE)
        return BackupHeader(version, iterations, salt, chunkSize, nonce, bytes, mac)
    }

    /**
     * Derives the keys of [header] from [passphrase] (slow on purpose: [BackupHeader.iterations]
     * rounds of PBKDF2) and checks them against the header's MAC.
     * @throws BackupException.WrongPassphrase if they do not match.
     */
    fun unlock(header: BackupHeader, passphrase: String): BackupKeys {
        val keys = deriveKeys(header, passphrase)
        val expected = HmacSha256(keys.mac).apply { update(header.authenticated) }.doFinal()
        if (!constantTimeEquals(expected, header.mac)) {
            keys.wipe()
            throw BackupException.WrongPassphrase()
        }
        return keys
    }

    /** The plaintext after [header] in [source], authenticated chunk by chunk ([keys] from [unlock]). */
    fun decrypt(source: Source, header: BackupHeader, keys: BackupKeys): RawSource = DecryptingSource(source, header, keys)

    private fun deriveKeys(header: BackupHeader, passphrase: String): BackupKeys {
        val password = passphrase.toNfc().encodeToByteArray()
        require(password.isNotEmpty()) { "empty passphrase" }
        val master = pbkdf2HmacSha256(password, header.salt, header.iterations, 32)
        password.wipe()
        val okm = hkdfSha256(master, header.fileNonce, KEY_INFO, 64)
        master.wipe()
        return BackupKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64)).also { okm.wipe() }
    }

    /** Counter block of chunk [index]: the index in the high 64 bits, so chunks never share key stream. */
    internal fun iv(index: Long): ByteArray = ByteArray(16).also { index.writeBe(it, 0) }

    internal fun chunkTag(mac: HmacSha256, header: BackupHeader, index: Long, final: Boolean, data: ByteArray, length: Int): ByteArray {
        mac.update(header.mac)
        mac.update(ByteArray(8).also { index.writeBe(it, 0) })
        mac.update(byteArrayOf(if (final) 1 else 0))
        mac.update(data, 0, length)
        return mac.doFinal()
    }
}

/**
 * Encrypts everything written to it into chunks after the header [BackupCipher.encrypt] wrote.
 * [flush] passes on whole chunks only; [close] does not close the destination (its owner does).
 */
class EncryptingSink internal constructor(
    private val out: RawSink,
    private val header: BackupHeader,
    private val keys: BackupKeys,
) : RawSink {
    private val aes = Aes256Ctr(keys.encryption)
    private val mac = HmacSha256(keys.mac)
    private val plain = ByteArray(header.chunkSize)
    private val cipher = ByteArray(header.chunkSize)
    private var filled = 0
    private var index = 0L
    private var done = false

    override fun write(source: Buffer, byteCount: Long) {
        check(!done) { "the backup is finished or closed" }
        require(byteCount <= source.size) { "byteCount > source.size" }
        var left = byteCount
        while (left > 0) {
            val n = minOf(left, (plain.size - filled).toLong()).toInt()
            source.readTo(plain, filled, filled + n)
            filled += n
            left -= n
            // A full chunk is never the last one: finish() then writes an empty final chunk.
            if (filled == plain.size) emit(final = false)
        }
    }

    /** Writes the final chunk and flushes. Nothing can be written afterwards. */
    fun finish() {
        check(!done) { "the backup is finished or closed" }
        emit(final = true)
        done = true
        out.flush()
        keys.wipe()
        plain.wipe()
    }

    private fun emit(final: Boolean) {
        aes.process(BackupCipher.iv(index), plain, 0, filled, cipher, 0)
        val tag = BackupCipher.chunkTag(mac, header, index, final, cipher, filled)
        Buffer().apply {
            write(cipher, 0, filled)
            write(tag)
            out.write(this, size)
        }
        index++
        filled = 0
    }

    override fun flush() = out.flush()

    /** Stops without the final chunk (e.g. after a failure): such a file is rejected when read. */
    override fun close() {
        if (done) return
        done = true
        keys.wipe()
        plain.wipe()
    }
}

/** Decrypts and authenticates chunk by chunk; any failure is a [BackupException.Corrupted]. */
private class DecryptingSource(source: Source, private val header: BackupHeader, keys: BackupKeys) : RawSource {
    private val input = source
    private val aes = Aes256Ctr(keys.encryption)
    private val mac = HmacSha256(keys.mac)
    private val record = ByteArray(header.chunkSize + BackupCipher.TAG_SIZE)
    private val plain = Buffer()
    private var index = 0L
    private var ended = false

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        while (plain.exhausted()) {
            if (ended) return -1
            next()
        }
        return plain.readAtMostTo(sink, byteCount)
    }

    private fun next() {
        var read = 0
        while (read < record.size) {
            val n = input.readAtMostTo(record, read, record.size)
            if (n == -1) break
            read += n
        }
        if (read < BackupCipher.TAG_SIZE) throw BackupException.Corrupted("truncated at chunk $index")
        // A full record is a middle chunk; a shorter one must be the final chunk.
        val final = read < record.size
        val length = read - BackupCipher.TAG_SIZE
        val expected = BackupCipher.chunkTag(mac, header, index, final, record, length)
        if (!constantTimeEquals(expected, record.copyOfRange(length, read))) {
            throw BackupException.Corrupted("chunk $index failed authentication")
        }
        val out = ByteArray(length)
        aes.process(BackupCipher.iv(index), record, 0, length, out, 0)
        plain.write(out)
        index++
        if (final) ended = true
    }

    override fun close() {}
}

/** HKDF (RFC 5869) with HMAC-SHA-256. */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 1..255 * 32) { "length out of range" }
    val prk = HmacSha256(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32)).apply { update(ikm) }.doFinal()
    val mac = HmacSha256(prk)
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var at = 0
    var counter = 1
    while (at < length) {
        mac.update(previous)
        mac.update(info)
        mac.update(byteArrayOf(counter.toByte()))
        previous = mac.doFinal()
        val n = minOf(previous.size, length - at)
        previous.copyInto(out, at, 0, n)
        at += n
        counter++
    }
    prk.wipe()
    return out
}

private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

private fun Long.writeBe(target: ByteArray, at: Int) {
    for (i in 0 until 8) target[at + i] = (this ushr (56 - 8 * i)).toByte()
}

private fun ByteArray.u16(): Int = ((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)

private fun ByteArray.u32(): Int =
    ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)
