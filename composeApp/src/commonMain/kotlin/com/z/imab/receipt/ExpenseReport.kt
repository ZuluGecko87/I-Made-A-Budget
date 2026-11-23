package com.z.imab.receipt

import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.Transaction
import kotlinx.datetime.LocalDate

/** A work expense going into a report, with its receipt photo when this device has it. */
class ExpenseItem(val transaction: Transaction, val receiptJpeg: ByteArray?)

/**
 * Work expense reports to send in for reimbursement: a PDF with a summary table and then each
 * receipt on its own page, or a CSV for spreadsheets and expense systems.
 * Amounts are what was spent, so purchases are positive and refunds negative.
 */
object ExpenseReport {
    private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun formatDate(d: LocalDate): String = "${monthNames[d.month.ordinal]} ${d.day}, ${d.year}"

    /** The amount claimed: purchases positive, refunds negative. */
    fun claimed(t: Transaction): Long = -t.amount

    fun total(items: List<Transaction>): Long = items.sumOf(::claimed)

    /** "IMAB expenses 2026-11-02 to 2026-11-18". */
    fun fileName(items: List<Transaction>, extension: String): String {
        val dates = items.map { it.date }.sorted()
        val range = when {
            dates.isEmpty() -> ""
            dates.first() == dates.last() -> " ${dates.first()}"
            else -> " ${dates.first()} to ${dates.last()}"
        }
        return "IMAB expenses$range.$extension"
    }

    fun csv(data: BudgetData, items: List<Transaction>): String = buildString {
        append("Date,Merchant,Category,Paid with,Memo,Amount,Receipt\r\n")
        for (t in items.sortedWith(compareBy({ it.date }, { it.payee }))) {
            val cells = listOf(
                t.date.toString(),
                data.payeeLabel(t),
                data.categoryLabel(t),
                data.accountName(t.accountId),
                t.memo,
                Money.toInput(claimed(t)),
                if (t.receipt != null) "yes" else "no",
            )
            append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
        }
    }

    private fun csvCell(s: String): String {
        // A leading =, +, - or @ would run as a formula in a spreadsheet.
        val safe = if (s.isNotEmpty() && s[0] in "=+@" ) "'$s" else s
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun pdf(data: BudgetData, items: List<ExpenseItem>, title: String, submittedBy: String, generated: LocalDate): ByteArray {
        val doc = PdfDocument()
        val sorted = items.sortedWith(compareBy({ it.transaction.date }, { it.transaction.payee }))
        val images = sorted.associateWith { item -> item.receiptJpeg?.let(doc::addJpeg) }
        // Receipt pages come after the summary; work out their numbers first so the table can point to them.
        val withPhotos = sorted.filter { images[it] != null }
        val margin = 40f
        val right = PdfPage.WIDTH - margin
        val bottom = 56f

        // Columns: date, merchant (memo below), category, paid with, amount, receipt page.
        val colDate = margin
        val colMerchant = margin + 62f
        val colCategory = colMerchant + 156f
        val colPaid = colCategory + 112f
        val colAmountRight = right - 40f
        val colReceipt = right - 30f

        val summaryPages = mutableListOf<PdfPage>()
        var page = PdfPage().also { summaryPages += it }
        var y = PdfPage.HEIGHT - margin - 14f

        page.text(margin, y, title.ifBlank { "Expense report" }, size = 18f, bold = true)
        y -= 20f
        val dates = sorted.map { it.transaction.date }
        val details = buildList {
            if (submittedBy.isNotBlank()) add("Submitted by ${submittedBy.trim()}")
            if (dates.isNotEmpty()) add(if (dates.first() == dates.last()) formatDate(dates.first()) else "${formatDate(dates.first())} – ${formatDate(dates.last())}")
            add("${sorted.size} expense${if (sorted.size == 1) "" else "s"} · prepared ${formatDate(generated)}")
        }
        details.forEach { page.text(margin, y, it, size = 10f, gray = 0.35f); y -= 14f }
        y -= 10f

        fun header() {
            page.text(colDate, y, "Date", size = 9f, bold = true)
            page.text(colMerchant, y, "Merchant", size = 9f, bold = true)
            page.text(colCategory, y, "Category", size = 9f, bold = true)
            page.text(colPaid, y, "Paid with", size = 9f, bold = true)
            page.textRight(colAmountRight, y, "Amount", size = 9f, bold = true)
            page.text(colReceipt, y, "Receipt", size = 9f, bold = true)
            y -= 6f
            page.line(margin, y, right, y, gray = 0.4f, width = 0.8f)
            y -= 14f
        }
        header()

        for (item in sorted) {
            val t = item.transaction
            val memo = t.memo.takeIf { it.isNotBlank() }
            val rowHeight = if (memo != null) 26f else 16f
            if (y - rowHeight < bottom) {
                page = PdfPage().also { summaryPages += it }
                y = PdfPage.HEIGHT - margin - 14f
                header()
            }
            val shortDate = "${monthNames[t.date.month.ordinal]} ${t.date.day}" + if (dates.first().year != dates.last().year) ", ${t.date.year % 100}" else ""
            page.text(colDate, y, shortDate, size = 9f)
            page.text(colMerchant, y, Helvetica.fit(data.payeeLabel(t), colCategory - colMerchant - 8f, 9f), size = 9f)
            memo?.let { page.text(colMerchant, y - 10f, Helvetica.fit(it, colPaid - colMerchant - 8f, 8f), size = 8f, gray = 0.4f) }
            page.text(colCategory, y, Helvetica.fit(data.categoryLabel(t), colPaid - colCategory - 8f, 9f), size = 9f)
            page.text(colPaid, y, Helvetica.fit(data.accountName(t.accountId), colAmountRight - 60f - colPaid, 9f), size = 9f)
            page.textRight(colAmountRight, y, Money.format(claimed(t)), size = 9f)
            val photoIndex = withPhotos.indexOf(item)
            page.text(colReceipt, y, if (photoIndex >= 0) "p. #${photoIndex}" else if (t.receipt != null) "not here" else "none", size = 8f, gray = 0.4f)
            y -= rowHeight - 4f
            page.line(margin, y + 2f, right, y + 2f, gray = 0.88f)
            y -= 8f
        }
        if (y - 24f < bottom) {
            page = PdfPage().also { summaryPages += it }
            y = PdfPage.HEIGHT - margin - 14f
        }
        y -= 4f
        page.text(colPaid, y, "Total", size = 11f, bold = true)
        page.textRight(colAmountRight, y, Money.format(total(sorted.map { it.transaction })), size = 11f, bold = true)
        if (sorted.any { it.transaction.receipt != null && images[it] == null }) {
            y -= 22f
            page.text(margin, y, "\"not here\": the receipt photo is saved on another device; export from that device to include it.", size = 8f, gray = 0.4f)
        }

        // Now that the number of summary pages is known, fill in the receipt page numbers.
        val firstPhotoPage = summaryPages.size + 1
        summaryPages.forEach { p ->
            val text = p.content.toString()
            val fixed = Regex("""\(p\. #(\d+)\)""").replace(text) { m -> "(p. ${firstPhotoPage + m.groupValues[1].toInt()})" }
            p.content.clear()
            p.content.append(fixed)
        }
        val pageCount = summaryPages.size + withPhotos.size
        summaryPages.forEachIndexed { i, p -> footer(p, i + 1, pageCount) }
        summaryPages.forEach(doc::addPage)

        withPhotos.forEachIndexed { i, item ->
            val t = item.transaction
            val img = images.getValue(item)!!
            val p = PdfPage()
            var top = PdfPage.HEIGHT - margin - 12f
            p.text(margin, top, data.payeeLabel(t), size = 13f, bold = true)
            p.textRight(right, top, Money.format(claimed(t)), size = 13f, bold = true)
            top -= 16f
            p.text(margin, top, "${formatDate(t.date)} · ${data.categoryLabel(t)} · ${data.accountName(t.accountId)}", size = 9f, gray = 0.35f)
            t.memo.takeIf { it.isNotBlank() }?.let { memo ->
                Helvetica.wrap(memo, right - margin, 9f).take(3).forEach { line -> top -= 12f; p.text(margin, top, line, size = 9f, gray = 0.35f) }
            }
            top -= 14f
            // Fit the photo in what's left, keeping its shape.
            val boxW = right - margin
            val boxH = top - bottom + 16f
            val scale = minOf(boxW / img.width, boxH / img.height)
            val w = img.width * scale
            val h = img.height * scale
            p.image(img, margin + (boxW - w) / 2, top - h, w, h)
            footer(p, summaryPages.size + i + 1, pageCount)
            doc.addPage(p)
        }
        return doc.toByteArray()
    }

    private fun footer(p: PdfPage, number: Int, count: Int) {
        p.text(40f, 28f, "Prepared with IMAB", size = 7f, gray = 0.55f)
        p.textRight(PdfPage.WIDTH - 40f, 28f, "Page $number of $count", size = 7f)
    }
}
