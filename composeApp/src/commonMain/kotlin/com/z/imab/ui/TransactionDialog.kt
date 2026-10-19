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
) {
    var accountId by mutableStateOf(accountId)
    var date by mutableStateOf(date)
    var outflow by mutableStateOf(amount <= 0)
    var amountText by mutableStateOf(if (amount == 0L) "" else Money.toInput(abs(amount)))
    var payee by mutableStateOf(payee)
    var memo by mutableStateOf(memo)
    var choice by mutableStateOf(choice)

    val amount: Long? get() = Money.parse(amountText)?.let { if (outflow) -abs(it) else abs(it) }
    val valid: Boolean get() = amount != null && accountId.isNotEmpty()
}

@Composable
private fun TxnFields(form: TxnForm, data: BudgetData, dateLabel: String) {
    val accounts = data.sortedAccounts.filter { !it.closed || it.id == form.accountId }
    Picker("Account", accounts.map { PickerOption(it.id, it.name) }, form.accountId, { form.accountId = it }, Modifier.fillMaxWidth())
    DateField(dateLabel, form.date, { form.date = it }, Modifier.fillMaxWidth())
    val isTransfer = Choice.transferId(form.choice) != null
    if (!isTransfer) {
        OutlinedTextField(form.payee, { form.payee = it }, label = { Text("Payee") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Picker("Category or transfer", categoryOptions(data, form.accountId), form.choice, { form.choice = it }, Modifier.fillMaxWidth())
    if (isTransfer) {
        val other = Choice.transferId(form.choice)
        if (data.isOnBudget(form.accountId) == data.isOnBudget(other)) {
            Text("Transfers between two budget accounts don't need a category.", style = MaterialTheme.typography.bodySmall)
        } else if (data.isOnBudget(form.accountId)) {
            Text("Money leaving the budget for a tracking account — pick a category instead if you want it budgeted.", style = MaterialTheme.typography.bodySmall)
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
        )
    }
    var cleared by remember { mutableStateOf(initial?.cleared ?: false) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cleared, { cleared = it })
                    Text("Cleared")
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
                        categoryId = Choice.categoryId(form.choice),
                        transferAccountId = transfer,
                        // Changing the transfer target re-links to a new leg.
                        transferPairId = base.transferPairId.takeIf { transfer != null && transfer == base.transferAccountId },
                        cleared = cleared,
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
                        categoryId = Choice.categoryId(form.choice),
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
    onSave: (Account, Long, LocalDate) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: suggestedName) }
    var type by remember { mutableStateOf(initial?.type ?: suggestedType) }
    var onBudget by remember { mutableStateOf(initial?.onBudget ?: (suggestedType !in setOf(AccountType.LOAN, AccountType.INVESTMENT))) }
    var closed by remember { mutableStateOf(initial?.closed ?: false) }
    var balanceText by remember { mutableStateOf(if (suggestedBalance == 0L) "" else Money.toInput(suggestedBalance)) }
    var startDate by remember { mutableStateOf(suggestedDate) }
    var confirmDelete by remember { mutableStateOf(false) }
    val balance = if (balanceText.isBlank()) 0L else Money.parse(balanceText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Type", AccountType.entries.map { PickerOption(it, it.label) }, type, { type = it }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(onBudget, { onBudget = it })
                    Column {
                        Text("Budget account")
                        Text("Off = tracking only (loans, investments)", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (initial == null) {
                    MoneyField("Current balance (negative for debt)", balanceText, { balanceText = it }, Modifier.fillMaxWidth(), isError = balance == null)
                    DateField("Balance as of", startDate, { startDate = it }, Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(closed, { closed = it })
                        Text("Closed")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && balance != null, onClick = {
                val a = (initial ?: Account(newId(), name)).copy(name = name.trim(), type = type, onBudget = onBudget, closed = closed)
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
    val from = data.sortedAccounts.firstOrNull { it.onBudget && !it.closed && !it.type.isCredit }
        ?: data.sortedAccounts.firstOrNull { it.id != cardId }
        ?: return null
    val owed = maxOf(0, -data.balance(cardId))
    val pay = if (amount > 0) minOf(amount, owed).takeIf { it > 0 } ?: amount else owed
    return Transaction(id, from.id, today(), -pay, transferAccountId = cardId)
}
