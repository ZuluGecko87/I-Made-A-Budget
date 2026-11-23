package com.z.imab

import com.z.imab.receipt.OcrLine
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Receipt photos on a computer: there's no camera, so they're image files (from the phone, a
 * scanner or an emailed receipt). Text is read on the computer: with the Mac's own text
 * recognition (Vision, through osascript), or Tesseract where it's installed.
 */
internal object DesktopReceipts {
    /** The longest side receipt photos are kept at: sharp enough to read, small enough to store. */
    const val MAX_SIDE = 1600

    val imageExtensions = listOf("jpg", "jpeg", "png", "heic", "heif", "tif", "tiff", "gif", "bmp", "webp")

    /** [file] as a JPEG the right way up and scaled down. Throws with a message if it can't be read. */
    fun normalize(file: File): ByteArray {
        val original = file.readBytes()
        var source = file
        var temp: File? = null
        try {
            var image = runCatching { ImageIO.read(file) }.getOrNull()
            // HEIC (iPhone photos) and other formats Java can't read: convert with the Mac's sips.
            if (image == null && isMac) {
                temp = File.createTempFile("imab-receipt", ".png")
                val p = ProcessBuilder("/usr/bin/sips", "-s", "format", "png", file.absolutePath, "--out", temp.absolutePath)
                    .redirectErrorStream(true).start()
                p.inputStream.readBytes()
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    image = ImageIO.read(temp)
                    source = temp
                }
            }
            image ?: error("Can't read ${file.name}. Use a JPEG or PNG photo of the receipt.")
            val oriented = rotate(image, if (source == file) exifOrientation(original) else 1)
            return encodeJpeg(scale(oriented, MAX_SIDE))
        } finally {
            temp?.delete()
        }
    }

    private fun scale(image: BufferedImage, maxSide: Int): BufferedImage {
        val factor = minOf(1.0, maxSide.toDouble() / max(image.width, image.height))
        val w = (image.width * factor).roundToInt().coerceAtLeast(1)
        val h = (image.height * factor).roundToInt().coerceAtLeast(1)
        // Always redrawn onto white RGB: JPEG has no transparency.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.drawImage(image, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.8f
            }
            writer.write(null, IIOImage(image, null, null), param)
            writer.dispose()
        }
        return out.toByteArray()
    }

    /** Turns a photo the way its camera recorded (EXIF orientation 1–8). */
    private fun rotate(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val swap = orientation >= 5
        val t = AffineTransform()
        when (orientation) {
            2 -> { t.translate(w, 0.0); t.scale(-1.0, 1.0) }
            3 -> { t.translate(w, h); t.rotate(Math.PI) }
            4 -> { t.translate(0.0, h); t.scale(1.0, -1.0) }
            5 -> { t.rotate(Math.PI / 2); t.scale(1.0, -1.0) }
            6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
        }
        val out = BufferedImage(if (swap) image.height else image.width, if (swap) image.width else image.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(image, t, null)
        g.dispose()
        return out
    }

    /** The EXIF orientation of a JPEG, or 1 (as is). */
    internal fun exifOrientation(b: ByteArray): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
        var i = 2
        while (i + 4 < b.size && u8(i) == 0xFF) {
            val marker = u8(i + 1)
            val len = (u8(i + 2) shl 8) or u8(i + 3)
            if (marker == 0xE1 && i + 10 < b.size && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val tiff = i + 10
                if (tiff + 8 > b.size) return 1
                val little = u8(tiff) == 'I'.code
                fun u16(at: Int) = if (little) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)
                fun u32(at: Int) = if (little) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
                val ifd = tiff + u32(tiff + 4)
                if (ifd + 2 > b.size) return 1
                val count = u16(ifd)
                for (e in 0 until count) {
                    val entry = ifd + 2 + e * 12
                    if (entry + 12 > b.size) return 1
                    if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
                }
                return 1
            }
            if (marker == 0xDA) return 1
            i += 2 + len
        }
        return 1
    }

    // ------------------------------------------------------------------ text recognition

    /** Mac: Vision's text recognition, run with osascript so nothing needs installing. */
    private val visionScript = """
        ObjC.import('Vision');
        function run(argv) {
          const url = ${'$'}.NSURL.fileURLWithPath(argv[0]);
          const req = ${'$'}.VNRecognizeTextRequest.alloc.init;
          req.recognitionLevel = 0;
          req.usesLanguageCorrection = false;
          const handler = ${'$'}.VNImageRequestHandler.alloc.initWithURLOptions(url, ${'$'}({}));
          if (!handler.performRequestsError(${'$'}([req]), null)) return '';
          const results = req.results;
          const out = [];
          for (let i = 0; i < results.count; i++) {
            const o = results.objectAtIndex(i);
            const c = o.topCandidates(1).objectAtIndex(0);
            const b = o.boundingBox;
            out.push([b.origin.x, 1 - b.origin.y - b.size.height, b.size.width, b.size.height, c.string.js.replace(/[\t\n]/g, ' ')].join('\t'));
          }
          return out.join('\n');
        }
    """.trimIndent()

    private val tesseractPaths = listOf(
        "/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract", "/usr/bin/tesseract",
        "C:\\Program Files\\Tesseract-OCR\\tesseract.exe", "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe",
    )

    /** The text in [jpeg], or null if this computer can't read text from images. */
    fun readText(jpeg: ByteArray): List<OcrLine>? {
        val image = File.createTempFile("imab-receipt", ".jpg")
        try {
            image.writeBytes(jpeg)
            if (isMac) visionText(image)?.let { return it }
            val tesseract = tesseractPaths.map(::File).firstOrNull { it.canExecute() } ?: return null
            return tesseractText(tesseract, image)
        } finally {
            image.delete()
        }
    }

    private fun run(vararg command: String): String? {
        val p = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        return out.takeIf { p.exitValue() == 0 }
    }

    private fun visionText(image: File): List<OcrLine>? {
        val script = File.createTempFile("imab-ocr", ".js")
        try {
            script.writeText(visionScript)
            val out = runCatching { run("/usr/bin/osascript", "-l", "JavaScript", script.absolutePath, image.absolutePath) }.getOrNull() ?: return null
            return OcrLine.parseTabbed(out)
        } finally {
            script.delete()
        }
    }

    private fun tesseractText(tesseract: File, image: File): List<OcrLine>? {
        val out = runCatching { run(tesseract.absolutePath, image.absolutePath, "stdout", "tsv") }.getOrNull() ?: return null
        return parseTesseract(out)
    }

    /** Tesseract's TSV: one word per row; words are put back together into lines. */
    internal fun parseTesseract(tsv: String): List<OcrLine> {
        var pageW = 1f
        var pageH = 1f
        val lines = LinkedHashMap<String, MutableList<List<String>>>()
        for (row in tsv.lines().drop(1)) {
            val c = row.split('\t')
            if (c.size < 10) continue
            if (c[0] == "1") { pageW = c[8].toFloatOrNull() ?: 1f; pageH = c[9].toFloatOrNull() ?: 1f }
            if (c[0] != "5" || c.size < 12 || c[11].isBlank()) continue
            lines.getOrPut("${c[2]}-${c[3]}-${c[4]}") { mutableListOf() } += c
        }
        return lines.values.map { words ->
            val left = words.minOf { it[6].toFloat() }
            val top = words.minOf { it[7].toFloat() }
            val right = words.maxOf { it[6].toFloat() + it[8].toFloat() }
            val bottom = words.maxOf { it[7].toFloat() + it[9].toFloat() }
            OcrLine(words.joinToString(" ") { it[11] }, left / pageW, top / pageH, (right - left) / pageW, (bottom - top) / pageH)
        }
    }
}
