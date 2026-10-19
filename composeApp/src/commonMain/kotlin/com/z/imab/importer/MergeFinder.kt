package com.z.imab.importer

import com.z.imab.model.BudgetData
import com.z.imab.model.Transaction

/** A transaction entered by hand (or from a schedule) that looks like the same thing as one from the bank. */
data class MergeCandidate(val entered: Transaction, val bank: Transaction) {
    val daysApart: Int get() = ImportMatcher.daysApart(entered.date, bank.date)
}

/**
 * Finds transactions that are in an account twice: once entered by hand and once imported. They
 * must have exactly the same amount and dates no more than [ImportMatcher.DAY_WINDOW] days apart;
 * when several fit, the closest dates are paired first, and each transaction is used once.
 */
object MergeFinder {
    fun find(data: BudgetData, accountId: String, window: Int = ImportMatcher.DAY_WINDOW): List<MergeCandidate> {
        val txns = data.transactionsByAccount[accountId].orEmpty()
        val entered = txns.filter { it.importId == null && it.payee != ImportWindow.STARTING_BALANCE_PAYEE }
        val bank = txns.filter { it.importId != null }
        val pairs = entered.flatMap { e ->
            bank.filter { b ->
                b.amount == e.amount &&
                    ImportMatcher.daysApart(e.date, b.date) <= window &&
                    b.id !in e.notDuplicateOf && e.id !in b.notDuplicateOf &&
                    // Two legs of the same transfer aren't duplicates of each other.
                    e.transferPairId != b.id
            }.map { b -> MergeCandidate(e, b) }
        }.sortedWith(compareBy({ it.daysApart }, { it.entered.date }, { it.entered.id }))

        val used = HashSet<String>()
        return pairs.filter { p ->
            if (p.entered.id in used || p.bank.id in used) return@filter false
            used += p.entered.id
            used += p.bank.id
            true
        }.sortedByDescending { it.bank.date }
    }

    /** Count per account, for showing a hint in the account list. */
    fun countByAccount(data: BudgetData): Map<String, Int> =
        data.accounts.keys.associateWith { find(data, it).size }.filterValues { it > 0 }
}
