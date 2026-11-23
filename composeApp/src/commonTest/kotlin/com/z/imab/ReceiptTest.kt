package com.z.imab

import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.PayeeRule
import com.z.imab.model.ReceiptRef
import com.z.imab.model.Transaction
import com.z.imab.receipt.ExpenseItem
import com.z.imab.receipt.ExpenseReport
import com.z.imab.receipt.JpegInfo
import com.z.imab.receipt.OcrLine
import com.z.imab.receipt.ReceiptMatcher
import com.z.imab.receipt.ReceiptParser
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptTest {
    private val today = LocalDate(2026, 11, 23)

    private fun plain(vararg rows: String) = rows.map { OcrLine(it) }

    /** What macOS Vision returned for a test receipt: labels and amounts as separate boxes. */
    private val visionLines = listOf(
        OcrLine("TRADER JOE'S #552", 0.063f, 0.037f, 0.417f, 0.035f),
        OcrLine("1234 Main St", 0.067f, 0.105f, 0.29f, 0.03f),
        OcrLine("Springfield, IL", 0.067f, 0.167f, 0.363f, 0.04f),
        OcrLine("BANANAS", 0.067f, 0.292f, 0.174f, 0.031f),
        OcrLine("COFFEE BEANS", 0.067f, 0.352f, 0.293f, 0.032f),
        OcrLine("SUBTOTAL", 0.063f, 0.417f, 0.2f, 0.03f),
        OcrLine("TAX", 0.063f, 0.48f, 0.08f, 0.03f),
        OcrLine("TOTAL", 0.063f, 0.542f, 0.127f, 0.031f),
        OcrLine("0.99", 0.473f, 0.292f, 0.103f, 0.033f),
        OcrLine("8.49", 0.473f, 0.352f, 0.104f, 0.038f),
        OcrLine("9.48", 0.473f, 0.417f, 0.104f, 0.036f),
        OcrLine("0.76", 0.47f, 0.4775f, 0.107f, 0.04f),
        OcrLine("$10.24", 0.45f, 0.542f, 0.15f, 0.036f),
        OcrLine("VISA ************4821", 0.063f, 0.67f, 0.507f, 0.027f),
        OcrLine("11/18/2026", 0.066f, 0.729f, 0.244f, 0.034f),
        OcrLine("14:32", 0.353f, 0.73f, 0.127f, 0.03f),
    )

    @Test
    fun separateBoxesAreJoinedIntoRows() {
        val rows = ReceiptParser.rows(visionLines)
        assertTrue("TOTAL  $10.24" in rows, rows.toString())
        assertTrue("SUBTOTAL  9.48" in rows, rows.toString())
        assertTrue("11/18/2026  14:32" in rows, rows.toString())
    }

    @Test
    fun readsAGroceryReceipt() {
        val scan = ReceiptParser.parse(visionLines, today)
        assertEquals("Trader Joe's", scan.merchant)
        assertEquals(1024L, scan.total)
        assertEquals(LocalDate(2026, 11, 18), scan.date)
        assertEquals("4821", scan.cardLast4)
        assertEquals("Visa", scan.cardBrand)
    }

    @Test
    fun takesTheTotalAfterTheTipNotTheSubtotal() {
        val scan = ReceiptParser.parse(
            plain(
                "Welcome to The Corner Bistro", "Server: Dana  Table 4", "Nov 3, 2026 7:41 PM",
                "Subtotal 52.00", "Tax 4.42", "Total 56.42", "Tip 11.00", "Total 67.42",
                "AMEX XXXXXXXXXXX1009", "Thank you!",
            ),
            today,
        )
        assertEquals("The Corner Bistro", scan.merchant)
        assertEquals(6742L, scan.total)
        assertEquals(LocalDate(2026, 11, 3), scan.date)
        assertEquals("1009", scan.cardLast4)
        assertEquals("Amex", scan.cardBrand)
    }

    @Test
    fun amountOnTheLineBelowItsLabel() {
        val scan = ReceiptParser.parse(plain("OFFICE DEPOT", "Amount Due", "$1,204.50", "Card ending in 7731"), today)
        assertEquals(120450L, scan.total)
        assertEquals("7731", scan.cardLast4)
    }

    @Test
    fun skipsSavingsAndTaxTotals() {
        val scan = ReceiptParser.parse(plain("SHOP", "TOTAL SAVINGS 3.00", "TOTAL TAX 1.10", "BALANCE DUE 21.10"), today)
        assertEquals(2110L, scan.total)
    }

    @Test
    fun ignoresAReturnByDateInTheFuture() {
        val scan = ReceiptParser.parse(plain("HOME STORE", "Return by 12/18/2026", "2026-11-17 10:02", "TOTAL 5.00"), today)
        assertEquals(LocalDate(2026, 11, 17), scan.date)
    }

    @Test
    fun readsOtherDateStyles() {
        assertEquals(listOf(LocalDate(2026, 3, 4)), ReceiptParser.dates("03/04/26"))
        assertEquals(listOf(LocalDate(2026, 4, 25)), ReceiptParser.dates("25/04/2026"))
        assertEquals(listOf(LocalDate(2026, 11, 2)), ReceiptParser.dates("2 Nov 2026"))
        assertEquals(listOf(LocalDate(2026, 9, 14)), ReceiptParser.dates("September 14th, 2026"))
        // A phone number isn't a date.
        assertEquals(emptyList(), ReceiptParser.dates("(555) 123-4567"))
    }

    @Test
    fun merchantSkipsAddressesAndPhones() {
        val scan = ReceiptParser.parse(plain("RECEIPT", "123 Market St", "(555) 123-4567", "Blue Bottle Coffee", "Latte 5.50", "Total 5.50"), today)
        assertEquals("Blue Bottle Coffee", scan.merchant)
    }

    @Test
    fun nothingReadable() {
        val scan = ReceiptParser.parse(plain("~~~", "##"), today)
        assertNull(scan.merchant)
        assertNull(scan.total)
        assertTrue(scan.isEmpty)
    }

    private var now = 1_000L

    private fun budgetWithCards(): Budget {
        val b = Budget("dev") { now++ }
        b.saveAccount(Account("chk", "Checking"))
        b.saveAccount(Account("gold", "Amex Gold", AccountType.CREDIT_CARD))
        b.saveAccount(Account("visa", "Chase Visa", AccountType.CREDIT_CARD, importKeys = listOf("card-••4821")))
        b.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 1), -800, payee = "Trader Joe's", categoryId = "groceries"))
        return b
    }

    @Test
    fun matchesTheCardAndPayee() {
        val b = budgetWithCards()
        val scan = ReceiptParser.parse(visionLines, today)
        val draft = ReceiptMatcher.draft(b.data, scan, "chk", "new", today)
        assertEquals("visa", draft.cardAccount?.id)
        assertEquals("visa", draft.transaction.accountId)
        assertEquals("Trader Joe's", draft.transaction.payee)
        assertEquals(-1024L, draft.transaction.amount)
        assertEquals("groceries", draft.transaction.categoryId)
    }

    @Test
    fun brandAloneMatchesTheOnlyCardOfThatBrand() {
        val b = budgetWithCards()
        assertEquals("gold", ReceiptMatcher.cardAccount(b.data, "1009", "Amex")?.id)
        assertNull(ReceiptMatcher.cardAccount(b.data, "1009", "Discover"))
    }

    @Test
    fun rememberedCardWinsAndMovesBetweenAccounts() {
        val b = budgetWithCards()
        b.rememberCard("gold", "5555")
        assertEquals("gold", ReceiptMatcher.cardAccount(b.data, "5555", null)?.id)
        b.rememberCard("chk", "5555")
        assertEquals("chk", ReceiptMatcher.cardAccount(b.data, "5555", null)?.id)
        assertEquals(emptyList(), b.data.accounts.getValue("gold").cardLast4)
    }

    @Test
    fun ruleGivesTheCategory() {
        val b = budgetWithCards()
        b.saveRule(PayeeRule("r", "blue bottle", categoryId = "coffee"))
        assertEquals("coffee", ReceiptMatcher.category(b.data, "Blue Bottle Coffee"))
    }

    @Test
    fun mergingKeepsTheReceiptAndWorkFlag() {
        val b = budgetWithCards()
        b.saveTransaction(Transaction("entered", "visa", LocalDate(2026, 11, 18), -1024, payee = "Trader Joe's", receipt = ReceiptRef("r1", "Phone"), workExpense = true))
        b.saveTransaction(Transaction("bank", "visa", LocalDate(2026, 11, 19), -1024, payee = "TRADER JOE S", importId = "fit1"))
        b.mergeDuplicates("bank", listOf("entered"))
        val kept = b.data.transactions.getValue("bank")
        assertEquals("r1", kept.receipt?.id)
        assertTrue(kept.workExpense)
    }

    @Test
    fun markSubmitted() {
        val b = budgetWithCards()
        b.saveTransaction(Transaction("w", "visa", today, -500, workExpense = true))
        b.markSubmitted(listOf("w"), today)
        assertEquals(today, b.data.transactions.getValue("w").expenseSubmitted)
        b.markSubmitted(listOf("w"), null)
        assertNull(b.data.transactions.getValue("w").expenseSubmitted)
    }

    @Test
    fun csvEscapesAndSigns() {
        val b = budgetWithCards()
        val items = listOf(
            Transaction("a", "visa", LocalDate(2026, 11, 2), -1250, payee = "Cafe, \"The\"", memo = "Client lunch"),
            Transaction("b", "visa", LocalDate(2026, 11, 3), 300, payee = "=REFUND"),
        )
        val csv = ExpenseReport.csv(b.data, items).lines()
        assertEquals("Date,Merchant,Category,Paid with,Memo,Amount,Receipt", csv[0])
        assertEquals("2026-11-02,\"Cafe, \"\"The\"\"\",Uncategorized,Chase Visa,Client lunch,12.50,no", csv[1])
        assertEquals("2026-11-03,'=REFUND,Uncategorized,Chase Visa,,-3.00,no", csv[2])
        assertEquals("IMAB expenses 2026-11-02 to 2026-11-03.csv", ExpenseReport.fileName(items, "csv"))
    }

    /** A 2×1 baseline JPEG's start: SOI, then a frame header saying 3 components. */
    private val tinyJpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00,
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x00, 0x01, 0x00, 0x02, 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test
    fun readsJpegSize() {
        val info = JpegInfo.read(tinyJpeg)!!
        assertEquals(2, info.width)
        assertEquals(1, info.height)
        assertEquals(3, info.components)
        assertNull(JpegInfo.read(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun pdfHasASummaryAndAPagePerReceipt() {
        val b = budgetWithCards()
        val items = (1..40).map { i ->
            val t = Transaction("e$i", "visa", LocalDate(2026, 11, 1 + i % 20), -1000L * i, payee = "Vendor $i", memo = if (i % 3 == 0) "Trip" else "")
            ExpenseItem(t, if (i <= 2) tinyJpeg else null)
        }
        val pdf = ExpenseReport.pdf(b.data, items, "November travel", "Sam Doe", today)
        val text = String(CharArray(pdf.size) { (pdf[it].toInt() and 0xFF).toChar() })
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        // 40 rows need two summary pages, then the two receipts.
        val pages = Regex("/Type /Page ").findAll(text).count()
        assertEquals(4, pages)
        assertTrue("(November travel)" in text)
        assertTrue("(Submitted by Sam Doe)" in text)
        assertTrue("(p. 3)" in text && "(p. 4)" in text, "receipt page numbers")
        assertTrue("/Filter /DCTDecode" in text)
        // Every object the cross-reference table lists starts where it says.
        val xrefAt = Regex("startxref\n(\\d+)").find(text)!!.groupValues[1].toInt()
        val offsets = Regex("(\\d{10}) 00000 n").findAll(text.substring(xrefAt)).map { it.groupValues[1].toInt() }.toList()
        offsets.forEachIndexed { i, at -> assertTrue(text.substring(at).startsWith("${i + 1} 0 obj"), "object ${i + 1}") }
    }
}
