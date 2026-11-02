package com.z.imab.budget

import com.z.imab.importer.ImportMatcher
import com.z.imab.model.BudgetData
import com.z.imab.model.Frequency
import com.z.imab.model.PREEXISTING_DEBT
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.STARTING_BALANCE_PAYEE
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlin.math.roundToLong

/** An inclusive range of months a report covers. */
data class ReportPeriod(val from: YearMonth, val to: YearMonth) {
    val months: List<YearMonth> by lazy {
        val out = mutableListOf<YearMonth>()
        var m = from
        while (m <= to) { out += m; m += 1 }
        out
    }
    val monthCount: Int get() = months.size

    /** The same length of time just before this one, for comparisons. */
    fun previous(): ReportPeriod = ReportPeriod(from - monthCount, from - 1)

    fun contains(t: Transaction) = YearMonth.of(t.date).let { it >= from && it <= to }

    companion object {
        fun month(m: YearMonth) = ReportPeriod(m, m)
        fun year(year: Int) = ReportPeriod(YearMonth(year, 1), YearMonth(year, 12))
        fun last12(ending: YearMonth) = ReportPeriod(ending - 11, ending)
    }
}

data class AmountRow(val id: String, val name: String, val group: String, val amount: Long)

data class SpendingReport(
    val total: Long,
    val previousTotal: Long,
    val byMonth: List<Pair<YearMonth, Long>>,
    val byCategory: List<AmountRow>,
    val byGroup: List<AmountRow>,
)

data class IncomeReport(
    val total: Long,
    val previousTotal: Long,
    val byMonth: List<Pair<YearMonth, Long>>,
    val bySource: List<AmountRow>,
)

enum class RecurringSource { SCHEDULED, DETECTED }

data class RecurringItem(
    val name: String,
    val categoryOrAccount: String,
    val frequency: String,
    /** What one payment usually is (positive = money out). */
    val typical: Long,
    /** That payment expressed per month. */
    val monthly: Long,
    val source: RecurringSource,
)

data class RecurringReport(val items: List<RecurringItem>) {
    val monthlyTotal: Long get() = items.sumOf { it.monthly }
    val yearlyTotal: Long get() = monthlyTotal * 12
}

data class DebtLine(val name: String, val monthly: Long, val basis: String)

data class DebtToIncomeReport(
    /** Average monthly income deposited in the period. */
    val monthlyIncome: Long,
    val monthlyDebt: Long,
    val lines: List<DebtLine>,
    /** Monthly payments on mortgages only (the "housing" or front-end ratio). */
    val monthlyHousing: Long,
    /** What's owed on cards and loans now. */
    val totalOwed: Long,
) {
    /** Debt payments as a share of income, or null with no income. */
    val ratio: Double? get() = if (monthlyIncome <= 0) null else monthlyDebt.toDouble() / monthlyIncome
    val housingRatio: Double? get() = if (monthlyIncome <= 0 || monthlyHousing == 0L) null else monthlyHousing.toDouble() / monthlyIncome
}

/** Report calculations. Everything counts budget accounts only, and transfers between them don't count. */
object Reports {
    private fun counts(data: BudgetData, t: Transaction) =
        data.isOnBudget(t.accountId) && !data.isInternalTransfer(t) && t.loanPaymentOf == null

    /**
     * Spending = money out of budget categories (refunds in the same category net against it).
     * Ready to Assign inflows, pre-existing debt and uncategorized transactions aren't spending.
     */
    fun spending(data: BudgetData, period: ReportPeriod): SpendingReport {
        // Split transactions count once per category line.
        fun spendingParts(t: Transaction) =
            if (counts(data, t)) t.parts().filter { it.categoryId != null && it.categoryId in data.categories } else emptyList()
        fun spent(t: Transaction) = -spendingParts(t).sumOf { it.amount }
        fun isSpending(t: Transaction) = spendingParts(t).isNotEmpty()
        val inPeriod = data.transactions.values.filter { isSpending(it) && period.contains(it) }
        val byCat = inPeriod.flatMap(::spendingParts).groupBy { it.categoryId!! }.mapValues { (_, l) -> -l.sumOf { it.amount } }
        val rows = byCat.map { (id, amount) ->
            val c = data.categories.getValue(id)
            AmountRow(id, c.name, data.groups[c.groupId]?.name ?: "", amount)
        }.filter { it.amount != 0L }.sortedByDescending { it.amount }
        val groups = rows.groupBy { it.group }.map { (g, l) -> AmountRow(g, g, g, l.sumOf { it.amount }) }.sortedByDescending { it.amount }
        val prev = period.previous()
        return SpendingReport(
            total = rows.sumOf { it.amount },
            previousTotal = data.transactions.values.filter { isSpending(it) && prev.contains(it) }.sumOf(::spent),
            byMonth = period.months.map { m -> m to inPeriod.filter { YearMonth.of(it.date) == m }.sumOf(::spent) },
            byCategory = rows,
            byGroup = groups,
        )
    }

    private fun isIncome(data: BudgetData, t: Transaction) =
        counts(data, t) && incomeOf(t) > 0 && t.payee != STARTING_BALANCE_PAYEE

    /** The part of a deposit that went to Ready to Assign (all of it, unless it's split). */
    private fun incomeOf(t: Transaction) = if (t.amount > 0) t.parts().filter { it.categoryId == READY_TO_ASSIGN }.sumOf { it.amount } else 0L

    /** Income = money coming in to Ready to Assign (not starting balances). */
    fun income(data: BudgetData, period: ReportPeriod): IncomeReport {
        val inPeriod = data.transactions.values.filter { isIncome(data, it) && period.contains(it) }
        val prev = period.previous()
        val sources = inPeriod.groupBy { it.payee.ifBlank { "(no payee)" } }
            .map { (p, l) -> AmountRow(p, p, "", l.sumOf(::incomeOf)) }
            .sortedByDescending { it.amount }
        return IncomeReport(
            total = inPeriod.sumOf(::incomeOf),
            previousTotal = data.transactions.values.filter { isIncome(data, it) && prev.contains(it) }.sumOf(::incomeOf),
            byMonth = period.months.map { m -> m to inPeriod.filter { YearMonth.of(it.date) == m }.sumOf(::incomeOf) },
            bySource = sources,
        )
    }

    /** How many times a year a schedule happens. */
    private fun perYear(f: Frequency): Double = when (f) {
        Frequency.ONCE -> 0.0
        Frequency.WEEKLY -> 52.0
        Frequency.EVERY_2_WEEKS -> 26.0
        Frequency.EVERY_4_WEEKS -> 13.0
        Frequency.MONTHLY -> 12.0
        Frequency.EVERY_2_MONTHS -> 6.0
        Frequency.QUARTERLY -> 4.0
        Frequency.TWICE_A_YEAR -> 2.0
        Frequency.YEARLY -> 1.0
    }

    /**
     * Recurring payments: every repeating scheduled outflow, plus payees you've paid in at least
     * [minMonths] of the 6 months ending at [asOf] that aren't scheduled (e.g. a streaming service).
     */
    fun recurring(data: BudgetData, asOf: YearMonth, minMonths: Int = 3): RecurringReport {
        val scheduled = data.scheduled.values
            .filter { it.frequency != Frequency.ONCE && it.amount < 0 }
            .map { s ->
                val name = s.payee.ifBlank { s.transferAccountId?.let { "Transfer to ${data.accountName(it)}" } ?: "(no payee)" }
                RecurringItem(
                    name = name,
                    categoryOrAccount = s.categoryId?.let { data.categoryName(it) } ?: data.accountName(s.accountId),
                    frequency = s.frequency.label,
                    typical = -s.amount,
                    monthly = (-s.amount * perYear(s.frequency) / 12.0).roundToLong(),
                    source = RecurringSource.SCHEDULED,
                )
            }
        val scheduledPayees = data.scheduled.values.map { ImportMatcher.normalizePayee(it.payee) }.filter { it.isNotEmpty() }.toSet()
        val window = ReportPeriod(asOf - 5, asOf)
        val detected = data.transactions.values
            .filter { counts(data, it) && it.amount < 0 && window.contains(it) && it.scheduledId == null && it.payee.isNotBlank() && it.categoryId != PREEXISTING_DEBT }
            .groupBy { ImportMatcher.normalizePayee(it.payee) }
            .filter { (key, l) -> key.isNotEmpty() && key !in scheduledPayees && looksRecurring(l, minMonths) }
            .map { (_, l) ->
                val months = l.map { YearMonth.of(it.date) }.distinct().size
                val latest = l.maxBy { it.date }
                RecurringItem(
                    name = latest.payee,
                    categoryOrAccount = data.categoryLabel(latest),
                    frequency = "About monthly ($months of last 6 months)",
                    typical = -latest.amount,
                    monthly = -l.sumOf { it.amount } / months,
                    source = RecurringSource.DETECTED,
                )
            }
        return RecurringReport((scheduled + detected).sortedByDescending { it.monthly })
    }

    /**
     * Bills and subscriptions, not everyday shopping: paid in enough months, about once a month,
     * for a consistent amount (each within 25% of the typical one).
     */
    private fun looksRecurring(txns: List<Transaction>, minMonths: Int): Boolean {
        val months = txns.map { YearMonth.of(it.date) }.distinct().size
        if (months < minMonths) return false
        if (txns.size > months * 1.25) return false
        val amounts = txns.map { -it.amount }.sorted()
        val typical = amounts[amounts.size / 2]
        return typical > 0 && amounts.all { kotlin.math.abs(it - typical) <= typical / 4 }
    }

    /**
     * Debt-to-income: average monthly debt payments ÷ average monthly income in the period.
     * A loan or mortgage counts its usual monthly payment when one is set, otherwise what was paid;
     * credit cards count what was paid to them. Income is what was deposited (take-home pay), so
     * this reads higher than a lender's figure based on gross pay.
     */
    fun debtToIncome(data: BudgetData, period: ReportPeriod): DebtToIncomeReport {
        val months = period.monthCount.coerceAtLeast(1)
        val income = data.transactions.values.filter { isIncome(data, it) && period.contains(it) }.sumOf(::incomeOf) / months
        val lines = mutableListOf<DebtLine>()
        var housing = 0L
        val debtAccounts = data.accounts.values.filter { it.type.isCredit || it.type.isLoan }.sortedBy { it.name.lowercase() }
        for (a in debtAccounts) {
            // Payments: linked loan payments, or transfers into the account from a budget account.
            val paid = data.transactions.values.filter { t ->
                period.contains(t) && data.isOnBudget(t.accountId) &&
                    (t.loanSplit?.loanAccountId == a.id || (t.transferAccountId == a.id && t.amount < 0))
            }.sumOf { -it.amount }
            val usual = a.loan?.monthlyPayment
            val monthly = when {
                usual != null && a.type.isLoan -> usual
                else -> paid / months
            }
            if (monthly <= 0) continue
            lines += DebtLine(a.name, monthly, if (usual != null && a.type.isLoan) "usual payment" else "paid in period")
            if (a.type == com.z.imab.model.AccountType.MORTGAGE) housing += monthly
        }
        val owed = debtAccounts.sumOf { maxOf(0, -data.balance(it.id)) }
        return DebtToIncomeReport(income, lines.sumOf { it.monthly }, lines.sortedByDescending { it.monthly }, housing, owed)
    }
}
