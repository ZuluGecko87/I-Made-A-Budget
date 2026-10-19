package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.z.imab.data.Budget
import com.z.imab.model.BudgetData

private const val SHOWN = 25

/** Settings card to find a payee and rename it everywhere (merging if the new name already exists). */
@Composable
fun PayeesCard(data: BudgetData, budget: Budget) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    val counts = remember(data) { data.payees.toMap() }
    val matches = remember(data, query) { data.searchPayees(query, limit = SHOWN) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Payees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Payees you've used are offered when you add a transaction. Tap one to rename it on every " +
                    "transaction — e.g. to fix a misspelling. Renaming to a name that already exists merges them.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                query, { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search ${data.payees.size} payees") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (matches.isEmpty()) {
                Text(if (data.payees.isEmpty()) "No payees yet." else "No payees match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            matches.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { renaming = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f))
                    val n = counts[p] ?: 0
                    Text("$n use${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            if (data.payees.size > SHOWN && query.isBlank()) {
                Text("Showing the $SHOWN most used. Search to find others.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    renaming?.let { old ->
        val n = counts[old] ?: 0
        TextInputDialog(
            title = "Rename payee",
            initial = old,
            label = "Payee name (used on $n transaction${if (n == 1) "" else "s"})",
            confirm = "Rename",
            onDismiss = { renaming = null },
        ) { new ->
            budget.renamePayee(old, new)
            renaming = null
        }
    }
}
