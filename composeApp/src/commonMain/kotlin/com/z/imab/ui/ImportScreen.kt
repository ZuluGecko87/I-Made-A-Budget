package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.data.Budget
import com.z.imab.data.BudgetStore
import com.z.imab.importer.AccountKeys
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.ImportRow
import com.z.imab.importer.ImportWindow
import com.z.imab.importer.RuleBook
import com.z.imab.model.PayeeRule
import com.z.imab.importer.MatchKind
import com.z.imab.importer.OfxParser
import com.z.imab.importer.OfxStatement
import com.z.imab.model.AccountType
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

private const val NEW_ACCOUNT = "__new__"

@Stable
private class StatementState(val stmt: OfxStatement) {
    val earliest: LocalDate = stmt.transactions.minOf { it.date }
    var accountId by mutableStateOf<String?>(null)
    /** Only bank transactions on or after this date are imported. */
    var from by mutableStateOf(earliest)
    /** For accounts imported into before: look past the last imported date. */
    var includeOlder by mutableStateOf(false)
    var showDuplicates by mutableStateOf(false)
    /** Account created from this file just now; its starting balance follows [from]. */
    var createdAccountId by mutableStateOf<String?>(null)
    val rows = mutableStateListOf<ImportRow>()

    val olderCount: Int get() = stmt.transactions.count { it.date < from }

    fun chooseAccount(id: String?, data: BudgetData) {
        accountId = id
        includeOlder = false
        from = ImportWindow.defaultStart(data, id, stmt.transactions)
        analyze(data)
    }

    fun changeFrom(date: LocalDate, budget: Budget) {
        from = date
        val created = createdAccountId
        if (created != null && created == accountId) {
            ImportWindow.openingBalance(stmt, date)?.let { budget.setStartingBalance(created, it, date.minus(1, DateTimeUnit.DAY)) }
        }
        analyze(budget.data)
    }

    /** Rows ticked to add together, by import id. */
    val selected = mutableStateListOf<String>()

    /** Rows that are added (not matched), so they can be ticked. */
    val selectable: List<ImportRow> get() = rows.filter { it.kind == MatchKind.NEW || it.kind == MatchKind.DUPLICATE }

    fun toggle(row: ImportRow) {
        if (!selected.remove(row.importId)) selected += row.importId
    }

    /** Gives every ticked row the same category or transfer (and drops any split). */
    fun setChoiceForSelected(choice: String) {
        val transfer = Choice.transferId(choice)
        rows.indices.filter { rows[it].importId in selected }.forEach { i ->
            rows[i] = rows[i].copy(
                categoryId = Choice.categoryId(choice),
                transferAccountId = transfer,
                loanAccountId = rows[i].loanAccountId.takeIf { transfer == null },
                splits = emptyList(),
            )
        }
    }

    /** Adds the ticked rows now and takes them off the list. */
    fun addSelected(budget: Budget) {
        val id = accountId ?: return
        val picked = rows.filter { it.importId in selected }
        val r = budget.applyImport(id, stmt.accountKey, picked.map { it.copy(action = ImportAction.ADD) })
        rows.removeAll { it.importId in selected }
        selected.clear()
        addedNow += r.added
    }

    fun analyze(data: BudgetData) {
        selected.clear()
        rows.clear()
        val id = accountId ?: return
        rows.addAll(ImportMatcher.analyze(stmt.transactions.filter { it.date >= from }, id, data))
    }

    /** Updates a row; when it will become a rule, the same choice is applied to matching rows in this file. */
    fun updateRow(index: Int, row: ImportRow) {
        rows[index] = row
        if (!row.makeRule || row.ruleMatch.isBlank()) return
        val rule = PayeeRule("", row.ruleMatch, row.categoryId, row.transferAccountId, row.loanAccountId)
        rows.indices.filter { it != index }.forEach { i ->
            val r = rows[i]
            if (r.action == ImportAction.ADD && r.splits.isEmpty() && RuleBook.matches(rule, r.payee)) {
                rows[i] = r.copy(
                    categoryId = row.categoryId,
                    transferAccountId = row.transferAccountId,
                    loanAccountId = row.loanAccountId,
                    reason = "New · same as \"${row.ruleMatch}\" above",
                )
            }
        }
    }

    /** Transactions already added, or merged / matched, one at a time from their rows. */
    var addedNow by mutableStateOf(0)
    var matchedNow by mutableStateOf(0)

    /** Adds ([ImportAction.ADD]) or merges ([ImportAction.MATCH]) one row straight away and takes it off the list. */
    fun applyNow(index: Int, action: ImportAction, budget: Budget) {
        val id = accountId ?: return
        val r = budget.applyImport(id, stmt.accountKey, listOf(rows[index].copy(action = action)))
        selected.remove(rows[index].importId)
        rows.removeAt(index)
        addedNow += r.added
        matchedNow += r.matched
    }

    fun setDuplicates(action: ImportAction) {
        rows.indices.filter { rows[it].kind == MatchKind.DUPLICATE }.forEach { rows[it] = rows[it].copy(action = action) }
    }
}

@Composable
fun ImportScreen(store: BudgetStore, pickers: Pickers, nav: NavState) {
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var importedInto by remember { mutableStateOf<List<String>>(emptyList()) }
    val statements = remember { mutableStateListOf<StatementState>() }
    var creatingFor by remember { mutableStateOf<StatementState?>(null) }

    fun load(file: PickedFile) {
        error = null
        result = null
        try {
            val parsed = OfxParser.parse(file.text).filter { it.transactions.isNotEmpty() }
            if (parsed.isEmpty()) {
                error = "No transactions found in ${file.name}."
                return
            }
            statements.clear()
            parsed.forEach { stmt ->
                val st = StatementState(stmt)
                // Only when exactly one account has this bank id and last 4 digits; otherwise ask.
                val key = AccountKeys.mask(stmt.accountKey)
                val known = data.accounts.values.filter { a -> a.importKeys.any { AccountKeys.mask(it) == key } }.singleOrNull()?.id
                    ?: nav.importAccountId?.takeIf { parsed.size == 1 && data.accounts.containsKey(it) }
                st.chooseAccount(known, data)
                statements += st
            }
            fileName = file.name
        } catch (e: Exception) {
            error = e.message ?: "Couldn't read ${file.name}."
        }
    }

    fun pick() {
        scope.launch {
            try {
                pickers.pickImportFile()?.let(::load)
            } catch (e: Exception) {
                error = "Couldn't open the file: ${e.message}"
            }
        }
    }

    // A file dropped onto the window (desktop) arrives here.
    LaunchedEffect(nav.pendingImport) {
        nav.pendingImport?.let {
            nav.pendingImport = null
            load(it)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.importAccountId?.let(nav::openAccount) ?: nav.go(Screen.Accounts) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Text("Import bank file", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "Download transactions from your bank as QuickBooks (.qbo) — Quicken (.qfx) and OFX (.ofx) work too. " +
                        "Transactions already in your budget are ignored as duplicates; ones you entered by hand " +
                        "or that were scheduled are matched instead of added twice.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = ::pick) { Text(if (fileName == null) "Choose file…" else "Choose another file…") }
                    if (statements.isNotEmpty()) {
                        OutlinedButton(onClick = { statements.clear(); fileName = null }) { Text("Cancel") }
                    }
                    if (pickers.supportsFileDrop && statements.isEmpty()) {
                        Text("or drag a file onto this window", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                fileName?.let { Text("File: $it", style = MaterialTheme.typography.labelMedium) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.let {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(it, fontWeight = FontWeight.Medium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                importedInto.forEach { id ->
                                    OutlinedButton(onClick = { nav.openAccount(id) }) { Text("Open ${data.accountName(id)}") }
                                }
                            }
                        }
                    }
                }
            }
        }

        for (st in statements) {
            item(key = "stmt-${st.stmt.accountKey}") {
                StatementHeader(
                    st, data, store.budget,
                    onCreateAccount = { creatingFor = st },
                )
            }
            val visible = st.rows.withIndex().filter { (_, r) ->
                st.showDuplicates || r.kind != MatchKind.DUPLICATE || r.action != ImportAction.SKIP
            }
            if (st.accountId != null && st.selectable.isNotEmpty()) {
                stickyHeader(key = "select-${st.stmt.accountKey}") { SelectionBar(st, data, store.budget) }
            }
            itemsIndexed(visible, key = { _, iv -> "row-${st.stmt.accountKey}-${iv.value.importId}" }) { _, (index, row) ->
                ImportRowView(
                    row, data, st.accountId,
                    selected = row.importId in st.selected,
                    onSelect = { st.toggle(row) }.takeIf { row.kind == MatchKind.NEW || row.kind == MatchKind.DUPLICATE },
                    onApply = { st.applyNow(index, it, store.budget) },
                ) { st.updateRow(index, it) }
            }
        }

        if (statements.isNotEmpty()) {
            item {
                val ready = statements.all { it.accountId != null }
                val count = statements.sumOf { s -> s.rows.count { it.action != ImportAction.SKIP } }
                val addedNow = statements.sumOf { it.addedNow }
                val matchedNow = statements.sumOf { it.matchedNow }
                val doneNow = addedNow + matchedNow
                Column(Modifier.padding(16.dp)) {
                    if (!ready) Text("Choose an account for each statement.", color = MaterialTheme.colorScheme.error)
                    Button(
                        enabled = ready && (count > 0 || doneNow > 0),
                        onClick = {
                            var added = addedNow; var matched = matchedNow; var skipped = 0
                            for (s in statements) {
                                val r = store.budget.applyImport(s.accountId!!, s.stmt.accountKey, s.rows.toList())
                                added += r.added; matched += r.matched; skipped += r.skipped
                            }
                            result = "Imported: $added added, $matched matched to existing, $skipped duplicates ignored."
                            importedInto = statements.mapNotNull { it.accountId }.distinct()
                            statements.clear()
                            fileName = null
                        },
                    ) {
                        Text(
                            when {
                                ready && count == 0 && doneNow > 0 -> "Done"
                                ready && count == 0 -> "Nothing new to import"
                                else -> "Import $count transaction(s)"
                            }
                        )
                    }
                    if (doneNow > 0) {
                        Text(
                            listOfNotNull(
                                "$addedNow added".takeIf { addedNow > 0 },
                                "$matchedNow merged or matched".takeIf { matchedNow > 0 },
                            ).joinToString(", ", postfix = " already."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    creatingFor?.let { st ->
        val stmt = st.stmt
        AccountDialog(
            initial = null,
            newId = store.budget::newId,
            suggestedName = if (stmt.isCreditCard) "Credit card ${stmt.shortName}" else "${stmt.accountType?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Account"} ${stmt.shortName}",
            suggestedType = when {
                stmt.isCreditCard -> AccountType.CREDIT_CARD
                stmt.accountType.equals("SAVINGS", true) -> AccountType.SAVINGS
                stmt.accountType.equals("CREDITLINE", true) -> AccountType.LINE_OF_CREDIT
                else -> AccountType.CHECKING
            },
            // Balance before the first imported transaction, so the imported ones bring it up to the bank's balance.
            suggestedBalance = ImportWindow.openingBalance(stmt, st.from) ?: 0,
            suggestedDate = st.from.minus(1, DateTimeUnit.DAY),
            onSave = { a, bal, date ->
                store.budget.saveAccount(a.copy(importKeys = listOf(AccountKeys.mask(stmt.accountKey))), bal, date)
                st.createdAccountId = a.id
                st.chooseAccount(a.id, store.budget.data)
                creatingFor = null
            },
            onDelete = null,
            onDismiss = { creatingFor = null },
        )
    }
}

/** Ticks rows to add several at once, with one category for all of them. Stays at the top while scrolling. */
@Composable
private fun SelectionBar(st: StatementState, data: BudgetData, budget: Budget) {
    val all = st.selectable
    val picked = st.rows.filter { it.importId in st.selected }
    val count = picked.size
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = if (count > 0) 3.dp else 0.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                    TriStateCheckbox(
                        when (count) { 0 -> ToggleableState.Off; all.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                        onClick = {
                            if (count == all.size) st.selected.clear()
                            else { st.selected.clear(); st.selected += all.map { it.importId } }
                        },
                        modifier = Modifier.semantics { contentDescription = "Select all" },
                    )
                    Text(if (count == 0) "Select rows to add several at once" else "$count selected", style = MaterialTheme.typography.bodyMedium)
                }
                if (count > 0) {
                    val choices = picked.map { Choice.of(it.categoryId, it.transferAccountId) }.distinct()
                    CategoryPicker(
                        "Category for selected", data, st.accountId,
                        choices.singleOrNull()?.takeIf { picked.none { it.splits.isNotEmpty() } } ?: Choice.NONE,
                        { st.setChoiceForSelected(it) },
                        Modifier.width(240.dp),
                    )
                    Button(onClick = { st.addSelected(budget) }) { Text(if (count == 1) "Add 1 transaction" else "Add $count transactions") }
                    TextButton(onClick = { st.selected.clear() }) { Text("Clear") }
                }
            }
            val missing = picked.count { data.needsCategoryForImport(it, st.accountId) }
            if (missing > 0) {
                Text(
                    "$missing of these ${if (missing == 1) "has" else "have"} no category yet; they'll be added uncategorized.",
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** Like [BudgetData.needsCategory], for a row that isn't a transaction yet. */
private fun BudgetData.needsCategoryForImport(r: ImportRow, accountId: String?): Boolean =
    isOnBudget(accountId) && r.transferAccountId == null && r.loanAccountId == null &&
        (if (r.splits.isEmpty()) r.categoryId == null else r.splits.any { it.categoryId == null })

@Composable
private fun StatementHeader(st: StatementState, data: BudgetData, budget: Budget, onCreateAccount: () -> Unit) {
    val stmt = st.stmt
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            "${if (stmt.isCreditCard) "Credit card" else stmt.accountType ?: "Account"} ${stmt.shortName} · ${stmt.transactions.size} transactions" +
                " · ${st.earliest.short()}–${stmt.transactions.maxOf { it.date }.short()}" +
                (stmt.ledgerBalance?.let { " · bank balance ${Money.format(it)}" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        val options = data.sortedAccounts.filter { !it.closed }.map { PickerOption<String?>(it.id, it.name) } +
            PickerOption<String?>(NEW_ACCOUNT, "+ Create new account…")
        Picker<String?>("Import into", options, st.accountId, {
            if (it == NEW_ACCOUNT) onCreateAccount() else st.chooseAccount(it, data)
        }, Modifier.widthIn(max = 420.dp).fillMaxWidth())

        val accountId = st.accountId ?: return@Column
        DateWindow(st, ImportWindow.lastImportDate(data, accountId), budget)
        DuplicateAlert(st)

        val counts = st.rows.groupingBy { it.kind }.eachCount()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                MatchKind.NEW to "new",
                MatchKind.MATCH_EXISTING to "match entered",
                MatchKind.MATCH_SCHEDULED to "match scheduled",
                MatchKind.DUPLICATE to "duplicates",
            ).forEach { (kind, label) -> counts[kind]?.let { KindBadge(kind, "$it $label") } }
            if (st.rows.isEmpty()) KindBadge(MatchKind.DUPLICATE, "No transactions in this date range")
        }
    }
}

/** How far back to import: a start date on the first import, "since last import" afterwards. */
@Composable
private fun DateWindow(st: StatementState, lastImport: LocalDate?, budget: Budget) {
    val reconciledOn = st.accountId?.let { budget.data.accounts[it]?.lastReconciled }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (lastImport == null) {
            Text(
                when {
                    st.createdAccountId != null ->
                        "First import into this account. Choose how far back to go — the starting balance adjusts so the account matches the bank."
                    reconciledOn != null ->
                        "First import into this account. It starts the day after your last reconcile (${reconciledOn.short()}); everything before that is already confirmed."
                    else -> "First import into this account. Choose how far back to go."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DateField("Import transactions from", st.from, { st.changeFrom(it, budget) }, Modifier.width(240.dp))
                if (st.from != st.earliest) {
                    AssistChip(onClick = { st.changeFrom(st.earliest, budget) }, label = { Text("Everything in file (from ${st.earliest.short()})") })
                }
            }
        } else {
            Text(
                "Last import went up to ${lastImport.short()}, so only transactions from that date on are checked.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(st.includeOlder, { include ->
                    st.includeOlder = include
                    st.changeFrom(if (include) st.earliest else maxOf(lastImport, st.earliest), budget)
                })
                Text("Also check older transactions in this file")
            }
            if (st.includeOlder) {
                DateField("Import transactions from", st.from, { st.changeFrom(it, budget) }, Modifier.width(240.dp))
            }
        }
        if (st.olderCount > 0) {
            Text(
                "${st.olderCount} transaction(s) before ${st.from.short()} are left out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Duplicates are ignored by default; say so, and let them be reviewed or imported anyway. */
@Composable
private fun DuplicateAlert(st: StatementState) {
    val dups = st.rows.count { it.kind == MatchKind.DUPLICATE }
    if (dups == 0) return
    val included = st.rows.count { it.kind == MatchKind.DUPLICATE && it.action != ImportAction.SKIP }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    if (included == 0) "$dups duplicate transaction(s) from earlier uploads will be ignored."
                    else "$included of $dups duplicate transaction(s) will be imported again.",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                "They're already in your budget. Only import them if they really are separate transactions, like two identical purchases on the same day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row {
                TextButton(onClick = { st.showDuplicates = !st.showDuplicates }) {
                    Text(if (st.showDuplicates) "Hide them" else "Show them")
                }
                if (included == 0) {
                    TextButton(onClick = { st.setDuplicates(ImportAction.ADD); st.showDuplicates = true }) { Text("Import them anyway") }
                } else {
                    TextButton(onClick = { st.setDuplicates(ImportAction.SKIP) }) { Text("Ignore them") }
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: MatchKind, text: String) {
    val (bg, fg) = when (kind) {
        MatchKind.NEW -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        MatchKind.MATCH_EXISTING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        MatchKind.MATCH_SCHEDULED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        MatchKind.DUPLICATE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun ImportRowView(
    row: ImportRow,
    data: BudgetData,
    accountId: String?,
    selected: Boolean,
    onSelect: (() -> Unit)?,
    onApply: (ImportAction) -> Unit,
    onChange: (ImportRow) -> Unit,
) {
    val skip = row.action == ImportAction.SKIP
    var splitting by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(start = 4.dp),
    ) {
        // Rows that are added (not matched) can be ticked to add several at once.
        Box(Modifier.width(40.dp).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
            if (onSelect != null && accountId != null) Checkbox(selected, { onSelect() }, Modifier.semantics { contentDescription = "Select ${row.payee}" })
        }
        Column(Modifier.weight(1f).padding(end = 16.dp, top = 6.dp, bottom = 6.dp).alpha(if (skip) 0.55f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(row.source.date.short(), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f)) {
                    Text(row.payee.ifBlank { "(no payee)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(Money.format(row.source.amount), color = amountColor(row.source.amount))
            }
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // These buttons act now and take the row off the list; the rest wait for the Import button.
                val match = when (row.kind) {
                    MatchKind.MATCH_EXISTING -> "Merge with what you entered"
                    MatchKind.MATCH_SCHEDULED -> "Match scheduled"
                    else -> null
                }
                if (match != null) {
                    // Keeps what you entered (payee, category, memo) as the one cleared transaction.
                    FilledTonalButton(onClick = { onApply(ImportAction.MATCH) }, enabled = accountId != null) { Text(match) }
                } else {
                    FilledTonalButton(onClick = { onApply(ImportAction.ADD) }, enabled = accountId != null) { Text("Add transaction") }
                }
                // Skip leaves it out of the Import; clicking again puts it back.
                val usual = if (match != null) ImportAction.MATCH else ImportAction.ADD
                OutlinedButton(onClick = { onChange(row.copy(action = if (skip) usual else ImportAction.SKIP)) }) { Text(if (skip) "Include" else "Skip") }
                if (match == null && row.splits.isNotEmpty()) {
                    OutlinedButton(onClick = { splitting = true }, enabled = accountId != null, modifier = Modifier.width(240.dp)) {
                        Text("Split: " + row.splits.joinToString(", ") { data.categoryName(it.categoryId) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else if (match == null) {
                    CategoryPicker(
                        "Category",
                        data,
                        accountId,
                        Choice.of(row.categoryId, row.transferAccountId),
                        {
                            val transfer = Choice.transferId(it)
                            onChange(
                                row.copy(
                                    categoryId = Choice.categoryId(it),
                                    transferAccountId = transfer,
                                    loanAccountId = row.loanAccountId.takeIf { transfer == null },
                                )
                            )
                        },
                        Modifier.width(240.dp),
                    )
                    val loans = data.loanAccounts.filter { it.id != accountId }
                    if (loans.isNotEmpty() && row.source.amount < 0 && row.transferAccountId == null) {
                        Picker(
                            "Loan payment",
                            listOf(PickerOption<String?>(null, "Not a loan payment")) + loans.map { PickerOption<String?>(it.id, "Payment on ${it.name}") },
                            row.loanAccountId,
                            { onChange(row.copy(loanAccountId = it)) },
                            Modifier.width(240.dp),
                        )
                    }
                    if (row.transferAccountId == null && row.loanAccountId == null) {
                        TextButton(onClick = { splitting = true }, enabled = accountId != null) { Text("Split") }
                    }
                }
            }
            if (splitting && accountId != null) {
                SplitDialog(
                    data, accountId, row.payee, row.source.amount, row.categoryId, row.splits,
                    onSave = { category, lines ->
                        // One category per rule, so a split row doesn't make one.
                        onChange(row.copy(categoryId = if (lines.isEmpty()) category else null, splits = lines, makeRule = row.makeRule && lines.isEmpty()))
                        splitting = false
                    },
                    onDismiss = { splitting = false },
                )
            }
            val existingRule = RuleBook.find(data, row.payee)
            val ruleAlreadyDoesThis = existingRule != null && existingRule.categoryId == row.categoryId &&
                existingRule.transferAccountId == row.transferAccountId && existingRule.loanAccountId == row.loanAccountId
            if (row.action == ImportAction.ADD && row.splits.isEmpty() && row.kind != MatchKind.MATCH_EXISTING && row.kind != MatchKind.MATCH_SCHEDULED &&
                row.ruleMatch.isNotBlank() && !ruleAlreadyDoesThis &&
                (row.categoryId != null || row.transferAccountId != null || row.loanAccountId != null)
            ) {
                Row(Modifier.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(row.makeRule, { onChange(row.copy(makeRule = it)) })
                    Text(
                        "Always use this for payees containing \"${row.ruleMatch}\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
