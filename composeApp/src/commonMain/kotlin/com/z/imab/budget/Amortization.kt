package com.z.imab.budget

import com.z.imab.model.YearMonth

/** A one-off payment toward principal, made with the regular payment in [month]. */
data class LumpSum(val month: YearMonth, val amount: Long)

/**
 * Extra payments to try against a loan: more principal every month (from [extraFrom] on, or
 * straight away), a yearly extra in [yearlyMonth] (a bonus or tax refund), and one-off lump sums.
 */
data class PayoffPlan(
    val extraMonthly: Long = 0,
    val extraFrom: YearMonth? = null,
    val yearlyExtra: Long = 0,
    val yearlyMonth: Int = 1,
    val lumpSums: List<LumpSum> = emptyList(),
) {
    val isEmpty: Boolean get() = extraMonthly <= 0 && yearlyExtra <= 0 && lumpSums.none { it.amount > 0 }

    /** Extra principal on top of the regular payment in [month]. */
    fun extraIn(month: YearMonth): Long {
        var extra = 0L
        if (extraMonthly > 0 && (extraFrom == null || month >= extraFrom)) extra += extraMonthly
        if (yearlyExtra > 0 && month.month == yearlyMonth) extra += yearlyExtra
        extra += lumpSums.filter { it.month == month && it.amount > 0 }.sumOf { it.amount }
        return extra
    }
}

/** One month of a schedule. [principal] includes [extra]; [balance] is what's owed after the payment. */
data class AmortizationRow(
    val month: YearMonth,
    val principal: Long,
    val interest: Long,
    val escrow: Long,
    val extra: Long,
    val balance: Long,
) {
    val payment: Long get() = principal + interest + escrow
}

/** A year of a schedule added up; [balance] is what's owed at the end of it. */
data class AmortizationYear(val year: Int, val principal: Long, val interest: Long, val escrow: Long, val extra: Long, val balance: Long) {
    val payment: Long get() = principal + interest + escrow
}

data class Amortization(val rows: List<AmortizationRow>) {
    val totalInterest: Long get() = rows.sumOf { it.interest }
    val totalEscrow: Long get() = rows.sumOf { it.escrow }
    val totalPrincipal: Long get() = rows.sumOf { it.principal }
    val totalPaid: Long get() = rows.sumOf { it.payment }
    val months: Int get() = rows.size

    /** The month of the last payment. */
    val payoff: YearMonth? get() = rows.lastOrNull()?.month

    val years: List<AmortizationYear> by lazy {
        rows.groupBy { it.month.year }.map { (y, rs) ->
            AmortizationYear(y, rs.sumOf { it.principal }, rs.sumOf { it.interest }, rs.sumOf { it.escrow }, rs.sumOf { it.extra }, rs.last().balance)
        }
    }
}

/** Month-by-month amortization, the same arithmetic [LoanMath.split] uses for real payments. */
object Amortizer {
    /** Longest schedule worked out: 100 years. */
    const val MAX_MONTHS = 1200

    /**
     * The schedule from [start], paying [principalAndInterest] each month plus [escrow], plus the
     * extras in [plan]. Escrow is paid in every month that has a payment. Null when the regular
     * payment doesn't cover the interest, so the loan would never be paid off.
     */
    fun schedule(
        owed: Long,
        annualRatePct: Double,
        principalAndInterest: Long,
        escrow: Long,
        start: YearMonth,
        plan: PayoffPlan = PayoffPlan(),
    ): Amortization? {
        if (owed > 0 && principalAndInterest <= LoanMath.monthlyInterest(owed, annualRatePct)) return null
        val rows = ArrayList<AmortizationRow>()
        var balance = owed
        var month = start
        while (balance > 0) {
            if (rows.size >= MAX_MONTHS) return null
            val interest = LoanMath.monthlyInterest(balance, annualRatePct)
            val regular = minOf(balance, principalAndInterest - interest)
            val extra = minOf(balance - regular, plan.extraIn(month))
            balance -= regular + extra
            rows += AmortizationRow(month, regular + extra, interest, escrow, extra, balance)
            month += 1
        }
        return Amortization(rows)
    }
}
