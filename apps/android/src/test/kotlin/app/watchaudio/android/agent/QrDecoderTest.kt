package app.watchaudio.android.agent

import app.watchaudio.core.agent.PairingUri
import app.watchaudio.core.agent.encodeBase64Url
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pairing QR code (#184) as a photo of a terminal: either theme, gaps between character rows. */
class QrDecoderTest {
    private val uri = PairingUri(
        1, "仕事用ノート", encodeBase64Url(ByteArray(32) { it.toByte() }), listOf("192.168.1.20:47823", "100.101.102.103:47823"),
        encodeBase64Url(ByteArray(16) { (it * 7).toByte() }), 1_760_000_300_000,
    ).toUri()

    @Test
    fun darkModulesOnALightTerminal() {
        val (pixels, width, height) = render(matrix(uri), dark = BLACK, light = WHITE)
        assertEquals(uri, QrDecoder.decode(pixels, width, height))
    }

    @Test
    fun lightModulesOnADarkTerminalWithGapsBetweenRows() {
        // A dark theme draws the "dark" modules in the light foreground colour, and the terminal
        // leaves a background line between character rows (two module rows each).
        val (pixels, width, height) = render(matrix(uri), dark = 0xFFE0E0E0.toInt(), light = 0xFF1E1E1E.toInt(), rowGap = true)
        assertEquals(uri, QrDecoder.decode(pixels, width, height))
    }

    @Test
    fun noCodeInThePicture() {
        val width = 200
        val height = 120
        val pixels = IntArray(width * height) { i -> if ((i / width / 10 + i % width / 10) % 2 == 0) WHITE else 0xFF808080.toInt() }
        assertNull(QrDecoder.decode(pixels, width, height))
    }

    /** Like the CLI's code (uqr, error correction L by default). */
    private fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0),
    )

    private fun render(matrix: BitMatrix, dark: Int, light: Int, rowGap: Boolean = false): Triple<IntArray, Int, Int> {
        val margin = 4
        val width = (matrix.width + 2 * margin) * SCALE
        val height = (matrix.height + 2 * margin) * SCALE
        val pixels = IntArray(width * height) { light }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val mx = x / SCALE - margin
                val my = y / SCALE - margin
                if (mx !in 0 until matrix.width || my !in 0 until matrix.height) continue
                val gap = rowGap && my % 2 == 1 && y % SCALE == SCALE - 1
                if (matrix[mx, my] && !gap) pixels[y * width + x] = dark
            }
        }
        return Triple(pixels, width, height)
    }

    private companion object {
        const val SCALE = 6
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
