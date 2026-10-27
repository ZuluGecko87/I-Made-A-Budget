package com.z.imab.data

import com.z.imab.budget.Scheduler
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.ImportRow
import com.z.imab.importer.ImportWindow
import com.z.imab.budget.LoanMath
import com.z.imab.model.LoanSplit
import com.z.imab.model.Money
import com.z.imab.model.PayeeRule
import kotlinx.datetime.toLocalDateTime
import com.z.imab.model.Account
import com.z.imab.model.Assignment
import com.z.imab.model.BudgetData
import com.z.imab.model.Category
import com.z.imab.model.CategoryGroup
import com.z.imab.model.CombinedGoal
import com.z.imab.model.PREEXISTING_DEBT
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlin.uuid.Uuid

data class ImportResult(val added: Int, val matched: Int, val skipped: Int)

/**
 * All budget rules live here, on top of the [Ledger]. Every change is recorded as ops, so it can
 * be saved locally and synced. Not thread-safe: call from one thread (the UI thread).
 */
class Budget(
    val deviceId: String,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val ledger = Ledger()
    private val b = DataBuilder()

    var data: BudgetData = BudgetData()
        private set

    /** Called after every change to [data] (local edits and merged remote changes). */
    var onChange: (() -> Unit)? = null

    fun newId(): String = Uuid.random().toString()

    // ---------------------------------------------------------------- loading & merging

    /** Merges ops from disk or another device. Returns how many entities changed. */
    fun merge(ops: List<Op>): Int {
        val changed = ops.count { ledger.merge(it) }
        if (changed > 0) rebuild()
        return changed
    }

    /** Replaces everything with [file]: loading from disk, or switching to a newer budget from another device. */
    fun adopt(file: LedgerFile) {
        ledger.reset(file.budgetId, file.startedAt)
        file.ops.forEach { ledger.merge(it) }
        rebuild()
    }

    // ---------------------------------------------------------------- starting over

    /**
     * Starts a new budget that keeps the setup — accounts, category groups and categories (with
     * goals), scheduled transactions, auto-categorize rules and combined goals — but no
     * transactions or assigned money. With [keepBalances] each open account starts with its
     * current balance (dated [today]) so the new budget begins with the money you actually have.
     */
    fun freshStart(keepBalances: Boolean, today: LocalDate) {
        val old = data
        ledger.reset(newId(), clock())
        b.clear()
        edit {
            old.accounts.values.forEach { put(Kinds.ACCOUNT, it.id, it, Account.serializer()) }
            old.groups.values.forEach { put(Kinds.GROUP, it.id, it, CategoryGroup.serializer()) }
            old.categories.values.forEach { put(Kinds.CATEGORY, it.id, it, Category.serializer()) }
            old.scheduled.values.forEach { putScheduled(it) }
            old.rules.values.forEach { put(Kinds.RULE, it.id, it, PayeeRule.serializer()) }
            old.combinedGoals.values.forEach { put(Kinds.COMBINED_GOAL, it.id, it, CombinedGoal.serializer()) }
            if (keepBalances) {
                old.accounts.values.filter { !it.closed }.forEach { a ->
                    val balance = old.balance(a.id)
                    if (balance != 0L) putTxn(startingBalanceTxn(newId(), a, balance, today))
                }
            }
        }
        rebuild()
    }

    /** Starts a brand-new, empty budget with the default categories, as on a new install. */
    fun startFromScratch() {
        ledger.reset(newId(), clock())
        b.clear()
        seedDefaults()
        rebuild()
    }

    /** Brings back an archived budget as the current one (as a new budget, so other devices switch to it). */
    fun restore(file: LedgerFile) {
        ledger.reset(newId(), clock())
        file.ops.forEach { ledger.merge(it) }
        rebuild()
    }

    private fun rebuild() {
        b.clear()
        ledger.all().forEach(b::apply)
        data = b.snapshot()
        onChange?.invoke()
    }

    inner class Edit {
        val d: DataBuilder get() = b
        var changes = 0
            private set

        fun <T> put(kind: String, id: String, value: T, serializer: KSerializer<T>) =
            record(Op(Kinds.key(kind, id), ledger.nextTs(clock()), deviceId, ImabJson.encodeToJsonElement(serializer, value)))

        fun remove(kind: String, id: String) {
            if (ledger[Kinds.key(kind, id)]?.data == null) return
            record(Op(Kinds.key(kind, id), ledger.nextTs(clock()), deviceId, null))
        }

        private fun record(op: Op) {
            if (ledger.merge(op)) {
                b.apply(op)
                changes++
            }
        }

        fun putTxn(t: Transaction) = put(Kinds.TXN, t.id, t, Transaction.serializer())

        fun putScheduled(s: ScheduledTransaction) = put(Kinds.SCHEDULED, s.id, s, ScheduledTransaction.serializer())

        /** Saves a transaction, keeping the other leg of a transfer and any loan principal entry in step. */
        fun saveTxn(input: Transaction) {
            val before = d.transactions[input.id]
            val wasLoan = before?.loanSplit?.loanAccountId
            val from = minOf(input.date, before?.date ?: input.date)
            saveTxnAndTransfer(input)
            val loan = input.loanSplit?.loanAccountId
            if (loan != null) recalcLoan(loan, from)
            if (wasLoan != null && wasLoan != loan) {
                remove(Kinds.TXN, principalLegId(input.id))
                recalcLoan(wasLoan, from)
            }
        }

        private fun saveTxnAndTransfer(input: Transaction) {
            val old = d.transactions[input.id]
            val xfer = input.transferAccountId?.takeIf { it != input.accountId && d.accounts.containsKey(it) }
            if (xfer == null) {
                old?.transferPairId?.let { removeTxn(it, removePair = false) }
                putTxn(input.copy(transferAccountId = null, transferPairId = null))
                return
            }

            val pairId = input.transferPairId ?: old?.transferPairId ?: "${input.id}-x"
            if (old?.transferPairId != null && old.transferPairId != pairId) removeTxn(old.transferPairId, removePair = false)
            val fromOn = d.accounts[input.accountId]?.inBudget == true
            val toOn = d.accounts[xfer]?.inBudget == true
            val t = input.copy(
                transferAccountId = xfer,
                transferPairId = pairId,
                categoryId = if (fromOn == toOn || !fromOn) null else input.categoryId,
            )
            val existingPair = d.transactions[pairId]
            val pair = Transaction(
                id = pairId,
                accountId = xfer,
                // Keep the bank's date on a leg that has already been imported.
                date = if (existingPair?.importId != null) existingPair.date else t.date,
                amount = -t.amount,
                payee = existingPair?.payee?.takeIf { existingPair.importId != null } ?: t.payee,
                memo = existingPair?.memo?.takeIf { it.isNotBlank() } ?: t.memo,
                // Budget money moves on the budget-side leg, so a category chosen from the tracking side goes there.
                categoryId = if (toOn && !fromOn) (input.categoryId ?: existingPair?.categoryId ?: READY_TO_ASSIGN) else null,
                transferAccountId = t.accountId,
                transferPairId = t.id,
                cleared = existingPair?.cleared ?: false,
                importId = existingPair?.importId,
                scheduledId = existingPair?.scheduledId,
            )
            putTxn(t)
            putTxn(pair)
        }

        fun removeTxn(id: String, removePair: Boolean = true) {
            val t = d.transactions[id] ?: return
            remove(Kinds.TXN, id)
            t.loanSplit?.let {
                remove(Kinds.TXN, principalLegId(id))
                recalcLoan(it.loanAccountId, t.date)
            }
            val pair = t.transferPairId?.let { d.transactions[it] } ?: return
            if (removePair) {
                remove(Kinds.TXN, pair.id)
            } else if (pair.transferPairId == id) {
                // The other leg stays as an ordinary (uncategorized) transaction.
                putTxn(pair.copy(transferAccountId = null, transferPairId = null))
            }
        }

        /**
         * Recomputes the principal / interest / escrow split of every payment on [loanId] dated on or
         * after [from], oldest first, since each payment's interest depends on the balance left by the ones before.
         */
        fun recalcLoan(loanId: String, from: LocalDate?) {
            val terms = d.accounts[loanId]?.loan
            val payments = d.transactions.values
                .filter { it.loanSplit?.loanAccountId == loanId && (from == null || it.date >= from) }
                .sortedWith(compareBy({ it.date }, { it.id }))
            for (p in payments) {
                val legId = principalLegId(p.id)
                if (terms == null || p.amount >= 0 || p.transferAccountId != null) {
                    remove(Kinds.TXN, legId)
                    putTxn(p.copy(loanSplit = null))
                    continue
                }
                val owed = LoanMath.owedBefore(d.transactions.values.filter { it.accountId == loanId }, p.date, p.id)
                val (principal, interest, escrow) = LoanMath.split(-p.amount, owed, terms)
                val split = LoanSplit(loanId, principal, interest, escrow)
                if (p.loanSplit != split) putTxn(p.copy(loanSplit = split))
                val leg = Transaction(
                    id = legId,
                    accountId = loanId,
                    date = p.date,
                    amount = principal,
                    payee = "Principal payment",
                    memo = "${p.payee.ifBlank { "Payment" }} ${Money.format(-p.amount)}: interest ${Money.format(interest)}, escrow ${Money.format(escrow)}",
                    cleared = p.cleared,
                    loanPaymentOf = p.id,
                )
                when {
                    principal == 0L -> remove(Kinds.TXN, legId)
                    d.transactions[legId] != leg -> putTxn(leg)
                }
            }
        }

        /** Enters one occurrence of a schedule as a real transaction. */
        fun enterOccurrence(s: ScheduledTransaction, date: LocalDate, fromImport: ImportRow? = null, importAccountId: String? = null) {
            val id = Scheduler.occurrenceTxnId(s.id, date)
            // Skip occurrences that already exist or that the user deleted (tombstone).
            if (fromImport == null && ledger.contains(Kinds.key(Kinds.TXN, id))) return
            val base = Transaction(
                id = id,
                accountId = s.accountId,
                date = date,
                amount = s.amount,
                payee = s.payee,
                memo = s.memo,
                categoryId = s.categoryId,
                transferAccountId = s.transferAccountId,
                scheduledId = s.id,
            )
            if (fromImport == null || importAccountId == s.accountId) {
                saveTxn(
                    if (fromImport == null) base
                    else base.copy(date = fromImport.source.date, amount = fromImport.source.amount, cleared = true, importId = fromImport.importId)
                )
            } else {
                // The bank file is for the receiving side of a scheduled transfer.
                saveTxn(base)
                val pairId = d.transactions[id]?.transferPairId ?: return
                d.transactions[pairId]?.let {
                    putTxn(it.copy(date = fromImport.source.date, cleared = true, importId = fromImport.importId, scheduledId = s.id))
                }
            }
        }

        /** Moves a schedule past [date], deleting it if it's finished. */
        fun advanceSchedule(s: ScheduledTransaction, date: LocalDate) {
            val next = Scheduler.nextAfter(s, maxOf(date, s.nextDate))
            if (next == null) remove(Kinds.SCHEDULED, s.id) else putScheduled(s.copy(nextDate = next))
        }
    }

    fun <R> edit(block: Edit.() -> R): R {
        val e = Edit()
        val result = e.block()
        if (e.changes > 0) {
            data = b.snapshot()
            onChange?.invoke()
        }
        return result
    }

    // ---------------------------------------------------------------- accounts

    /** Creates or updates an account. A starting balance is only used for a new account. */
    fun saveAccount(input: Account, startingBalance: Long = 0, startDate: LocalDate? = null): String = edit {
        // Loans are always tracked outside the budget.
        val account = if (input.type.isLoan) input.copy(onBudget = false) else input
        val previous = d.accounts[account.id]
        val isNew = previous == null
        put(Kinds.ACCOUNT, account.id, account, Account.serializer())
        // New loan terms (e.g. a new escrow amount) apply from the current month on.
        if (!isNew && previous?.loan != account.loan) recalcLoan(account.id, YearMonth.of(today()).firstDay)
        if (isNew && startingBalance != 0L && startDate != null) {
            putTxn(startingBalanceTxn(newId(), account, startingBalance, startDate))
        }
        account.id
    }

    /**
     * Sets how much is still owed on a loan today, by adjusting its starting balance, then re-splits
     * its payments (their interest depends on the balance).
     */
    fun setLoanPrincipal(accountId: String, owed: Long) = edit {
        val account = d.accounts[accountId] ?: return@edit
        // Changing the opening balance changes each payment's interest and so its principal, which
        // moves the balance again; repeat until it lands on [owed] (the change shrinks each round).
        repeat(20) {
            val delta = -owed - d.transactions.values.filter { it.accountId == accountId }.sumOf { it.amount }
            if (delta == 0L) return@edit
            val opening = d.transactions.values.firstOrNull {
                it.accountId == accountId && it.payee == ImportWindow.STARTING_BALANCE_PAYEE && it.importId == null
            }
            val date = opening?.date ?: d.transactions.values.filter { it.accountId == accountId }.minOfOrNull { it.date } ?: today()
            putTxn(startingBalanceTxn(opening?.id ?: newId(), account, (opening?.amount ?: 0) + delta, date))
            recalcLoan(accountId, null)
        }
    }

    /**
     * Sets an account's starting balance (creating, moving or removing the "Starting Balance"
     * transaction). Used when the start date of a first import changes.
     */
    fun setStartingBalance(accountId: String, amount: Long, date: LocalDate) = edit {
        val account = d.accounts[accountId] ?: return@edit
        val existing = d.transactions.values.firstOrNull {
            it.accountId == accountId && it.payee == ImportWindow.STARTING_BALANCE_PAYEE && it.importId == null
        }
        when {
            amount == 0L -> existing?.let { removeTxn(it.id) }
            else -> putTxn(startingBalanceTxn(existing?.id ?: newId(), account, amount, date))
        }
    }

    private fun startingBalanceTxn(id: String, account: Account, amount: Long, date: LocalDate) = Transaction(
        id = id,
        accountId = account.id,
        date = date,
        amount = amount,
        payee = ImportWindow.STARTING_BALANCE_PAYEE,
        categoryId = when {
            !account.inBudget -> null
            account.type.isCredit && amount < 0 -> PREEXISTING_DEBT
            else -> READY_TO_ASSIGN
        },
        cleared = true,
    )

    fun deleteAccount(id: String) = edit {
        d.transactions.values.filter { it.loanSplit?.loanAccountId == id }.forEach { putTxn(it.copy(loanSplit = null)) }
        d.transactions.values.filter { it.accountId == id }.map { it.id }.forEach { removeTxn(it, removePair = false) }
        d.scheduled.values.filter { it.accountId == id || it.transferAccountId == id }.forEach { remove(Kinds.SCHEDULED, it.id) }
        remove(Kinds.ACCOUNT, id)
    }

    // ---------------------------------------------------------------- categories

    fun saveGroup(g: CategoryGroup) = edit { put(Kinds.GROUP, g.id, g, CategoryGroup.serializer()) }

    fun deleteGroup(id: String) = edit {
        d.categories.values.filter { it.groupId == id }.forEach { deleteCategoryIn(this, it.id) }
        remove(Kinds.GROUP, id)
    }

    fun saveCategory(c: Category) = edit { put(Kinds.CATEGORY, c.id, c, Category.serializer()) }

    fun deleteCategory(id: String) = edit { deleteCategoryIn(this, id) }

    /** Deleting a category leaves its transactions uncategorized and returns its money to Ready to Assign. */
    private fun deleteCategoryIn(e: Edit, id: String) = with(e) {
        d.assignments.values.filter { it.categoryId == id }.forEach { remove(Kinds.ASSIGNMENT, it.key) }
        d.transactions.values.filter { it.categoryId == id }.forEach { putTxn(it.copy(categoryId = null)) }
        d.scheduled.values.filter { it.categoryId == id }.forEach { putScheduled(it.copy(categoryId = null)) }
        d.combinedGoals.values.filter { id in it.categoryIds }.forEach {
            put(Kinds.COMBINED_GOAL, it.id, it.copy(categoryIds = it.categoryIds - id), CombinedGoal.serializer())
        }
        remove(Kinds.CATEGORY, id)
    }

    /**
     * Renames a payee on every transaction and schedule that uses it, e.g. to fix a misspelling.
     * Renaming to a payee that already exists merges the two.
     */
    fun renamePayee(from: String, to: String) = edit {
        val old = from.trim()
        val new = to.trim()
        if (old.isEmpty() || new.isEmpty() || old == new) return@edit
        d.transactions.values.filter { it.payee.trim() == old }.forEach { putTxn(it.copy(payee = new)) }
        d.scheduled.values.filter { it.payee.trim() == old }.forEach { putScheduled(it.copy(payee = new)) }
    }

    fun saveCombinedGoal(g: CombinedGoal) = edit { put(Kinds.COMBINED_GOAL, g.id, g, CombinedGoal.serializer()) }

    fun deleteCombinedGoal(id: String) = edit { remove(Kinds.COMBINED_GOAL, id) }

    /**
     * Moves [amount] of available money in [month] from one category to another. A null category
     * means Ready to Assign. Both assignments change in one edit, so they sync together.
     */
    fun moveMoney(month: YearMonth, fromCategoryId: String?, toCategoryId: String?, amount: Long) = edit {
        if (amount <= 0 || fromCategoryId == toCategoryId) return@edit
        fun adjust(categoryId: String, delta: Long) {
            val key = Assignment(month.toString(), categoryId, 0).key
            val now = d.assignments[key]?.amount ?: 0
            val a = Assignment(month.toString(), categoryId, now + delta)
            if (a.amount == 0L) remove(Kinds.ASSIGNMENT, key) else put(Kinds.ASSIGNMENT, key, a, Assignment.serializer())
        }
        fromCategoryId?.let { adjust(it, -amount) }
        toCategoryId?.let { adjust(it, amount) }
    }

    /** Sets several categories' assigned amounts in one edit (used to undo a change). */
    fun setAssignedAll(month: YearMonth, amounts: Map<String, Long>) = edit {
        amounts.forEach { (categoryId, amount) ->
            val a = Assignment(month.toString(), categoryId, amount)
            if (amount == 0L) remove(Kinds.ASSIGNMENT, a.key) else put(Kinds.ASSIGNMENT, a.key, a, Assignment.serializer())
        }
    }

    fun setAssigned(month: YearMonth, categoryId: String, amount: Long) = edit {
        val a = Assignment(month.toString(), categoryId, amount)
        if (amount == 0L) remove(Kinds.ASSIGNMENT, a.key) else put(Kinds.ASSIGNMENT, a.key, a, Assignment.serializer())
    }

    // ---------------------------------------------------------------- transactions

    fun saveTransaction(t: Transaction) = edit { saveTxn(t) }

    fun deleteTransaction(id: String) = edit { removeTxn(id) }

    fun setCleared(id: String, cleared: Boolean) = edit {
        // A reconciled transaction stays cleared; un-reconcile it in the transaction form first.
        d.transactions[id]?.takeIf { !it.reconciled }?.let { putTxn(it.copy(cleared = cleared)) }
    }

    /**
     * Finishes a reconcile of [accountId] against the bank's [bankBalance] on [date]: every cleared
     * transaction up to that date is marked reconciled. If the cleared balance doesn't match and
     * [adjust] is set, an adjustment transaction for the difference is added first.
     */
    fun reconcile(accountId: String, date: LocalDate, bankBalance: Long, adjust: Boolean) = edit {
        val account = d.accounts[accountId] ?: return@edit
        val cleared = d.transactions.values.filter { it.accountId == accountId && it.cleared && it.date <= date }
        val difference = bankBalance - cleared.sumOf { it.amount }
        if (difference != 0L && adjust) {
            putTxn(
                Transaction(
                    id = newId(),
                    accountId = accountId,
                    date = date,
                    amount = difference,
                    payee = RECONCILE_ADJUSTMENT_PAYEE,
                    memo = "Bank balance ${Money.format(bankBalance)}",
                    categoryId = if (account.inBudget) READY_TO_ASSIGN else null,
                    cleared = true,
                    reconciled = true,
                )
            )
        }
        cleared.filter { !it.reconciled }.forEach { putTxn(it.copy(reconciled = true)) }
        put(Kinds.ACCOUNT, accountId, account.copy(lastReconciled = date, reconciledBalance = bankBalance), Account.serializer())
    }

    /**
     * Merges a transaction entered by hand with the bank's copy so it shows once. What you entered
     * (payee, category or transfer, memo, schedule) is kept; the bank supplies its date, its id
     * (so the next import sees it as a duplicate) and marks it cleared.
     */
    fun mergeTransactions(enteredId: String, bankId: String) = edit {
        val entered = d.transactions[enteredId] ?: return@edit
        val bank = d.transactions[bankId] ?: return@edit
        if (entered.amount != bank.amount || entered.accountId != bank.accountId) return@edit
        val enteredHasJob = entered.categoryId != null || entered.transferAccountId != null
        // The bank copy goes; its transfer partner (if any) is kept as a plain transaction.
        removeTxn(bank.id, removePair = false)
        val merged = entered.copy(
            date = bank.date,
            payee = entered.payee.ifBlank { bank.payee },
            memo = entered.memo.ifBlank { bank.memo },
            categoryId = if (enteredHasJob) entered.categoryId else bank.categoryId,
            transferAccountId = if (enteredHasJob) entered.transferAccountId else bank.transferAccountId,
            transferPairId = if (enteredHasJob) entered.transferPairId else bank.transferPairId,
            cleared = true,
            merged = true,
            importId = bank.importId,
            reconciled = entered.reconciled || bank.reconciled,
            loanSplit = entered.loanSplit ?: bank.loanSplit.takeIf { !enteredHasJob },
        )
        saveTxn(merged)
    }

    /** Remembers that two transactions are separate, so they're no longer offered for merging. */
    fun markNotDuplicate(enteredId: String, bankId: String) = edit {
        val entered = d.transactions[enteredId] ?: return@edit
        if (bankId !in entered.notDuplicateOf) putTxn(entered.copy(notDuplicateOf = entered.notDuplicateOf + bankId))
    }

    // ---------------------------------------------------------------- rules and loans

    fun saveRule(rule: PayeeRule) = edit { put(Kinds.RULE, rule.id, rule, PayeeRule.serializer()) }

    fun deleteRule(id: String) = edit { remove(Kinds.RULE, id) }

    /** Creates a rule, or updates the one that already matches the same text. */
    private fun saveRuleIn(e: Edit, match: String, categoryId: String?, transferAccountId: String?, loanAccountId: String?) = with(e) {
        val text = ImportMatcher.normalizePayee(match)
        val existing = d.rules.values.firstOrNull { ImportMatcher.normalizePayee(it.match) == text }
        val rule = PayeeRule(existing?.id ?: newId(), text, categoryId, transferAccountId.takeIf { loanAccountId == null }, loanAccountId)
        if (rule != existing) put(Kinds.RULE, rule.id, rule, PayeeRule.serializer())
    }

    /** Links (or unlinks, with null) a payment to a loan and splits it into principal, interest and escrow. */
    fun setLoanPayment(txnId: String, loanAccountId: String?) = edit {
        val t = d.transactions[txnId] ?: return@edit
        saveTxn(t.copy(loanSplit = loanAccountId?.let { LoanSplit(it, 0, 0, 0) }, transferAccountId = t.transferAccountId.takeIf { loanAccountId == null }))
    }

    private fun today(): LocalDate =
        kotlinx.datetime.Instant.fromEpochMilliseconds(clock()).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date

    // ---------------------------------------------------------------- scheduled

    fun saveScheduled(s: ScheduledTransaction) = edit { putScheduled(s) }

    fun deleteScheduled(id: String) = edit { remove(Kinds.SCHEDULED, id) }

    /** Enters the next occurrence now (e.g. a bill paid early) and advances the schedule. */
    fun enterScheduledNow(id: String) = edit {
        val s = d.scheduled[id] ?: return@edit
        enterOccurrence(s, s.nextDate)
        advanceSchedule(s, s.nextDate)
    }

    /** Enters every scheduled occurrence that is due on or before [today]. */
    fun processScheduled(today: LocalDate) = edit {
        for (s in d.scheduled.values.toList()) {
            var cur: ScheduledTransaction? = s
            var guard = 0
            while (cur != null && cur.nextDate <= today && guard++ < 500) {
                enterOccurrence(cur, cur.nextDate)
                val next = Scheduler.nextAfter(cur, cur.nextDate)
                cur = next?.let { cur!!.copy(nextDate = it) }
            }
            when {
                cur == null -> remove(Kinds.SCHEDULED, s.id)
                cur != s -> putScheduled(cur)
            }
        }
    }

    // ---------------------------------------------------------------- import

    fun applyImport(accountId: String, accountKey: String?, rows: List<ImportRow>): ImportResult = edit {
        var added = 0
        var matched = 0
        var skipped = 0
        d.accounts[accountId]?.let { acct ->
            if (accountKey != null && accountKey !in acct.importKeys) {
                put(Kinds.ACCOUNT, acct.id, acct.copy(importKeys = acct.importKeys + accountKey), Account.serializer())
            }
        }
        // Oldest first, so loan payments split against the right balance.
        for (row in rows.sortedBy { it.source.date }) {
            if (row.action == ImportAction.ADD && row.makeRule && row.ruleMatch.isNotBlank()) {
                saveRuleIn(this, row.ruleMatch, row.categoryId, row.transferAccountId, row.loanAccountId)
            }
            when (row.action) {
                ImportAction.SKIP -> skipped++

                ImportAction.MATCH -> {
                    val existing = row.matchedTxnId?.let { d.transactions[it] }
                    val sched = row.scheduledId?.let { d.scheduled[it] }
                    when {
                        existing != null -> {
                            // Same as merging afterwards: keep what was entered, take the bank's date and id.
                            saveTxn(
                                existing.copy(
                                    date = row.source.date,
                                    importId = row.importId,
                                    cleared = true,
                                    merged = true,
                                    payee = existing.payee.ifBlank { row.payee },
                                )
                            )
                            matched++
                        }
                        sched != null && row.scheduledDate != null -> {
                            enterOccurrence(sched, row.scheduledDate, fromImport = row, importAccountId = accountId)
                            d.scheduled[sched.id]?.let { advanceSchedule(it, row.scheduledDate) }
                            matched++
                        }
                        else -> { addImported(this, accountId, row); added++ }
                    }
                }

                ImportAction.ADD -> { addImported(this, accountId, row); added++ }
            }
        }
        ImportResult(added, matched, skipped)
    }

    private fun addImported(e: Edit, accountId: String, row: ImportRow) = with(e) {
        var t = Transaction(
            id = newId(),
            accountId = accountId,
            date = row.source.date,
            amount = row.source.amount,
            payee = row.payee,
            memo = row.source.memo.takeIf { it != row.payee }.orEmpty(),
            categoryId = row.categoryId,
            transferAccountId = row.transferAccountId.takeIf { row.loanAccountId == null },
            cleared = true,
            importId = row.importId,
            loanSplit = row.loanAccountId?.let { LoanSplit(it, 0, 0, 0) },
        )
        val xfer = row.transferAccountId
        if (xfer != null) {
            // If the other account already has this transfer (e.g. imported from its own bank file), link to it.
            val leg = d.transactions.values
                .filter {
                    it.accountId == xfer && it.transferPairId == null && it.amount == -t.amount &&
                        ImportMatcher.daysApart(it.date, t.date) <= ImportMatcher.DAY_WINDOW
                }
                .minByOrNull { ImportMatcher.daysApart(it.date, t.date) }
            if (leg != null) t = t.copy(transferPairId = leg.id)
        }
        saveTxn(t)
    }

    // ---------------------------------------------------------------- defaults

    /**
     * Starter categories for a brand-new budget. They use fixed ids and timestamp 0, so if two
     * devices both start fresh and then sync, the copies collapse into one and any real edit wins.
     */
    fun seedDefaults() {
        if (ledger.size > 0) return
        val groups = listOf(
            "Bills" to listOf("Rent/Mortgage", "Electric", "Water", "Internet", "Phone", "Insurance"),
            "Needs" to listOf("Groceries", "Transportation", "Medical", "Household"),
            "Wants" to listOf("Dining Out", "Entertainment", "Shopping", "Vacation"),
            "Savings" to listOf("Emergency Fund", "Car Replacement", "Gifts"),
        )
        val ops = mutableListOf<Op>()
        groups.forEachIndexed { gi, (gName, cats) ->
            val gid = "seed-" + slug(gName)
            ops += Op(Kinds.key(Kinds.GROUP, gid), 0, "seed", ImabJson.encodeToJsonElement(CategoryGroup.serializer(), CategoryGroup(gid, gName, gi)))
            cats.forEachIndexed { ci, cName ->
                val cid = "seed-" + slug(cName)
                ops += Op(Kinds.key(Kinds.CATEGORY, cid), 0, "seed", ImabJson.encodeToJsonElement(Category.serializer(), Category(cid, gid, cName, ci)))
            }
        }
        merge(ops)
    }

    private fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
}

/** Payee of the transaction a reconcile adds to make up a difference with the bank. */
const val RECONCILE_ADJUSTMENT_PAYEE = "Reconciliation adjustment"

/** Id of the principal entry a loan payment creates in the loan account. */
fun principalLegId(paymentId: String) = "$paymentId-principal"
