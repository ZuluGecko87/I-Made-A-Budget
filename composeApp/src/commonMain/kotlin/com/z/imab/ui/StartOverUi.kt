package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.z.imab.data.Budget
import com.z.imab.data.BudgetStore
import com.z.imab.data.Ledger
import com.z.imab.data.LedgerFile
import com.z.imab.platform.Pickers
import kotlinx.coroutines.launch

private enum class Reset { FRESH, SCRATCH }

/** An archive picked to restore, with a summary to confirm against. */
private class Restorable(val name: String, val file: LedgerFile, val accounts: Int, val transactions: Int, val categories: Int)

private fun restorable(name: String, text: String): Restorable {
    val file = Ledger.decodeFile(text)
    val preview = Budget("preview").also { it.adopt(file) }.data
    return Restorable(name, file, preview.accounts.size, preview.transactions.size, preview.categories.size)
}

/** Settings card: fresh start, start from scratch, and restoring an archived budget. */
@Composable
fun StartOverCard(store: BudgetStore, pickers: Pickers) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Reset?>(null) }
    var restoring by remember { mutableStateOf<Restorable?>(null) }
    var keepBalances by remember { mutableStateOf(true) }
    var understood by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val localArchives by produceState(emptyList<String>(), refresh) { value = store.localArchives().take(5) }

    /** Archives the current budget, then runs [action]. Stops if the archive couldn't be saved. */
    fun archiveThen(what: String, action: () -> Unit) {
        scope.launch {
            busy = true
            try {
                val archive = store.archiveCurrent()
                val where = archive.savedTo ?: pickers.saveFile(archive.name, archive.text)
                if (where == null) {
                    message = "Cancelled — nothing was changed. (A copy of the budget was kept on this device.)"
                    return@launch
                }
                action()
                message = "$what. The previous budget was archived as \"${archive.name}\"" +
                    (if (archive.savedTo != null) " in ${archive.savedTo.substringBeforeLast('/', "your sync folder")}" else " ($where)") +
                    " and can be restored below."
            } catch (e: Exception) {
                message = "Couldn't archive the budget, so nothing was changed: ${e.message}"
            } finally {
                busy = false
                refresh++
            }
        }
    }

    fun pickRestore(name: String?, text: String?) {
        if (text == null) return
        message = null
        restoring = runCatching { restorable(name ?: "archive", text) }.getOrElse {
            message = "That isn't an IMAB budget file."
            null
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Start over", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Both options first save the whole budget as a dated archive next to your sync file (and on this device), " +
                    "so it can be brought back if it was a mistake. Your other devices switch to the new budget when they sync.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { confirming = Reset.FRESH }) { Text("Fresh start…") }
                OutlinedButton(enabled = !busy, onClick = { understood = false; confirming = Reset.SCRATCH }) {
                    Text("Start from scratch…", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch { pickers.pickArchive()?.let { pickRestore(it.name, it.text) } }
                }) { Text("Restore from archive…") }
            }
            if (busy) Text("Archiving…", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (localArchives.isNotEmpty()) {
                Text("Archives on this device", style = MaterialTheme.typography.labelLarge)
                localArchives.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name.removeSuffix(".json"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = { scope.launch { pickRestore(name, store.readLocalArchive(name)) } }) { Text("Restore") }
                    }
                }
            }
        }
    }

    when (confirming) {
        Reset.FRESH -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Fresh start") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keeps: accounts, category groups and categories (with goals), scheduled transactions, auto-categorize rules and combined goals.")
                    Text("Removes: all transactions and all assigned money, in every month.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(keepBalances, { keepBalances = it })
                        Column {
                            Text("Keep current account balances")
                            Text(
                                "Each account starts the new budget with today's balance. Off: every account starts at \$0.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    val keep = keepBalances
                    archiveThen("Started fresh") { store.freshStart(keep) }
                }) { Text("Archive and start fresh") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
        Reset.SCRATCH -> AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Start from scratch") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Removes everything — accounts, categories, transactions, goals, schedules and rules — and starts a new budget with the default categories.")
                    Text("Your sync location, device name and theme are kept.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(understood, { understood = it })
                        Text("I understand everything will be removed (an archive is saved first).")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = understood, onClick = {
                    confirming = null
                    archiveThen("Started from scratch") { store.startFromScratch() }
                }) { Text("Archive and start from scratch", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
        null -> {}
    }

    restoring?.let { r ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore archive?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.name.removeSuffix(".json"), fontWeight = FontWeight.SemiBold)
                    Text("${r.accounts} accounts · ${r.categories} categories · ${r.transactions} transactions")
                    Text(
                        "This replaces the current budget on all your devices. The current budget is archived first, so this can be undone too.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    restoring = null
                    archiveThen("Restored \"${r.name.removeSuffix(".json")}\"") { store.restore(r.file) }
                }) { Text("Archive current and restore") }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } },
        )
    }
}
