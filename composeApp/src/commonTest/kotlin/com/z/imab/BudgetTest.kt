package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.budget.Scheduler
import com.z.imab.data.Budget
import com.z.imab.data.Ledger
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.MatchKind
import com.z.imab.importer.OfxParser
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Frequency
import com.z.imab.model.Money
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetTest {
    private var fakeNow = 1_000L
    private fun budget(device: String = "dev-a") = Budget(device) { fakeNow++ }.also { it.seedDefaults() }

    private fun Budget.checking() = saveAccount(Account("chk", "Checking"))
    private fun Budget.savings() = saveAccount(Account("sav", "Savings", AccountType.SAVINGS))

    @Test
    fun moneyParsingAndFormatting() {
        assertEquals(123456L, Money.parse("1,234.56"))
        assertEquals(-1200L, Money.parse("(12.00)"))
        assertEquals(1234L, Money.parse("12,34"))
        assertEquals(550L, Money.parse("$5.5"))
        assertEquals(123456L, Money.parse("1.234,56"))
        assertNull(Money.parse("abc"))
        assertEquals("-$1,234.56", Money.format(-123456))
        assertEquals("$0.05", Money.format(5))
    }

    @Test
    fun budgetMathCarriesForwardAndComputesReadyToAssign() {
        val b = budget()
        b.checking()
        val oct = YearMonth(2026, 10)
        b.saveTransaction(Transaction("pay", "chk", LocalDate(2026, 10, 1), 300000, "Job", categoryId = READY_TO_ASSIGN))
        b.setAssigned(oct, "seed-groceries", 50000)
        b.saveTransaction(Transaction("g1", "chk", LocalDate(2026, 10, 5), -12000, "Kroger", categoryId = "seed-groceries"))

        val m = BudgetMath.compute(b.data, oct)
        assertEquals(250000L, m.readyToAssign)
        assertEquals(CategoryCheck(50000, -12000, 38000), m.category("seed-groceries").let { CategoryCheck(it.assigned, it.activity, it.available) })

        // November: nothing assigned, October's leftover carries over.
        val nov = BudgetMath.compute(b.data, oct + 1)
        assertEquals(38000L, nov.category("seed-groceries").available)
        assertEquals(0L, nov.category("seed-groceries").assigned)

        // An uncategorized outflow comes out of Ready to Assign and is flagged.
        b.saveTransaction(Transaction("u1", "chk", LocalDate(2026, 10, 6), -1000, "Mystery"))
        assertEquals(249000L, BudgetMath.compute(b.data, oct).readyToAssign)
        assertEquals(1, b.data.uncategorizedCount)
    }

    @Test
    fun transfersCreateAndMaintainBothLegs() {
        val b = budget()
        b.checking(); b.savings()
        b.saveTransaction(Transaction("t1", "chk", LocalDate(2026, 10, 5), -50000, transferAccountId = "sav", categoryId = "seed-groceries"))

        val leg = b.data.transactions["t1"]!!
        val pair = b.data.transactions[leg.transferPairId]!!
        assertEquals("sav", pair.accountId)
        assertEquals(50000L, pair.amount)
        assertEquals("chk", pair.transferAccountId)
        assertNull(leg.categoryId, "on-budget to on-budget transfers have no category")
        assertEquals(0, b.data.uncategorizedCount)

        b.saveTransaction(leg.copy(amount = -60000))
        assertEquals(60000L, b.data.transactions[pair.id]!!.amount)

        b.deleteTransaction("t1")
        assertTrue(b.data.transactions.isEmpty())
    }

    @Test
    fun importMarksDuplicatesAndMatchesManualEntries() {
        val b = budget()
        b.checking()
        // Entered by hand two days before the bank posted it.
        b.saveTransaction(Transaction("manual", "chk", LocalDate(2026, 9, 29), -4512, "Groceries run", categoryId = "seed-groceries"))
        val stmt = OfxParser.parse(Samples.QBO_CHECKING).single()

        val rows = ImportMatcher.analyze(stmt.transactions, "chk", b.data)
        assertEquals(MatchKind.MATCH_EXISTING, rows[0].kind)
        assertEquals("manual", rows[0].matchedTxnId)
        assertEquals(MatchKind.NEW, rows[1].kind)

        val result = b.applyImport("chk", stmt.accountKey, rows)
        assertEquals(1, result.matched)
        assertEquals(2, result.added)
        val manual = b.data.transactions["manual"]!!
        assertEquals("20261001001", manual.importId)
        assertTrue(manual.cleared)
        assertEquals("seed-groceries", manual.categoryId)
        assertTrue(stmt.accountKey in b.data.accounts["chk"]!!.importKeys)

        // Importing the same file again: everything is a duplicate and skipped.
        val again = ImportMatcher.analyze(stmt.transactions, "chk", b.data)
        assertTrue(again.all { it.kind == MatchKind.DUPLICATE && it.action == ImportAction.SKIP })
        assertEquals(3, b.applyImport("chk", stmt.accountKey, again).skipped)
        assertEquals(3, b.data.transactions.size)
    }

    @Test
    fun importLinksTransferToExistingLeg() {
        val b = budget()
        b.checking(); b.savings()
        // Savings statement was imported first.
        b.saveTransaction(Transaction("s1", "sav", LocalDate(2026, 10, 6), 120000, "TRANSFER FROM CHECKING", cleared = true, importId = "SAV-1"))
        val stmt = OfxParser.parse(Samples.QBO_CHECKING).single()
        val rows = ImportMatcher.analyze(stmt.transactions, "chk", b.data)
            .map { if (it.source.fitId == "20261005001") it.copy(transferAccountId = "sav") else it }
        b.applyImport("chk", stmt.accountKey, rows)

        val chkLeg = b.data.transactions.values.single { it.importId == "20261005001" }
        assertEquals("s1", chkLeg.transferPairId)
        val savLeg = b.data.transactions["s1"]!!
        assertEquals("chk", savLeg.transferAccountId)
        assertEquals("SAV-1", savLeg.importId, "existing leg keeps its bank id")
        assertEquals(LocalDate(2026, 10, 6), savLeg.date)
        assertEquals(4, b.data.transactions.size)
    }

    @Test
    fun scheduledTransactionsEnterAndMatchImports() {
        val b = budget()
        b.checking()
        val rent = ScheduledTransaction(
            "rent", "chk", startDate = LocalDate(2026, 8, 31), nextDate = LocalDate(2026, 8, 31),
            frequency = Frequency.MONTHLY, amount = -150000, payee = "Landlord", categoryId = "seed-rent-mortgage",
        )
        b.saveScheduled(rent)
        b.processScheduled(LocalDate(2026, 10, 1))
        // Aug 31 and Sep 30 entered; month-end anchor is kept (Oct 31, not Oct 30).
        assertNotNull(b.data.transactions[Scheduler.occurrenceTxnId("rent", LocalDate(2026, 8, 31))])
        assertNotNull(b.data.transactions[Scheduler.occurrenceTxnId("rent", LocalDate(2026, 9, 30))])
        assertEquals(LocalDate(2026, 10, 31), b.data.scheduled["rent"]!!.nextDate)

        // Bank posts September's rent two days late -> matches the entered occurrence.
        val sept = OfxTx("R-SEP", LocalDate(2026, 10, 2), -150000, "LANDLORD LLC")
        // Bank posts October's rent a day early -> matches the upcoming schedule.
        val oct = OfxTx("R-OCT", LocalDate(2026, 10, 30), -150000, "LANDLORD LLC")
        val rows = ImportMatcher.analyze(listOf(sept, oct), "chk", b.data)
        assertEquals(MatchKind.MATCH_EXISTING, rows[0].kind)
        assertEquals(MatchKind.MATCH_SCHEDULED, rows[1].kind)

        b.applyImport("chk", null, rows)
        val octTxn = b.data.transactions[Scheduler.occurrenceTxnId("rent", LocalDate(2026, 10, 31))]!!
        assertEquals("R-OCT", octTxn.importId)
        assertEquals("seed-rent-mortgage", octTxn.categoryId)
        assertEquals(LocalDate(2026, 11, 30), b.data.scheduled["rent"]!!.nextDate)

        // Running the schedule again doesn't double-enter October.
        b.processScheduled(LocalDate(2026, 11, 1))
        assertEquals(3, b.data.transactions.size)
    }

    @Test
    fun deletedScheduledOccurrenceIsNotRecreated() {
        val b = budget()
        b.checking()
        b.saveScheduled(ScheduledTransaction("s", "chk", LocalDate(2026, 10, 1), LocalDate(2026, 10, 1), Frequency.ONCE, -100))
        b.processScheduled(LocalDate(2026, 10, 1))
        val id = Scheduler.occurrenceTxnId("s", LocalDate(2026, 10, 1))
        assertNotNull(b.data.transactions[id])
        assertTrue(b.data.scheduled.isEmpty(), "one-time schedule is removed once entered")
        b.deleteTransaction(id)
        b.processScheduled(LocalDate(2026, 10, 2))
        assertNull(b.data.transactions[id])
    }

    @Test
    fun syncMergeConvergesInAnyOrder() {
        val a = budget("dev-a")
        val c = budget("dev-c")
        a.checking()
        a.saveTransaction(Transaction("x", "chk", LocalDate(2026, 10, 1), -500, "Coffee"))
        // Device C starts fresh and receives A's data.
        c.merge(Ledger.decode(a.ledger.encode()))
        assertEquals(1, c.data.transactions.size)
        // Both default category sets collapse into one.
        assertEquals(a.data.categories.keys, c.data.categories.keys)

        // Concurrent edits: C renames payee later; A adds another transaction.
        c.saveTransaction(c.data.transactions["x"]!!.copy(payee = "Starbucks"))
        a.saveTransaction(Transaction("y", "chk", LocalDate(2026, 10, 2), -900, "Lunch"))

        val fromA = Ledger.decode(a.ledger.encode())
        val fromC = Ledger.decode(c.ledger.encode())
        a.merge(fromC)
        c.merge(fromA)
        assertEquals(a.data, c.data)
        assertEquals("Starbucks", a.data.transactions["x"]!!.payee)
        assertTrue(a.ledger.sameAs(Ledger.decode(c.ledger.encode())))
    }

    @Test
    fun seedRenameSurvivesFreshDeviceJoining() {
        val a = budget("dev-a")
        a.saveCategory(a.data.categories["seed-groceries"]!!.copy(name = "Food"))
        val fresh = budget("dev-z")
        fresh.merge(Ledger.decode(a.ledger.encode()))
        a.merge(Ledger.decode(fresh.ledger.encode()))
        assertEquals("Food", fresh.data.categories["seed-groceries"]!!.name)
        assertEquals("Food", a.data.categories["seed-groceries"]!!.name)
    }

    private data class CategoryCheck(val assigned: Long, val activity: Long, val available: Long)

    private fun OfxTx(fit: String, date: LocalDate, amount: Long, name: String) =
        com.z.imab.importer.OfxTransaction(fit, "DEBIT", date, amount, name, "", null)
}
