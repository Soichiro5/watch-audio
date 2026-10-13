package app.watchaudio.android.agent

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads the pairing QR code (#184) from a photo of the PC's terminal, on the device with ZXing:
 * dark-on-light or light-on-dark (terminal themes), with the gaps a terminal leaves between
 * character rows. Plain JVM code, so it is tested off the device.
 */
object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** Smallest side worth decoding (a version 10 code with its quiet zone is ~65 modules). */
    private const val MIN_SIDE = 100

    /**
     * The text of the QR code in the ARGB [pixels] (row-major, [width] x [height]), or null if none
     * is found. Tries the picture as it is, then box-averaged to half and a third of its size: the
     * thin background lines a terminal leaves between character rows break ZXing's finder pattern
     * check, and averaging fills them in.
     */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        decodeAt(pixels, width, height)?.let { return it }
        for (factor in intArrayOf(2, 3)) {
            if (width / factor < MIN_SIDE || height / factor < MIN_SIDE) break
            decodeAt(downscale(pixels, width, height, factor), width / factor, height / factor)?.let { return it }
        }
        return null
    }

    /** Gray ARGB pixels, each the average luminance of a [factor] x [factor] block. */
    internal fun downscale(pixels: IntArray, width: Int, height: Int, factor: Int): IntArray {
        val w = width / factor
        val h = height / factor
        return IntArray(w * h) { i ->
            val x0 = (i % w) * factor
            val y0 = (i / w) * factor
            var sum = 0
            for (y in y0 until y0 + factor) {
                for (x in x0 until x0 + factor) {
                    val p = pixels[y * width + x]
                    sum += ((p shr 16 and 0xff) * 299 + (p shr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000
                }
            }
            val gray = sum / (factor * factor)
            (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
    }

    private fun decodeAt(pixels: IntArray, width: Int, height: Int): String? {
        val plain: LuminanceSource = RGBLuminanceSource(width, height, pixels)
        // Light modules on a dark background (a dark terminal theme) read as the inverted image.
        for (source in listOf(plain, InvertedLuminanceSource(plain))) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
                try {
                    return QRCodeReader().decode(bitmap, hints).text
                } catch (e: ReaderException) {
                    continue
                }
            }
        }
        return null
    }
}
