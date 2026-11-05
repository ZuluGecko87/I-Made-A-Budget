package com.z.imab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.MergeCandidate
import com.z.imab.importer.MergeFinder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth

@Composable
fun AccountsScreen(store: BudgetStore, nav: NavState) {
    val data by store.data.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val duplicates = remember(data) { MergeFinder.countByAccount(data) }
    var addingTo by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Accounts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { nav.importInto(null) }, modifier = Modifier.padding(end = 8.dp)) { Text("Import") }
                Button(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null)
                    Text("Add account")
                }
            }
        }
        if (data.accounts.isEmpty()) {
            item {
                Text(
                    "Add your checking, savings and credit card accounts to get started. You can then import bank files (QBO/OFX/QFX) into them.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        val sections = listOf(
            "Budget" to data.sortedAccounts.filter { it.inBudget && !it.closed },
            "Tracking" to data.sortedAccounts.filter { !it.inBudget && !it.closed },
            "Closed" to data.sortedAccounts.filter { it.closed },
        )
        for ((title, accounts) in sections) {
            if (accounts.isEmpty()) continue
            item(key = "h-$title") {
                SectionHeader(title) {
                    Text(Money.format(accounts.sumOf { data.balance(it.id) }), fontWeight = FontWeight.SemiBold)
                }
            }
            items(accounts, key = { it.id }) { a ->
                AccountRow(a, data, duplicates[a.id] ?: 0, onAdd = { addingTo = a.id }) { nav.openAccount(a.id) }
            }
        }
        if (data.uncategorizedCount > 0) {
            item {
                OutlinedButton(onClick = { nav.openAccount(UNCATEGORIZED_VIEW) }, modifier = Modifier.padding(16.dp)) {
                    Text("Review ${data.uncategorizedCount} uncategorized transaction(s)")
                }
            }
        }
    }

    addingTo?.let { id ->
        TransactionDialog(
            data = data,
            initial = null,
            defaultAccountId = id,
            newId = store.budget::newId,
            onSave = { store.budget.saveTransaction(it); addingTo = null },
            onDelete = null,
            onDismiss = { addingTo = null },
        )
    }
    if (adding) {
        AccountDialog(
            initial = null,
            newId = store.budget::newId,
            onSave = { a, bal, date ->
                store.budget.saveAccount(a, bal, date)
                adding = false
                nav.openAccount(a.id)
            },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun AccountRow(a: Account, data: BudgetData, possibleDuplicates: Int, onAdd: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(a.name, fontWeight = FontWeight.Medium)
            Text(a.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (possibleDuplicates > 0) {
                Text(
                    "$possibleDuplicates possible duplicate${if (possibleDuplicates == 1) "" else "s"} to merge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        val bal = data.balance(a.id)
        // Cards and loans read as what you owe.
        if ((a.type.isCredit || a.type.isLoan) && bal < 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(-bal), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Text("owed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(Money.format(bal), color = amountColor(bal), fontWeight = FontWeight.SemiBold)
        }
        if (!a.closed) {
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add transaction to ${a.name}", tint = MaterialTheme.colorScheme.primary) }
        }
    }
    HorizontalDivider()
}

@Composable
fun RegisterScreen(store: BudgetStore, nav: NavState, accountId: String, wide: Boolean, fileDrop: Boolean = false) {
    val data by store.data.collectAsState()
    val uncategorizedView = accountId == UNCATEGORIZED_VIEW
    val account = data.accounts[accountId]
    if (!uncategorizedView && account == null) {
        LaunchedEffect(accountId) { nav.accountId = null }
        return
    }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var adding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<Transaction?>(null) }
    val isCard = data.isCreditAccount(accountId)
    val cardInfo = remember(data, accountId) {
        if (isCard) BudgetMath.compute(data, YearMonth.of(today())).cards.firstOrNull { it.accountId == accountId } else null
    }
    var editingAccount by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Picking transactions to merge as duplicates.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var mergingSelected by remember { mutableStateOf(false) }

    val all = if (uncategorizedView) data.transactions.values.filter { data.needsCategory(it) } else data.transactionsByAccount[accountId].orEmpty()
    // Running balance is computed oldest-first, then shown newest-first.
    val rows = remember(all, query) {
        var running = 0L
        val withBalance = all.sortedWith(compareBy({ it.date }, { it.id })).map { t -> running += t.amount; t to running }
        val q = query.trim().lowercase()
        withBalance.reversed().filter { (t, _) ->
            q.isEmpty() || data.payeeLabel(t).lowercase().contains(q) || t.memo.lowercase().contains(q) ||
                data.categoryLabel(t).lowercase().contains(q) || Money.format(t.amount).contains(q)
        }
    }

    val merges = remember(data, accountId) { if (uncategorizedView) emptyList() else MergeFinder.find(data, accountId) }
    val inMerge = remember(merges) { merges.flatMap { listOf(it.entered.id, it.bank.id) }.toSet() }
    var reviewingMerges by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.accountId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                if (uncategorizedView) "Needs a category" else account!!.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!uncategorizedView) IconButton(onClick = { editingAccount = true }) { Icon(Icons.Default.Edit, "Edit account") }
        }
        if (!uncategorizedView) {
            FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val total = data.balance(accountId)
                val cleared = data.clearedBalance(accountId)
                if (account!!.type.isCredit || account.type.isLoan) {
                    // Lead with what's owed, including charges that haven't cleared yet.
                    OwedBalance(if (total < 0) "Owed" else "Credit balance", total)
                    Balance("Cleared", cleared)
                    Balance("Pending (not cleared)", total - cleared)
                } else {
                    Balance("Cleared", cleared)
                    Balance("Uncleared", total - cleared)
                    Balance("Working balance", total)
                }
            }
        }
        HorizontalDivider()
        // Everything below the balances scrolls with the transactions; the search bar stays at the top of the list.
        LazyColumn(Modifier.weight(1f)) {
            if (!uncategorizedView) {
                item(key = "actions") {
                    Column {
                        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text("Add transaction") }
                            if (isCard && data.sortedAccounts.size > 1) {
                                OutlinedButton(onClick = { paying = paymentTemplate(data, accountId, cardInfo?.available ?: 0, store.budget.newId()) }) {
                                    Text("Make payment")
                                }
                            }
                            OutlinedButton(onClick = { nav.importInto(accountId) }) { Text("Import bank file") }
                            OutlinedButton(onClick = { reconciling = true }) { Text("Reconcile") }
                            if (!selecting) OutlinedButton(onClick = { selecting = true; selected.clear() }) { Text("Merge duplicates") }
                        }
                        if (fileDrop) {
                            Text(
                                "Tip: drag a .qbo, .qfx or .ofx file onto this window to import it into ${account!!.name}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                        if (account?.type?.isLoan == true) LoanSummary(account, data, onPlan = { nav.loanPlannerFor = accountId }) { editingAccount = true }
                        account?.lastReconciled?.let { date ->
                            val bal = account.reconciledBalance
                            Text(
                                "Last reconciled ${date.short()}" + (bal?.let {
                                    " at " + if ((account.type.isCredit || account.type.isLoan) && it < 0) "${Money.format(-it)} owed" else Money.format(it)
                                } ?: "") + " · 🔒 transactions up to then are locked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                        cardInfo?.let { c ->
                            Text(
                                when {
                                    c.owed <= 0 -> "Paid off. Spending on this card moves money from its category into the payment category."
                                    c.underfunded == 0L -> "Payment category has ${Money.format(c.available)} set aside — enough to pay the ${Money.format(c.owed)} balance."
                                    else -> "Payment category has ${Money.format(maxOf(0, c.available))} set aside; ${Money.format(c.underfunded)} of the ${Money.format(c.owed)} balance isn't covered yet."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (c.underfunded > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
            if (merges.isNotEmpty()) {
                item(key = "merge-banner") {
                    MergeBanner(
                        count = merges.size,
                        reviewing = reviewingMerges,
                        onReview = { reviewingMerges = !reviewingMerges },
                        onMergeAll = { merges.forEach { store.budget.mergeTransactions(it.entered.id, it.bank.id) } },
                    )
                }
            }
            stickyHeader(key = "search") {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        if (selecting) {
                            SelectionBar(
                                count = selected.size,
                                onMerge = { mergingSelected = true },
                                onDone = { selecting = false; selected.clear() },
                            )
                        }
                        SearchBar(query, { query = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
                        HorizontalDivider()
                    }
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    Text(if (uncategorizedView) "Everything has a category." else "No transactions yet.", Modifier.padding(16.dp))
                }
            }
            if (reviewingMerges) {
                items(merges, key = { "merge-${it.entered.id}-${it.bank.id}" }) { c ->
                    MergeCard(
                        c, data,
                        onMerge = { store.budget.mergeTransactions(c.entered.id, c.bank.id) },
                        onKeepBoth = { store.budget.markNotDuplicate(c.entered.id, c.bank.id) },
                    )
                }
            }
            items(rows, key = { it.first.id }) { (t, running) ->
                TxnRow(
                    t, data, running.takeIf { wide && !uncategorizedView && query.isBlank() },
                    showAccount = uncategorizedView,
                    possibleDuplicate = t.id in inMerge,
                    selected = if (selecting) t.id in selected else null,
                    onClick = {
                        when {
                            !selecting -> editing = t
                            t.id in selected -> selected.remove(t.id)
                            else -> selected.add(t.id)
                        }
                    },
                    // A long press starts picking duplicates, with this one ticked.
                    onLongClick = if (uncategorizedView) null else ({
                        if (!selecting) { selecting = true; selected.clear() }
                        if (t.id !in selected) selected.add(t.id)
                    }),
                    onToggleCleared = { store.budget.setCleared(t.id, !t.cleared) },
                )
            }
        }
    }

    if (adding || editing != null) {
        val t = editing
        TransactionDialog(
            data = data,
            initial = t,
            defaultAccountId = if (uncategorizedView) data.sortedAccounts.first().id else accountId,
            newId = store.budget::newId,
            onSave = { store.budget.saveTransaction(it); adding = false; editing = null },
            onDelete = t?.let { { store.budget.deleteTransaction(it.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
    if (mergingSelected) {
        val picked = selected.mapNotNull { data.transactions[it] }
        MergeSelectedDialog(
            picked, data,
            onMerge = { keepId ->
                store.budget.mergeDuplicates(keepId, picked.map { it.id })
                mergingSelected = false
                selecting = false
                selected.clear()
            },
            onDismiss = { mergingSelected = false },
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
    if (reconciling && account != null) {
        ReconcileDialog(
            data = data,
            account = account,
            onToggleCleared = { id, on -> store.budget.setCleared(id, on) },
            onReconcile = { date, balance, adjust -> store.budget.reconcile(account.id, date, balance, adjust); reconciling = false },
            onDismiss = { reconciling = false },
        )
    }
        if (editingAccount && account != null) {
        AccountDialog(
            initial = account,
            newId = store.budget::newId,
            currentBalance = data.balance(account.id),
            onSave = { a, bal, _ ->
                store.budget.saveAccount(a)
                if (a.type.isLoan) store.budget.setLoanPrincipal(a.id, -bal)
                editingAccount = false
            },
            onDelete = { store.budget.deleteAccount(account.id); editingAccount = false; nav.accountId = null },
            onDismiss = { editingAccount = false },
        )
    }
}

/** Owed amount shown as a positive number, larger, in the debt color. */
@Composable
private fun OwedBalance(label: String, balance: Long) {
    Column {
        Text(
            Money.format(kotlin.math.abs(balance)),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
            color = if (balance < 0) MaterialTheme.colorScheme.error else amountColor(balance),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Balance(label: String, cents: Long) {
    Column {
        Text(Money.format(cents), fontWeight = FontWeight.SemiBold, color = amountColor(cents))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TxnRow(
    t: Transaction,
    data: BudgetData,
    running: Long?,
    showAccount: Boolean,
    possibleDuplicate: Boolean = false,
    /** Whether it's ticked, while picking transactions; null otherwise. */
    selected: Boolean? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onToggleCleared: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = if (selected != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) Checkbox(selected, { onClick() })
        Text(t.date.short(), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(data.payeeLabel(t), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (t.merged) MergedLabel()
            }
            val needs = data.needsCategory(t)
            val sub = buildString {
                if (showAccount) append(data.accountName(t.accountId)).append(" · ")
                append(data.categoryLabel(t))
                if (t.memo.isNotBlank()) append(" · ").append(t.memo)
                if (t.scheduledId != null && !t.cleared) append(" · scheduled")
                t.loanSplit?.let { append(" · principal ${Money.format(it.principal)}, interest ${Money.format(it.interest)}, escrow ${Money.format(it.escrow)}") }
                if (possibleDuplicate) append(" · possible duplicate")
            }
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    possibleDuplicate -> MaterialTheme.colorScheme.tertiary
                    needs -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.format(t.amount), color = amountColor(t.amount), textAlign = TextAlign.End)
            if (running != null) Text(Money.format(running), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (t.reconciled) {
            // Locked by a reconcile; unlock it in the transaction form if it needs changing.
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, "Reconciled", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            IconButton(onClick = onToggleCleared) {
                Icon(
                    Icons.Default.CheckCircle,
                    if (t.cleared) "Cleared" else "Uncleared",
                    tint = if (t.cleared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** A slim search box, so it doesn't take much of a phone screen. */
@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search payee, category, memo, amount", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    query, onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Clear search", Modifier.size(18.dp)) }
            }
        }
    }
}

/** Shown while picking transactions: how many are ticked, and what can be done with them. */
@Composable
private fun SelectionBar(count: Int, onMerge: () -> Unit, onDone: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (count) {
                    0 -> "Tick the copies of the same transaction"
                    1 -> "1 selected · pick at least one more"
                    else -> "$count selected"
                },
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onMerge, enabled = count >= 2) { Text("Merge…") }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/**
 * Merging duplicates you picked: choose the one to keep. The suggestion is the one that has a
 * category (what you entered), otherwise the first.
 */
@Composable
private fun MergeSelectedDialog(txns: List<Transaction>, data: BudgetData, onMerge: (String) -> Unit, onDismiss: () -> Unit) {
    var keep by remember {
        mutableStateOf(
            (txns.firstOrNull { !data.needsCategory(it) && it.importId == null } ?: txns.firstOrNull { !data.needsCategory(it) } ?: txns.first()).id
        )
    }
    val kept = txns.firstOrNull { it.id == keep } ?: txns.first()
    val balanceChange = txns.filter { it.id != keep }.sumOf { -it.amount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge ${txns.size} transactions into one") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Keep:", style = MaterialTheme.typography.labelLarge)
                txns.sortedBy { it.date }.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { keep = t.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(t.id == keep, { keep = t.id })
                        Column(Modifier.weight(1f)) {
                            Text("${t.date.short()} · ${data.payeeLabel(t)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                data.categoryLabel(t) + (if (t.importId != null) " · from bank" else "") + if (t.memo.isNotBlank()) " · ${t.memo}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(Money.format(t.amount), color = amountColor(t.amount))
                    }
                }
                Text(
                    "The others are deleted. The one you keep is marked Merged and keeps its payee, category and memo; " +
                        "anything it's missing comes from the others. If one came from the bank, the bank's date is used " +
                        "and importing the same bank file won't add the duplicates again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (txns.any { it.amount != kept.amount }) {
                    Text(
                        "The amounts aren't all the same. The kept one's amount is used, so the balance changes by " +
                            "${Money.format(balanceChange)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "The balance changes by ${Money.format(balanceChange)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onMerge(keep) }) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Marks a transaction you entered that was merged with the bank's copy of it. */
@Composable
private fun MergedLabel() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
        Text(
            "Merged",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MergeBanner(count: Int, reviewing: Boolean, onReview: () -> Unit, onMergeAll: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    if (count == 1) "A transaction you entered also came in from the bank."
                    else "$count transactions you entered also came in from the bank.",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                "Same amount, dates no more than ${ImportMatcher.DAY_WINDOW} days apart. Merge them so each shows once — " +
                    "your payee, category and memo are kept, with the bank's date.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row {
                TextButton(onClick = onReview) { Text(if (reviewing) "Hide" else "Review") }
                TextButton(onClick = onMergeAll) { Text(if (count == 1) "Merge" else "Merge all $count") }
            }
        }
    }
}

@Composable
private fun MergeCard(c: MergeCandidate, data: BudgetData, onMerge: () -> Unit, onKeepBoth: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MergeLine("You entered", c.entered, data)
            MergeLine("From bank", c.bank, data)
            Text(
                "Both ${Money.format(c.entered.amount)}" +
                    if (c.daysApart == 0) " on the same day" else ", ${c.daysApart} day${if (c.daysApart == 1) "" else "s"} apart",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMerge) { Text("Merge") }
                OutlinedButton(onClick = onKeepBoth) { Text("Not a duplicate") }
            }
        }
    }
}

@Composable
private fun MergeLine(label: String, t: Transaction, data: BudgetData) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(t.date.short(), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f)) {
            Text(data.payeeLabel(t), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                data.categoryLabel(t) + if (t.memo.isNotBlank()) " · ${t.memo}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Money.format(t.amount), color = amountColor(t.amount))
    }
}
