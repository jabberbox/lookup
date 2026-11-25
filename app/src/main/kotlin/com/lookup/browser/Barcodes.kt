package com.lookup.browser

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Reads product barcodes (UPC, EAN, and the common shelf codes) from a camera frame's brightness plane. */
object Barcodes {
    private val formats = listOf(
        BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF,
    )

    /**
     * [luma] is the frame's Y plane as the camera hands it over: [rowStride] bytes per row, of which the first
     * [width] are picture. Returns the barcode's digits, or null when the frame has none.
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        // The last row of a camera buffer can be short; pad so nothing reads past the end.
        val data = if (luma.size >= rowStride * height) luma else luma.copyOf(rowStride * height)
        readUpright(data, rowStride, width, height)?.let { return it }
        // A barcode held upright in a landscape frame lies on its side: turn the frame a quarter and look again.
        val turned = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) turned[x * height + (height - 1 - y)] = data[y * rowStride + x]
        return readUpright(turned, height, height, width)
    }

    private fun readUpright(data: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        val reader = MultiFormatReader()
        reader.setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to formats,
            DecodeHintType.TRY_HARDER to true,
        ))
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
    }
}
