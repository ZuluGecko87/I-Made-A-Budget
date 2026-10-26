package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.BudgetData
import com.z.imab.model.Frequency
import com.z.imab.model.LoanTerms
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.z.imab.model.Money
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/** Shared editable fields for transactions and scheduled transactions. */
private class TxnForm(
    accountId: String,
    date: LocalDate,
    amount: Long,
    payee: String,
    memo: String,
    choice: String,
    transferCategory: String? = null,
) {
    var accountId by mutableStateOf(accountId)
    /** For a transfer between a budget account and a tracking account: the budget category it uses. */
    var transferCategory by mutableStateOf(transferCategory)
    var date by mutableStateOf(date)
    var outflow by mutableStateOf(amount <= 0)
    var amountText by mutableStateOf(if (amount == 0L) "" else Money.toInput(abs(amount)))
    var payee by mutableStateOf(payee)
    var memo by mutableStateOf(memo)
    var choice by mutableStateOf(choice)

    val amount: Long? get() = Money.parse(amountText)?.let { if (outflow) -abs(it) else abs(it) }
    val valid: Boolean get() = amount != null && accountId.isNotEmpty()

    /** The category to save: the picked one, or for a budget/tracking transfer the budget category. */
    fun categoryToSave(data: BudgetData): String? {
        val transfer = Choice.transferId(choice) ?: return Choice.categoryId(choice)
        return transferCategory.takeIf { data.isOnBudget(accountId) != data.isOnBudget(transfer) }
    }
}

@Composable
private fun TxnFields(form: TxnForm, data: BudgetData, dateLabel: String) {
    val accounts = data.sortedAccounts.filter { !it.closed || it.id == form.accountId }
    Picker("Account", accounts.map { PickerOption(it.id, it.name) }, form.accountId, { form.accountId = it }, Modifier.fillMaxWidth())
    DateField(dateLabel, form.date, { form.date = it }, Modifier.fillMaxWidth())
    val isTransfer = Choice.transferId(form.choice) != null
    if (!isTransfer) {
        PayeeField(form.payee, { form.payee = it }, data, Modifier.fillMaxWidth())
    }
    Picker("Category or transfer", categoryOptions(data, form.accountId), form.choice, { form.choice = it }, Modifier.fillMaxWidth())
    if (isTransfer) {
        val other = Choice.transferId(form.choice)
        if (data.isOnBudget(form.accountId) == data.isOnBudget(other)) {
            Text("Transfers between two budget accounts don't need a category.", style = MaterialTheme.typography.bodySmall)
        } else {
            // Money crossing between the budget and a tracking account comes out of (or into) a category.
            val leaving = data.isOnBudget(form.accountId) == form.outflow
            Picker(
                "Budget category",
                categoryOptions(data, null, allowTransfers = false),
                Choice.of(form.transferCategory, null),
                { form.transferCategory = Choice.categoryId(it) },
                Modifier.fillMaxWidth(),
            )
            Text(
                if (leaving) "Money leaving your budget for a tracking account — choose the category it comes out of (e.g. an investing category)."
                else "Money coming into your budget from a tracking account — usually Ready to Assign.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = form.outflow, onClick = { form.outflow = true }, label = { Text("Outflow") })
        FilterChip(selected = !form.outflow, onClick = { form.outflow = false }, label = { Text("Inflow") })
    }
    MoneyField("Amount", form.amountText, { form.amountText = it }, Modifier.fillMaxWidth(), isError = form.amountText.isNotEmpty() && Money.parse(form.amountText) == null)
    OutlinedTextField(form.memo, { form.memo = it }, label = { Text("Memo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
fun TransactionDialog(
    data: BudgetData,
    initial: Transaction?,
    defaultAccountId: String,
    newId: () -> String,
    onSave: (Transaction) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val form = remember {
        TxnForm(
            accountId = initial?.accountId ?: defaultAccountId,
            date = initial?.date ?: today(),
            amount = initial?.amount ?: 0,
            payee = initial?.payee.orEmpty(),
            memo = initial?.memo.orEmpty(),
            choice = Choice.of(initial?.categoryId, initial?.transferAccountId),
            // A budget/tracking transfer keeps its category on the budget-side leg.
            transferCategory = initial?.categoryId ?: initial?.transferPairId?.let { data.transactions[it]?.categoryId },
        )
    }
    var cleared by remember { mutableStateOf(initial?.cleared ?: false) }
    var loanId by remember { mutableStateOf(initial?.loanSplit?.loanAccountId) }
    var reconciled by remember { mutableStateOf(initial?.reconciled ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A prefilled template (e.g. a card payment) isn't saved yet, so it's still "new".
    val isNew = initial == null || initial.id !in data.transactions
    val isCardPayment = isNew && data.isCreditAccount(initial?.transferAccountId) && (initial?.amount ?: 0) < 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCardPayment) "Record card payment" else if (isNew) "Add transaction" else "Edit transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TxnFields(form, data, "Date")
                val loans = data.loanAccounts.filter { it.id != form.accountId }
                if (loans.isNotEmpty() && form.outflow && Choice.transferId(form.choice) == null) {
                    Picker(
                        "Loan payment",
                        listOf(PickerOption<String?>(null, "Not a loan payment")) + loans.map { PickerOption<String?>(it.id, "Payment on ${it.name}") },
                        loanId,
                        { loanId = it },
                        Modifier.fillMaxWidth(),
                    )
                    val split = initial?.loanSplit?.takeIf { it.loanAccountId == loanId }
                    Text(
                        when {
                            loanId == null -> "Link a mortgage or loan payment to split it into principal, interest and escrow."
                            split != null -> "Principal ${Money.format(split.principal)} · interest ${Money.format(split.interest)} · escrow ${Money.format(split.escrow)}"
                            else -> "Split into principal, interest and escrow when saved."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reconciled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(true, { reconciled = false })
                        Column {
                            Text("🔒 Reconciled")
                            Text("Confirmed against a bank statement. Untick to change whether it's cleared.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cleared, { cleared = it })
                        Text("Cleared")
                    }
                }
                if (initial?.importId != null) {
                    Text("Imported from bank", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = form.valid, onClick = {
                val amount = form.amount ?: return@TextButton
                val base = initial ?: Transaction(newId(), form.accountId, form.date, amount)
                val transfer = Choice.transferId(form.choice)
                onSave(
                    base.copy(
                        accountId = form.accountId,
                        date = form.date,
                        amount = amount,
                        payee = if (transfer != null) "" else form.payee.trim(),
                        memo = form.memo.trim(),
                        categoryId = form.categoryToSave(data),
                        transferAccountId = transfer,
                        // Changing the transfer target re-links to a new leg.
                        transferPairId = base.transferPairId.takeIf { transfer != null && transfer == base.transferAccountId },
                        cleared = cleared || reconciled,
                        reconciled = reconciled,
                        loanSplit = loanId?.takeIf { transfer == null && amount < 0 }?.let { id ->
                            base.loanSplit?.takeIf { it.loanAccountId == id } ?: com.z.imab.model.LoanSplit(id, 0, 0, 0)
                        },
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        val msg = if (initial?.transferAccountId != null) "This also deletes the other side of the transfer." else "This can't be undone."
        ConfirmDialog("Delete transaction?", msg, onDismiss = { confirmDelete = false }, onConfirm = onDelete)
    }
}

@Composable
fun ScheduledDialog(
    data: BudgetData,
    initial: ScheduledTransaction?,
    newId: () -> String,
    onSave: (ScheduledTransaction) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val form = remember {
        TxnForm(
            accountId = initial?.accountId ?: data.sortedAccounts.firstOrNull()?.id.orEmpty(),
            date = initial?.nextDate ?: today(),
            amount = initial?.amount ?: 0,
            payee = initial?.payee.orEmpty(),
            memo = initial?.memo.orEmpty(),
            choice = Choice.of(initial?.categoryId, initial?.transferAccountId),
            transferCategory = initial?.categoryId,
        )
    }
    var frequency by remember { mutableStateOf(initial?.frequency ?: Frequency.MONTHLY) }
    var hasEnd by remember { mutableStateOf(initial?.endDate != null) }
    var endDate by remember { mutableStateOf(initial?.endDate ?: today()) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New scheduled transaction" else "Edit scheduled transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.accounts.isEmpty()) Text("Add an account first.", color = MaterialTheme.colorScheme.error)
                TxnFields(form, data, "Next date")
                Picker("Repeat", Frequency.entries.map { PickerOption(it, it.label) }, frequency, { frequency = it }, Modifier.fillMaxWidth())
                if (frequency != Frequency.ONCE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(hasEnd, { hasEnd = it })
                        Text("Ends")
                    }
                    if (hasEnd) DateField("End date", endDate, { endDate = it }, Modifier.fillMaxWidth())
                }
                Text(
                    "On its date it's entered in the account automatically; when you import a bank file it's matched to the bank's copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = form.valid, onClick = {
                val amount = form.amount ?: return@TextButton
                val transfer = Choice.transferId(form.choice)
                // Re-anchor the repetition whenever the date or frequency is edited.
                val anchor = if (initial != null && initial.nextDate == form.date && initial.frequency == frequency) initial.startDate else form.date
                onSave(
                    ScheduledTransaction(
                        id = initial?.id ?: newId(),
                        accountId = form.accountId,
                        startDate = anchor,
                        nextDate = form.date,
                        frequency = frequency,
                        amount = amount,
                        payee = if (transfer != null) "" else form.payee.trim(),
                        memo = form.memo.trim(),
                        categoryId = form.categoryToSave(data),
                        transferAccountId = transfer,
                        endDate = endDate.takeIf { hasEnd && frequency != Frequency.ONCE },
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete scheduled transaction?", "Transactions already entered are kept.", onDismiss = { confirmDelete = false }, onConfirm = onDelete)
    }
}

@Composable
fun AccountDialog(
    initial: Account?,
    newId: () -> String,
    suggestedName: String = "",
    suggestedType: AccountType = AccountType.CHECKING,
    suggestedBalance: Long = 0,
    suggestedDate: LocalDate = today(),
    /** Current balance of [initial]; lets a loan's principal be corrected when editing. */
    currentBalance: Long = 0,
    onSave: (Account, Long, LocalDate) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: suggestedName) }
    var type by remember { mutableStateOf(initial?.type ?: suggestedType) }
    var onBudget by remember { mutableStateOf(initial?.onBudget ?: (suggestedType != AccountType.INVESTMENT)) }
    var closed by remember { mutableStateOf(initial?.closed ?: false) }
    val isLoan = type.isLoan
    val startBalance = if (initial != null) currentBalance else suggestedBalance
    // Loans are entered as the positive principal remaining and stored as debt.
    var balanceText by remember {
        mutableStateOf(
            when {
                startBalance == 0L -> ""
                (initial?.type ?: suggestedType).isLoan -> Money.toInput(kotlin.math.abs(startBalance))
                else -> Money.toInput(startBalance)
            }
        )
    }
    var startDate by remember { mutableStateOf(suggestedDate) }
    var confirmDelete by remember { mutableStateOf(false) }
    val balance = (if (balanceText.isBlank()) 0L else Money.parse(balanceText))?.let { if (isLoan) -kotlin.math.abs(it) else it }
    var rateText by remember { mutableStateOf(initial?.loan?.annualRatePct?.toString().orEmpty()) }
    var escrowText by remember { mutableStateOf(initial?.loan?.monthlyEscrow?.takeIf { it > 0 }?.let(Money::toInput).orEmpty()) }
    var paymentText by remember { mutableStateOf(initial?.loan?.monthlyPayment?.let(Money::toInput).orEmpty()) }
    var extraText by remember { mutableStateOf(initial?.loan?.extraPrincipal?.takeIf { it > 0 }?.let(Money::toInput).orEmpty()) }
    val extra = if (extraText.isBlank()) 0L else Money.parse(extraText)
    val rate = rateText.trim().removeSuffix("%").toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
    val escrow = if (escrowText.isBlank()) 0L else Money.parse(escrowText)
    val payment = if (paymentText.isBlank()) null else Money.parse(paymentText)
    val loanValid = !isLoan || rateText.isBlank() ||
        (rate != null && escrow != null && extra != null && (paymentText.isBlank() || payment != null))
    val loanTerms = if (isLoan && rate != null && escrow != null) {
        LoanTerms(rate, kotlin.math.abs(escrow), payment?.let { kotlin.math.abs(it) }, kotlin.math.abs(extra ?: 0))
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Type", AccountType.entries.map { PickerOption(it, it.label) }, type, {
                    // Pick a sensible default when the type changes; investments are usually tracked.
                    if (it == AccountType.INVESTMENT) onBudget = false
                    if (type == AccountType.INVESTMENT && it != AccountType.INVESTMENT) onBudget = true
                    type = it
                }, Modifier.fillMaxWidth())
                // Budget vs tracking, as an explicit choice. Loans and mortgages are always tracking.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(onBudget && !isLoan, { onBudget = true }, enabled = !isLoan, label = { Text("Budget account") })
                    FilterChip(!onBudget || isLoan, { onBudget = false }, label = { Text("Tracking account") })
                }
                Text(
                    when {
                        isLoan -> "Loans and mortgages are always tracking accounts: what you owe doesn't reduce Ready to Assign. " +
                            "Budget the payments you make from checking (e.g. in a Mortgage category) and link them to this loan."
                        onBudget -> "Money in this account can be assigned in your budget. Use this for checking, savings, cash and credit cards."
                        else -> "Tracking only: the balance is shown but isn't part of your budget (e.g. investments)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (initial == null || isLoan) {
                    MoneyField(
                        when {
                            isLoan && initial != null -> "Principal remaining today"
                            isLoan -> "Principal remaining"
                            else -> "Current balance (negative for debt)"
                        },
                        balanceText, { balanceText = it }, Modifier.fillMaxWidth(), isError = balance == null,
                    )
                    if (initial == null) DateField("Balance as of", startDate, { startDate = it }, Modifier.fillMaxWidth())
                }
                if (isLoan) {
                    Text("Loan details", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Used to split each payment into interest, escrow and principal.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        rateText, { rateText = it },
                        label = { Text("Interest rate (% per year)") },
                        placeholder = { Text("e.g. 6.25") },
                        singleLine = true,
                        isError = rateText.isNotBlank() && rate == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MoneyField("Monthly escrow (taxes & insurance)", escrowText, { escrowText = it }, Modifier.fillMaxWidth(), isError = escrow == null)
                    MoneyField("Usual monthly payment incl. escrow (optional)", paymentText, { paymentText = it }, Modifier.fillMaxWidth(), isError = paymentText.isNotBlank() && payment == null)
                    MoneyField("Extra toward principal each month (optional)", extraText, { extraText = it }, Modifier.fillMaxWidth(), isError = extra == null)
                    Text(
                        "Used for the payoff estimate: the loan screen compares paying it off with and without the extra.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (initial?.loan != null) {
                        Text("Changes to the rate or escrow apply to payments from this month on.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (initial != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(closed, { closed = it })
                        Text("Closed")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && balance != null && loanValid, onClick = {
                val a = (initial ?: Account(newId(), name)).copy(
                    name = name.trim(), type = type, onBudget = onBudget && !isLoan, closed = closed,
                    loan = if (isLoan) loanTerms else null,
                )
                onSave(a, balance ?: 0, startDate)
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            "Delete account?",
            "All of its transactions are deleted too. To keep history, mark it Closed instead.",
            onDismiss = { confirmDelete = false },
            onConfirm = onDelete,
        )
    }
}

/**
 * A payment to [cardId] from the first budget (non-credit) account, for [amount] or what's owed if
 * that's less, or null if there's no other account to pay from. Open it in [TransactionDialog] so the account and amount can be changed before saving.
 */
fun paymentTemplate(data: BudgetData, cardId: String, amount: Long, id: String): Transaction? {
    val from = data.sortedAccounts.firstOrNull { it.inBudget && !it.closed && !it.type.isCredit }
        ?: data.sortedAccounts.firstOrNull { it.id != cardId }
        ?: return null
    val owed = maxOf(0, -data.balance(cardId))
    val pay = if (amount > 0) minOf(amount, owed).takeIf { it > 0 } ?: amount else owed
    return Transaction(id, from.id, today(), -pay, transferAccountId = cardId)
}
