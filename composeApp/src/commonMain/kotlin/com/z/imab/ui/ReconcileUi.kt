package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.model.Account
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import kotlinx.datetime.LocalDate

/**
 * Reconcile an account against a bank statement: enter the statement date and balance, compare
 * with the cleared balance up to that date, clear anything that's missing, then lock them in.
 * [data] is live, so ticking a transaction cleared updates the difference straight away.
 */
@Composable
fun ReconcileDialog(
    data: BudgetData,
    account: Account,
    onToggleCleared: (String, Boolean) -> Unit,
    onReconcile: (date: LocalDate, bankBalance: Long, adjust: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Pre-filled statement balance (for previews). */
    initialBalanceText: String = "",
) {
    val debt = account.type.isCredit || account.type.isLoan
    var date by remember { mutableStateOf(today()) }
    var text by remember { mutableStateOf(initialBalanceText) }
    // Card and loan statements show what's owed as a positive number.
    var owed by remember { mutableStateOf(debt) }
    var confirmAdjust by remember { mutableStateOf(false) }
    val entered = Money.parse(text)
    val bankBalance = entered?.let { if (owed) -kotlin.math.abs(it) else it }

    val txns = data.transactionsByAccount[account.id].orEmpty().filter { it.date <= date }
    val cleared = txns.filter { it.cleared }.sumOf { it.amount }
    val uncleared = txns.filter { !it.cleared }.sortedByDescending { it.date }
    val difference = bankBalance?.minus(cleared)
    fun shown(balance: Long) = if (owed) "${Money.format(-balance)} owed" else Money.format(balance)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reconcile ${account.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the balance on your bank statement (or online banking). Only cleared transactions up to that date are compared.",
                    style = MaterialTheme.typography.bodySmall,
                )
                account.lastReconciled?.let {
                    Text(
                        "Last reconciled ${it.short()} at ${account.reconciledBalance?.let(::shown) ?: "—"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DateField("Statement date", date, { date = it }, Modifier.fillMaxWidth())
                MoneyField(
                    if (owed) "Amount owed on the statement" else "Balance on the statement",
                    text, { text = it }, Modifier.fillMaxWidth(), isError = text.isNotBlank() && entered == null,
                )
                if (debt) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(owed, { owed = true }, label = { Text("Amount owed") })
                        FilterChip(!owed, { owed = false }, label = { Text("In credit") })
                    }
                }

                // The comparison.
                val matches = difference == 0L
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            difference == null -> MaterialTheme.colorScheme.surfaceVariant
                            matches -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompareLine("Cleared in IMAB", shown(cleared))
                        CompareLine("Bank statement", bankBalance?.let(::shown) ?: "—")
                        HorizontalDivider()
                        when {
                            difference == null -> Text("Enter the statement balance to compare.", style = MaterialTheme.typography.bodySmall)
                            matches -> Text("✓ Matches the bank", fontWeight = FontWeight.SemiBold)
                            else -> {
                                // Say which way it's off in plain words.
                                val ahead = if (owed) difference < 0 else difference > 0
                                Text(
                                    "Off by ${Money.format(kotlin.math.abs(difference))} — the bank shows ${if (ahead) (if (owed) "more owed" else "more") else (if (owed) "less owed" else "less")} than your cleared transactions.",
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                if (uncleared.isNotEmpty()) {
                    Text("Not cleared yet (up to ${date.short()})", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tick any that appear on the statement; the difference updates as you go.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        uncleared.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(t.cleared, { onToggleCleared(t.id, it) })
                                Text(t.date.short(), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                                Text(data.payeeLabel(t), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Money.format(t.amount), color = amountColor(t.amount))
                            }
                        }
                    }
                } else if (difference != null && difference != 0L) {
                    Text(
                        "Everything up to ${date.short()} is cleared, so the difference is a transaction IMAB doesn't have (a fee or interest, say) or an amount that's different.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (confirmAdjust && difference != null && difference != 0L) {
                    Text(
                        "This adds a cleared \"Reconciliation adjustment\" of ${Money.format(difference)} dated ${date.short()}" +
                            if (account.inBudget) ", which comes out of (or goes into) Ready to Assign." else ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            when {
                difference == 0L -> TextButton(onClick = { onReconcile(date, bankBalance!!, false) }) { Text("Finish reconcile") }
                difference != null && !confirmAdjust -> TextButton(onClick = { confirmAdjust = true }) { Text("Adjust and finish…") }
                difference != null -> TextButton(onClick = { onReconcile(date, bankBalance!!, true) }) {
                    Text("Add ${Money.format(difference)} adjustment and finish")
                }
                else -> TextButton(enabled = false, onClick = {}) { Text("Finish reconcile") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (difference != null && difference != 0L) "Finish later" else "Cancel") } },
    )
}

@Composable
private fun CompareLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
