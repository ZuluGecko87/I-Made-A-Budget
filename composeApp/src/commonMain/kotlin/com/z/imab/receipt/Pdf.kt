package com.z.imab.receipt

/**
 * Just enough PDF to write an expense report on every platform without a library: pages of text
 * in the built-in Helvetica fonts, lines, and JPEG photos (which PDF embeds as they are).
 */
internal class PdfDocument {
    private val objects = mutableListOf<ByteArray?>()
    private val pageIds = mutableListOf<Int>()
    private val pagesId = reserve()
    private val fontId = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    private val boldId = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")

    private fun reserve(): Int { objects += null; return objects.size }

    private fun set(id: Int, bytes: ByteArray) { objects[id - 1] = bytes }

    private fun add(text: String): Int = add(text.encodeToByteArray())

    private fun add(bytes: ByteArray): Int { objects += bytes; return objects.size }

    private fun stream(dict: String, data: ByteArray): ByteArray =
        "<< $dict /Length ${data.size} >>\nstream\n".encodeToByteArray() + data + "\nendstream".encodeToByteArray()

    /** Adds a JPEG for pages to draw; null if it isn't a JPEG this can read. */
    fun addJpeg(jpeg: ByteArray): PdfImage? {
        val info = JpegInfo.read(jpeg) ?: return null
        val space = when (info.components) {
            1 -> "/DeviceGray"
            4 -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]"
            else -> "/DeviceRGB"
        }
        val id = add(stream("/Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} /ColorSpace $space /BitsPerComponent 8 /Filter /DCTDecode", jpeg))
        return PdfImage(id, info.width, info.height)
    }

    fun addPage(page: PdfPage) {
        val content = add(stream("", latin1(page.content.toString())))
        val xobjects = page.images.joinToString(" ") { "/Im${it.id} ${it.id} 0 R" }
        pageIds += add(
            "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${PdfPage.WIDTH} ${PdfPage.HEIGHT}] " +
                "/Resources << /Font << /F1 $fontId 0 R /F2 $boldId 0 R >> /XObject << $xobjects >> >> /Contents $content 0 R >>"
        )
    }

    fun toByteArray(): ByteArray {
        set(pagesId, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>".encodeToByteArray())
        val catalog = add("<< /Type /Catalog /Pages $pagesId 0 R >>")
        val out = ByteBuilder()
        out.append("%PDF-1.4\n".encodeToByteArray())
        out.append(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val offsets = objects.mapIndexed { i, bytes ->
            val at = out.size
            out.append("${i + 1} 0 obj\n".encodeToByteArray())
            out.append(bytes ?: "null".encodeToByteArray())
            out.append("\nendobj\n".encodeToByteArray())
            at
        }
        val xref = out.size
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".encodeToByteArray())
        offsets.forEach { out.append("${it.toString().padStart(10, '0')} 00000 n \n".encodeToByteArray()) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root $catalog 0 R >>\nstartxref\n$xref\n%%EOF\n".encodeToByteArray())
        return out.toByteArray()
    }

    companion object {
        /** PDF's built-in fonts use Windows-1252: Latin-1 plus a few typographic marks; anything else shows as "?". */
        fun latin1(s: String): ByteArray = ByteArray(s.length) { i ->
            val c = s[i]
            when {
                c.code < 0x80 || c.code in 0xA0..0xFF -> c.code.toByte()
                else -> when (c) {
                    '‘' -> 0x91; '’' -> 0x92; '“' -> 0x93; '”' -> 0x94; '•' -> 0x95; '–' -> 0x96; '—' -> 0x97
                    '€' -> 0x80; '…' -> 0x85; '™' -> 0x99
                    else -> '?'.code
                }.toByte()
            }
        }
    }
}

internal class PdfImage(val id: Int, val width: Int, val height: Int)

/** One page's drawing, in points from the bottom-left corner. */
internal class PdfPage {
    val content = StringBuilder()
    val images = mutableListOf<PdfImage>()

    private fun n(v: Float): String {
        val r = kotlin.math.round(v * 100) / 100
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    fun text(x: Float, y: Float, s: String, size: Float = 10f, bold: Boolean = false, gray: Float = 0f) {
        if (s.isEmpty()) return
        val escaped = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\r", " ").replace("\n", " ")
        content.append("BT ${n(gray)} g /${if (bold) "F2" else "F1"} ${n(size)} Tf ${n(x)} ${n(y)} Td ($escaped) Tj ET\n")
    }

    /** Text ending at [right]. */
    fun textRight(right: Float, y: Float, s: String, size: Float = 10f, bold: Boolean = false) =
        text(right - Helvetica.width(s, size, bold), y, s, size, bold)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, gray: Float = 0.75f, width: Float = 0.5f) {
        content.append("${n(gray)} G ${n(width)} w ${n(x1)} ${n(y1)} m ${n(x2)} ${n(y2)} l S\n")
    }

    fun image(img: PdfImage, x: Float, y: Float, w: Float, h: Float) {
        images += img
        content.append("q ${n(w)} 0 0 ${n(h)} ${n(x)} ${n(y)} cm /Im${img.id} Do Q\n")
    }

    companion object {
        /** US Letter. */
        const val WIDTH = 612f
        const val HEIGHT = 792f
    }
}

/** Approximate Helvetica widths, for lining up columns and cutting long text short. */
internal object Helvetica {
    private const val NARROW = "iljtfrI.,;:!|'`()[] "

    fun width(s: String, size: Float, bold: Boolean = false): Float {
        var em = 0f
        for (c in s) {
            em += when {
                c.isDigit() || c == '$' -> 0.556f
                c == ' ' -> 0.278f
                c == '-' -> 0.333f
                c in NARROW -> 0.28f
                c == 'm' || c == 'w' || c == 'M' || c == 'W' -> 0.85f
                c.isUpperCase() -> 0.68f
                else -> 0.53f
            }
        }
        return em * size * (if (bold) 1.06f else 1f)
    }

    /** [s] cut to fit [maxWidth], ending in "…" when shortened. */
    fun fit(s: String, maxWidth: Float, size: Float, bold: Boolean = false): String {
        if (width(s, size, bold) <= maxWidth) return s
        var end = s.length
        while (end > 0 && width(s.substring(0, end) + "…", size, bold) > maxWidth) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    /** [s] broken into lines that fit [maxWidth]. */
    fun wrap(s: String, maxWidth: Float, size: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in s.split(' ').filter { it.isNotEmpty() }) {
            val next = if (current.isEmpty()) word else "$current $word"
            if (width(next, size) <= maxWidth || current.isEmpty()) current = next else { lines += current; current = word }
        }
        if (current.isNotEmpty()) lines += current
        return lines.map { fit(it, maxWidth, size) }
    }
}

/** Size and colour channels of a JPEG, from its frame header. */
internal class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        fun read(b: ByteArray): JpegInfo? {
            fun u(i: Int) = b[i].toInt() and 0xFF
            if (b.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return null
            var i = 2
            while (i + 9 < b.size) {
                if (u(i) != 0xFF) { i++; continue }
                val marker = u(i + 1)
                if (marker == 0xFF) { i++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                val length = (u(i + 2) shl 8) or u(i + 3)
                // Start-of-frame markers, except DHT (C4), JPG (C8) and DAC (CC).
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val height = (u(i + 5) shl 8) or u(i + 6)
                    val width = (u(i + 7) shl 8) or u(i + 8)
                    val comps = u(i + 9)
                    return if (width > 0 && height > 0) JpegInfo(width, height, comps) else null
                }
                if (marker == 0xDA) return null
                i += 2 + length
            }
            return null
        }
    }
}

/** A growable byte buffer (common code has no ByteArrayOutputStream). */
internal class ByteBuilder {
    private var buf = ByteArray(64 * 1024)
    var size = 0
        private set

    fun append(bytes: ByteArray) {
        if (size + bytes.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + bytes.size))
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
