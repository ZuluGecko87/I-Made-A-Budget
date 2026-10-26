package com.z.imab.importer

import com.z.imab.model.BudgetData
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Decides how far back in a bank file to look when importing into an account. */
object ImportWindow {
    const val STARTING_BALANCE_PAYEE = com.z.imab.model.STARTING_BALANCE_PAYEE

    /** Date of the newest transaction that came from a bank file, or null if the account was never imported into. */
    fun lastImportDate(data: BudgetData, accountId: String): LocalDate? =
        data.transactionsByAccount[accountId].orEmpty().filter { it.importId != null }.maxOfOrNull { it.date }

    /**
     * Where an import starts by default:
     *  - after an earlier import: the last imported date (same-day transactions are still de-duplicated);
     *  - first import into an account: the day after its last reconcile or its starting balance,
     *    whichever is later (everything up to then is already confirmed);
     *  - otherwise: the earliest date in the file.
     */
    fun defaultStart(data: BudgetData, accountId: String?, txns: List<OfxTransaction>): LocalDate {
        val earliest = txns.minOf { it.date }
        if (accountId == null) return earliest
        lastImportDate(data, accountId)?.let { return maxOf(it, earliest) }
        val opening = data.transactionsByAccount[accountId].orEmpty()
            .filter { it.payee == STARTING_BALANCE_PAYEE && it.importId == null }
            .maxOfOrNull { it.date }
        val confirmedUpTo = listOfNotNull(opening, data.accounts[accountId]?.lastReconciled).maxOrNull()
        return confirmedUpTo?.let { maxOf(it.plus(1, DateTimeUnit.DAY), earliest) } ?: earliest
    }

    /**
     * Starting balance for a new account so that it ends up at the bank's balance after importing the
     * transactions from [from] on. Returns null if the file has no balance.
     */
    fun openingBalance(stmt: OfxStatement, from: LocalDate): Long? {
        val bank = stmt.ledgerBalance ?: return null
        return bank - stmt.transactions.filter { it.date >= from }.sumOf { it.amount }
    }
}
