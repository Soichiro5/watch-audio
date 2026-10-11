package app.watchaudio.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256().digestHex())
        val abc = Sha256().apply { update("abc".encodeToByteArray()) }.digestHex()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc)
    }

    @Test
    fun incrementalEqualsOneShot() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val oneShot = Sha256().apply { update(data) }.digestHex()
        val incremental = Sha256().apply {
            update(data, 0, 70_000)
            update(data, 70_000, 130_000)
        }.digestHex()
        assertEquals(oneShot, incremental)
    }
}
