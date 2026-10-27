package com.z.imab.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Category id used for inflows that go straight to "Ready to Assign". */
const val READY_TO_ASSIGN = "rta"

/**
 * Category for debt a credit account already had when it was added. It sits outside the budget:
 * you pay it down by assigning money to the card's payment category.
 */
const val PREEXISTING_DEBT = "debt"

/** Every on-budget credit account has a virtual payment category with this id prefix + account id. */
const val CC_PAYMENT_PREFIX = "ccpay:"

fun paymentCategoryId(accountId: String) = CC_PAYMENT_PREFIX + accountId

@Serializable
enum class AccountType(val label: String) {
    CHECKING("Checking"),
    SAVINGS("Savings"),
    CREDIT_CARD("Credit card"),
    CASH("Cash"),
    LINE_OF_CREDIT("Line of credit"),
    LOAN("Loan"),
    MORTGAGE("Mortgage"),
    INVESTMENT("Investment"),
    OTHER("Other"),
    ;

    /** Credit accounts get a payment category, and spending on them moves money into it. */
    val isCredit: Boolean get() = this == CREDIT_CARD || this == LINE_OF_CREDIT

    /** Loans and mortgages: tracked outside the budget, with terms to split payments. */
    val isLoan: Boolean get() = this == LOAN || this == MORTGAGE
}

@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: AccountType = AccountType.CHECKING,
    /** On-budget accounts feed the budget; off-budget ones are tracking-only. */
    val onBudget: Boolean = true,
    val closed: Boolean = false,
    /** Bank account identifiers (from QBO/OFX files) that map to this account. */
    val importKeys: List<String> = emptyList(),
    val sortOrder: Int = 0,
    /** For loans and mortgages: used to split each payment into principal, interest and escrow. */
    val loan: LoanTerms? = null,
    /** Statement date of the last reconcile, and the balance the bank showed then. */
    val lastReconciled: LocalDate? = null,
    val reconciledBalance: Long? = null,
) {
    /**
     * Whether this account's money counts in the budget. Loans never do, even if marked otherwise:
     * what you owe isn't money to assign; the payments from checking are what get budgeted.
     */
    val inBudget: Boolean get() = onBudget && !type.isLoan
}

@Serializable
data class LoanTerms(
    /** Annual interest rate in percent, e.g. 6.25. */
    val annualRatePct: Double,
    val monthlyEscrow: Long = 0,
    /** Usual total monthly payment including escrow, for the payoff estimate (optional). */
    val monthlyPayment: Long? = null,
    /** Extra paid toward principal each month on top of the usual payment, for the payoff estimate. */
    val extraPrincipal: Long = 0,
)

/**
 * A savings goal spanning several categories, e.g. "House" over Down Payment + Closing Costs.
 * Progress is the money in all of them together.
 */
@Serializable
data class CombinedGoal(
    val id: String,
    val name: String,
    val categoryIds: List<String>,
    val target: Long,
    val targetDate: LocalDate? = null,
    val monthly: Long? = null,
)

/** How a loan payment was divided. Stored on the payment; the principal also appears in the loan account. */
@Serializable
data class LoanSplit(
    val loanAccountId: String,
    val principal: Long,
    val interest: Long,
    val escrow: Long,
)

/**
 * Auto-categorize rule: a bank payee containing [match] (compared as lowercase words) gets this
 * category or transfer, and optionally is treated as a payment on [loanAccountId].
 */
@Serializable
data class PayeeRule(
    val id: String,
    val match: String,
    val categoryId: String? = null,
    val transferAccountId: String? = null,
    val loanAccountId: String? = null,
)

@Serializable
data class CategoryGroup(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
)

@Serializable
data class Category(
    val id: String,
    val groupId: String,
    val name: String,
    val sortOrder: Int = 0,
    val goal: CategoryGoal? = null,
)

@Serializable
enum class GoalType {
    /** Build up to [CategoryGoal.target] over time; money always rolls over. */
    SAVINGS,
    /** Have [CategoryGoal.target] available to spend every month, like rent. */
    MONTHLY_SPENDING,
}

/**
 * A goal for a category.
 *  - SAVINGS: save up to [target], optionally by [targetDate] and/or with a planned [monthly] amount.
 *  - MONTHLY_SPENDING: [target] is needed each month. With [rollover] off, money left at the end of
 *    a month goes back to Ready to Assign instead of carrying over.
 */
@Serializable
data class CategoryGoal(
    val target: Long,
    val targetDate: LocalDate? = null,
    val monthly: Long? = null,
    val type: GoalType = GoalType.SAVINGS,
    val rollover: Boolean = true,
) {
    /** Whether leftover money carries into next month. */
    val rollsOver: Boolean get() = type == GoalType.SAVINGS || rollover
}

/** Amounts are in cents. Negative = outflow, positive = inflow. */
@Serializable
data class Transaction(
    val id: String,
    val accountId: String,
    val date: LocalDate,
    val amount: Long,
    val payee: String = "",
    val memo: String = "",
    val categoryId: String? = null,
    /** Set when this is one leg of a transfer: the other account. */
    val transferAccountId: String? = null,
    /** Id of the matching leg in [transferAccountId]. */
    val transferPairId: String? = null,
    val cleared: Boolean = false,
    /** Bank-provided id (OFX FITID) once matched to an imported transaction. */
    val importId: String? = null,
    /** Scheduled transaction this was entered from, if any. */
    val scheduledId: String? = null,
    /** Transactions the user said are not duplicates of this one, so they aren't offered for merging. */
    val notDuplicateOf: List<String> = emptyList(),
    /** Set on a payment toward a loan: how it splits into principal, interest and escrow. */
    val loanSplit: LoanSplit? = null,
    /** Set on the principal entry in a loan account: the payment it came from. */
    val loanPaymentOf: String? = null,
    /** Confirmed against a bank statement in a reconcile. */
    val reconciled: Boolean = false,
    /** Entered by hand, then merged with the same transaction from a bank import. */
    val merged: Boolean = false,
)

@Serializable
enum class Frequency(val label: String) {
    ONCE("Once"),
    WEEKLY("Weekly"),
    EVERY_2_WEEKS("Every 2 weeks"),
    EVERY_4_WEEKS("Every 4 weeks"),
    MONTHLY("Monthly"),
    EVERY_2_MONTHS("Every 2 months"),
    QUARTERLY("Every 3 months"),
    TWICE_A_YEAR("Every 6 months"),
    YEARLY("Yearly"),
}

@Serializable
data class ScheduledTransaction(
    val id: String,
    val accountId: String,
    /** Anchor date the repetition is computed from (keeps "the 31st" stable across short months). */
    val startDate: LocalDate,
    /** Next occurrence that hasn't been entered yet. */
    val nextDate: LocalDate,
    val frequency: Frequency = Frequency.MONTHLY,
    val amount: Long,
    val payee: String = "",
    val memo: String = "",
    val categoryId: String? = null,
    val transferAccountId: String? = null,
    val endDate: LocalDate? = null,
)

/** Money assigned to a category for one month. */
@Serializable
data class Assignment(
    val month: String,
    val categoryId: String,
    val amount: Long,
) {
    val key: String get() = "$month|$categoryId"
}
