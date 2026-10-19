package com.z.imab.budget

import com.z.imab.model.Transaction
import com.z.imab.model.LoanTerms
import kotlinx.datetime.LocalDate
import kotlin.math.roundToLong

data class LoanPayoff(val months: Int, val totalInterest: Long)

/** Standard monthly-amortized loan arithmetic. Amounts in cents; payments and owed are positive. */
object LoanMath {
    /** One month of interest on [owed]. */
    fun monthlyInterest(owed: Long, annualRatePct: Double): Long =
        if (owed <= 0) 0 else (owed * annualRatePct / 100.0 / 12.0).roundToLong()

    /**
     * Splits a payment: interest on what's owed first, then escrow, and the rest pays down principal
     * (never more than is owed). Returns principal, interest, escrow.
     */
    fun split(payment: Long, owed: Long, terms: LoanTerms): Triple<Long, Long, Long> {
        val interest = minOf(payment, monthlyInterest(owed, terms.annualRatePct))
        val escrow = minOf(payment - interest, terms.monthlyEscrow)
        val principal = minOf(maxOf(0, owed), payment - interest - escrow)
        // Anything over the remaining principal is counted as interest/escrow so the parts add up.
        return Triple(principal, payment - principal - escrow, escrow)
    }

    /** What was owed on the loan just before a payment on [date] (excluding that payment's own principal entry). */
    fun owedBefore(loanTxns: Iterable<Transaction>, date: LocalDate, excludingPaymentId: String?): Long =
        -loanTxns
            .filter { excludingPaymentId == null || it.loanPaymentOf != excludingPaymentId }
            .filter { it.date < date || (it.date == date && it.loanPaymentOf == null) }
            .sumOf { it.amount }

    /**
     * Months until paid off and the interest still to pay, making [principalAndInterest] payments
     * each month. Null if the payment doesn't even cover the interest.
     */
    fun payoff(owed: Long, annualRatePct: Double, principalAndInterest: Long): LoanPayoff? {
        var balance = owed
        var months = 0
        var interestTotal = 0L
        while (balance > 0) {
            val interest = monthlyInterest(balance, annualRatePct)
            if (principalAndInterest <= interest || months >= 1200) return null
            interestTotal += interest
            balance -= minOf(balance, principalAndInterest - interest)
            months++
        }
        return LoanPayoff(months, interestTotal)
    }
}
