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
    INVESTMENT("Investment"),
    OTHER("Other"),
    ;

    /** Credit accounts get a payment category, and spending on them moves money into it. */
    val isCredit: Boolean get() = this == CREDIT_CARD || this == LINE_OF_CREDIT
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
)

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
