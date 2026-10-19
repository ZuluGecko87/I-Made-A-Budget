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
) {
    /** Debt that no budgeted money is set aside for yet. */
    val underfunded: Long get() = maxOf(0, owed - maxOf(0, available))
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
        var result: Map<String, CategoryMonth> = emptyMap()
        val firstMonth = (activity.keys + assigned.keys).minOrNull()

        var m = firstMonth ?: month
        while (firstMonth != null && m <= month) {
            val act = activity[m] ?: MonthActivity()
            val asg = assigned[m].orEmpty()
            val months = HashMap<String, CategoryMonth>()
            val intoPayment = HashMap<String, Long>()
            var cashOverspent = 0L

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
                carry[cat] = maxOf(0, end)
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
            if (m == month) result = months
            m += 1
        }

        val categories = validIds.associateWith { result[it] ?: CategoryMonth() }
        val inflows = activity.filterKeys { it <= month }.values.sumOf { it.toBeBudgeted }
        val pastOverspending = cashOverspentBy.filterKeys { it < month }.values.sum()
        val income = data.transactions.values
            .filter {
                it.categoryId == READY_TO_ASSIGN && it.amount > 0 && YearMonth.of(it.date) == month &&
                    data.isOnBudget(it.accountId) && !data.isInternalTransfer(it)
            }
            .sumOf { it.amount }

        return MonthBudget(
            month = month,
            readyToAssign = inflows - assignedEver - pastOverspending,
            categories = categories,
            cards = paymentIds.map { (card, pid) ->
                CardPayment(card, pid, owed = -data.balance(card), available = categories.getValue(pid).available)
            },
            incomeThisMonth = income,
            overspentLastMonth = cashOverspentBy[month - 1] ?: 0,
        )
    }

    private fun HashMap<String, Long>.add(key: String, amount: Long) {
        this[key] = (this[key] ?: 0) + amount
    }
}
