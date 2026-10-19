package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.z.imab.budget.BudgetMath
import com.z.imab.budget.GoalBoard
import com.z.imab.budget.GoalStatus
import com.z.imab.data.BudgetStore
import com.z.imab.model.BudgetData
import com.z.imab.model.Category
import com.z.imab.model.CombinedGoal
import com.z.imab.model.GoalType
import com.z.imab.model.Money
import com.z.imab.model.YearMonth
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

/** All goals in one place: per-category goals and goals spanning several categories. */
@Composable
fun GoalsScreen(store: BudgetStore) {
    val data by store.data.collectAsState()
    var month by remember { mutableStateOf(YearMonth.of(today())) }
    val board = remember(data, month) { GoalBoard(data, month) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var pickingCategory by remember { mutableStateOf(false) }
    var editingCombined by remember { mutableStateOf<CombinedGoal?>(null) }
    var addingCombined by remember { mutableStateOf(false) }
    var funding by remember { mutableStateOf(false) }

    val withGoals = data.sortedGroups.flatMap { data.categoriesIn(it.id) }.filter { it.goal != null }
    val monthly = withGoals.filter { it.goal!!.type == GoalType.MONTHLY_SPENDING }
    val savings = withGoals.filter { it.goal!!.type == GoalType.SAVINGS }
    val combined = data.combinedGoals.values.sortedBy { it.name.lowercase() }
    val stillNeeded = board.needs().sumOf { maxOf(0, it.wanted - it.assigned) }
    val free = BudgetMath.freeToAssign(data, month, YearMonth.of(today()))

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Goals", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { month -= 1 }) { Text("‹") }
                    Text(month.label, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { month += 1 }) { Text("›") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickingCategory = true }) { Icon(Icons.Default.Add, null); Text("Goal for a category") }
                    OutlinedButton(onClick = { addingCombined = true }) { Icon(Icons.Default.Add, null); Text("Goal across categories") }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (stillNeeded > 0) "Category goals need ${Money.format(stillNeeded)} more in ${month.label}."
                            else "Category goals are fully funded for ${month.label}.",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Ready to Assign: ${Money.format(free)}", style = MaterialTheme.typography.bodySmall)
                        if (stillNeeded > 0 && free > 0) {
                            TextButton(onClick = { funding = true }) { Text("Fund goals…") }
                        }
                    }
                }
                if (withGoals.isEmpty() && combined.isEmpty()) {
                    Text(
                        "No goals yet. Give a category a savings target (an emergency fund, a vacation) or a monthly " +
                            "amount to spend (rent, groceries), or make a goal that adds up several categories.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        goalSection("Monthly spending", "Money to have each month, like rent or groceries.", monthly) { c ->
            GoalRow(c.name, board.status(c)!!, board.current.category(c.id)) { editingCategory = c }
        }
        goalSection("Savings", "Saving up toward a total; money rolls over until you use it.", savings) { c ->
            GoalRow(c.name, board.status(c)!!, board.current.category(c.id)) { editingCategory = c }
        }
        goalSection("Across categories", "One target for the money in several categories together.", combined) { g ->
            val s = board.status(g)
            GoalRow(
                g.name, s, com.z.imab.budget.CategoryMonth(available = s.saved),
                subtitle = g.categoryIds.joinToString(", ") { data.categoryName(it) }.ifEmpty { "No categories" },
            ) { editingCombined = g }
        }
    }

    if (pickingCategory) {
        PickCategoryDialog(data, onPick = { pickingCategory = false; editingCategory = it }, onDismiss = { pickingCategory = false })
    }
    editingCategory?.let { c ->
        GoalDialog(
            category = c,
            month = month,
            history = board.history(c.id),
            onSave = { goal -> store.budget.saveCategory(c.copy(goal = goal)); editingCategory = null },
            onDismiss = { editingCategory = null },
        )
    }
    if (addingCombined || editingCombined != null) {
        CombinedGoalDialog(
            data = data,
            board = board,
            initial = editingCombined,
            newId = store.budget::newId,
            onSave = { store.budget.saveCombinedGoal(it); addingCombined = false; editingCombined = null },
            onDelete = editingCombined?.let { g -> { store.budget.deleteCombinedGoal(g.id); editingCombined = null } },
            onDismiss = { addingCombined = false; editingCombined = null },
        )
    }
    if (funding) FundGoalsDialog(data, board, store.budget) { funding = false }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.goalSection(
    title: String,
    description: String,
    list: List<T>,
    row: @Composable (T) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h-$title") {
        Column {
            SectionHeader(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    items(list) { row(it) }
}

@Composable
private fun GoalRow(name: String, status: GoalStatus, month: com.z.imab.budget.CategoryMonth, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(name, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            GoalProgress(status, wide = false)
        }
        AvailablePill(month.available, creditOverspending = month.creditOverspent > 0 && month.cashOverspent == 0L)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** Choose a category to give a goal to (categories that already have one are marked). */
@Composable
private fun PickCategoryDialog(data: BudgetData, onPick: (Category) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which category?") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                for (g in data.sortedGroups) {
                    val cats = data.categoriesIn(g.id)
                    if (cats.isEmpty()) continue
                    Text(g.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    cats.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 8.dp)) {
                            Text(c.name, Modifier.weight(1f))
                            if (c.goal != null) Text("has a goal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CombinedGoalDialog(
    data: BudgetData,
    board: GoalBoard,
    initial: CombinedGoal?,
    newId: () -> String,
    onSave: (CombinedGoal) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var selected by remember { mutableStateOf(initial?.categoryIds?.toSet() ?: emptySet()) }
    var targetText by remember { mutableStateOf(initial?.target?.let(Money::toInput).orEmpty()) }
    var hasDate by remember { mutableStateOf(initial?.targetDate != null) }
    var date by remember { mutableStateOf(initial?.targetDate ?: today().plus(1, DateTimeUnit.YEAR)) }
    var monthlyText by remember { mutableStateOf(initial?.monthly?.let(Money::toInput).orEmpty()) }
    val target = Money.parse(targetText)?.takeIf { it > 0 }
    val monthly = if (monthlyText.isBlank()) null else Money.parse(monthlyText)?.takeIf { it > 0 }
    val ordered = data.sortedGroups.flatMap { data.categoriesIn(it.id) }.map { it.id }.filter { it in selected }
    val draft = if (target != null && name.isNotBlank() && selected.isNotEmpty()) {
        CombinedGoal(initial?.id ?: "", name.trim(), ordered, target, date.takeIf { hasDate }, monthly)
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Goal across categories" else "Edit goal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Goal name, e.g. House") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MoneyField("Target amount", targetText, { targetText = it }, Modifier.fillMaxWidth(), isError = targetText.isNotBlank() && target == null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(hasDate, { hasDate = it })
                    Text("Reach it by a date")
                }
                if (hasDate) DateField("Target date", date, { date = it }, Modifier.fillMaxWidth())
                MoneyField("Planned monthly amount (optional)", monthlyText, { monthlyText = it }, Modifier.fillMaxWidth(), isError = monthlyText.isNotBlank() && monthly == null)
                Text("Categories it adds up", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    for (g in data.sortedGroups) {
                        val cats = data.categoriesIn(g.id)
                        if (cats.isEmpty()) continue
                        Text(g.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        cats.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(c.id in selected, { on -> selected = if (on) selected + c.id else selected - c.id })
                                Text(c.name, Modifier.weight(1f))
                                Text(Money.format(board.current.category(c.id).available), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                draft?.let { d ->
                    val s = board.status(d)
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${Money.format(s.saved)} of ${Money.format(d.target)} saved in ${d.categoryIds.size} categories",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(goalHeadline(s), style = MaterialTheme.typography.bodySmall)
                            if (s.monthsLeft != null && !s.reached) {
                                Text(
                                    "Put ${Money.format(s.assignThisMonth)} a month into these categories to reach it by ${YearMonth.of(d.targetDate!!).label}.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = draft != null, onClick = { draft?.let { onSave(it.copy(id = initial?.id ?: newId())) } }) { Text("Save goal") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
