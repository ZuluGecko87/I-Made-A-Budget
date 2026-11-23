package com.z.imab

import com.z.imab.receipt.JpegInfo
import com.z.imab.receipt.ReceiptParser
import kotlinx.datetime.LocalDate
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopReceiptsTest {
    /** A receipt drawn as a large PNG, as if photographed. */
    private fun receiptPng(): File {
        val lines = listOf(
            "STAPLES #0412", "88 Commerce Way", "Austin, TX 78701", "",
            "PRINTER PAPER        12.99", "PENS 12PK             8.49", "SUBTOTAL             21.48",
            "SALES TAX             1.77", "TOTAL               \$23.25", "", "MASTERCARD ************3307", "10/29/2026  09:15",
        )
        val img = BufferedImage(1200, 2400, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.BLACK
        g.font = Font(Font.MONOSPACED, Font.BOLD, 52)
        lines.forEachIndexed { i, l -> g.drawString(l, 60, 160 + i * 120) }
        g.dispose()
        return File.createTempFile("receipt", ".png").also { ImageIO.write(img, "png", it); it.deleteOnExit() }
    }

    @Test
    fun scalesDownToAJpeg() {
        val jpeg = DesktopReceipts.normalize(receiptPng())
        val info = JpegInfo.read(jpeg)!!
        assertEquals(1600, info.height)
        assertEquals(800, info.width)
        assertEquals(3, info.components)
    }

    @Test
    fun macReadsTheReceipt() {
        if (!isMac) return
        val jpeg = DesktopReceipts.normalize(receiptPng())
        val lines = DesktopReceipts.readText(jpeg)!!
        val scan = ReceiptParser.parse(lines, LocalDate(2026, 11, 23))
        assertEquals("Staples", scan.merchant)
        assertEquals(2325L, scan.total)
        assertEquals(LocalDate(2026, 10, 29), scan.date)
        assertEquals("3307", scan.cardLast4)
        assertEquals("Mastercard", scan.cardBrand)
    }

    @Test
    fun readsTesseractOutput() {
        val tsv = """
            level	page_num	block_num	par_num	line_num	word_num	left	top	width	height	conf	text
            1	1	0	0	0	0	0	0	800	1000	-1
            5	1	1	1	1	1	40	100	120	30	95	TOTAL
            5	1	1	1	1	2	500	102	100	28	93	${'$'}9.99
            5	1	1	1	2	1	40	200	300	30	90	Thanks
        """.trimIndent()
        val lines = DesktopReceipts.parseTesseract(tsv)
        assertEquals(listOf("TOTAL \$9.99", "Thanks"), lines.map { it.text })
        assertEquals(0.05f, lines[0].x)
        assertEquals(0.1f, lines[0].y)
    }

    @Test
    fun readsExifOrientation() {
        // SOI, then an APP1 Exif block (big-endian TIFF) with one entry: orientation 6.
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val app1 = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = app1.size + 2
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + app1 +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(6, DesktopReceipts.exifOrientation(jpeg))
        assertEquals(1, DesktopReceipts.exifOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test
    fun parsesVisionOutput() {
        val lines = com.z.imab.receipt.OcrLine.parseTabbed("0.1\t0.2\t0.3\t0.04\tTOTAL\n0.5\t0.2\t0.1\t0.04\t\$5.00\n")
        assertEquals(2, lines.size)
        assertTrue(ReceiptParser.rows(lines).contains("TOTAL  \$5.00"))
    }
}
