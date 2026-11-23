package com.z.imab.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.itemsIndexed
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
            LargeTitle("Scheduled") {
                IconButton(onClick = { adding = true }, enabled = data.accounts.isNotEmpty()) {
                    Icon(Icons.Default.Add, "Schedule a transaction", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (list.isEmpty()) {
            item {
                Group(Modifier.padding(top = 8.dp)) {
                    Text(
                        "Schedule bills, paychecks and recurring transfers. Each one is added to its account on its date, " +
                            "and matched to the bank's copy when you import a statement.",
                        Modifier.padding(16.dp),
                    )
                    RowSeparator()
                    TextButton(onClick = { adding = true }, enabled = data.accounts.isNotEmpty(), modifier = Modifier.padding(horizontal = 4.dp)) { Text("Schedule a transaction") }
                }
            }
        }
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
          Column(Modifier.groupRow(first = i == 0, last = i == list.lastIndex)) {
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
            if (i < list.lastIndex) RowSeparator()
          }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
