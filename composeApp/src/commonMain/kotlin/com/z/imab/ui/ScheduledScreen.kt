package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.model.Money
import com.z.imab.model.ScheduledTransaction
import com.z.imab.data.BudgetStore

@Composable
fun ScheduledScreen(store: BudgetStore, wide: Boolean) {
    val data by store.data.collectAsState()
    var editing by remember { mutableStateOf<ScheduledTransaction?>(null) }
    var adding by remember { mutableStateOf(false) }
    val list = data.scheduled.values.sortedWith(compareBy({ it.nextDate }, { it.payee }))

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Scheduled", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Button(onClick = { adding = true }, enabled = data.accounts.isNotEmpty()) {
                    Icon(Icons.Default.Add, null)
                    Text("Schedule")
                }
            }
        }
        if (list.isEmpty()) {
            item {
                Text(
                    "Schedule bills, paychecks and recurring transfers. Each one is added to its account on its date, " +
                        "and matched to the bank's copy when you import a statement.",
                    Modifier.padding(16.dp),
                )
            }
        }
        items(list, key = { it.id }) { s ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(96.dp)) {
                    Text(s.nextDate.short(), fontWeight = FontWeight.Medium)
                    Text(s.frequency.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    val title = if (s.transferAccountId != null) {
                        "Transfer ${if (s.amount < 0) "to" else "from"} ${data.accountName(s.transferAccountId)}"
                    } else {
                        s.payee.ifBlank { "(no payee)" }
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = data.accountName(s.accountId) +
                        (if (s.transferAccountId == null || s.categoryId != null) " · " + data.categoryName(s.categoryId) else "")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(Money.format(s.amount), color = amountColor(s.amount))
                if (wide) {
                    TextButton(onClick = { store.budget.enterScheduledNow(s.id) }) { Text("Enter now") }
                }
            }
            HorizontalDivider()
        }
    }

    if (adding || editing != null) {
        val s = editing
        ScheduledDialog(
            data = data,
            initial = s,
            newId = store.budget::newId,
            onSave = {
                store.budget.saveScheduled(it)
                store.budget.processScheduled(today())
                adding = false; editing = null
            },
            onDelete = s?.let { { store.budget.deleteScheduled(it.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}
