package com.z.imab.data

import com.z.imab.budget.Scheduler
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.ImportRow
import com.z.imab.importer.ImportWindow
import com.z.imab.model.Account
import com.z.imab.model.Assignment
import com.z.imab.model.BudgetData
import com.z.imab.model.Category
import com.z.imab.model.CategoryGroup
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

        /** Saves a transaction, keeping the other leg of a transfer in step. */
        fun saveTxn(input: Transaction) {
            val old = d.transactions[input.id]
            val xfer = input.transferAccountId?.takeIf { it != input.accountId && d.accounts.containsKey(it) }
            if (xfer == null) {
                old?.transferPairId?.let { removeTxn(it, removePair = false) }
                putTxn(input.copy(transferAccountId = null, transferPairId = null))
                return
            }

            val pairId = input.transferPairId ?: old?.transferPairId ?: "${input.id}-x"
            if (old?.transferPairId != null && old.transferPairId != pairId) removeTxn(old.transferPairId, removePair = false)
            val fromOn = d.accounts[input.accountId]?.onBudget == true
            val toOn = d.accounts[xfer]?.onBudget == true
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
                categoryId = if (toOn && !fromOn) (existingPair?.categoryId ?: input.categoryId ?: READY_TO_ASSIGN) else null,
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
            val pair = t.transferPairId?.let { d.transactions[it] } ?: return
            if (removePair) {
                remove(Kinds.TXN, pair.id)
            } else if (pair.transferPairId == id) {
                // The other leg stays as an ordinary (uncategorized) transaction.
                putTxn(pair.copy(transferAccountId = null, transferPairId = null))
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
    fun saveAccount(account: Account, startingBalance: Long = 0, startDate: LocalDate? = null): String = edit {
        val isNew = !d.accounts.containsKey(account.id)
        put(Kinds.ACCOUNT, account.id, account, Account.serializer())
        if (isNew && startingBalance != 0L && startDate != null) {
            putTxn(startingBalanceTxn(newId(), account, startingBalance, startDate))
        }
        account.id
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
            !account.onBudget -> null
            account.type.isCredit && amount < 0 -> PREEXISTING_DEBT
            else -> READY_TO_ASSIGN
        },
        cleared = true,
    )

    fun deleteAccount(id: String) = edit {
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
        remove(Kinds.CATEGORY, id)
    }

    fun setAssigned(month: YearMonth, categoryId: String, amount: Long) = edit {
        val a = Assignment(month.toString(), categoryId, amount)
        if (amount == 0L) remove(Kinds.ASSIGNMENT, a.key) else put(Kinds.ASSIGNMENT, a.key, a, Assignment.serializer())
    }

    // ---------------------------------------------------------------- transactions

    fun saveTransaction(t: Transaction) = edit { saveTxn(t) }

    fun deleteTransaction(id: String) = edit { removeTxn(id) }

    fun setCleared(id: String, cleared: Boolean) = edit {
        d.transactions[id]?.let { putTxn(it.copy(cleared = cleared)) }
    }

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
        for (row in rows) {
            when (row.action) {
                ImportAction.SKIP -> skipped++

                ImportAction.MATCH -> {
                    val existing = row.matchedTxnId?.let { d.transactions[it] }
                    val sched = row.scheduledId?.let { d.scheduled[it] }
                    when {
                        existing != null -> {
                            putTxn(existing.copy(importId = row.importId, cleared = true, payee = existing.payee.ifBlank { row.payee }))
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
            transferAccountId = row.transferAccountId,
            cleared = true,
            importId = row.importId,
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
