package com.lookup.browser

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodesTest {
    /** A frame the way the camera delivers it: brightness bytes, [pad] extra bytes at the end of every row. */
    private fun frame(code: String, format: BarcodeFormat, pad: Int, turned: Boolean): Triple<ByteArray, Int, Int> {
        val m = MultiFormatWriter().encode(code, format, 360, 120)
        val w = if (turned) m.height else m.width
        val h = if (turned) m.width else m.height
        val stride = w + pad
        val out = ByteArray(stride * h) { 0xFF.toByte() }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val black = m.get(x, y)
            val (px, py) = if (turned) y to x else x to y
            if (black) out[py * stride + px] = 0
        }
        return Triple(out, stride, h).let { Triple(it.first, w, h) }.also { }
    }

    private fun decode(code: String, format: BarcodeFormat, pad: Int, turned: Boolean): String? {
        val (bytes, w, h) = frame(code, format, pad, turned)
        return Barcodes.decode(bytes, w + pad, w, h)
    }

    @Test fun readsEan13() = assertEquals("5901234123457", decode("5901234123457", BarcodeFormat.EAN_13, 0, false))

    @Test fun readsWithRowPadding() = assertEquals("5901234123457", decode("5901234123457", BarcodeFormat.EAN_13, 24, false))

    @Test fun readsUpcA() = assertEquals("036000291452", decode("036000291452", BarcodeFormat.UPC_A, 8, false))

    @Test fun readsBarcodeLyingOnItsSide() = assertEquals("5901234123457", decode("5901234123457", BarcodeFormat.EAN_13, 16, true))

    @Test fun nothingInABlankFrame() {
        val blank = ByteArray(300 * 200) { 0x80.toByte() }
        assertNull(Barcodes.decode(blank, 300, 300, 200))
    }
}
