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
import kotlin.test.assertNull

class TrackingTransferTest {
    private val oct = YearMonth(2026, 10)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 500000, LocalDate(2026, 9, 30))
        it.saveAccount(Account("invest", "Brokerage", AccountType.INVESTMENT, onBudget = false))
    }

    @Test
    fun transferToTrackingAccountIsJustATransfer() {
        val b = budget()
        val rtaBefore = BudgetMath.compute(b.data, oct).readyToAssign
        b.saveTransaction(Transaction("t", "chk", LocalDate(2026, 10, 21), -5000, "Brokerage deposit", transferAccountId = "invest"))
        val t = b.data.transactions.getValue("t")
        assertNull(t.categoryId)
        assertEquals(0, b.data.uncategorizedCount, "no category needed")
        assertEquals("Transfer", b.data.categoryLabel(t))
        assertEquals("Transfer to Brokerage", b.data.payeeLabel(t))
        // The $50 leaves the budget out of Ready to Assign; Brokerage's balance goes up.
        assertEquals(rtaBefore - 5000, BudgetMath.compute(b.data, oct).readyToAssign)
        assertEquals(5000L, b.data.balance("invest"))
    }

    @Test
    fun moneyComingBackGoesIntoReadyToAssign() {
        val b = budget()
        val rtaBefore = BudgetMath.compute(b.data, oct).readyToAssign
        b.saveTransaction(Transaction("t", "invest", LocalDate(2026, 10, 22), -3000, transferAccountId = "chk"))
        val budgetSide = b.data.transactions.getValue(b.data.transactions.getValue("t").transferPairId!!)
        assertEquals("Transfer", b.data.categoryLabel(budgetSide))
        assertEquals(0, b.data.uncategorizedCount)
        assertEquals(rtaBefore + 3000, BudgetMath.compute(b.data, oct).readyToAssign)
    }
}
