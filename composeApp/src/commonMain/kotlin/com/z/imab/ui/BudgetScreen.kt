package com.z.imab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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

    /** Category id and display name being assigned to (regular or credit card payment). */
    var assigning by remember { mutableStateOf<Pair<String, String>?>(null) }
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
            if (wide) ReadyToAssignCard(mb.readyToAssign)
        }
        if (!wide) ReadyToAssignCard(mb.readyToAssign, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))

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
            if (mb.overspentLastMonth > 0) {
                AssistChip(onClick = {}, label = { Text("${Money.format(mb.overspentLastMonth)} overspent last month") })
            }
            if (wide) {
                AssistChip(onClick = {}, label = { Text("Income this month: ${Money.format(mb.incomeThisMonth)}") })
            }
        }

        BudgetHeaderRow(wide)
        LazyColumn(Modifier.weight(1f)) {
            if (mb.cards.isNotEmpty()) {
                item(key = "g-cards") { CardGroupRow(mb.cards.map { mb.category(it.categoryId) }, wide) }
                items(mb.cards, key = { "p-${it.accountId}" }) { card ->
                    CardPaymentRow(
                        name = data.accountName(card.accountId),
                        card = card,
                        cm = mb.category(card.categoryId),
                        wide = wide,
                        onAssign = { assigning = card.categoryId to data.categoryName(card.categoryId) },
                        onPay = { paying = paymentTemplate(data, card.accountId, card.available, store.budget.newId()) },
                        onOpen = { nav.openAccount(card.accountId) },
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
                        onAssign = { assigning = c.id to c.name },
                        onRename = { renamingCategory = c },
                        onDelete = { deleting = "Delete \"${c.name}\"? Its transactions become uncategorized." to { store.budget.deleteCategory(c.id) } },
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
            name = name,
            month = month,
            current = mb.category(id),
            lastMonth = prev.category(id),
            card = mb.cards.firstOrNull { it.categoryId == id },
            onDismiss = { assigning = null },
            onSave = { store.budget.setAssigned(month, id, it); assigning = null },
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
            val order = (data.categoriesIn(g.id).maxOfOrNull { it.sortOrder } ?: -1) + 1
            store.budget.saveCategory(Category(store.budget.newId(), g.id, name, order))
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
        TextInputDialog("Rename category", initial = c.name, onDismiss = { renamingCategory = null }) {
            store.budget.saveCategory(c.copy(name = it)); renamingCategory = null
        }
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
private fun ReadyToAssignCard(cents: Long, modifier: Modifier = Modifier) {
    val colors = if (cents >= 0) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    }
    Card(colors = colors, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            Text(Money.format(cents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (cents >= 0) "Ready to Assign" else "Assigned more than you have", style = MaterialTheme.typography.labelSmall)
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
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAssign).padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            Money.format(cm.assigned),
            Modifier.width(if (wide) 120.dp else 90.dp),
            textAlign = TextAlign.End,
            color = if (cm.assigned == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (wide) Text(Money.format(cm.activity), Modifier.width(120.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(if (wide) 130.dp else 105.dp), contentAlignment = Alignment.CenterEnd) {
            AvailablePill(cm.available, creditOverspending = cm.creditOverspent > 0 && cm.cashOverspent == 0L)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Category options") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Assign money") }, onClick = { menu = false; onAssign() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun AssignDialog(
    name: String,
    month: YearMonth,
    current: CategoryMonth,
    lastMonth: CategoryMonth,
    card: CardPayment?,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(if (current.assigned == 0L) "" else Money.toInput(current.assigned)) }
    val parsed = if (text.isBlank()) 0L else Money.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name · ${month.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Activity ${Money.format(current.activity)} · Available ${Money.format(current.available)}", style = MaterialTheme.typography.bodySmall)
                if (card != null) {
                    Text(
                        "You owe ${Money.format(card.owed)} on this card. Money assigned here is set aside to pay it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (current.creditOverspent > 0) {
                    Text(
                        "${Money.format(current.creditOverspent)} was charged to a credit card without money behind it. " +
                            "Cover it this month so it can be paid off, or it stays as card debt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                MoneyField("Assigned", text, { text = it }, Modifier.fillMaxWidth(), isError = parsed == null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.available < 0) {
                        AssistChip(onClick = { text = Money.toInput(current.assigned - current.available) }, label = { Text("Cover overspending") })
                    }
                    if (card != null && card.underfunded > 0) {
                        AssistChip(
                            onClick = { text = Money.toInput(current.assigned + card.underfunded) },
                            label = { Text("Fund whole balance (+${Money.format(card.underfunded)})") },
                        )
                    }
                    AssistChip(onClick = { text = Money.toInput(lastMonth.assigned) }, label = { Text("Assigned last month (${Money.format(lastMonth.assigned)})") })
                    AssistChip(onClick = { text = Money.toInput(-lastMonth.activity) }, label = { Text("Spent last month (${Money.format(-lastMonth.activity)})") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("Assign") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CardGroupRow(totals: List<CategoryMonth>, wide: Boolean) {
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

@Composable
private fun CardPaymentRow(
    name: String,
    card: CardPayment,
    cm: CategoryMonth,
    wide: Boolean,
    onAssign: () -> Unit,
    onPay: () -> Unit,
    onOpen: () -> Unit,
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
                card.underfunded == 0L -> if (wide) "Owe ${Money.format(card.owed)} · fully funded" else "Fully funded"
                wide -> "Owe ${Money.format(card.owed)} · ${Money.format(card.underfunded)} not covered"
                else -> "${Money.format(card.underfunded)} not covered"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (card.underfunded > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            Money.format(cm.assigned),
            Modifier.width(if (wide) 120.dp else 90.dp),
            textAlign = TextAlign.End,
            color = if (cm.assigned == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (wide) Text(Money.format(cm.activity), Modifier.width(120.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.width(if (wide) 130.dp else 105.dp), contentAlignment = Alignment.CenterEnd) { AvailablePill(cm.available) }
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
