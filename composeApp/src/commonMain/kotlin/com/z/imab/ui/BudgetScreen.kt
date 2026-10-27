package com.z.imab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import com.z.imab.model.BudgetData
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.budget.BudgetMath
import com.z.imab.budget.CardPayment
import com.z.imab.budget.GoalBoard
import com.z.imab.budget.MonthBudget
import com.z.imab.budget.GoalMath
import com.z.imab.budget.GoalNeed
import com.z.imab.budget.GoalStatus
import com.z.imab.budget.CategoryMonth
import com.z.imab.data.BudgetStore
import com.z.imab.model.Category
import com.z.imab.model.CategoryGroup
import com.z.imab.model.Money
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth

@Composable
fun BudgetScreen(store: BudgetStore, nav: NavState, wide: Boolean) {
    val data by store.data.collectAsState()
    var month by remember { mutableStateOf(YearMonth.of(today())) }
    val mb = remember(data, month) { BudgetMath.compute(data, month) }
    val prev = remember(data, month) { BudgetMath.compute(data, month - 1) }
    val prev2 = remember(data, month) { BudgetMath.compute(data, month - 2) }
    fun goalStatus(c: Category): GoalStatus? = c.goal?.let {
        GoalMath.status(it, month, listOf(prev2.category(c.id), prev.category(c.id), mb.category(c.id)))
    }
    var editingGoal by remember { mutableStateOf<Category?>(null) }
    var fundingGoals by remember { mutableStateOf(false) }
    var showingActivity by remember { mutableStateOf<Category?>(null) }
    /** Category whose available amount was clicked, to cover overspending or move money. */
    /** Last change to assigned money, so it can be undone. */
    var lastChange by remember { mutableStateOf<AssignChange?>(null) }
    var assigningMany by remember { mutableStateOf(false) }
    var editingTxn by remember { mutableStateOf<Transaction?>(null) }

    /**
     * A new, unsaved transaction for [categoryId] (or none). The account is the one last used for
     * that category, else the first budget account; it can be changed in the form.
     */
    fun newTransaction(categoryId: String?): Transaction {
        val lastAccount = categoryId?.let { id ->
            data.transactions.values.filter { it.categoryId == id && data.accounts[it.accountId]?.closed == false }
                .maxByOrNull { it.date }?.accountId
        }
        val account = lastAccount
            ?: data.sortedAccounts.firstOrNull { it.inBudget && !it.closed && !it.type.isCredit }?.id
            ?: data.sortedAccounts.firstOrNull { !it.closed }?.id
            ?: ""
        val date = if (month == YearMonth.of(today())) today() else month.firstDay
        return Transaction(store.budget.newId(), account, date, 0, categoryId = categoryId)
    }

    /** Category id and display name being assigned to (regular or credit card payment). */
    var assigning by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** Whether the assign dialog was opened from an overspent amount (pre-fills covering it). */
    var assigningToCover by remember { mutableStateOf(false) }
    /** Whether to open the category dialog on its Move money tab. */
    var assigningMove by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<Transaction?>(null) }
    var renamingCategory by remember { mutableStateOf<Category?>(null) }
    var renamingGroup by remember { mutableStateOf<CategoryGroup?>(null) }
    var addingTo by remember { mutableStateOf<CategoryGroup?>(null) }
    var addingGroup by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { month -= 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
            Text(mb.month.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { month += 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
            if (month != YearMonth.of(today())) TextButton(onClick = { month = YearMonth.of(today()) }) { Text("Today") }
            Spacer(Modifier.weight(1f))
            if (wide) {
                OutlinedButton(onClick = { editingTxn = newTransaction(null) }, modifier = Modifier.padding(end = 12.dp)) {
                    Icon(Icons.Default.Add, null)
                    Text("Add transaction")
                }
                ReadyToAssignCard(mb.readyToAssign) { assigningMany = true }
            } else {
                IconButton(onClick = { editingTxn = newTransaction(null) }) { Icon(Icons.Default.Add, "Add transaction") }
            }
        }
        if (!wide) ReadyToAssignCard(mb.readyToAssign, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { assigningMany = true }

        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (data.uncategorizedCount > 0) {
                AssistChip(
                    onClick = { nav.openAccount(UNCATEGORIZED_VIEW) },
                    label = { Text("${data.uncategorizedCount} transaction(s) need a category") },
                )
            }
            if (mb.overspentCount > 0) {
                AssistChip(onClick = {}, label = { Text("${mb.overspentCount} overspent categor${if (mb.overspentCount == 1) "y" else "ies"}") })
            }
            val needs = remember(data, month) { GoalBoard(data, month).needs() }
            val stillNeeded = needs.sumOf { maxOf(0, it.wanted - it.assigned) }
            if (stillNeeded > 0) {
                val canFund = minOf(stillNeeded, BudgetMath.freeToAssign(data, month, YearMonth.of(today())))
                AssistChip(
                    onClick = { if (canFund > 0) fundingGoals = true },
                    label = {
                        Text(
                            if (canFund > 0) "Fund goals: ${Money.format(stillNeeded)} needed this month"
                            else "Goals need ${Money.format(stillNeeded)} — nothing left to assign"
                        )
                    },
                )
            }
            if (mb.returnedLastMonth > 0) {
                AssistChip(onClick = {}, label = { Text("${Money.format(mb.returnedLastMonth)} returned from last month") })
            }
            if (mb.overspentLastMonth > 0) {
                AssistChip(onClick = {}, label = { Text("${Money.format(mb.overspentLastMonth)} overspent last month") })
            }
            if (wide) {
                AssistChip(onClick = {}, label = { Text("Income this month: ${Money.format(mb.incomeThisMonth)}") })
            }
        }

        lastChange?.let { m ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { store.budget.setAssignedAll(m.month, m.previous); lastChange = null }) { Text("Undo") }
                    TextButton(onClick = { lastChange = null }) { Text("OK") }
                }
            }
        }
        BudgetHeaderRow(wide)
        LazyColumn(Modifier.weight(1f)) {
            if (mb.cards.isNotEmpty()) {
                item(key = "g-cards") { CardGroupRow(mb.cards.map { cardFigures(it, mb.category(it.categoryId)) }, wide) }
                items(mb.cards, key = { "p-${it.accountId}" }) { card ->
                    CardPaymentRow(
                        name = data.accountName(card.accountId),
                        card = card,
                        cm = mb.category(card.categoryId),
                        wide = wide,
                        onAssign = { assigningToCover = false; assigningMove = false; assigning = card.categoryId to data.categoryName(card.categoryId) },
                        onPay = { paying = paymentTemplate(data, card.accountId, card.available, store.budget.newId()) },
                        onOpen = { nav.openAccount(card.accountId) },
                        onAvailable = {
                            assigningToCover = card.coverage < 0
                            assigningMove = false
                            assigning = card.categoryId to data.categoryName(card.categoryId)
                        },
                    )
                }
            }
            for (g in data.sortedGroups) {
                val cats = data.categoriesIn(g.id)
                item(key = "g-${g.id}") {
                    GroupRow(
                        group = g,
                        totals = cats.map { mb.category(it.id) },
                        wide = wide,
                        onAdd = { addingTo = g },
                        onRename = { renamingGroup = g },
                        onDelete = { deleting = "Delete group \"${g.name}\" and its ${cats.size} categories?" to { store.budget.deleteGroup(g.id) } },
                    )
                }
                items(cats, key = { "c-${it.id}" }) { c ->
                    CategoryRow(
                        category = c,
                        cm = mb.category(c.id),
                        wide = wide,
                        onAssign = { assigningToCover = false; assigningMove = false; assigning = c.id to c.name },
                        onRename = { renamingCategory = c },
                        onDelete = { deleting = "Delete \"${c.name}\"? Its transactions become uncategorized." to { store.budget.deleteCategory(c.id) } },
                        goal = goalStatus(c),
                        onGoal = { editingGoal = c },
                        onActivity = { showingActivity = c },
                        onAvailable = { assigningToCover = mb.category(c.id).available < 0; assigningMove = false; assigning = c.id to c.name },
                        onMoveMoney = { assigningToCover = false; assigningMove = true; assigning = c.id to c.name },
                        onAddTransaction = { editingTxn = newTransaction(c.id) },
                        onNewCategory = { addingTo = g },
                    )
                }
            }
            item {
                TextButton(onClick = { addingGroup = true }, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Default.Add, null)
                    Text("Add category group")
                }
            }
        }
    }

    assigning?.let { (id, name) ->
        AssignDialog(
            data = data,
            mb = mb,
            categoryId = id,
            name = name,
            month = month,
            lastMonth = prev.category(id),
            goal = data.categories[id]?.let(::goalStatus),
            free = BudgetMath.freeToAssign(data, month, YearMonth.of(today())),
            coverOverspending = assigningToCover,
            // Regular categories can be renamed right here; card payment categories are named after the card.
            renameable = data.categories.containsKey(id),
            startOnMove = assigningMove,
            onDismiss = { assigning = null },
            onMove = { from, to, amount ->
                val previous = listOfNotNull(from, to).associateWith { mb.category(it).assigned }
                store.budget.moveMoney(month, from, to, amount)
                fun n(x: String?) = x?.let { data.categoryName(it) } ?: "Ready to Assign"
                lastChange = AssignChange(month, "Moved ${Money.format(amount)} from ${n(from)} to ${n(to)} (${month.label})", previous)
                assigning = null
            },
            onSave = { amount, newName, other ->
                data.categories[id]?.let { c -> if (newName.isNotBlank() && newName != c.name) store.budget.saveCategory(c.copy(name = newName)) }
                val before = mb.category(id).assigned
                val delta = amount - before
                if (delta != 0L) {
                    val previous = mutableMapOf(id to before)
                    val otherName = other?.let { data.categoryName(it) } ?: "Ready to Assign"
                    if (other == null) {
                        store.budget.setAssigned(month, id, amount)
                    } else {
                        previous[other] = mb.category(other).assigned
                        if (delta > 0) store.budget.moveMoney(month, other, id, delta) else store.budget.moveMoney(month, id, other, -delta)
                    }
                    lastChange = AssignChange(
                        month,
                        if (delta > 0) "Assigned ${Money.format(delta)} more to $name from $otherName (${month.label})"
                        else "Moved ${Money.format(-delta)} from $name to $otherName (${month.label})",
                        previous,
                    )
                }
                assigning = null
            },
        )
    }
    showingActivity?.let { c ->
        ActivityDialog(
            data, c, month,
            onEdit = { editingTxn = it },
            onAdd = { editingTxn = newTransaction(c.id) },
            onDismiss = { showingActivity = null },
        )
    }
    editingTxn?.let { t ->
        TransactionDialog(
            data = data,
            initial = t,
            defaultAccountId = t.accountId,
            newId = store.budget::newId,
            onSave = { store.budget.saveTransaction(it); editingTxn = null },
            // A new transaction started from the budget has nothing to delete yet.
            onDelete = if (t.id in data.transactions) {
                { store.budget.deleteTransaction(t.id); editingTxn = null }
            } else null,
            onDismiss = { editingTxn = null },
        )
    }
    if (assigningMany) {
        AssignMoneyDialog(
            data = data,
            board = GoalBoard(data, month),
            free = BudgetMath.freeToAssign(data, month, YearMonth.of(today())),
            onAssign = { newAmounts ->
                val previous = newAmounts.keys.associateWith { mb.category(it).assigned }
                store.budget.setAssignedAll(month, newAmounts)
                val total = newAmounts.entries.sumOf { (id, v) -> v - (previous[id] ?: 0) }
                lastChange = AssignChange(month, "Assigned ${Money.format(total)} to ${newAmounts.size} categor${if (newAmounts.size == 1) "y" else "ies"} (${month.label})", previous)
                assigningMany = false
            },
            onDismiss = { assigningMany = false },
        )
    }
    if (fundingGoals) {
        FundGoalsDialog(data, GoalBoard(data, month), store.budget) { fundingGoals = false }
    }
    editingGoal?.let { c ->
        GoalDialog(
            category = c,
            month = month,
            history = listOf(prev2.category(c.id), prev.category(c.id), mb.category(c.id)),
            onSave = { goal -> store.budget.saveCategory(c.copy(goal = goal)); editingGoal = null },
            onDismiss = { editingGoal = null },
        )
    }
    paying?.let { t ->
        TransactionDialog(
            data = data,
            initial = t,
            defaultAccountId = t.accountId,
            newId = store.budget::newId,
            onSave = { store.budget.saveTransaction(it); paying = null },
            onDelete = null,
            onDismiss = { paying = null },
        )
    }
    addingTo?.let { g ->
        TextInputDialog("New category in ${g.name}", onDismiss = { addingTo = null }) { name ->
            store.budget.addCategory(g.id, name)
            addingTo = null
        }
    }
    if (addingGroup) {
        TextInputDialog("New category group", onDismiss = { addingGroup = false }) { name ->
            val order = (data.sortedGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1
            store.budget.saveGroup(CategoryGroup(store.budget.newId(), name, order))
            addingGroup = false
        }
    }
    renamingCategory?.let { c ->
        EditCategoryDialog(
            category = c,
            groups = data.sortedGroups,
            onSave = { store.budget.saveCategory(it); renamingCategory = null },
            onGoal = { renamingCategory = null; editingGoal = c },
            onDelete = {
                renamingCategory = null
                deleting = "Delete \"${c.name}\"? Its transactions become uncategorized." to { store.budget.deleteCategory(c.id) }
            },
            onDismiss = { renamingCategory = null },
        )
    }
    renamingGroup?.let { g ->
        TextInputDialog("Rename group", initial = g.name, onDismiss = { renamingGroup = null }) {
            store.budget.saveGroup(g.copy(name = it)); renamingGroup = null
        }
    }
    deleting?.let { (msg, action) ->
        ConfirmDialog("Delete", msg, onDismiss = { deleting = null }, onConfirm = action)
    }
}

@Composable
private fun ReadyToAssignCard(cents: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (cents >= 0) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    }
    Card(onClick = onClick, colors = colors, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            Text(Money.format(cents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (cents >= 0) "Ready to Assign · tap to assign" else "Assigned more than you have",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun BudgetHeaderRow(wide: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 48.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelMedium
        Text("CATEGORY", style = style, modifier = Modifier.weight(1f))
        Text("ASSIGNED", style = style, modifier = Modifier.width(if (wide) 120.dp else 90.dp), textAlign = TextAlign.End)
        if (wide) Text("ACTIVITY", style = style, modifier = Modifier.width(120.dp), textAlign = TextAlign.End)
        Text("AVAILABLE", style = style, modifier = Modifier.width(if (wide) 130.dp else 105.dp), textAlign = TextAlign.End)
    }
    HorizontalDivider()
}

@Composable
private fun GroupRow(
    group: CategoryGroup,
    totals: List<CategoryMonth>,
    wide: Boolean,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add category") }
        Text(Money.format(totals.sumOf { it.assigned }), Modifier.width(if (wide) 120.dp else 90.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
        if (wide) Text(Money.format(totals.sumOf { it.activity }), Modifier.width(120.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
        Text(Money.format(totals.sumOf { it.available }), Modifier.width(if (wide) 130.dp else 105.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Group options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Add category") }, onClick = { menu = false; onAdd() })
                DropdownMenuItem(text = { Text("Rename group") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete group") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    cm: CategoryMonth,
    wide: Boolean,
    onAssign: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    goal: GoalStatus?,
    onGoal: () -> Unit,
    onActivity: () -> Unit,
    onAvailable: () -> Unit,
    onAddTransaction: () -> Unit,
    onMoveMoney: () -> Unit,
    onNewCategory: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onAssign).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the name side edits the category (the whole height of the row); tapping the numbers assigns money.
        Column(
            Modifier.weight(1f).fillMaxHeight().clickable(onClick = onRename).padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Phones don't have room for the Activity column, so show what was spent under the name.
            if (!wide && cm.activity != 0L) {
                Text(
                    if (cm.activity < 0) "Spent ${Money.format(-cm.activity)}" else "Received ${Money.format(cm.activity)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onActivity),
                )
            }
            if (goal != null) GoalProgress(goal, wide)
        }
        Text(
            Money.format(cm.assigned),
            Modifier.width(if (wide) 120.dp else 90.dp),
            textAlign = TextAlign.End,
            color = if (cm.assigned == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (wide) Text(Money.format(cm.activity), Modifier.width(120.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Clicking the available amount covers overspending or moves money.
        Box(Modifier.width(if (wide) 130.dp else 105.dp), contentAlignment = Alignment.CenterEnd) {
            AvailablePill(
                cm.available,
                Modifier.clickable(onClick = onAvailable),
                creditOverspending = cm.creditOverspent > 0 && cm.cashOverspent == 0L,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Category options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Assign money") }, onClick = { menu = false; onAssign() })
                DropdownMenuItem(text = { Text("Move money…") }, onClick = { menu = false; onMoveMoney() })
                DropdownMenuItem(text = { Text("Add transaction") }, onClick = { menu = false; onAddTransaction() })
                DropdownMenuItem(text = { Text("Show activity") }, onClick = { menu = false; onActivity() })
                DropdownMenuItem(text = { Text(if (goal == null) "Set goal…" else "Edit goal…") }, onClick = { menu = false; onGoal() })
                DropdownMenuItem(text = { Text("Edit category…") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("New category in this group…") }, onClick = { menu = false; onNewCategory() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun EditCategoryDialog(
    category: Category,
    groups: List<CategoryGroup>,
    onSave: (Category) -> Unit,
    onGoal: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(category.name) }
    var groupId by remember { mutableStateOf(category.groupId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Group", groups.map { PickerOption(it.id, it.name) }, groupId, { groupId = it }, Modifier.fillMaxWidth())
                OutlinedButton(onClick = onGoal) { Text(if (category.goal == null) "Set goal…" else "Edit goal…") }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(category.copy(name = name.trim(), groupId = groupId))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Picker value meaning "nothing chosen yet" in the Move money tab. */
private const val CHOOSE = "__choose__"

/** A change to assigned money in [month]; [previous] holds the amounts to put back to undo it. */
internal data class AssignChange(val month: YearMonth, val description: String, val previous: Map<String, Long>)

/**
 * Set how much is assigned to a category. Extra money comes from Ready to Assign unless another
 * category is chosen; lowering it returns the difference to Ready to Assign or a chosen category.
 */
@Composable
internal fun AssignDialog(
    data: BudgetData,
    mb: MonthBudget,
    categoryId: String,
    name: String,
    month: YearMonth,
    lastMonth: CategoryMonth,
    goal: GoalStatus? = null,
    free: Long,
    coverOverspending: Boolean,
    renameable: Boolean = false,
    startOnMove: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (amount: Long, name: String, other: String?) -> Unit,
    onMove: (from: String?, to: String?, amount: Long) -> Unit,
) {
    val current = mb.category(categoryId)
    val card = mb.cards.firstOrNull { it.categoryId == categoryId }
    val start = when {
        // A card short of its balance: assign enough to set aside everything owed.
        coverOverspending && card != null && card.coverage < 0 -> current.assigned - card.coverage
        coverOverspending && current.available < 0 -> current.assigned - current.available
        else -> current.assigned
    }
    var text by remember { mutableStateOf(if (start == 0L) "" else Money.toInput(start)) }
    var newName by remember { mutableStateOf(name) }
    /** Where extra money comes from, or where a reduction goes. null = Ready to Assign. */
    var other by remember { mutableStateOf<String?>(null) }
    // Arithmetic works here, and a leading operator applies to what's assigned now ("+5" adds $5).
    val parsed = if (text.isBlank()) 0L else Money.eval(text, current.assigned)
    val delta = (parsed ?: current.assigned) - current.assigned
    fun has(id: String?): Long = if (id == null) free else mb.category(id).available
    fun label(id: String?): String = if (id == null) "Ready to Assign" else data.categoryName(id)
    val ids = (data.sortedGroups.flatMap { data.categoriesIn(it.id) }.map { it.id } + mb.cards.map { it.categoryId }).filter { it != categoryId }
    val rta = PickerOption<String?>(null, "Ready to Assign — ${Money.format(free)}")
    val sources = listOf(rta) + ids.filter { has(it) > 0 || it == other }.map { PickerOption<String?>(it, "${data.categoryName(it)} — ${Money.format(has(it))}") }
    val destinations = listOf(rta) + ids.map { PickerOption<String?>(it, "${data.categoryName(it)} — ${Money.format(has(it))}") }
    // You can always lower an amount; raising it can't take more than the source has.
    val tooMuch = delta > 0 && delta > has(other)
    val current0 = YearMonth.of(today())

    // Move money tab: this category is the source when it has money, otherwise the destination.
    var tab by remember { mutableStateOf(if (startOnMove) 1 else 0) }
    val withMoney = current.available > 0
    var moveFrom by remember { mutableStateOf<String?>(if (withMoney) categoryId else CHOOSE) }
    var moveTo by remember { mutableStateOf<String?>(if (withMoney) CHOOSE else categoryId) }
    var moveText by remember { mutableStateOf(if (current.available < 0) Money.toInput(-current.available) else "") }
    val moveAmount = Money.eval(moveText)
    val everything = listOf(categoryId) + ids
    val moveOptions = listOf(PickerOption<String?>(CHOOSE, "Choose…"), rta) +
        everything.map { PickerOption<String?>(it, "${data.categoryName(it)} — ${Money.format(has(it))}") }
    val moveChosen = moveFrom != CHOOSE && moveTo != CHOOSE
    val moveValid = moveChosen && moveFrom != moveTo && moveAmount != null && moveAmount > 0 && moveAmount <= has(moveFrom)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name · ${month.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Assign") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Move money") })
                }
                if (month != current0) {
                    Text(
                        "This changes the budget for ${month.label}, not ${current0.label}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (tab == 1) {
                    Text(
                        "Move money between categories (or Ready to Assign). ${data.categoryName(categoryId)} has ${Money.format(current.available)} available.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Picker("From", moveOptions, moveFrom, { moveFrom = it }, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { val f = moveFrom; moveFrom = moveTo; moveTo = f }) { Text("⇅ Swap") }
                    }
                    Picker("To", moveOptions, moveTo, { moveTo = it }, Modifier.fillMaxWidth())
                    MoneyField("Amount", moveText, { moveText = it }, Modifier.fillMaxWidth(), isError = moveText.isNotBlank() && moveChosen && !moveValid, result = moveAmount)
                    when {
                        !moveChosen -> Text("Choose where the money comes from and where it goes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        moveFrom == moveTo -> Text("Choose two different places.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        moveAmount != null && moveAmount > has(moveFrom) -> Text(
                            "${label(moveFrom)} only has ${Money.format(maxOf(0, has(moveFrom)))}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        moveAmount != null && moveAmount > 0 -> Text(
                            "Moves ${Money.format(moveAmount)} from ${label(moveFrom)} to ${label(moveTo)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                if (renameable) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Category name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Text("Activity ${Money.format(current.activity)} · Available ${Money.format(current.available)}", style = MaterialTheme.typography.bodySmall)
                if (card != null) {
                    // Assigned is only what you assign here; card spending arrives as activity.
                    val shown = cardFigures(card, current)
                    val carried = current.available - current.assigned - current.activity
                    Text(
                        "Assigned ${Money.format(shown.assigned)} = ${Money.format(current.assigned)} assigned here" +
                            (if (card.fromSpending != 0L) " ${if (card.fromSpending > 0) "+" else "−"} ${Money.format(kotlin.math.abs(card.fromSpending))} from the categories of card spending" else "") + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        (if (carried != 0L) "${Money.format(carried)} from last month + " else "") +
                            "${Money.format(shown.assigned)}" +
                            (if (card.paid != 0L) " ${if (card.paid > 0) "−" else "+"} ${Money.format(kotlin.math.abs(card.paid))} paid" else "") +
                            " = ${Money.format(current.available)} set aside; you owe ${Money.format(card.owed)}, so you're " +
                            (if (card.coverage < 0) "${Money.format(-card.coverage)} short." else "covered with ${Money.format(card.coverage)} to spare."),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "You owe ${Money.format(card.owed)} on this card" +
                            when {
                                card.underfunded == 0L -> ", and it's all set aside."
                                else -> ". Not set aside yet: " + listOfNotNull(
                                    card.startingDebtLeft.takeIf { it > 0 }?.let { "${Money.format(it)} of the debt it started with" },
                                    card.overspentDebt.takeIf { it > 0 }?.let { "${Money.format(it)} spent beyond what categories had" },
                                ).joinToString(" and ") + "."
                            } +
                            " Card spending in a category moves that money here on its own; assign here only to pay down older debt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (goal != null) {
                    Text(goalHeadline(goal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (current.available < 0) {
                    Text(
                        "${Money.format(-current.available)} overspent" +
                            if (current.creditOverspent > 0) " (on a credit card — cover it this month so the card can be paid, or it stays as card debt)." else ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current.creditOverspent > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }
                MoneyField(if (card != null) "Assigned here" else "Assigned", text, { text = it }, Modifier.fillMaxWidth(), isError = parsed == null || tooMuch, result = parsed)
                Text(
                    "You can type a calculation, like ${Money.toInput(current.assigned)} + 5 — or just +5 to add to what's assigned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    delta > 0 -> {
                        Picker("Take the money from", sources, other, { other = it }, Modifier.fillMaxWidth())
                        Text(
                            if (tooMuch) "${label(other)} only has ${Money.format(maxOf(0, has(other)))}."
                            else "Takes ${Money.format(delta)} from ${label(other)}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tooMuch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    delta < 0 -> {
                        Picker("Put the difference in", destinations, other, { other = it }, Modifier.fillMaxWidth())
                        Text("Moves ${Money.format(-delta)} to ${label(other)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text(
                        "Up to ${Money.format(current.assigned + free)} can be assigned from Ready to Assign, or choose another category once you raise it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.available < 0) {
                        AssistChip(onClick = { text = Money.toInput(current.assigned - current.available) }, label = { Text("Cover overspending") })
                    }
                    if (goal != null && !goal.reached && goal.assignThisMonth > 0) {
                        AssistChip(
                            onClick = { text = Money.toInput(goal.assignThisMonth) },
                            label = { Text("Goal: ${Money.format(goal.assignThisMonth)} this month") },
                        )
                    }
                    if (card != null && card.coverage < 0) {
                        AssistChip(
                            onClick = { text = Money.toInput(current.assigned - card.coverage) },
                            label = { Text("Fund whole balance (+${Money.format(-card.coverage)})") },
                        )
                    }
                    AssistChip(onClick = { text = Money.toInput(lastMonth.assigned) }, label = { Text("Assigned last month (${Money.format(lastMonth.assigned)})") })
                    AssistChip(onClick = { text = Money.toInput(-lastMonth.activity) }, label = { Text("Spent last month (${Money.format(-lastMonth.activity)})") })
                }
                }
            }
        },
        confirmButton = {
            if (tab == 1) {
                TextButton(enabled = moveValid, onClick = { moveAmount?.let { onMove(moveFrom, moveTo, it) } }) { Text("Move") }
            } else {
                TextButton(
                    onClick = { parsed?.let { onSave(it, newName.trim(), other.takeIf { delta != 0L }) } },
                    enabled = parsed != null && !tooMuch && newName.isNotBlank(),
                ) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CardGroupRow(totals: List<CategoryMonth>, wide: Boolean) {
    // Card rows are shown as cardFigures: assigned + money in from spending, payments, and coverage.
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (wide) "Credit Card Payments" else "Card Payments", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Money.format(totals.sumOf { it.assigned }), Modifier.width(if (wide) 120.dp else 90.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
        if (wide) Text(Money.format(totals.sumOf { it.activity }), Modifier.width(120.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
        Text(Money.format(totals.sumOf { it.available }), Modifier.width(if (wide) 130.dp else 105.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * How a card's row reads in the budget: Assigned is what you assigned plus the money card spending
 * moved in from its categories; Activity is payments; Available is what's set aside minus what's
 * owed (negative = short of paying the card off). The payment category's own math is unchanged.
 */
internal fun cardFigures(card: CardPayment, cm: CategoryMonth) = CategoryMonth(
    assigned = cm.assigned + card.fromSpending,
    activity = -card.paid,
    available = card.coverage,
)

/** "+$120.00 from categories · $450.00 paid" for this month, or null when there's neither. */
private fun cardActivityLine(card: CardPayment): String? = listOfNotNull(
    card.fromSpending.takeIf { it != 0L }?.let { "${if (it > 0) "+" else "−"}${Money.format(kotlin.math.abs(it))} from categories" },
    card.paid.takeIf { it != 0L }?.let { "${Money.format(kotlin.math.abs(it))} ${if (it > 0) "paid" else "moved out"}" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun CardPaymentRow(
    name: String,
    card: CardPayment,
    cm: CategoryMonth,
    wide: Boolean,
    onAssign: () -> Unit,
    onPay: () -> Unit,
    onOpen: () -> Unit,
    onAvailable: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAssign).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                card.owed <= 0 -> "Paid off"
                card.underfunded == 0L -> "Owe ${Money.format(card.owed)} · ${Money.format(maxOf(0, cm.available))} set aside · covered"
                else -> listOfNotNull(
                    "Owe ${Money.format(card.owed)}",
                    "${Money.format(maxOf(0, cm.available))} set aside".takeIf { wide },
                    if (card.overspentDebt == 0L) "spending covered" else "${Money.format(card.overspentDebt)} overspent",
                    card.startingDebtLeft.takeIf { it > 0 }?.let { "${Money.format(it)} ${if (wide) "of starting debt to set aside" else "starting debt"}" },
                ).joinToString(" · ")
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (card.underfunded > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (wide) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            cardActivityLine(card)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val shown = cardFigures(card, cm)
        Text(
            Money.format(shown.assigned),
            Modifier.width(if (wide) 120.dp else 90.dp),
            textAlign = TextAlign.End,
            color = if (shown.assigned == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (wide) Text(Money.format(shown.activity), Modifier.width(120.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(if (wide) 130.dp else 105.dp), contentAlignment = Alignment.CenterEnd) {
            // Short of the balance shows amber (card debt); red only when payments went past what was set aside.
            AvailablePill(shown.available, Modifier.clickable(onClick = onAvailable), creditOverspending = cm.available >= 0)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Card options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Assign money") }, onClick = { menu = false; onAssign() })
                DropdownMenuItem(text = { Text("Record payment") }, onClick = { menu = false; onPay() })
                DropdownMenuItem(text = { Text("Open account") }, onClick = { menu = false; onOpen() })
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** The transactions behind a category's activity for one month. Tap one to edit it. */
@Composable
private fun ActivityDialog(
    data: BudgetData,
    category: Category,
    month: YearMonth,
    onEdit: (Transaction) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val txns = data.transactions.values
        .filter { it.categoryId == category.id && YearMonth.of(it.date) == month && data.isOnBudget(it.accountId) }
        .sortedWith(compareByDescending<Transaction> { it.date }.thenBy { it.id })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${category.name} · ${month.label}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (txns.isEmpty()) Text("No transactions in this category this month.")
                txns.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(t) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.date.short(), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.weight(1f)) {
                            Text(data.payeeLabel(t), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                data.accountName(t.accountId) + if (t.memo.isNotBlank()) " · ${t.memo}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(Money.format(t.amount), color = amountColor(t.amount))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                if (txns.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Total", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(Money.format(txns.sumOf { it.amount }), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onAdd) { Text("Add transaction") } },
    )
}

