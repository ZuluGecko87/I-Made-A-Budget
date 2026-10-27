package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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

/** Payees shown before expanding. */
private const val TOP = 5
private val PAGE_SIZES = listOf(10, 25, 50)

/** Settings card to find a payee and rename it everywhere (merging if the new name already exists). */
@Composable
fun PayeesCard(data: BudgetData, budget: Budget) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    val counts = remember(data) { data.payees.toMap() }
    val matches = remember(data, query) { data.searchPayees(query, limit = Int.MAX_VALUE) }
    // Collapsed: the top few. Expanded: a page at a time.
    var expanded by remember { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(PAGE_SIZES[1]) }
    var page by remember { mutableStateOf(0) }
    val pages = maxOf(1, (matches.size + pageSize - 1) / pageSize)
    if (page >= pages) page = pages - 1
    val shown = if (expanded) matches.drop(page * pageSize).take(pageSize) else matches.take(TOP)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Payees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Payees you've used are offered when you add a transaction. Tap one to rename it on every " +
                    "transaction — e.g. to fix a misspelling. Renaming to a name that already exists merges them.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                query, { query = it; page = 0 },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search ${data.payees.size} payees") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (matches.isEmpty()) {
                Text(if (data.payees.isEmpty()) "No payees yet." else "No payees match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            shown.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { renaming = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f))
                    val n = counts[p] ?: 0
                    Text("$n use${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            if (!expanded && matches.size > TOP) {
                TextButton(onClick = { expanded = true; page = 0 }) {
                    Text("Show all ${matches.size} ${if (query.isBlank()) "payees" else "matches"}")
                }
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Per page", style = MaterialTheme.typography.bodySmall)
                    PAGE_SIZES.forEach { n ->
                        FilterChip(pageSize == n, { pageSize = n; page = 0 }, label = { Text("$n") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                    val from = if (matches.isEmpty()) 0 else page * pageSize + 1
                    Text("$from–${minOf(matches.size, (page + 1) * pageSize)} of ${matches.size}", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { page++ }, enabled = page < pages - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { expanded = false; page = 0 }) { Text("Show top $TOP") }
                }
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
