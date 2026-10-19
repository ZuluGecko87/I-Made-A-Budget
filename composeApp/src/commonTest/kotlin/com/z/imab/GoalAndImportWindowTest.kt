package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.budget.CategoryMonth
import com.z.imab.budget.GoalMath
import com.z.imab.data.Budget
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.ImportWindow
import com.z.imab.importer.OfxParser
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.CategoryGoal
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalAndImportWindowTest {
    private val oct = YearMonth(2026, 10)

    // ---------------------------------------------------------------- goals

    @Test
    fun progressRemainingAndEtaAtPlannedAmount() {
        val goal = CategoryGoal(target = 500000, monthly = 50000)
        // Saved 100 a month for three months; $1,200 in the category now.
        val history = listOf(CategoryMonth(10000, 0, 100000), CategoryMonth(10000, 0, 110000), CategoryMonth(10000, 0, 120000))
        val s = GoalMath.status(goal, oct, history)
        assertEquals(120000L, s.saved)
        assertEquals(380000L, s.remaining)
        assertEquals(0.24f, s.progress)
        assertEquals(10000L, s.recentPace)
        // $3,800 left at $500/month -> 8 more months.
        assertEquals(YearMonth(2027, 6), s.etaAtPlan)
        // $3,800 left at $100/month -> 38 more months.
        assertEquals(YearMonth(2029, 12), s.etaAtPace)
        assertNull(s.onTrack)
        assertEquals(50000L, s.assignThisMonth)
    }

    @Test
    fun targetDateGivesMonthlyAmountNeeded() {
        // $1,200 by March 2027: Oct..Mar is 6 months.
        val goal = CategoryGoal(target = 120000, targetDate = LocalDate(2027, 3, 15))
        val notYet = GoalMath.status(goal, oct, listOf(CategoryMonth()))
        assertEquals(6, notYet.monthsLeft)
        assertEquals(20000L, notYet.assignThisMonth)
        assertEquals(false, notYet.onTrack)
        assertNull(notYet.etaAtPace, "no savings yet, so no estimate from pace")

        // Assigning this month's $200 counts toward it without changing what's needed.
        val assigned = GoalMath.status(goal, oct, listOf(CategoryMonth(20000, 0, 20000)))
        assertEquals(20000L, assigned.assignThisMonth)
        assertEquals(true, assigned.onTrack)
    }

    @Test
    fun reachedAndOverdueGoals() {
        val goal = CategoryGoal(target = 50000, targetDate = LocalDate(2026, 8, 1))
        val reached = GoalMath.status(goal, oct, listOf(CategoryMonth(0, 0, 60000)))
        assertTrue(reached.reached)
        assertEquals(1f, reached.progress)
        assertFalse(reached.overdue)

        val late = GoalMath.status(goal, oct, listOf(CategoryMonth(0, 0, 10000)))
        assertTrue(late.overdue)
        assertEquals(40000L, late.assignThisMonth, "past the date, everything left is due now")
    }

    @Test
    fun goalUsesRealBudgetNumbers() {
        val b = Budget("dev").also { it.seedDefaults() }
        b.saveAccount(Account("chk", "Checking"), 500000, LocalDate(2026, 7, 31))
        val ef = b.data.categories.getValue("seed-emergency-fund")
        b.saveCategory(ef.copy(goal = CategoryGoal(target = 300000)))
        b.setAssigned(YearMonth(2026, 8), ef.id, 30000)
        b.setAssigned(YearMonth(2026, 9), ef.id, 30000)
        b.setAssigned(oct, ef.id, 30000)
        val history = (2 downTo 0).map { BudgetMath.compute(b.data, oct - it).category(ef.id) }
        val s = GoalMath.status(b.data.categories.getValue(ef.id).goal!!, oct, history)
        assertEquals(90000L, s.saved)
        assertEquals(30000L, s.recentPace)
        assertEquals(YearMonth(2027, 5), s.etaAtPace)
    }

    // ---------------------------------------------------------------- import window

    private fun tx(fit: String, day: Int, month: Int = 10) =
        OfxTransaction(fit, "DEBIT", LocalDate(2026, month, day), -100, "Store $fit", "", null)

    @Test
    fun firstImportStartsAtEarliestOrAfterStartingBalance() {
        val b = Budget("dev").also { it.seedDefaults() }
        val txns = listOf(tx("a", 3), tx("b", 10), tx("c", 20))
        b.saveAccount(Account("new", "New"))
        assertEquals(LocalDate(2026, 10, 3), ImportWindow.defaultStart(b.data, "new", txns))

        b.saveAccount(Account("opened", "Opened"), 50000, LocalDate(2026, 10, 9))
        assertEquals(LocalDate(2026, 10, 10), ImportWindow.defaultStart(b.data, "opened", txns))
        assertNull(ImportWindow.lastImportDate(b.data, "opened"))
    }

    @Test
    fun laterImportsStartAtLastImportedDate() {
        val b = Budget("dev").also { it.seedDefaults() }
        b.saveAccount(Account("chk", "Checking"))
        val first = listOf(tx("a", 3), tx("b", 10))
        b.applyImport("chk", "key", ImportMatcher.analyze(first, "chk", b.data))
        assertEquals(LocalDate(2026, 10, 10), ImportWindow.lastImportDate(b.data, "chk"))

        // Next download overlaps: Oct 3..Oct 25.
        val second = listOf(tx("a", 3), tx("b", 10), tx("c", 10), tx("d", 25))
        val start = ImportWindow.defaultStart(b.data, "chk", second)
        assertEquals(LocalDate(2026, 10, 10), start)
        val rows = ImportMatcher.analyze(second.filter { it.date >= start }, "chk", b.data)
        assertEquals(listOf("b", "c", "d"), rows.map { it.source.fitId })
        assertEquals(1, rows.count { it.kind == com.z.imab.importer.MatchKind.DUPLICATE })
    }

    @Test
    fun startingBalanceFollowsChosenStartDate() {
        val stmt = OfxParser.parse(Samples.QBO_CHECKING).single()
        // Bank balance 1520.34; transactions: Oct 1 -45.12, Oct 3 +2500, Oct 5 -1200.
        assertEquals(152034L - 125488L, ImportWindow.openingBalance(stmt, LocalDate(2026, 10, 1)))
        assertEquals(152034L - 130000L, ImportWindow.openingBalance(stmt, LocalDate(2026, 10, 3)))

        val b = Budget("dev").also { it.seedDefaults() }
        b.saveAccount(Account("chk", "Checking"), 26546, LocalDate(2026, 9, 30))
        b.setStartingBalance("chk", 22034, LocalDate(2026, 10, 2))
        val opening = b.data.transactions.values.single { it.payee == ImportWindow.STARTING_BALANCE_PAYEE }
        assertEquals(22034L, opening.amount)
        assertEquals(LocalDate(2026, 10, 2), opening.date)
        b.setStartingBalance("chk", 0, LocalDate(2026, 10, 2))
        assertTrue(b.data.transactions.isEmpty())
    }
}
