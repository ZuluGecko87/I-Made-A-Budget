package com.z.imab

import com.z.imab.budget.ReportPeriod
import com.z.imab.budget.Reports
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Net worth over time: every account, owed amounts counting against it. */
class NetWorthTest {
    private val aug = YearMonth(2026, 8)
    private val oct = YearMonth(2026, 10)
    private val today = LocalDate(2026, 10, 20)

    /** From August: $5,000 checking, $1,000 owed on a card, $12,000 car loan, $8,000 invested: $0 net. */
    private fun budget() = Budget("dev").apply {
        seedDefaults()
        saveAccount(Account("chk", "Checking"), 500000, LocalDate(2026, 8, 1))
        saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD), -100000, LocalDate(2026, 8, 1))
        saveAccount(Account("car", "Car loan", AccountType.LOAN), -1200000, LocalDate(2026, 8, 1))
        saveAccount(Account("inv", "Brokerage", AccountType.INVESTMENT, onBudget = false), 800000, LocalDate(2026, 8, 1))
    }

    @Test
    fun ownedMinusOwedEachMonth() {
        val b = budget()
        // September: pay $400 toward the loan from checking. October: $300 on the card, $200 invested gain.
        b.saveTransaction(Transaction("p", "chk", LocalDate(2026, 9, 10), -40000, transferAccountId = "car"))
        b.saveTransaction(Transaction("c", "visa", LocalDate(2026, 10, 5), -30000, "Store", categoryId = "seed-groceries"))
        b.saveTransaction(Transaction("g", "inv", LocalDate(2026, 10, 15), 20000, "Market gain"))
        // Dated after "today": not counted yet.
        b.saveTransaction(Transaction("later", "chk", LocalDate(2026, 10, 28), -10000, "Later"))

        val r = Reports.netWorth(b.data, ReportPeriod(aug, YearMonth(2026, 12)), today)
        assertEquals(listOf(aug, aug + 1, oct), r.points.map { it.month }, "no future months")
        // A loan payment from checking moves money without changing net worth; the card charge and gain do.
        assertEquals(listOf(0L, 0L, -10000L), r.points.map { it.netWorth })
        val now = r.end!!
        assertEquals(460000 + 820000, now.assets)
        assertEquals(-(130000 + 1160000), now.debts)
        assertEquals(0, r.before, "nothing before August")
        assertEquals(-10000, r.change)

        assertEquals(listOf("Brokerage", "Checking"), r.assets.map { it.name })
        assertEquals("Investment · tracking", r.assets.first().group)
        assertEquals(listOf("Car loan", "Visa"), r.debts.map { it.name })
        assertEquals(-1160000, r.debts.first().amount)
    }

    @Test
    fun changeIsSinceTheMonthBeforeThePeriod() {
        val b = budget()
        b.saveTransaction(Transaction("c", "visa", LocalDate(2026, 10, 5), -30000, "Store", categoryId = "seed-groceries"))
        val r = Reports.netWorth(b.data, ReportPeriod.month(oct), today)
        assertEquals(0, r.before)
        assertEquals(-30000, r.change)
        assertEquals(-30000, r.end!!.netWorth)
        assertEquals(1, r.points.size)
    }

    @Test
    fun anOverdrawnAccountIsADebt() {
        val b = budget()
        b.saveTransaction(Transaction("big", "chk", LocalDate(2026, 10, 1), -600000, "Rent"))
        val r = Reports.netWorth(b.data, ReportPeriod.month(oct), today)
        assertEquals(listOf("Car loan", "Checking", "Visa"), r.debts.map { it.name })
    }
}
