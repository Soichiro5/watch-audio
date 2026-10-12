package app.watchaudio.core.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The crypto primitives against published test vectors, on every platform (#40). */
class CryptoTest {
    @Test
    fun hmacMatchesRfc4231() {
        // RFC 4231 test case 2.
        val mac = HmacSha256("Jefe".encodeToByteArray())
        mac.update("what do ya want ".encodeToByteArray())
        mac.update("for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.doFinal().hex())
        // Reusable: the next message starts fresh.
        mac.update("what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.doFinal().hex())
        // RFC 4231 test case 1, with an offset into a larger array.
        val data = "xxHi Theryy".encodeToByteArray()
        val key1 = HmacSha256(ByteArray(20) { 0x0b })
        key1.update(data, 2, 7)
        key1.update("e".encodeToByteArray())
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", key1.doFinal().hex())
    }

    @Test
    fun aesCtrMatchesSp80038a() {
        // NIST SP 800-38A F.5.5 (CTR-AES256.Encrypt); the counter's low bytes carry over (…feff -> …ff00).
        val ctr = Aes256Ctr("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".unhex())
        val iv = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff".unhex()
        val plain = (
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
            ).unhex()
        val expected = "601ec313775789a5b7a7f504bbf3d228f443e3ca4d62b59aca84e990cacaf5c5" +
            "2b0930daa23de94ce87017ba2d84988ddfc9c58db67aada613c2dd08457941a6"
        val out = ByteArray(plain.size + 3)
        ctr.process(iv, plain, 0, plain.size, out, 3)
        assertEquals(expected, out.copyOfRange(3, out.size).hex())
        // Decrypting is the same operation; a partial block at the end works too.
        val back = ByteArray(61)
        ctr.process(iv, out, 3, 61, back, 0)
        assertContentEquals(plain.copyOf(61), back)
    }

    @Test
    fun pbkdf2MatchesRfc7914() {
        // RFC 7914 §11 (PBKDF2-HMAC-SHA256): two output blocks, then many iterations.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64).hex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            pbkdf2HmacSha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64).hex(),
        )
        // A prefix of the output does not depend on the requested length.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605",
            pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 16).hex(),
        )
    }

    @Test
    fun nfcComposesJapaneseVoicedMarks() {
        val decomposed = "がパ" // か + ゛, ハ + ゜
        assertEquals("がパ", decomposed.toNfc()) // が, パ
        assertEquals("パスフレーズ abc", "パスフレーズ abc".toNfc())
    }

    @Test
    fun randomBytesDiffer() {
        val a = secureRandomBytes(32)
        assertEquals(32, a.size)
        assertNotEquals(a.hex(), secureRandomBytes(32).hex())
        assertEquals(0, secureRandomBytes(0).size)
    }
}

private fun ByteArray.hex(): String = toHex()

private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
