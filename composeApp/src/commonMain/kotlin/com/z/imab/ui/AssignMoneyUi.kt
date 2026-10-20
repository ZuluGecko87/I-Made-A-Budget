package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.budget.GoalBoard
import com.z.imab.model.BudgetData
import com.z.imab.model.Money

/** One category in the Assign money list. */
private class AssignRow(val id: String, val name: String, val group: String, val assigned: Long, val available: Long, val need: Long, val needLabel: String?)

/**
 * Opened from Ready to Assign: every category with an amount to add, goals that still need money
 * first (with a one-tap amount), and a running total that can't go past what's available.
 */
@Composable
fun AssignMoneyDialog(
    data: BudgetData,
    board: GoalBoard,
    free: Long,
    onAssign: (newAssigned: Map<String, Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val mb = board.current
    val rows = remember(data, board) {
        val goalNeeds = board.needs().associateBy { it.categoryId }
        val cats = data.sortedGroups.flatMap { g ->
            data.categoriesIn(g.id).map { c ->
                val cm = mb.category(c.id)
                // Overspending comes first; a goal may want more on top.
                val cover = maxOf(0, -cm.available)
                val goal = goalNeeds[c.id]?.let { maxOf(0, it.wanted - it.assigned) } ?: 0
                val need = maxOf(cover, goal)
                AssignRow(c.id, c.name, g.name, cm.assigned, cm.available, need, if (need == 0L) null else if (cover >= goal) "to cover overspending" else "for goal")
            }
        }
        // Covering credit card overspending also funds the card, so don't ask for that part twice.
        val creditOverspent = cats.sumOf { mb.category(it.id).creditOverspent }
        val cards = mb.cards.map { c ->
            val cm = mb.category(c.categoryId)
            val need = maxOf(0, c.underfunded - creditOverspent)
            AssignRow(c.categoryId, data.categoryName(c.categoryId), "Credit Card Payments", cm.assigned, cm.available, need, if (need > 0) "to cover the card" else null)
        }
        cards + cats
    }
    // Amount to add per category, as typed.
    val adds = remember { mutableStateMapOf<String, String>() }
    fun added(id: String): Long = adds[id]?.takeIf { it.isNotBlank() }?.let(Money::parse) ?: 0
    val invalid = adds.values.any { it.isNotBlank() && Money.parse(it) == null }
    val total = rows.sumOf { added(it.id) }
    val left = free - total
    val needing = rows.filter { it.need > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign money · ${board.month.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            Money.format(left),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(if (left < 0) "More than you have to assign" else "left to assign", style = MaterialTheme.typography.labelSmall)
                    }
                    if (needing.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            // Overspending first, then monthly bills, savings goals and cards, within what's available.
                            adds.clear()
                            var remaining = free
                            val order = needing.sortedBy {
                                when {
                                    mb.category(it.id).available < 0 -> 0
                                    board.needs().any { n -> n.categoryId == it.id && n.monthly } -> 1
                                    it.group != "Credit Card Payments" -> 2
                                    else -> 3
                                }
                            }
                            for (r in order) {
                                val give = minOf(r.need, remaining)
                                if (give <= 0) break
                                adds[r.id] = Money.toInput(give)
                                remaining -= give
                            }
                        }) { Text("Fill all needs") }
                    }
                }
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    if (needing.isNotEmpty()) {
                        item { SectionLabel("Needs money this month") }
                        items(needing, key = { "n-${it.id}" }) { r -> AssignRowView(r, adds, showGroup = true) }
                    }
                    var lastGroup: String? = null
                    rows.forEach { r ->
                        if (r.group != lastGroup) {
                            lastGroup = r.group
                            item(key = "g-${r.group}") { SectionLabel(r.group) }
                        }
                        item(key = "r-${r.id}") { AssignRowView(r, adds, showGroup = false) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = total != 0L && left >= 0 && !invalid,
                onClick = {
                    onAssign(rows.filter { added(it.id) != 0L }.associate { it.id to it.assigned + added(it.id) })
                },
            ) { Text(if (total > 0) "Assign ${Money.format(total)}" else "Assign") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun AssignRowView(r: AssignRow, adds: MutableMap<String, String>, showGroup: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (showGroup) "${r.group} · " else "") + "Available ${Money.format(r.available)}" +
                        if (r.need > 0) " · needs ${Money.format(r.need)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.need > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MoneyField(
                "Add",
                adds[r.id].orEmpty(),
                { adds[r.id] = it },
                Modifier.width(130.dp),
                isError = adds[r.id]?.let { it.isNotBlank() && Money.parse(it) == null } == true,
            )
        }
        if (r.need > 0) {
            AssistChip(
                onClick = { adds[r.id] = Money.toInput(r.need) },
                label = { Text("+${Money.format(r.need)} ${r.needLabel ?: ""}".trim()) },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
