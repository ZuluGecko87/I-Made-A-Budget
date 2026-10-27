package com.z.imab.budget

import com.z.imab.model.BudgetData
import com.z.imab.model.PREEXISTING_DEBT
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.YearMonth
import com.z.imab.model.paymentCategoryId

data class CategoryMonth(
    val assigned: Long = 0,
    val activity: Long = 0,
    val available: Long = 0,
    /** Part of a negative [available] caused by credit card spending (it becomes card debt, not a budget hit). */
    val creditOverspent: Long = 0,
) {
    val cashOverspent: Long get() = if (available < 0) -available - creditOverspent else 0
}

/** A credit account's payment category alongside what is actually owed on the card. */
data class CardPayment(
    val accountId: String,
    val categoryId: String,
    /** What you owe on the card right now (positive = debt). */
    val owed: Long,
    /** Money in the payment category this month. */
    val available: Long,
    /**
     * This month: money that moved into the payment category from the categories of card spending
     * (net of refunds). It's part of the payment category's activity, not its assigned amount.
     */
    val fromSpending: Long = 0,
    /** This month: payments made to the card (net of any balance transfers out). */
    val paid: Long = 0,
    /**
     * Debt the card had when it was added (Pre-existing debt) that hasn't had money assigned
     * toward it yet. It's part of [underfunded], and isn't caused by spending.
     */
    val startingDebtLeft: Long = 0,
) {
    /** Debt that no budgeted money is set aside for yet. */
    val underfunded: Long get() = maxOf(0, owed - maxOf(0, available))

    /** The part of [underfunded] that came from spending more than categories had (credit overspending). */
    val overspentDebt: Long get() = maxOf(0, underfunded - startingDebtLeft)
}

data class MonthBudget(
    val month: YearMonth,
    /** Money that hasn't been given a job yet. Negative means more was assigned than you have. */
    val readyToAssign: Long,
    /** Regular categories and credit card payment categories, keyed by category id. */
    val categories: Map<String, CategoryMonth>,
    val cards: List<CardPayment>,
    val incomeThisMonth: Long,
    /** Cash overspending from last month that was taken out of this month's Ready to Assign. */
    val overspentLastMonth: Long,
    /** Money left last month in categories that don't roll over, returned to Ready to Assign. */
    val returnedLastMonth: Long = 0,
) {
    fun category(id: String) = categories[id] ?: CategoryMonth()
    val overspentCount: Int get() = categories.values.count { it.available < 0 }
}

/**
 * Envelope budgeting.
 *
 * Every month, for each category: available = carried over + assigned + activity.
 *  - Inflows categorized "Ready to Assign" (and anything uncategorized) feed Ready to Assign.
 *  - Ready to Assign = inflows up to this month − everything assigned − cash overspending from earlier months.
 *  - Transfers between two budget accounts don't touch categories, except credit card payments (below).
 *
 * Credit cards (and lines of credit) each have a payment category:
 *  - Spending on the card in category X moves the money from X into the card's payment category,
 *    as far as X has money to cover it. Refunds on the card move it back.
 *  - Paying the card (a transfer from a budget account) spends from the payment category; a cash
 *    advance (a transfer out of the card) adds to it, because you'll owe that money back.
 *  - Uncategorized card spending comes out of Ready to Assign into the payment category; a reward
 *    credited to the card as Ready to Assign comes out of the payment category.
 *  - Debt from before the card was added ("pre-existing debt") isn't in the budget; you pay it down
 *    by assigning money to the payment category.
 *
 * A category whose goal is monthly spending without roll over starts each month empty; what was
 * left goes back to Ready to Assign.
 *
 * At the end of a month a negative balance resets to zero:
 *  - cash overspending is taken out of next month's Ready to Assign;
 *  - credit overspending (card spending with no money behind it) just stays as card debt.
 */
object BudgetMath {
    private class MonthActivity {
        /** category -> spending from cash accounts */
        val cash = HashMap<String, Long>()
        /** category -> card account -> spending on that card */
        val credit = HashMap<String, HashMap<String, Long>>()
        /** card account -> payments (negative) and cash advances (positive) */
        val cardTransfers = HashMap<String, Long>()
        /** inflows to Ready to Assign, plus uncategorized activity */
        var toBeBudgeted = 0L
        /** card account -> Ready to Assign / uncategorized activity on that card */
        val cardUnbudgeted = HashMap<String, Long>()
    }

    fun compute(data: BudgetData, month: YearMonth): MonthBudget {
        val cards = data.creditAccounts.map { it.id }
        val paymentIds = cards.associateWith { paymentCategoryId(it) }
        val activity = HashMap<YearMonth, MonthActivity>()
        fun at(m: YearMonth) = activity.getOrPut(m) { MonthActivity() }

        for (t in data.transactions.values) {
            // Principal entries in a loan account are tracking only; the payment itself is budgeted.
            if (!data.isOnBudget(t.accountId) || t.loanPaymentOf != null) continue
            val m = YearMonth.of(t.date)
            val onCard = t.accountId in paymentIds
            if (data.isInternalTransfer(t)) {
                if (onCard) at(m).cardTransfers.add(t.accountId, t.amount)
                continue
            }
            if (onCard && t.categoryId == PREEXISTING_DEBT) continue
            val cat = t.categoryId?.takeIf { it in data.categories }
            when {
                cat == null -> {
                    at(m).toBeBudgeted += t.amount
                    // Uncategorized card spending (or a reward credited as Ready to Assign) changes the
                    // debt, so the payment category moves the opposite way to keep the budget balanced.
                    if (onCard) at(m).cardUnbudgeted.add(t.accountId, t.amount)
                }
                onCard -> at(m).credit.getOrPut(cat) { HashMap() }.add(t.accountId, t.amount)
                else -> at(m).cash.add(cat, t.amount)
            }
        }

        val validIds = data.categories.keys + paymentIds.values
        val assigned = HashMap<YearMonth, HashMap<String, Long>>()
        var assignedEver = 0L
        for (a in data.assignments.values) {
            if (a.categoryId !in validIds || a.amount == 0L) continue
            val m = runCatching { YearMonth.parse(a.month) }.getOrNull() ?: continue
            assigned.getOrPut(m) { HashMap() }[a.categoryId] = a.amount
            assignedEver += a.amount
        }

        val carry = HashMap<String, Long>()
        val cashOverspentBy = HashMap<YearMonth, Long>()
        val returnedBy = HashMap<YearMonth, Long>()
        val rollsOver = data.categories.mapValues { (_, c) -> c.goal?.rollsOver ?: true }
        var result: Map<String, CategoryMonth> = emptyMap()
        var resultFromSpending: Map<String, Long> = emptyMap()
        var resultPaid: Map<String, Long> = emptyMap()
        val firstMonth = (activity.keys + assigned.keys).minOrNull()

        var m = firstMonth ?: month
        while (firstMonth != null && m <= month) {
            val act = activity[m] ?: MonthActivity()
            val asg = assigned[m].orEmpty()
            val months = HashMap<String, CategoryMonth>()
            val intoPayment = HashMap<String, Long>()
            var cashOverspent = 0L
            var returned = 0L

            for (cat in data.categories.keys) {
                val byCard = act.credit[cat].orEmpty()
                val spent = act.cash[cat] ?: 0
                val start = (carry[cat] ?: 0) + (asg[cat] ?: 0)
                val total = spent + byCard.values.sum()
                val end = start + total

                // Card spending is covered as far as the category has money; the rest is credit overspending.
                val cardSpend = byCard.mapValues { (_, v) -> maxOf(0, -v) }
                var uncovered = if (end < 0) minOf(-end, cardSpend.values.sum()) else 0
                val creditOverspent = uncovered
                for (card in byCard.keys.sorted()) {
                    val net = byCard.getValue(card)
                    val unfunded = minOf(uncovered, cardSpend.getValue(card))
                    uncovered -= unfunded
                    intoPayment.add(card, -net - unfunded)
                }
                if (end < 0) cashOverspent += -end - creditOverspent

                months[cat] = CategoryMonth(asg[cat] ?: 0, total, end, creditOverspent)
                // Leftovers in a category that doesn't roll over go back to Ready to Assign.
                if (rollsOver[cat] == false) {
                    returned += maxOf(0, end)
                    carry[cat] = 0
                } else {
                    carry[cat] = maxOf(0, end)
                }
            }

            for ((card, pid) in paymentIds) {
                // Paying the card (positive on the card side) uses up the payment category.
                val total = (intoPayment[card] ?: 0) - (act.cardTransfers[card] ?: 0) - (act.cardUnbudgeted[card] ?: 0)
                val end = (carry[pid] ?: 0) + (asg[pid] ?: 0) + total
                months[pid] = CategoryMonth(asg[pid] ?: 0, total, end)
                if (end < 0) cashOverspent += -end
                carry[pid] = maxOf(0, end)
            }

            cashOverspentBy[m] = cashOverspent
            returnedBy[m] = returned
            if (m == month) {
                result = months
                resultFromSpending = cards.associateWith { (intoPayment[it] ?: 0) - (act.cardUnbudgeted[it] ?: 0) }
                resultPaid = cards.associateWith { act.cardTransfers[it] ?: 0 }
            }
            m += 1
        }

        val categories = validIds.associateWith { result[it] ?: CategoryMonth() }
        val inflows = activity.filterKeys { it <= month }.values.sumOf { it.toBeBudgeted }
        val pastOverspending = cashOverspentBy.filterKeys { it < month }.values.sum()
        val pastReturned = returnedBy.filterKeys { it < month }.values.sum()
        val income = data.transactions.values
            .filter {
                it.categoryId == READY_TO_ASSIGN && it.amount > 0 && YearMonth.of(it.date) == month &&
                    data.isOnBudget(it.accountId) && !data.isInternalTransfer(it)
            }
            .sumOf { it.amount }

        return MonthBudget(
            month = month,
            readyToAssign = inflows - assignedEver - pastOverspending + pastReturned,
            categories = categories,
            cards = paymentIds.map { (card, pid) ->
                val owed = -data.balance(card)
                val available = categories.getValue(pid).available
                // Starting debt not yet paid for by money assigned to the payment category.
                val startingDebt = -(data.transactionsByAccount[card].orEmpty()
                    .filter { it.categoryId == PREEXISTING_DEBT && YearMonth.of(it.date) <= month }.sumOf { it.amount })
                val assignedToCard = data.assignments.values
                    .filter { it.categoryId == pid && (runCatching { YearMonth.parse(it.month) }.getOrNull()?.let { m -> m <= month } == true) }
                    .sumOf { it.amount }
                val underfunded = maxOf(0, owed - maxOf(0, available))
                CardPayment(
                    card, pid, owed = owed, available = available,
                    fromSpending = resultFromSpending[card] ?: 0, paid = resultPaid[card] ?: 0,
                    startingDebtLeft = minOf(underfunded, maxOf(0, startingDebt - assignedToCard)),
                )
            },
            incomeThisMonth = income,
            overspentLastMonth = cashOverspentBy[month - 1] ?: 0,
            returnedLastMonth = returnedBy[month - 1] ?: 0,
        )
    }

    /**
     * The most that can be assigned to [categoryId] in [month] without assigning more money than you
     * have: what's there now plus Ready to Assign, using the tighter of [month] and [currentMonth]
     * (a past month has had less income, and an assignment anywhere uses up money now).
     */
    fun maxAssignable(data: BudgetData, month: YearMonth, currentMonth: YearMonth, categoryId: String): Long =
        compute(data, month).category(categoryId).assigned + freeToAssign(data, month, currentMonth)

    /** Ready to Assign that can still be given out in [month] (never negative). */
    fun freeToAssign(data: BudgetData, month: YearMonth, currentMonth: YearMonth): Long {
        val viewed = compute(data, month).readyToAssign
        return maxOf(0, if (month == currentMonth) viewed else minOf(viewed, compute(data, currentMonth).readyToAssign))
    }

    private fun HashMap<String, Long>.add(key: String, amount: Long) {
        this[key] = (this[key] ?: 0) + amount
    }
}
