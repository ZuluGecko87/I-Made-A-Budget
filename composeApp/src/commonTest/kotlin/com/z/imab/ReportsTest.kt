package com.z.imab

import com.z.imab.budget.RecurringSource
import com.z.imab.budget.ReportPeriod
import com.z.imab.budget.Reports
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Frequency
import com.z.imab.model.LoanSplit
import com.z.imab.model.LoanTerms
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportsTest {
    private val aug = YearMonth(2026, 8)
    private val sep = YearMonth(2026, 9)
    private val oct = YearMonth(2026, 10)
    private var n = 0

    private fun Budget.tx(acct: String, m: YearMonth, day: Int, cents: Long, payee: String, cat: String? = null, xfer: String? = null) =
        saveTransaction(Transaction("t${n++}", acct, LocalDate(m.year, m.month, day), cents, payee, categoryId = cat, transferAccountId = xfer))

    /** Three months of pay, groceries, Netflix and a mortgage; a card that gets paid off. */
    private fun budget() = Budget("dev").also { b ->
        b.seedDefaults()
        b.saveAccount(Account("chk", "Checking"), 500000, LocalDate(2026, 7, 31))
        b.saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD))
        b.saveAccount(Account("home", "Home", AccountType.MORTGAGE, loan = LoanTerms(6.0, 40000, monthlyPayment = 250000)), -30_000_000, LocalDate(2026, 7, 31))
        for (m in listOf(aug, sep, oct)) {
            b.tx("chk", m, 1, 600000, "Acme Payroll", READY_TO_ASSIGN)
            b.tx("visa", m, 5, -10000, "Kroger", "seed-groceries")
            b.tx("chk", m, 9, -1999, "Netflix", "seed-entertainment")
            b.saveTransaction(Transaction("m${n++}", "chk", LocalDate(m.year, m.month, 15), -250000, "Main Street Mortgage", categoryId = "seed-rent-mortgage", loanSplit = LoanSplit("home", 0, 0, 0)))
            b.tx("chk", m, 20, -10000, "", xfer = "visa") // pay the card
        }
        b.tx("visa", oct, 6, 2500, "Kroger refund", "seed-groceries")
    }

    @Test
    fun spendingByCategoryAndMonthNetOfRefunds() {
        val r = Reports.spending(budget().data, ReportPeriod.month(oct))
        // Oct: mortgage 2,500 + groceries 100 - 25 refund + Netflix 19.99. Card payment isn't spending.
        assertEquals(259499L, r.total)
        assertEquals(7500L, r.byCategory.first { it.id == "seed-groceries" }.amount)
        assertEquals("seed-rent-mortgage", r.byCategory.first().id, "largest first")
        assertEquals(261999L, r.previousTotal, "September")
        val q = Reports.spending(budget().data, ReportPeriod(aug, oct))
        assertEquals(listOf(261999L, 261999L, 259499L), q.byMonth.map { it.second })
        assertTrue(q.byGroup.first().name == "Bills")
    }

    @Test
    fun incomeIgnoresStartingBalances() {
        val r = Reports.income(budget().data, ReportPeriod(aug, oct))
        assertEquals(1_800_000L, r.total)
        assertEquals(listOf("Acme Payroll"), r.bySource.map { it.name })
        assertEquals(0L, r.previousTotal, "the starting balance in July isn't income")
    }

    @Test
    fun recurringIncludesSchedulesAndDetectsRepeatPayees() {
        val b = budget()
        b.saveScheduled(ScheduledTransaction("gym", "chk", LocalDate(2026, 11, 1), LocalDate(2026, 11, 1), Frequency.EVERY_2_WEEKS, -1500, "Gym"))
        b.saveScheduled(ScheduledTransaction("ins", "chk", LocalDate(2027, 1, 1), LocalDate(2027, 1, 1), Frequency.YEARLY, -120000, "Car insurance"))
        val r = Reports.recurring(b.data, oct)
        val byName = r.items.associateBy { it.name }
        assertEquals(3250L, byName.getValue("Gym").monthly, "15 x 26 / 12")
        assertEquals(10000L, byName.getValue("Car insurance").monthly)
        assertEquals(RecurringSource.DETECTED, byName.getValue("Netflix").source)
        assertEquals(1999L, byName.getValue("Netflix").monthly)
        assertTrue("Acme Payroll" !in byName, "income isn't a payment")
        assertTrue("Kroger" !in byName || byName.getValue("Kroger").source == RecurringSource.DETECTED)
        assertEquals(r.items.sumOf { it.monthly } * 12, r.yearlyTotal)
    }

    @Test
    fun debtToIncomeUsesUsualLoanPaymentsAndCardPayments() {
        val r = Reports.debtToIncome(budget().data, ReportPeriod(aug, oct))
        assertEquals(600000L, r.monthlyIncome)
        // Mortgage usual payment 2,500 + card payments 100/month.
        assertEquals(260000L, r.monthlyDebt)
        assertEquals(0.4333, r.ratio!!, 0.001)
        assertEquals(250000L, r.monthlyHousing)
        assertEquals(0.4167, r.housingRatio!!, 0.001)
        assertEquals(listOf("Home", "Visa"), r.lines.map { it.name })
    }

    @Test
    fun noIncomeMeansNoRatio() {
        val b = Budget("dev").also { it.seedDefaults() }
        assertNull(Reports.debtToIncome(b.data, ReportPeriod.month(oct)).ratio)
    }

    @Test
    fun periodsStepAndCompare() {
        assertEquals(12, ReportPeriod.year(2026).monthCount)
        assertEquals(ReportPeriod(YearMonth(2025, 1), YearMonth(2025, 12)), ReportPeriod.year(2026).previous())
        assertEquals(ReportPeriod(YearMonth(2025, 11), oct), ReportPeriod.last12(oct))
    }

    @Test
    fun everydayShoppingIsNotRecurring() {
        val b = budget()
        // Several grocery trips a month for different amounts.
        for (m in listOf(aug, sep, oct)) {
            b.tx("chk", m, 11, -6400, "Kroger", "seed-groceries")
            b.tx("chk", m, 22, -12300, "Kroger", "seed-groceries")
        }
        val names = Reports.recurring(b.data, oct).items.map { it.name }
        assertTrue("Kroger" !in names)
        assertTrue("Netflix" in names)
    }
}
