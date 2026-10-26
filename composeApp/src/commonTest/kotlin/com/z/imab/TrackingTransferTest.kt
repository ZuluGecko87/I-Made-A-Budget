package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingTransferTest {
    private val oct = YearMonth(2026, 10)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 500000, LocalDate(2026, 9, 30))
        it.saveAccount(Account("invest", "Brokerage", AccountType.INVESTMENT, onBudget = false))
    }

    @Test
    fun transferToTrackingAccountKeepsItsBudgetCategory() {
        val b = budget()
        b.setAssigned(oct, "seed-car-replacement", 5000)
        b.saveTransaction(Transaction("t", "chk", LocalDate(2026, 10, 21), -5000, "Brokerage deposit", categoryId = "seed-car-replacement", transferAccountId = "invest"))
        val t = b.data.transactions.getValue("t")
        assertEquals("seed-car-replacement", t.categoryId)
        assertEquals("invest", t.transferAccountId)
        assertEquals(0, b.data.uncategorizedCount)
        assertEquals("Transfer to Brokerage", b.data.payeeLabel(t))
        // The $50 leaves the budget from that category; Brokerage's balance goes up.
        assertEquals(-5000L, BudgetMath.compute(b.data, oct).category("seed-car-replacement").activity)
        assertEquals(5000L, b.data.balance("invest"))
    }

    @Test
    fun withoutACategoryItSaysWhatIsNeeded() {
        val b = budget()
        b.saveTransaction(Transaction("t", "chk", LocalDate(2026, 10, 21), -5000, transferAccountId = "invest"))
        assertEquals(1, b.data.uncategorizedCount)
        assertEquals("Transfer — needs a budget category", b.data.categoryLabel(b.data.transactions.getValue("t")))
    }

    @Test
    fun choosingTheCategoryFromTheTrackingSideUpdatesTheBudgetSide() {
        val b = budget()
        b.saveTransaction(Transaction("t", "chk", LocalDate(2026, 10, 21), -5000, transferAccountId = "invest"))
        val leg = b.data.transactions.getValue(b.data.transactions.getValue("t").transferPairId!!)
        // Edited from Brokerage's register, choosing the category there.
        b.saveTransaction(leg.copy(categoryId = "seed-gifts"))
        assertEquals("seed-gifts", b.data.transactions.getValue("t").categoryId)
        assertEquals(0, b.data.uncategorizedCount)
    }
}
