package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.data.Budget
import com.z.imab.data.RECONCILE_ADJUSTMENT_PAYEE
import com.z.imab.importer.ImportWindow
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconcileTest {
    private fun d(day: Int) = LocalDate(2026, 10, day)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30)) // cleared
        it.saveTransaction(Transaction("a", "chk", d(3), -2000, "Kroger", categoryId = "seed-groceries", cleared = true))
        it.saveTransaction(Transaction("b", "chk", d(5), -500, "Coffee", categoryId = "seed-dining-out")) // not cleared
        it.saveTransaction(Transaction("c", "chk", d(25), -3000, "Target", categoryId = "seed-shopping", cleared = true)) // after the statement
    }

    @Test
    fun matchingBalanceLocksOnlyClearedTransactionsUpToTheDate() {
        val b = budget()
        // Bank says $980 on Oct 20: $1,000 - $20 (Kroger). The uncleared coffee doesn't count.
        b.reconcile("chk", d(20), 98000, adjust = false)
        val t = b.data.transactions
        assertTrue(t.getValue("a").reconciled)
        assertFalse(t.getValue("b").reconciled, "uncleared transactions aren't reconciled")
        assertFalse(t.getValue("c").reconciled, "after the statement date")
        assertTrue(t.values.first { it.payee == "Starting Balance" }.reconciled)
        val acct = b.data.accounts.getValue("chk")
        assertEquals(d(20), acct.lastReconciled)
        assertEquals(98000L, acct.reconciledBalance)
        assertTrue(t.values.none { it.payee == RECONCILE_ADJUSTMENT_PAYEE })
    }

    @Test
    fun aDifferenceCanBeAdjustedSoTheBalanceMatches() {
        val b = budget()
        val rtaBefore = BudgetMath.compute(b.data, YearMonth(2026, 10)).readyToAssign
        // Bank shows $975: $5 IMAB doesn't know about (e.g. a fee).
        b.reconcile("chk", d(20), 97500, adjust = true)
        val adj = b.data.transactions.values.single { it.payee == RECONCILE_ADJUSTMENT_PAYEE }
        assertEquals(-500L, adj.amount)
        assertTrue(adj.reconciled && adj.cleared)
        assertEquals(97500L, b.data.transactions.values.filter { it.accountId == "chk" && it.cleared && it.date <= d(20) }.sumOf { it.amount })
        assertEquals(rtaBefore - 500, BudgetMath.compute(b.data, YearMonth(2026, 10)).readyToAssign, "the adjustment comes out of Ready to Assign")
    }

    @Test
    fun reconciledTransactionsStayCleared() {
        val b = budget()
        b.reconcile("chk", d(20), 98000, adjust = false)
        b.setCleared("a", false)
        assertTrue(b.data.transactions.getValue("a").cleared)
    }

    @Test
    fun creditCardReconcileUsesWhatIsOwed() {
        val b = budget()
        b.saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD))
        b.saveTransaction(Transaction("v", "visa", d(4), -15000, "State Farm", categoryId = "seed-insurance", cleared = true))
        // The bank says $150 owed -> a balance of -$150.
        b.reconcile("visa", d(20), -15000, adjust = false)
        assertTrue(b.data.transactions.getValue("v").reconciled)
    }

    @Test
    fun firstImportStartsTheDayAfterTheLastReconcile() {
        val b = budget()
        b.reconcile("chk", d(20), 98000, adjust = false)
        val file = listOf(d(1), d(18), d(21), d(26)).mapIndexed { i, day -> OfxTransaction("F$i", "DEBIT", day, -100, "X", "", null) }
        assertEquals(d(21), ImportWindow.defaultStart(b.data, "chk", file))
        // Once something has been imported, imports carry on from the last imported date.
        b.saveTransaction(Transaction("imp", "chk", d(26), -100, "X", cleared = true, importId = "F3"))
        assertEquals(d(26), ImportWindow.defaultStart(b.data, "chk", file))
    }
}
