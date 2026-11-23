package com.z.imab.receipt

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.math.max

/**
 * A piece of text the phone or computer found in a receipt photo, with its box as fractions of the
 * image (x from the left, y from the top). Text recognition often returns a label and its amount
 * as separate pieces ("TOTAL" … "$10.24"); the boxes put them back on one row. Without boxes
 * (all zero) each piece is taken as its own row, in order.
 */
data class OcrLine(val text: String, val x: Float = 0f, val y: Float = 0f, val w: Float = 0f, val h: Float = 0f) {
    companion object {
        /**
         * Apple's text recognition, as the Mac (osascript) and iPhone (Swift) hand it over: one line
         * per piece, "x, y, width, height, text" separated by tabs.
         */
        fun parseTabbed(out: String): List<OcrLine> = out.lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 5)
            if (parts.size < 5) return@mapNotNull null
            val (x, y, w, h) = parts.take(4).map { it.toFloatOrNull() ?: return@mapNotNull null }
            OcrLine(parts[4], x, y, w, h)
        }
    }
}

/** What could be read from a receipt. Anything not found is null. */
data class ReceiptScan(
    val merchant: String? = null,
    /** In cents, positive. */
    val total: Long? = null,
    val date: LocalDate? = null,
    val cardLast4: String? = null,
    /** "Visa", "Mastercard", "Amex" or "Discover". */
    val cardBrand: String? = null,
) {
    val isEmpty: Boolean get() = merchant == null && total == null && date == null && cardLast4 == null
}

/**
 * Reads the merchant, total, date and card from the text of a receipt. Receipts differ a lot, so
 * each field is a best guess the person checks before saving.
 */
object ReceiptParser {
    fun parse(lines: List<OcrLine>, today: LocalDate): ReceiptScan {
        val rows = rows(lines)
        val card = card(rows)
        return ReceiptScan(
            merchant = merchant(rows),
            total = total(rows),
            date = date(rows, today),
            cardLast4 = card.first,
            cardBrand = card.second,
        )
    }

    /** The receipt's text as rows, top to bottom, with the pieces of each row left to right. */
    fun rows(lines: List<OcrLine>): List<String> {
        val pieces = lines.filter { it.text.isNotBlank() }
        if (pieces.all { it.h == 0f }) return pieces.map { it.text.trim() }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (p in pieces.sortedBy { it.y + it.h / 2 }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val center = row.sumOf { (it.y + it.h / 2).toDouble() } / row.size
                val height = row.maxOf { it.h }
                if (abs((p.y + p.h / 2) - center) < 0.5 * max(height, p.h)) {
                    row += p
                    continue
                }
            }
            rows += mutableListOf(p)
        }
        return rows.map { r -> r.sortedBy { it.x }.joinToString("  ") { it.text.trim() } }
    }

    // ------------------------------------------------------------------ total

    /** "$1,234.56", "12.34", "12,34" (a comma for cents too). Not part of a longer number. */
    private val moneyRegex = Regex("""(?<![\d.,])-?\$?\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})(?![\d%])""")

    internal fun amounts(row: String): List<Long> = moneyRegex.findAll(row).mapNotNull { m ->
        if (m.value.trimStart().startsWith("-")) return@mapNotNull null
        val whole = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
        whole * 100 + m.groupValues[2].toLong()
    }.toList()

    /** Rows naming the amount paid, best first. */
    private val totalWords = listOf(
        "grand total", "total due", "amount due", "balance due", "total amount", "amount paid", "total paid",
        "order total", "total charged", "total sale", "sale total", "total",
    )

    /** "Subtotal", "Total tax", "Total savings"… aren't what was paid. */
    private val notTotal = listOf(
        "subtotal", "sub total", "sub-total", "tax", "saving", "saved", "discount", "item", "qty", "quantity",
        "point", "reward", "before", "tip guide", "suggested",
    )

    private val paymentWords = listOf("visa", "mastercard", "master card", "amex", "american express", "discover", "debit", "credit", "card", "charged", "tender", "payment")

    private fun total(rows: List<String>): Long? {
        val lower = rows.map { it.lowercase() }
        // A total row's amount, or the next row's when the amount is printed below the label.
        fun amountAt(i: Int): Long? = amounts(rows[i]).lastOrNull()
            ?: rows.getOrNull(i + 1)?.takeIf { next -> lower[i + 1].none { it.isLetter() } || amounts(next).size == 1 }
                ?.let { amounts(it).lastOrNull() }
        for (word in totalWords) {
            val found = lower.indices.filter { i ->
                lower[i].contains(word) && notTotal.none { lower[i].contains(it) }
            }.mapNotNull(::amountAt)
            // With several (e.g. before and after the tip), the largest is what was paid.
            found.maxOrNull()?.let { return it }
        }
        lower.indices.filter { i -> paymentWords.any { lower[i].contains(it) } && !lower[i].contains("change") }
            .mapNotNull { amounts(rows[it]).lastOrNull() }.maxOrNull()?.let { return it }
        return lower.indices.filter { !lower[it].contains("change") && !lower[it].contains("cash") }
            .flatMap { amounts(rows[it]) }.maxOrNull()
    }

    // ------------------------------------------------------------------ date

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val numericDate = Regex("""(?<!\d)(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{2,4})(?!\d)""")
    private val monthFirst = Regex("""(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{2,4})\b""")
    private val dayFirst = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?[\s\-]+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?[\s\-,]+(\d{2,4})\b""")

    private fun year(s: String): Int? = s.toIntOrNull()?.let { if (s.length == 2) 2000 + it else it }

    private fun dateOf(y: Int?, m: Int, d: Int): LocalDate? =
        if (y == null) null else runCatching { LocalDate(y, m, d) }.getOrNull()

    /** Dates written on the row, in order. Month-first (US) unless the first number can't be a month. */
    internal fun dates(row: String): List<LocalDate> {
        val found = mutableListOf<Pair<Int, LocalDate>>()
        numericDate.findAll(row).forEach { m ->
            val (a, b, c) = m.destructured
            val date = if (a.length == 4) {
                dateOf(a.toInt(), b.toInt(), c.toInt())
            } else {
                val first = a.toInt()
                val second = b.toInt()
                if (first > 12) dateOf(year(c), second, first) else dateOf(year(c), first, second)
            }
            date?.let { found += m.range.first to it }
        }
        monthFirst.findAll(row).forEach { m ->
            val month = months.indexOf(m.groupValues[1].lowercase()) + 1
            dateOf(year(m.groupValues[3]), month, m.groupValues[2].toInt())?.let { found += m.range.first to it }
        }
        dayFirst.findAll(row).forEach { m ->
            val month = months.indexOf(m.groupValues[2].lowercase()) + 1
            dateOf(year(m.groupValues[3]), month, m.groupValues[1].toInt())?.let { found += m.range.first to it }
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * The first plausible date: not in the future (a "return by" date is) and not before 2000.
     * One day ahead is allowed for time zones.
     */
    private fun date(rows: List<String>, today: LocalDate): LocalDate? {
        val latest = today.plus(DatePeriod(days = 1))
        return rows.asSequence().flatMap { dates(it) }.firstOrNull { it.year >= 2000 && it <= latest }
    }

    // ------------------------------------------------------------------ card

    private val last4Regexes = listOf(
        Regex("""[*xX•#.]{2,}\s?-?\s?(\d{4})(?!\d)"""),
        Regex("""(?i)ending\s+(?:in\s+)?(\d{4})(?!\d)"""),
        Regex("""(?i)(?:acct|account|card)\s*(?:#|no\.?|number)?\s*:?\s*[*xX•.]*\s*(\d{4})(?!\d)"""),
    )

    private val brands = listOf(
        "american express" to "Amex", "amex" to "Amex", "mastercard" to "Mastercard", "master card" to "Mastercard",
        "visa" to "Visa", "discover" to "Discover",
    )

    private fun card(rows: List<String>): Pair<String?, String?> {
        val last4 = rows.firstNotNullOfOrNull { row -> last4Regexes.firstNotNullOfOrNull { it.find(row)?.groupValues?.get(1) } }
        val brand = rows.firstNotNullOfOrNull { row ->
            val lower = row.lowercase()
            brands.firstOrNull { (word, _) -> Regex("""\b${word}\b""").containsMatchIn(lower) }?.second
                ?: if (Regex("""\bmc\b""").containsMatchIn(lower) && last4Regexes.any { it.containsMatchIn(row) }) "Mastercard" else null
        }
        return last4 to brand
    }

    // ------------------------------------------------------------------ merchant

    /** Lines with these words aren't the store's name. */
    private val skipMerchant = Regex(
        """\b(receipt|thank|customer copy|merchant copy|invoice|tel|phone|fax|www|http|cashier|server|register|order|transaction|guest|table)\b|\.com\b""",
        RegexOption.IGNORE_CASE,
    )
    private val address = Regex("""^\d+\s+\S+""")
    private val phone = Regex("""\(?\d{3}\)?[\s.\-]\d{3}[\s.\-]\d{4}""")
    private val cityState = Regex(""",\s*[A-Z]{2}(\s+\d{5}(-\d{4})?)?$""")

    /** The store's name: usually the first line that's a name rather than an address or a number. */
    private fun merchant(rows: List<String>): String? {
        for (raw in rows.take(8)) {
            var row = raw.trim()
            val lower = row.lowercase()
            if (lower.startsWith("welcome to ")) row = row.substring("welcome to ".length).trim()
            else if (skipMerchant.containsMatchIn(row)) continue
            if (address.containsMatchIn(row) || phone.containsMatchIn(row) || cityState.containsMatchIn(row)) continue
            if (dates(row).isNotEmpty() || amounts(row).isNotEmpty()) continue
            // Store numbers ("#552", "Store 0123") aren't part of the name.
            row = row.replace(Regex("""\s*(#|no\.?\s|store\s)\s*\d+\s*$""", RegexOption.IGNORE_CASE), "")
                .trim(' ', '-', '*', '|', '.', ':')
            if (row.count { it.isLetter() } < 3) continue
            return tidy(row)
        }
        return null
    }

    /** "TRADER JOE'S" → "Trader Joe's"; names in mixed case are kept as they are. */
    internal fun tidy(name: String): String {
        val collapsed = name.split(Regex("""\s+""")).joinToString(" ")
        if (collapsed.any { it.isLowerCase() }) return collapsed
        val sb = StringBuilder()
        var startOfWord = true
        for (c in collapsed.lowercase()) {
            sb.append(if (startOfWord && c.isLetter()) c.uppercaseChar() else c)
            startOfWord = c == ' ' || c == '-' || c == '/' || c == '&' || c == '('
        }
        return sb.toString()
    }
}
