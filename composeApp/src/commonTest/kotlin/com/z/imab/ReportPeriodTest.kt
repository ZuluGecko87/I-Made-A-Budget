package com.z.imab

import com.z.imab.budget.ReportPeriod
import com.z.imab.budget.Reports
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The time ranges reports can cover. */
class ReportPeriodTest {
    private val nov = YearMonth(2026, 11)

    @Test
    fun lastThreeMonthsEndsWithTheChosenMonth() {
        val p = ReportPeriod.last3(nov)
        assertEquals(listOf(YearMonth(2026, 9), YearMonth(2026, 10), nov), p.months)
        assertEquals(ReportPeriod(YearMonth(2026, 6), YearMonth(2026, 8)), p.previous())
        assertEquals(ReportPeriod(YearMonth(2025, 11), YearMonth(2026, 1)), ReportPeriod.last3(YearMonth(2026, 1)))
    }

    @Test
    fun aCustomRangeWorksEitherWayRound() {
        assertEquals(ReportPeriod(YearMonth(2025, 3), nov), ReportPeriod.between(nov, YearMonth(2025, 3)))
        assertEquals(21, ReportPeriod.between(YearMonth(2025, 3), nov).monthCount)
    }

    @Test
    fun theBudgetStartsAtItsEarliestTransactionOrAssignment() {
        val b = Budget("dev").apply { seedDefaults() }
        assertNull(ReportPeriod.budgetStart(b.data))
        b.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2025, 6, 15))
        assertEquals(YearMonth(2025, 6), ReportPeriod.budgetStart(b.data))
        b.setAssigned(YearMonth(2025, 4), "seed-groceries", 5000)
        assertEquals(YearMonth(2025, 4), ReportPeriod.budgetStart(b.data))
    }

    @Test
    fun aLongRangeAddsUpEveryMonth() {
        val b = Budget("dev").apply {
            seedDefaults()
            saveAccount(Account("chk", "Checking"), 1000000, LocalDate(2025, 1, 1))
        }
        // $10 of groceries every month from Jan 2025 to Nov 2026.
        var m = YearMonth(2025, 1)
        while (m <= nov) {
            b.saveTransaction(Transaction("g$m", "chk", m.firstDay, -1000, "Store", categoryId = "seed-groceries"))
            m += 1
        }
        val all = ReportPeriod.between(ReportPeriod.budgetStart(b.data)!!, nov)
        val r = Reports.spending(b.data, all)
        assertEquals(23, all.monthCount)
        assertEquals(23000, r.total)
        assertEquals(23, r.byMonth.size)
        assertEquals(3000, Reports.spending(b.data, ReportPeriod.last3(nov)).total)
    }
}

/** The spending report limited to chosen categories. */
class SpendingFilterTest {
    private val oct = YearMonth(2026, 10)

    @Test
    fun onlyTheChosenCategoriesCount() {
        val b = Budget("dev").apply {
            seedDefaults()
            saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 1))
            saveTransaction(Transaction("g", "chk", LocalDate(2026, 10, 2), -6000, "Store", categoryId = "seed-groceries"))
            saveTransaction(Transaction("d", "chk", LocalDate(2026, 10, 3), -3000, "Cafe", categoryId = "seed-dining-out"))
            saveTransaction(
                Transaction(
                    "s", "chk", LocalDate(2026, 10, 4), -1000, "Big Box",
                    splits = listOf(com.z.imab.model.SplitLine("seed-groceries", -400), com.z.imab.model.SplitLine("seed-household", -600)),
                )
            )
            saveTransaction(Transaction("g9", "chk", LocalDate(2026, 9, 2), -5000, "Store", categoryId = "seed-groceries"))
        }
        val all = Reports.spending(b.data, ReportPeriod.month(oct))
        assertEquals(10000, all.total)
        val groceries = Reports.spending(b.data, ReportPeriod.month(oct), setOf("seed-groceries"))
        assertEquals(6400, groceries.total, "includes its share of the split")
        assertEquals(5000, groceries.previousTotal)
        assertEquals(listOf("seed-groceries"), groceries.byCategory.map { it.id })
        val two = Reports.spending(b.data, ReportPeriod.month(oct), setOf("seed-groceries", "seed-household"))
        assertEquals(7000, two.total)
        assertEquals(listOf("Needs"), two.byGroup.map { it.name })
    }
}
