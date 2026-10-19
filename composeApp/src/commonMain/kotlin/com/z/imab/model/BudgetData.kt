package com.z.imab.model

/** Immutable snapshot of everything in the budget. */
data class BudgetData(
    val accounts: Map<String, Account> = emptyMap(),
    val groups: Map<String, CategoryGroup> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val transactions: Map<String, Transaction> = emptyMap(),
    val scheduled: Map<String, ScheduledTransaction> = emptyMap(),
    val assignments: Map<String, Assignment> = emptyMap(),
) {
    val sortedAccounts: List<Account> by lazy {
        accounts.values.sortedWith(
            compareBy<Account>({ it.closed }, { !it.onBudget }, { it.sortOrder }, { it.name.lowercase() })
        )
    }

    val sortedGroups: List<CategoryGroup> by lazy {
        groups.values.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
    }

    private val categoriesByGroup: Map<String, List<Category>> by lazy {
        categories.values
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
            .groupBy { it.groupId }
    }

    val transactionsByAccount: Map<String, List<Transaction>> by lazy {
        transactions.values.groupBy { it.accountId }
    }

    fun categoriesIn(groupId: String): List<Category> = categoriesByGroup[groupId].orEmpty()

    fun balance(accountId: String): Long = transactionsByAccount[accountId].orEmpty().sumOf { it.amount }

    fun clearedBalance(accountId: String): Long =
        transactionsByAccount[accountId].orEmpty().filter { it.cleared }.sumOf { it.amount }

    fun accountName(id: String?): String = id?.let { accounts[it]?.name } ?: "(deleted account)"

    fun categoryName(id: String?): String = when {
        id == null -> "Uncategorized"
        id == READY_TO_ASSIGN -> "Ready to Assign"
        id == PREEXISTING_DEBT -> "Pre-existing debt"
        id.startsWith(CC_PAYMENT_PREFIX) -> "${accountName(id.removePrefix(CC_PAYMENT_PREFIX))} Payment"
        else -> categories[id]?.name ?: "Uncategorized"
    }

    fun isOnBudget(accountId: String?): Boolean = accountId?.let { accounts[it]?.onBudget } == true

    /** On-budget credit card or line of credit. */
    fun isCreditAccount(accountId: String?): Boolean =
        accountId?.let { accounts[it] }?.let { it.onBudget && it.type.isCredit } == true

    /** Credit accounts that have a payment category, in display order. */
    val creditAccounts: List<Account> by lazy { sortedAccounts.filter { isCreditAccount(it.id) } }

    /** True for a transfer whose two legs are both on- or both off-budget, so no category applies. */
    fun isInternalTransfer(t: Transaction): Boolean =
        t.transferAccountId != null && accounts.containsKey(t.transferAccountId) &&
            isOnBudget(t.accountId) == isOnBudget(t.transferAccountId)

    /** Whether this transaction affects the budget but still lacks a category. */
    fun needsCategory(t: Transaction): Boolean {
        if (!isOnBudget(t.accountId) || isInternalTransfer(t)) return false
        val c = t.categoryId
        if (c == PREEXISTING_DEBT && isCreditAccount(t.accountId)) return false
        return c == null || (c != READY_TO_ASSIGN && c !in categories)
    }

    /** Display name for the payee column; transfers show the other account. */
    fun payeeLabel(t: Transaction): String {
        if (t.transferAccountId != null) {
            val dir = if (t.amount < 0) "to" else "from"
            return "Transfer $dir ${accountName(t.transferAccountId)}"
        }
        return t.payee.ifBlank { "(no payee)" }
    }

    fun categoryLabel(t: Transaction): String = when {
        isInternalTransfer(t) -> "Transfer"
        !isOnBudget(t.accountId) && t.categoryId == null -> "Tracking"
        else -> categoryName(t.categoryId)
    }

    val uncategorizedCount: Int by lazy { transactions.values.count { needsCategory(it) } }
}
