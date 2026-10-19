package com.z.imab

import com.z.imab.budget.GoalBoard
import com.z.imab.budget.LoanMath
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.CombinedGoal
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CombinedGoalAndExtraTest {
    private val oct = YearMonth(2026, 10)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 2_000_000, LocalDate(2026, 7, 31))
    }

    @Test
    fun combinedGoalAddsUpItsCategories() {
        val b = budget()
        val house = CombinedGoal("h", "House", listOf("seed-emergency-fund", "seed-car-replacement"), target = 1_000_000)
        b.saveCombinedGoal(house)
        for (m in listOf(oct - 2, oct - 1, oct)) {
            b.setAssigned(m, "seed-emergency-fund", 30000)
            b.setAssigned(m, "seed-car-replacement", 20000)
        }
        val s = GoalBoard(b.data, oct).status(b.data.combinedGoals.getValue("h"))
        assertEquals(150000L, s.saved, "3 months x ($300 + $200)")
        assertEquals(850000L, s.remaining)
        assertEquals(50000L, s.recentPace)
        // $8,500 left at $500/month -> 17 more months.
        assertEquals(oct + 17, s.etaAtPace)
    }

    @Test
    fun combinedGoalWithDateSaysWhatToSaveMonthly() {
        val b = budget()
        b.saveCombinedGoal(CombinedGoal("h", "House", listOf("seed-emergency-fund", "seed-car-replacement"), 1_200_000, targetDate = LocalDate(2027, 9, 1)))
        val s = GoalBoard(b.data, oct).status(b.data.combinedGoals.getValue("h"))
        assertEquals(12, s.monthsLeft)
        assertEquals(100000L, s.assignThisMonth)
    }

    @Test
    fun deletingACategoryRemovesItFromCombinedGoals() {
        val b = budget()
        b.saveCombinedGoal(CombinedGoal("h", "House", listOf("seed-emergency-fund", "seed-car-replacement"), 1_000_000))
        b.deleteCategory("seed-car-replacement")
        assertEquals(listOf("seed-emergency-fund"), b.data.combinedGoals.getValue("h").categoryIds)
    }

    @Test
    fun extraPrincipalShortensPayoffAndSavesInterest() {
        // $300,000 at 6%, $1,798.65 principal & interest.
        val base = LoanMath.payoff(30_000_000, 6.0, 179865)!!
        val extra = LoanMath.payoff(30_000_000, 6.0, 179865 + 50000)!!
        assertTrue(base.months in 360..361)
        // Standard formula: n = -ln(1 - 1,500 / 2,298.65) / ln(1.005) = 211.97 -> 212 months (17 yr 8 mo).
        assertEquals(212, extra.months)
        assertTrue(base.totalInterest - extra.totalInterest > 10_000_000, "saved ${base.totalInterest - extra.totalInterest}")
    }
}
