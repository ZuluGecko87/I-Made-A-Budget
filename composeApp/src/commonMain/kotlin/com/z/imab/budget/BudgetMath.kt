package com.z.imab.budget

import com.z.imab.model.BudgetData
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.YearMonth

data class CategoryMonth(val assigned: Long = 0, val activity: Long = 0, val available: Long = 0)

data class MonthBudget(
    val month: YearMonth,
    /** Money that hasn't been given a job yet. Negative means more was assigned than you have. */
    val readyToAssign: Long,
    val categories: Map<String, CategoryMonth>,
    val incomeThisMonth: Long,
) {
    fun category(id: String) = categories[id] ?: CategoryMonth()
    val totalAssigned: Long get() = categories.values.sumOf { it.assigned }
    val totalActivity: Long get() = categories.values.sumOf { it.activity }
    val totalAvailable: Long get() = categories.values.sumOf { it.available }
    val overspentCount: Int get() = categories.values.count { it.available < 0 }
}

/**
 * Envelope budgeting:
 *  - Inflows categorized "Ready to Assign" (and anything uncategorized) feed Ready to Assign.
 *  - Each month, available = last month's available + assigned + activity. Balances carry forward,
 *    including overspending, until you cover it.
 *  - Ready to Assign = all inflows up to this month − everything assigned (in any month).
 *  - Transfers between two on-budget accounts don't touch the budget.
 */
object BudgetMath {
    fun compute(data: BudgetData, month: YearMonth): MonthBudget {
        val activity = HashMap<YearMonth, HashMap<String, Long>>()
        val toBeBudgeted = HashMap<YearMonth, Long>()

        for (t in data.transactions.values) {
            if (!data.isOnBudget(t.accountId) || data.isInternalTransfer(t)) continue
            val m = YearMonth.of(t.date)
            val cat = t.categoryId?.takeIf { it in data.categories }
            if (cat == null) {
                toBeBudgeted[m] = (toBeBudgeted[m] ?: 0) + t.amount
            } else {
                val byCat = activity.getOrPut(m) { HashMap() }
                byCat[cat] = (byCat[cat] ?: 0) + t.amount
            }
        }

        val assigned = HashMap<YearMonth, HashMap<String, Long>>()
        var assignedEver = 0L
        for (a in data.assignments.values) {
            if (a.categoryId !in data.categories || a.amount == 0L) continue
            val m = runCatching { YearMonth.parse(a.month) }.getOrNull() ?: continue
            assigned.getOrPut(m) { HashMap() }[a.categoryId] = a.amount
            assignedEver += a.amount
        }

        val firstMonth = (activity.keys + assigned.keys).minOrNull()
        val available = HashMap<String, Long>()
        var target = CategoryMonthMap()
        if (firstMonth != null && firstMonth <= month) {
            var m: YearMonth = firstMonth
            while (m <= month) {
                val act = activity[m].orEmpty()
                val asg = assigned[m].orEmpty()
                for (cat in act.keys + asg.keys) {
                    available[cat] = (available[cat] ?: 0) + (asg[cat] ?: 0) + (act[cat] ?: 0)
                }
                if (m == month) target = CategoryMonthMap(asg, act)
                m += 1
            }
        }

        val categories = data.categories.keys.associateWith { id ->
            CategoryMonth(
                assigned = target.assigned[id] ?: 0,
                activity = target.activity[id] ?: 0,
                available = available[id] ?: 0,
            )
        }
        val inflowsToDate = toBeBudgeted.filterKeys { it <= month }.values.sum()
        val income = data.transactions.values
            .filter {
                it.categoryId == READY_TO_ASSIGN && it.amount > 0 && YearMonth.of(it.date) == month &&
                    data.isOnBudget(it.accountId) && !data.isInternalTransfer(it)
            }
            .sumOf { it.amount }

        return MonthBudget(
            month = month,
            readyToAssign = inflowsToDate - assignedEver,
            categories = categories,
            incomeThisMonth = income,
        )
    }

    private class CategoryMonthMap(
        val assigned: Map<String, Long> = emptyMap(),
        val activity: Map<String, Long> = emptyMap(),
    )
}
