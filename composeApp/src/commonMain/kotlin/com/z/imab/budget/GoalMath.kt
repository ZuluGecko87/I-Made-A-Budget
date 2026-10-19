package com.z.imab.budget

import com.z.imab.model.CategoryGoal
import com.z.imab.model.GoalType
import com.z.imab.model.YearMonth

data class GoalStatus(
    val goal: CategoryGoal,
    /**
     * Savings: money in the category at the end of the viewed month.
     * Monthly spending: money allocated for this month (carried over + assigned), before spending.
     */
    val saved: Long,
    val remaining: Long,
    /** 0..1 */
    val progress: Float,
    /** Average monthly change in the category over the last 3 months (assigned + spending). */
    val recentPace: Long,
    /** Months from the viewed month to the target date, counting both; null without a date. */
    val monthsLeft: Int?,
    /** What to assign in the viewed month to stay on plan (date, monthly amount, or the whole remainder). */
    val assignThisMonth: Long,
    /** Estimated month the goal is reached at the planned monthly amount. */
    val etaAtPlan: YearMonth?,
    /** Estimated month the goal is reached at the recent pace; null if not saving. */
    val etaAtPace: YearMonth?,
    /** For goals with a date: whether this month's assignment keeps it on schedule. */
    val onTrack: Boolean?,
    val overdue: Boolean,
    /** Spent from the category in the viewed month. */
    val spent: Long = 0,
) {
    /** Savings: target reached. Monthly spending: this month is fully funded. */
    val reached: Boolean get() = remaining == 0L
    val isMonthly: Boolean get() = goal.type == GoalType.MONTHLY_SPENDING

    /** More money this goal wants assigned in the viewed month. */
    fun shortfall(assigned: Long): Long = maxOf(0, assignThisMonth - assigned)
}

/** One goal's request when funding goals: how much is assigned now and how much it should be. */
data class GoalNeed(val categoryId: String, val assigned: Long, val wanted: Long, val monthly: Boolean)

object GoalMath {
    /**
     * [history] is the category's months, oldest first, ending with [month] (normally 3 entries).
     */
    fun status(goal: CategoryGoal, month: YearMonth, history: List<CategoryMonth>): GoalStatus {
        if (goal.type == GoalType.MONTHLY_SPENDING) return monthlyStatus(goal, history.last())
        val current = history.last()
        val saved = maxOf(0, current.available)
        val remaining = maxOf(0, goal.target - saved)
        val progress = if (goal.target <= 0) 1f else (saved.toFloat() / goal.target).coerceIn(0f, 1f)
        val pace = history.sumOf { it.assigned + it.activity } / history.size

        // What's left before this month's assignment, so the suggestion doesn't count it twice.
        val beforeAssigning = maxOf(0, goal.target - maxOf(0, current.available - current.assigned))
        val targetMonth = goal.targetDate?.let { YearMonth.of(it) }
        val overdue = targetMonth != null && targetMonth < month && remaining > 0
        val monthsLeft = targetMonth?.let { maxOf(1, monthsBetween(month, it) + 1) }
        val assignThisMonth = when {
            monthsLeft != null -> ceilDiv(beforeAssigning, monthsLeft.toLong())
            goal.monthly != null && goal.monthly > 0 -> minOf(goal.monthly, beforeAssigning)
            else -> beforeAssigning
        }

        return GoalStatus(
            goal = goal,
            saved = saved,
            remaining = remaining,
            progress = progress,
            recentPace = pace,
            monthsLeft = monthsLeft,
            assignThisMonth = assignThisMonth,
            etaAtPlan = goal.monthly?.takeIf { it > 0 }?.let { eta(month, remaining, it) },
            etaAtPace = pace.takeIf { it > 0 }?.let { eta(month, remaining, it) },
            onTrack = if (monthsLeft == null) null else remaining == 0L || current.assigned >= assignThisMonth,
            overdue = overdue,
        )
    }

    /**
     * Monthly spending: [CategoryGoal.target] should be available this month. Money carried over
     * (when rolling over) counts toward it, so only the difference needs assigning.
     */
    private fun monthlyStatus(goal: CategoryGoal, current: CategoryMonth): GoalStatus {
        val carried = current.available - current.assigned - current.activity
        val funded = maxOf(0, carried + current.assigned)
        val remaining = maxOf(0, goal.target - funded)
        return GoalStatus(
            goal = goal,
            saved = funded,
            remaining = remaining,
            progress = if (goal.target <= 0) 1f else (funded.toFloat() / goal.target).coerceIn(0f, 1f),
            recentPace = 0,
            monthsLeft = null,
            assignThisMonth = maxOf(0, goal.target - maxOf(0, carried)),
            etaAtPlan = null,
            etaAtPace = null,
            onTrack = remaining == 0L,
            overdue = false,
            spent = maxOf(0, -current.activity),
        )
    }

    /**
     * Fills goals from the money available to assign, without going over it: monthly spending goals
     * first (bills come first), then savings goals in the order given. Returns the new assigned amount
     * for each goal that gets money.
     */
    fun fundingPlan(needs: List<GoalNeed>, available: Long): Map<String, Long> {
        var left = maxOf(0, available)
        val plan = LinkedHashMap<String, Long>()
        for (n in needs.sortedBy { if (it.monthly) 0 else 1 }) {
            val gap = n.wanted - n.assigned
            if (gap <= 0 || left <= 0) continue
            val give = minOf(gap, left)
            plan[n.categoryId] = n.assigned + give
            left -= give
        }
        return plan
    }

    /** The month the goal is reached saving [perMonth] from next month on (this month is already in [remaining]). */
    fun eta(month: YearMonth, remaining: Long, perMonth: Long): YearMonth =
        if (remaining <= 0) month else month + ceilDiv(remaining, perMonth).toInt()

    fun monthsBetween(from: YearMonth, to: YearMonth): Int = (to.year - from.year) * 12 + (to.month - from.month)

    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b
}
