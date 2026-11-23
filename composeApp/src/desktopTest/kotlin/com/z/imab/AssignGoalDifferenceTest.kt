package com.z.imab

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.budget.BudgetMath
import com.z.imab.budget.GoalMath
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.CategoryGoal
import com.z.imab.model.GoalType
import com.z.imab.model.YearMonth
import com.z.imab.ui.AssignDialog
import com.z.imab.ui.LightColors
import com.z.imab.ui.today
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Assign dialog offers to add just the difference a goal still needs, not its whole amount. */
@OptIn(ExperimentalTestApi::class)
class AssignGoalDifferenceTest {
    private fun budget(goal: CategoryGoal, assigned: Long): Pair<Budget, YearMonth> {
        var now = 1L
        val month = YearMonth.of(today())
        val b = Budget("dev") { now++ }.apply {
            seedDefaults()
            saveAccount(Account("chk", "Checking"), 500_000, month.firstDay)
            saveCategory(data.categories.getValue("seed-groceries").copy(goal = goal))
            setAssigned(month, "seed-groceries", assigned)
        }
        return b to month
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(b: Budget, month: YearMonth, onSave: (Long) -> Unit) {
        val data = b.data
        val mb = BudgetMath.compute(data, month)
        val history = listOf(month - 2, month - 1, month).map { BudgetMath.compute(data, it).category("seed-groceries") }
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                AssignDialog(
                    data = data, mb = mb, categoryId = "seed-groceries", name = "Groceries", month = month,
                    lastMonth = history[1],
                    goal = GoalMath.status(data.categories.getValue("seed-groceries").goal!!, month, history),
                    free = BudgetMath.freeToAssign(data, month, month),
                    coverOverspending = false,
                    onDismiss = {}, onSave = { amount, _, _ -> onSave(amount) }, onMove = { _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun savingsGoalAddsOnlyWhatsMissing() = runComposeUiTest {
        // $1,000 target, $100 a month planned, $40 already assigned this month.
        val (b, month) = budget(CategoryGoal(100_000, monthly = 10_000), 4_000)
        var saved: Long? = null
        show(b, month) { saved = it }
        onNodeWithText("Meet this month's goal (+$60.00)").performClick()
        onNodeWithText("Save").performClick()
        assertEquals(10_000, saved)
    }

    @Test
    fun savingsGoalCanBeFinishedNow() = runComposeUiTest {
        val (b, month) = budget(CategoryGoal(100_000, monthly = 10_000), 4_000)
        var saved: Long? = null
        show(b, month) { saved = it }
        onNodeWithText("Reach the target now (+$960.00)").performClick()
        onNodeWithText("Save").performClick()
        assertEquals(100_000, saved)
    }

    @Test
    fun monthlyGoalAddsTheDifferenceOnce() = runComposeUiTest {
        // $500 a month wanted, $350 assigned: one button for the $150 still missing.
        val (b, month) = budget(CategoryGoal(50_000, type = GoalType.MONTHLY_SPENDING), 35_000)
        var saved: Long? = null
        show(b, month) { saved = it }
        onNodeWithText("Reach the target now", substring = true).assertDoesNotExist()
        onNodeWithText("Meet this month's goal (+$150.00)").performClick()
        onNodeWithText("Save").performClick()
        assertEquals(50_000, saved)
    }
}
