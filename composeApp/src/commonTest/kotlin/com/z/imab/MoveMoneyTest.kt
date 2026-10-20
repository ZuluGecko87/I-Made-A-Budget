package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveMoneyTest {
    private val oct = YearMonth(2026, 10)
    private val groceries = "seed-groceries"
    private val dining = "seed-dining-out"

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        it.setAssigned(oct, groceries, 30000)
        it.setAssigned(oct, dining, 5000)
        // $80 at a restaurant: dining is $30 overspent.
        it.saveTransaction(Transaction("d", "chk", LocalDate(2026, 10, 3), -8000, "Bistro", categoryId = dining))
    }

    @Test
    fun coverOverspendingFromAnotherCategory() {
        val b = budget()
        assertEquals(-3000L, BudgetMath.compute(b.data, oct).category(dining).available)
        b.moveMoney(oct, groceries, dining, 3000)
        val m = BudgetMath.compute(b.data, oct)
        assertEquals(0L, m.category(dining).available)
        assertEquals(27000L, m.category(groceries).available)
        assertEquals(65000L, m.readyToAssign, "Ready to Assign is unchanged by moving between categories")
    }

    @Test
    fun coverOverspendingFromReadyToAssign() {
        val b = budget()
        b.moveMoney(oct, null, dining, 3000)
        val m = BudgetMath.compute(b.data, oct)
        assertEquals(0L, m.category(dining).available)
        assertEquals(8000L, m.category(dining).assigned)
        assertEquals(62000L, m.readyToAssign)
    }

    @Test
    fun moveMoneyBackToReadyToAssign() {
        val b = budget()
        b.moveMoney(oct, groceries, null, 10000)
        val m = BudgetMath.compute(b.data, oct)
        assertEquals(20000L, m.category(groceries).assigned)
        assertEquals(75000L, m.readyToAssign)
    }

    @Test
    fun undoPutsBothCategoriesBack() {
        val b = budget()
        val before = mapOf(groceries to 30000L, dining to 5000L)
        b.moveMoney(oct, groceries, dining, 3000)
        b.setAssignedAll(oct, before)
        val m = BudgetMath.compute(b.data, oct)
        assertEquals(30000L, m.category(groceries).assigned)
        assertEquals(5000L, m.category(dining).assigned)
    }
}
