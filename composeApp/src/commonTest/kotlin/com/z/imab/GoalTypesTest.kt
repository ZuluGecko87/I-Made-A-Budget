package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.budget.CategoryMonth
import com.z.imab.budget.GoalMath
import com.z.imab.budget.GoalNeed
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.CategoryGoal
import com.z.imab.model.GoalType
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalTypesTest {
    private val oct = YearMonth(2026, 10)
    private val nov = YearMonth(2026, 11)
    private val rent = "seed-rent-mortgage"
    private val dining = "seed-dining-out"

    /** $5,000 to budget, starting in October. */
    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 500000, LocalDate(2026, 9, 30))
    }

    private fun Budget.goal(categoryId: String, goal: CategoryGoal) =
        saveCategory(data.categories.getValue(categoryId).copy(goal = goal))

    private fun Budget.spend(day: Int, cents: Long, category: String, month: Int = 10) =
        saveTransaction(Transaction("s-$category-$month-$day", "chk", LocalDate(2026, month, day), -cents, "Store", categoryId = category))

    @Test
    fun monthlySpendingWithoutRolloverReturnsLeftovers() {
        val b = budget()
        b.goal(dining, CategoryGoal(20000, type = GoalType.MONTHLY_SPENDING, rollover = false))
        b.setAssigned(oct, dining, 20000)
        b.spend(5, 15000, dining)
        assertEquals(5000L, BudgetMath.compute(b.data, oct).category(dining).available)

        val n = BudgetMath.compute(b.data, nov)
        assertEquals(0L, n.category(dining).available, "starts the month empty")
        assertEquals(5000L, n.returnedLastMonth)
        // $5,000 - $200 assigned + $50 returned.
        assertEquals(485000L, n.readyToAssign)
    }

    @Test
    fun monthlySpendingWithRolloverCarriesAndNeedsLess() {
        val b = budget()
        b.goal(rent, CategoryGoal(150000, type = GoalType.MONTHLY_SPENDING, rollover = true))
        b.setAssigned(oct, rent, 160000) // $100 extra in October
        b.spend(1, 150000, rent)
        val history = listOf(BudgetMath.compute(b.data, nov).category(rent))
        val s = GoalMath.status(b.data.categories.getValue(rent).goal!!, nov, history)
        assertEquals(10000L, s.saved, "the extra $100 carried over")
        assertEquals(140000L, s.assignThisMonth, "only $1,400 more is needed in November")
        assertEquals(140000L, s.remaining)
    }

    @Test
    fun monthlyStatusTracksAllocationAndSpending() {
        val goal = CategoryGoal(150000, type = GoalType.MONTHLY_SPENDING)
        val half = GoalMath.status(goal, oct, listOf(CategoryMonth(assigned = 75000, activity = -20000, available = 55000)))
        assertEquals(75000L, half.saved, "allocated this month, before spending")
        assertEquals(75000L, half.remaining)
        assertEquals(0.5f, half.progress)
        assertEquals(20000L, half.spent)
        val full = GoalMath.status(goal, oct, listOf(CategoryMonth(assigned = 150000, activity = -150000, available = 0)))
        assertTrue(full.reached)
        assertEquals(true, full.onTrack)
    }

    @Test
    fun cannotAssignMoreThanIsAvailable() {
        val b = budget()
        assertEquals(500000L, BudgetMath.maxAssignable(b.data, oct, oct, rent))
        b.setAssigned(oct, rent, 150000)
        b.setAssigned(oct, dining, 300000)
        // $500 left: rent can go up to $1,500 + $500.
        assertEquals(200000L, BudgetMath.maxAssignable(b.data, oct, oct, rent))
        // Assigning in a future month uses the same money.
        assertEquals(50000L, BudgetMath.maxAssignable(b.data, nov, oct, rent))
        // Over-assigned: nothing more can be added, but lowering is fine.
        b.setAssigned(oct, dining, 400000)
        assertEquals(150000L, BudgetMath.maxAssignable(b.data, oct, oct, rent))
    }

    @Test
    fun fundingPlanPutsBillsFirstAndStopsAtAvailable() {
        val needs = listOf(
            GoalNeed("vacation", assigned = 0, wanted = 30000, monthly = false),
            GoalNeed("rent", assigned = 50000, wanted = 150000, monthly = true),
            GoalNeed("phone", assigned = 0, wanted = 8000, monthly = true),
            GoalNeed("done", assigned = 5000, wanted = 5000, monthly = true),
        )
        val plan = GoalMath.fundingPlan(needs, available = 120000)
        assertEquals(150000L, plan["rent"])
        assertEquals(8000L, plan["phone"])
        assertEquals(12000L, plan["vacation"], "gets what's left")
        assertTrue("done" !in plan)
        assertEquals(120000L, plan.map { (k, v) -> v - needs.first { it.categoryId == k }.assigned }.sum())
    }
}
