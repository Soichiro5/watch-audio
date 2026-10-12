package app.watchaudio.core.backup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** The encrypted container of backups (#40): round trips, and every kind of damage is detected. */
class BackupCipherTest {
    private val chunk = 1024

    /** Deterministic "random" bytes, so files can be compared byte for byte. */
    private val fixedRandom: (Int) -> ByteArray = { n -> ByteArray(n) { (it * 7 + n).toByte() } }

    private fun encrypt(plain: ByteArray, passphrase: String = PASS, finish: Boolean = true): ByteArray {
        val out = Buffer()
        val sink = BackupCipher.encrypt(out, passphrase, iterations = 10, chunkSize = chunk, random = fixedRandom)
        // Written in uneven pieces, as the ZIP writer's buffer does.
        var at = 0
        while (at < plain.size) {
            val n = minOf(plain.size - at, 700)
            sink.write(Buffer().apply { write(plain, at, at + n) }, n.toLong())
            at += n
        }
        if (finish) sink.finish()
        sink.close()
        return out.readByteArray()
    }

    private fun decrypt(file: ByteArray, passphrase: String = PASS): ByteArray {
        val source = Buffer().apply { write(file) }
        val header = BackupCipher.readHeader(source)
        val keys = BackupCipher.unlock(header, passphrase)
        val plain = Buffer()
        val decrypted = BackupCipher.decrypt(source, header, keys)
        while (decrypted.readAtMostTo(plain, 10_000) != -1L) {
            // Reads to the end.
        }
        return plain.readByteArray()
    }

    private fun data(size: Int) = ByteArray(size) { (it * 31 + 5).toByte() }

    @Test
    fun hkdfMatchesRfc5869() {
        // RFC 5869 test case 1.
        val okm = hkdfSha256(
            ByteArray(22) { 0x0b },
            ByteArray(13) { it.toByte() },
            ByteArray(10) { (0xf0 + it).toByte() },
            42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        )
    }

    @Test
    fun roundTripsAnyLength() {
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 5)) {
            val plain = data(size)
            val file = encrypt(plain)
            assertContentEquals(plain, decrypt(file), "size $size")
            // Header + one tag per chunk; a multiple of the chunk size ends with an empty final chunk.
            val chunks = size / chunk + 1
            assertEquals(HEADER_SIZE + size + chunks * BackupCipher.TAG_SIZE, file.size, "size $size")
        }
    }

    @Test
    fun sameInputsWriteTheSameBytes() = assertContentEquals(encrypt(data(2000)), encrypt(data(2000)))

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val plain = "会議は10時から。".repeat(100).encodeToByteArray()
        val file = encrypt(plain)
        val needle = "会議は".encodeToByteArray()
        assertTrue((0..file.size - needle.size).none { at -> needle.indices.all { file[at + it] == needle[it] } })
    }

    @Test
    fun wrongPassphraseFailsAtTheHeader() {
        val file = encrypt(data(3000))
        assertFailsWith<BackupException.WrongPassphrase> { decrypt(file, "wrong passphrase") }
        assertFailsWith<BackupException.WrongPassphrase> { decrypt(file, PASS.dropLast(1)) }
    }

    @Test
    fun passphraseIsNormalizedToNfc() {
        val composed = "がぎぐげご-パスフレーズ"
        val decomposed = "がぎぐげご-パスフレーズ"
        val file = encrypt(data(100), composed)
        assertContentEquals(data(100), decrypt(file, decomposed))
    }

    @Test
    fun everyTruncationIsDetected() {
        val file = encrypt(data(2 * chunk + 300))
        for (length in 0 until file.size) {
            val error = assertFailsWith<BackupException>("cut to $length bytes") { decrypt(file.copyOf(length)) }
            if (length == 0) assertTrue(error is BackupException.NotABackup) else if (length >= MAGIC_SIZE) assertTrue(error is BackupException.Corrupted, "$length: $error")
        }
    }

    @Test
    fun truncationAtAChunkBoundaryIsDetected() {
        // Exactly two full chunks of plaintext: the file ends with an empty final chunk.
        val file = encrypt(data(2 * chunk))
        val withoutFinal = file.copyOf(file.size - BackupCipher.TAG_SIZE)
        assertFailsWith<BackupException.Corrupted> { decrypt(withoutFinal) }
        val oneChunk = file.copyOf(HEADER_SIZE + chunk + BackupCipher.TAG_SIZE)
        assertFailsWith<BackupException.Corrupted> { decrypt(oneChunk) }
    }

    @Test
    fun everyFlippedByteIsDetected() {
        val file = encrypt(data(2 * chunk + 300))
        for (at in file.indices) {
            val damaged = file.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
            assertFailsWith<BackupException>("byte $at") { decrypt(damaged) }
        }
    }

    @Test
    fun appendedOrReorderedChunksAreDetected() {
        val file = encrypt(data(3 * chunk + 10))
        assertFailsWith<BackupException.Corrupted> { decrypt(file + byteArrayOf(0)) }
        assertFailsWith<BackupException.Corrupted> { decrypt(file + file.copyOfRange(HEADER_SIZE, file.size)) }
        val record = chunk + BackupCipher.TAG_SIZE
        val first = file.copyOfRange(HEADER_SIZE, HEADER_SIZE + record)
        val second = file.copyOfRange(HEADER_SIZE + record, HEADER_SIZE + 2 * record)
        val swapped = file.copyOf().also {
            second.copyInto(it, HEADER_SIZE)
            first.copyInto(it, HEADER_SIZE + record)
        }
        assertFailsWith<BackupException.Corrupted> { decrypt(swapped) }
        // A chunk dropped from the middle.
        val dropped = file.copyOfRange(0, HEADER_SIZE + record) + file.copyOfRange(HEADER_SIZE + 2 * record, file.size)
        assertFailsWith<BackupException.Corrupted> { decrypt(dropped) }
    }

    @Test
    fun anUnfinishedFileIsRejected() {
        val file = encrypt(data(2 * chunk + 300), finish = false)
        assertFailsWith<BackupException.Corrupted> { decrypt(file) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertFailsWith<BackupException.NotABackup> { decrypt("PK\u0003\u0004 a zip file".encodeToByteArray()) }
        val newer = encrypt(data(10)).also { it[MAGIC_SIZE + 1] = 2 } // format version 2
        assertFailsWith<BackupException.UnsupportedVersion> { decrypt(newer) }
    }

    private companion object {
        const val PASS = "correct horse battery staple"
        const val MAGIC_SIZE = 9

        /** magic, version, KDF, iterations, salt length + salt, cipher, chunk size, nonce, MAC. */
        const val HEADER_SIZE = MAGIC_SIZE + 2 + 1 + 4 + 1 + 16 + 1 + 4 + 16 + 32
    }
}
