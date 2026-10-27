package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.z.imab.budget.LoanMath
import com.z.imab.data.Budget
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.RuleBook
import com.z.imab.model.Account
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.PayeeRule
import com.z.imab.model.YearMonth

/** Settings card listing auto-categorize rules. */
@Composable
fun RulesCard(data: BudgetData, budget: Budget) {
    var editing by remember { mutableStateOf<PayeeRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    val rules = data.rules.values.sortedBy { it.match }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto-categorize rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Imported transactions come in uncategorized. A rule fills in the category (and can mark a payment " +
                    "as a loan payment) whenever the bank's payee contains its words. Make one here, or tick " +
                    "\"Always use this\" on a row when importing.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (rules.isEmpty()) {
                Text("No rules yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rules.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("\"${r.match}\"", fontWeight = FontWeight.Medium)
                        Text("→ ${RuleBook.describe(data, r)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, null)
                Text("New rule")
            }
        }
    }

    if (adding || editing != null) {
        RuleDialog(
            data = data,
            initial = editing,
            onSave = { budget.saveRule(it.copy(id = editing?.id ?: budget.newId())); adding = false; editing = null },
            onDelete = editing?.let { r -> { budget.deleteRule(r.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@Composable
private fun RuleDialog(
    data: BudgetData,
    initial: PayeeRule?,
    onSave: (PayeeRule) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var match by remember { mutableStateOf(initial?.match.orEmpty()) }
    var choice by remember { mutableStateOf(Choice.of(initial?.categoryId, initial?.transferAccountId)) }
    var loanId by remember { mutableStateOf(initial?.loanAccountId) }
    val text = ImportMatcher.normalizePayee(match)
    val transfer = Choice.transferId(choice)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New rule" else "Edit rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    match, { match = it },
                    label = { Text("When the payee contains") },
                    supportingText = { Text("Words only; numbers and symbols are ignored. e.g. main street mortgage") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CategoryPicker("Set category or transfer", data, null, choice, { choice = it }, Modifier.fillMaxWidth())
                if (data.loanAccounts.isNotEmpty() && transfer == null) {
                    Picker(
                        "Loan payment",
                        listOf(PickerOption<String?>(null, "Not a loan payment")) + data.loanAccounts.map { PickerOption<String?>(it.id, "Payment on ${it.name}") },
                        loanId,
                        { loanId = it },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotEmpty() && (choice != Choice.NONE || loanId != null),
                onClick = { onSave(PayeeRule("", text, Choice.categoryId(choice), transfer, loanId.takeIf { transfer == null })) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Summary at the top of a loan account: principal left, terms, last payment split, payoff estimate. */
@Composable
fun LoanSummary(account: Account, data: BudgetData, onEdit: () -> Unit) {
    val terms = account.loan
    Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (terms == null) {
                Text("Add the interest rate and escrow to split payments into principal, interest and escrow.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onEdit) { Text("Set loan details") }
                return@Column
            }
            val owed = maxOf(0, -data.balance(account.id))
            val payments = data.transactions.values.filter { it.loanSplit?.loanAccountId == account.id }.sortedBy { it.date }
            val last = payments.lastOrNull()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Principal left", Money.format(owed))
                Stat("Rate", "${terms.annualRatePct}%")
                Stat("Escrow", "${Money.format(terms.monthlyEscrow)}/mo")
                if (terms.extraPrincipal > 0) Stat("Extra principal", "${Money.format(terms.extraPrincipal)}/mo")
                Stat("Paid toward principal", Money.format(payments.sumOf { it.loanSplit!!.principal }))
                Stat("Interest paid", Money.format(payments.sumOf { it.loanSplit!!.interest }))
            }
            last?.loanSplit?.let { s ->
                Text(
                    "Last payment ${last.date.short()}: ${Money.format(-last.amount)} = ${Money.format(s.principal)} principal + " +
                        "${Money.format(s.interest)} interest + ${Money.format(s.escrow)} escrow",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PayoffEstimate(owed, terms, lastPayment = last?.let { -it.amount })
        }
    }
}

/**
 * Payoff date and remaining interest at the usual payment, and with the extra principal if one is
 * set. Without a usual payment it uses the last linked payment as the whole monthly amount.
 */
@Composable
private fun PayoffEstimate(owed: Long, terms: com.z.imab.model.LoanTerms, lastPayment: Long?) {
    if (owed == 0L) {
        Text("Paid off!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        return
    }
    val base = terms.monthlyPayment ?: lastPayment
    if (base == null) {
        Text(
            "Set a usual monthly payment (or link a payment) to estimate the payoff date.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val pi = base - terms.monthlyEscrow
    // With only a past payment to go on, it may already include the extra, so don't add it twice.
    val extra = if (terms.monthlyPayment != null) terms.extraPrincipal else 0
    val without = LoanMath.payoff(owed, terms.annualRatePct, pi)
    val with = if (extra > 0) LoanMath.payoff(owed, terms.annualRatePct, pi + extra) else null
    val now = YearMonth.of(today())
    fun line(label: String, p: com.z.imab.budget.LoanPayoff?, monthly: Long) =
        if (p == null) "$label: ${Money.format(monthly)}/month doesn't cover the interest, so it wouldn't be paid off."
        else "$label: paid off ${(now + p.months).label} (${p.months / 12} yr ${p.months % 12} mo) · ${Money.format(p.totalInterest)} more interest"

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            line(if (terms.monthlyPayment != null) "At ${Money.format(base)}/month" else "At your last payment (${Money.format(base)})", without, base),
            style = MaterialTheme.typography.bodySmall,
            color = if (with == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (with != null) {
            Text(
                line("With ${Money.format(extra)} extra toward principal", with, base + extra),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (without != null) {
                val months = without.months - with.months
                Text(
                    "The extra saves ${months / 12} yr ${months % 12} mo and ${Money.format(without.totalInterest - with.totalInterest)} in interest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (terms.monthlyPayment == null && terms.extraPrincipal > 0) {
            Text(
                "Set the usual monthly payment to compare with and without the extra ${Money.format(terms.extraPrincipal)}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
