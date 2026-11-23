package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.data.BudgetStore
import com.z.imab.model.Money
import com.z.imab.model.Transaction
import com.z.imab.platform.Pickers
import com.z.imab.receipt.ExpenseItem
import com.z.imab.receipt.ExpenseReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExpenseFilter(val label: String) { TO_SUBMIT("To submit"), SUBMITTED("Submitted"), ALL("All") }

private enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    PDF("PDF with receipts", "pdf", "application/pdf"),
    CSV("Spreadsheet (CSV)", "csv", "text/csv"),
}

/**
 * Work expenses: transactions marked as paid for work. Pick the ones to claim, export them as a PDF
 * report with their receipts (or a CSV), and mark them submitted so they drop off the list.
 */
@Composable
fun ExpensesScreen(store: BudgetStore, pickers: Pickers, nav: NavState) {
    val data by store.data.collectAsState()
    val settings by store.settings.collectAsState()
    var filter by remember { mutableStateOf(ExpenseFilter.TO_SUBMIT) }
    val all = remember(data) { data.transactions.values.filter { it.workExpense }.sortedWith(compareByDescending<Transaction> { it.date }.thenBy { it.payee }) }
    val shown = when (filter) {
        ExpenseFilter.TO_SUBMIT -> all.filter { it.expenseSubmitted == null }
        ExpenseFilter.SUBMITTED -> all.filter { it.expenseSubmitted != null }
        ExpenseFilter.ALL -> all
    }
    // Ticked expenses; everything shown starts ticked when the filter changes.
    val unticked = remember(filter) { mutableStateListOf<String>() }
    val picked = shown.filter { it.id !in unticked }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var exporting by remember { mutableStateOf<ExportFormat?>(null) }
    var exported by remember { mutableStateOf<Pair<String, List<Transaction>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun export(format: ExportFormat, title: String, name: String) {
        val items = picked
        busy = true
        scope.launch {
            runCatching {
                val bytes = when (format) {
                    ExportFormat.CSV -> ExpenseReport.csv(data, items).encodeToByteArray()
                    ExportFormat.PDF -> {
                        val withPhotos = items.map { ExpenseItem(it, store.receiptImage(it.receipt)) }
                        withContext(Dispatchers.Default) { ExpenseReport.pdf(data, withPhotos, title, name, today()) }
                    }
                }
                pickers.shareFile(ExpenseReport.fileName(items, format.extension), bytes, format.mime)
            }.onSuccess { where ->
                if (where != null) exported = where to items
            }.onFailure { error = it.message ?: "Couldn't export the expenses." }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.go(Screen.Reports) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Work expenses", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            ScanReceiptButton(store, fallbackAccountId = { null }, iconOnly = true, workExpense = true)
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpenseFilter.entries.forEach { f ->
                val count = when (f) {
                    ExpenseFilter.TO_SUBMIT -> all.count { it.expenseSubmitted == null }
                    ExpenseFilter.SUBMITTED -> all.count { it.expenseSubmitted != null }
                    ExpenseFilter.ALL -> all.size
                }
                FilterChip(filter == f, { filter = f }, label = { Text("${f.label} ($count)") })
            }
        }
        if (all.isEmpty()) {
            Text(
                "No work expenses yet. Tick “Work expense” on a transaction, or when you scan a receipt, and it's listed here " +
                    "to export as a PDF with the receipts (or a CSV) and send in.",
                Modifier.padding(16.dp),
            )
        }
        if (shown.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Row(Modifier.padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(picked.size == shown.size, { all -> if (all) unticked.clear() else unticked.addAll(shown.map { it.id }) })
                    Text("${picked.size} of ${shown.size} selected", Modifier.weight(1f))
                    Text(Money.format(ExpenseReport.total(picked)), fontWeight = FontWeight.SemiBold)
                }
            }
            val missing = picked.count { it.receipt == null }
            if (missing > 0) {
                Text(
                    "$missing selected expense${if (missing == 1) " has" else "s have"} no receipt photo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = t }.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(t.id !in unticked, { on -> if (on) unticked.remove(t.id) else unticked.add(t.id) })
                    Text(t.date.short(), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.weight(1f)) {
                        Text(data.payeeLabel(t), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        val sub = buildString {
                            append(data.categoryLabel(t)).append(" · ").append(data.accountName(t.accountId))
                            if (t.memo.isNotBlank()) append(" · ").append(t.memo)
                            t.expenseSubmitted?.let { append(" · submitted ").append(it.short()) }
                        }
                        Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (t.receipt != null) Icon(ReceiptIcon, "Has receipt", Modifier.size(18.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(Money.format(ExpenseReport.claimed(t)), Modifier.padding(start = 8.dp))
                }
                HorizontalDivider()
            }
        }
        if (shown.isNotEmpty()) {
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exporting = ExportFormat.PDF }, enabled = picked.isNotEmpty() && !busy) { Text(if (busy) "Exporting…" else "Export PDF") }
                OutlinedButton(onClick = { exporting = ExportFormat.CSV }, enabled = picked.isNotEmpty() && !busy) { Text("Export CSV") }
                val submitted = picked.filter { it.expenseSubmitted != null }
                val notSubmitted = picked.filter { it.expenseSubmitted == null }
                if (notSubmitted.isNotEmpty()) {
                    TextButton(onClick = { store.markSubmitted(notSubmitted.map { it.id }, today()) }) { Text("Mark submitted") }
                }
                if (submitted.isNotEmpty()) {
                    TextButton(onClick = { store.markSubmitted(submitted.map { it.id }, null) }) { Text("Mark not submitted") }
                }
            }
        }
    }

    exporting?.let { format ->
        var title by remember { mutableStateOf("Expense report") }
        var name by remember { mutableStateOf(settings.expenseName) }
        AlertDialog(
            onDismissRequest = { exporting = null },
            title = { Text("Export ${picked.size} expense${if (picked.size == 1) "" else "s"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${format.label} · ${Money.format(ExpenseReport.total(picked))}", style = MaterialTheme.typography.bodyMedium)
                    if (format == ExportFormat.PDF) {
                        OutlinedTextField(title, { title = it }, label = { Text("Report title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(name, { name = it }, label = { Text("Your name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val elsewhere = picked.count { it.receipt != null && it.receipt.device.isNotBlank() && it.receipt.device != settings.deviceName }
                        Text(
                            "A summary table, then each receipt on its own page." +
                                if (elsewhere > 0) " $elsewhere receipt photo${if (elsewhere == 1) " is" else "s are"} on another device and won't be included; export from there to add them." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Date, merchant, category, card or account, memo and amount, one expense per row.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exporting = null
                    if (format == ExportFormat.PDF) store.setExpenseName(name.trim())
                    export(format, title.trim(), name.trim())
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { exporting = null }) { Text("Cancel") } },
        )
    }
    exported?.let { (where, items) ->
        val toMark = items.filter { it.expenseSubmitted == null }
        AlertDialog(
            onDismissRequest = { exported = null },
            title = { Text("Exported") },
            text = {
                Text(
                    "$where." + if (toMark.isNotEmpty()) "\n\nOnce you've sent it in, mark ${if (toMark.size == 1) "this expense" else "these ${toMark.size} expenses"} as submitted so ${if (toMark.size == 1) "it moves" else "they move"} off the To submit list." else "",
                )
            },
            confirmButton = {
                if (toMark.isNotEmpty()) {
                    TextButton(onClick = { store.markSubmitted(toMark.map { it.id }, today()); exported = null }) { Text("Mark submitted") }
                } else {
                    TextButton(onClick = { exported = null }) { Text("OK") }
                }
            },
            dismissButton = { if (toMark.isNotEmpty()) TextButton(onClick = { exported = null }) { Text("Not yet") } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Can't export") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
    editing?.let { t ->
        TransactionDialog(
            data = data,
            initial = t,
            defaultAccountId = t.accountId,
            newId = store.budget::newId,
            onSave = { store.saveTransaction(it); editing = null },
            onDelete = { store.deleteTransaction(t.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}
