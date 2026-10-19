package com.z.imab

import com.z.imab.data.Budget
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.MatchKind
import com.z.imab.importer.MergeFinder
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Transaction
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeTest {
    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"))
        it.saveAccount(Account("sav", "Savings", AccountType.SAVINGS))
    }

    private fun bankTx(fit: String, day: Int, cents: Long, name: String) =
        OfxTransaction(fit, "DEBIT", LocalDate(2026, 10, day), cents, name, "", null)

    /** Imports the given bank transactions as new, the way "Add as new" would. */
    private fun Budget.importAsNew(vararg txns: OfxTransaction) =
        applyImport("chk", null, ImportMatcher.analyze(txns.toList(), "chk", data).map { it.copy(action = ImportAction.ADD) })

    @Test
    fun findsEnteredAndBankCopiesByAmountAndDate() {
        val b = budget()
        b.saveTransaction(Transaction("m1", "chk", LocalDate(2026, 10, 1), -4512, "Groceries", categoryId = "seed-groceries"))
        b.saveTransaction(Transaction("m2", "chk", LocalDate(2026, 10, 1), -999, "Lunch"))
        b.importAsNew(
            bankTx("A", 3, -4512, "KROGER #123"), // same amount, 2 days later -> match
            bankTx("B", 20, -999, "CAFE"), // same amount but 19 days later -> not a match
            bankTx("C", 1, -4500, "OTHER"), // same day, different amount -> not a match
        )
        val found = MergeFinder.find(b.data, "chk")
        assertEquals(1, found.size)
        assertEquals("m1", found[0].entered.id)
        assertEquals("A", found[0].bank.importId)
        assertEquals(2, found[0].daysApart)
    }

    @Test
    fun pairsClosestDatesFirstAndUsesEachTransactionOnce() {
        val b = budget()
        b.saveTransaction(Transaction("early", "chk", LocalDate(2026, 10, 1), -2000, "Gas"))
        b.saveTransaction(Transaction("late", "chk", LocalDate(2026, 10, 8), -2000, "Gas"))
        b.importAsNew(bankTx("G1", 2, -2000, "SHELL"), bankTx("G2", 8, -2000, "SHELL"))
        val found = MergeFinder.find(b.data, "chk").associate { it.entered.id to it.bank.importId }
        assertEquals(mapOf("early" to "G1", "late" to "G2"), found)
    }

    @Test
    fun mergeKeepsWhatYouEnteredAndShowsOnce() {
        val b = budget()
        b.saveTransaction(Transaction("m", "chk", LocalDate(2026, 10, 1), -4512, "Weekly shop", "receipt in drawer", categoryId = "seed-groceries"))
        b.importAsNew(bankTx("A", 3, -4512, "KROGER #123"))
        val c = MergeFinder.find(b.data, "chk").single()
        b.mergeTransactions(c.entered.id, c.bank.id)

        val left = b.data.transactionsByAccount["chk"].orEmpty()
        assertEquals(1, left.size)
        val merged = left.single()
        assertEquals("m", merged.id)
        assertEquals("Weekly shop", merged.payee)
        assertEquals("receipt in drawer", merged.memo)
        assertEquals("seed-groceries", merged.categoryId)
        assertEquals(LocalDate(2026, 10, 3), merged.date, "uses the bank's date")
        assertEquals("A", merged.importId)
        assertTrue(merged.cleared)
        assertTrue(MergeFinder.find(b.data, "chk").isEmpty())

        // Importing the same bank file again now sees it as a duplicate.
        val again = ImportMatcher.analyze(listOf(bankTx("A", 3, -4512, "KROGER #123")), "chk", b.data)
        assertEquals(MatchKind.DUPLICATE, again.single().kind)
    }

    @Test
    fun mergeTakesBankCategoryWhenNothingWasEntered() {
        val b = budget()
        b.saveTransaction(Transaction("m", "chk", LocalDate(2026, 10, 1), -1500, "Pizza"))
        b.applyImport(
            "chk", null,
            ImportMatcher.analyze(listOf(bankTx("P", 2, -1500, "DOMINOS")), "chk", b.data)
                .map { it.copy(action = ImportAction.ADD, categoryId = "seed-dining-out") },
        )
        b.mergeTransactions("m", b.data.transactions.values.single { it.importId == "P" }.id)
        assertEquals("seed-dining-out", b.data.transactions.getValue("m").categoryId)
    }

    @Test
    fun mergingKeepsATransferLinked() {
        val b = budget()
        // Entered by hand as a transfer to savings; the bank copy came in uncategorized.
        b.saveTransaction(Transaction("t", "chk", LocalDate(2026, 10, 5), -50000, transferAccountId = "sav"))
        b.importAsNew(bankTx("X", 6, -50000, "ONLINE TRANSFER"))
        val c = MergeFinder.find(b.data, "chk").single()
        assertEquals("t", c.entered.id)
        b.mergeTransactions(c.entered.id, c.bank.id)
        val t = b.data.transactions.getValue("t")
        assertEquals("sav", t.transferAccountId)
        val pair = b.data.transactions.getValue(t.transferPairId!!)
        assertEquals(50000L, pair.amount)
        assertEquals(LocalDate(2026, 10, 6), pair.date)
        assertEquals(2, b.data.transactions.size)
    }

    @Test
    fun notADuplicateStopsAsking() {
        val b = budget()
        b.saveTransaction(Transaction("m", "chk", LocalDate(2026, 10, 1), -500, "Coffee"))
        b.importAsNew(bankTx("C", 1, -500, "COFFEE SHOP"))
        val c = MergeFinder.find(b.data, "chk").single()
        b.markNotDuplicate(c.entered.id, c.bank.id)
        assertTrue(MergeFinder.find(b.data, "chk").isEmpty())
        assertEquals(2, b.data.transactions.size)
    }

    @Test
    fun importPreviewMergeUsesBankDate() {
        val b = budget()
        b.saveTransaction(Transaction("m", "chk", LocalDate(2026, 10, 1), -4512, "Groceries", categoryId = "seed-groceries"))
        val rows = ImportMatcher.analyze(listOf(bankTx("A", 3, -4512, "KROGER")), "chk", b.data)
        assertEquals(MatchKind.MATCH_EXISTING, rows.single().kind)
        assertEquals(ImportAction.MATCH, rows.single().action)
        b.applyImport("chk", null, rows)
        val m = b.data.transactions.getValue("m")
        assertEquals(LocalDate(2026, 10, 3), m.date)
        assertEquals("A", m.importId)
        assertNull(MergeFinder.find(b.data, "chk").firstOrNull())
    }
}
