package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.z.imab.data.BudgetStore
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.ImportRow
import com.z.imab.importer.MatchKind
import com.z.imab.importer.OfxParser
import com.z.imab.importer.OfxStatement
import com.z.imab.model.AccountType
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.platform.Pickers
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus

private const val NEW_ACCOUNT = "__new__"

@Stable
private class StatementState(val stmt: OfxStatement) {
    var accountId by mutableStateOf<String?>(null)
    val rows = mutableStateListOf<ImportRow>()
    var hideDuplicates by mutableStateOf(true)

    fun analyze(data: BudgetData) {
        rows.clear()
        accountId?.let { rows.addAll(ImportMatcher.analyze(stmt.transactions, it, data)) }
    }
}

@Composable
fun ImportScreen(store: BudgetStore, pickers: Pickers, nav: NavState) {
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val statements = remember { mutableStateListOf<StatementState>() }
    var creatingFor by remember { mutableStateOf<StatementState?>(null) }

    fun load() {
        scope.launch {
            error = null
            result = null
            val file = try {
                pickers.pickImportFile()
            } catch (e: Exception) {
                error = "Couldn't open the file: ${e.message}"
                null
            } ?: return@launch
            try {
                val parsed = OfxParser.parse(file.text)
                if (parsed.isEmpty() || parsed.all { it.transactions.isEmpty() }) {
                    error = "No transactions found in ${file.name}."
                    return@launch
                }
                statements.clear()
                parsed.filter { it.transactions.isNotEmpty() }.forEach { stmt ->
                    val st = StatementState(stmt)
                    st.accountId = data.accounts.values.firstOrNull { stmt.accountKey in it.importKeys }?.id
                        ?: nav.importAccountId?.takeIf { parsed.size == 1 && data.accounts.containsKey(it) }
                    st.analyze(data)
                    statements += st
                }
                fileName = file.name
            } catch (e: Exception) {
                error = e.message ?: "Couldn't read ${file.name}."
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import bank file", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Download transactions from your bank as QuickBooks (.qbo) — Quicken (.qfx) and OFX (.ofx) work too. " +
                        "Transactions already in your budget are marked as duplicates and skipped; ones you entered by hand " +
                        "or that were scheduled are matched instead of added twice.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::load) { Text(if (fileName == null) "Choose file…" else "Choose another file…") }
                    if (statements.isNotEmpty()) {
                        OutlinedButton(onClick = { statements.clear(); fileName = null }) { Text("Cancel") }
                    }
                }
                fileName?.let { Text("File: $it", style = MaterialTheme.typography.labelMedium) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.let {
                    Card { Text(it, Modifier.padding(16.dp), fontWeight = FontWeight.Medium) }
                }
            }
        }

        for (st in statements) {
            item(key = "stmt-${st.stmt.accountKey}") {
                StatementHeader(st, data, onCreateAccount = { creatingFor = st }) { st.analyze(data) }
            }
            val visible = st.rows.withIndex().filter { !st.hideDuplicates || it.value.kind != MatchKind.DUPLICATE }
            itemsIndexed(visible, key = { _, iv -> "row-${st.stmt.accountKey}-${iv.index}" }) { _, (index, row) ->
                ImportRowView(row, data, st.accountId) { st.rows[index] = it }
            }
        }

        if (statements.isNotEmpty()) {
            item {
                val ready = statements.all { it.accountId != null }
                val count = statements.sumOf { s -> s.rows.count { it.action != ImportAction.SKIP } }
                Column(Modifier.padding(16.dp)) {
                    if (!ready) Text("Choose an account for each statement.", color = MaterialTheme.colorScheme.error)
                    Button(
                        enabled = ready,
                        onClick = {
                            var added = 0; var matched = 0; var skipped = 0
                            for (s in statements) {
                                val r = store.budget.applyImport(s.accountId!!, s.stmt.accountKey, s.rows.toList())
                                added += r.added; matched += r.matched; skipped += r.skipped
                            }
                            result = "Imported: $added added, $matched matched to existing, $skipped skipped as duplicates."
                            statements.clear()
                            fileName = null
                        },
                    ) { Text("Import $count transaction(s)") }
                }
            }
        }
    }

    creatingFor?.let { st ->
        val stmt = st.stmt
        val sum = stmt.transactions.sumOf { it.amount }
        val earliest = stmt.transactions.minOf { it.date }
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
            suggestedBalance = (stmt.ledgerBalance ?: sum) - sum,
            suggestedDate = earliest.minus(1, DateTimeUnit.DAY),
            onSave = { a, bal, date ->
                store.budget.saveAccount(a.copy(importKeys = listOf(stmt.accountKey)), bal, date)
                st.accountId = a.id
                st.analyze(store.budget.data)
                creatingFor = null
            },
            onDelete = null,
            onDismiss = { creatingFor = null },
        )
    }
}

@Composable
private fun StatementHeader(st: StatementState, data: BudgetData, onCreateAccount: () -> Unit, onChanged: () -> Unit) {
    val stmt = st.stmt
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(
            "${if (stmt.isCreditCard) "Credit card" else stmt.accountType ?: "Account"} ${stmt.shortName} · ${stmt.transactions.size} transactions" +
                (stmt.ledgerBalance?.let { " · bank balance ${Money.format(it)}" } ?: ""),
            style = MaterialTheme.typography.titleMedium,
        )
        val options = data.sortedAccounts.filter { !it.closed }.map { PickerOption<String?>(it.id, it.name) } +
            PickerOption<String?>(NEW_ACCOUNT, "+ Create new account…")
        Picker<String?>("Import into", options, st.accountId, {
            if (it == NEW_ACCOUNT) {
                onCreateAccount()
            } else {
                st.accountId = it
                onChanged()
            }
        }, Modifier.widthIn(max = 420.dp).fillMaxWidth())

        if (st.accountId != null) {
            val counts = st.rows.groupingBy { it.kind }.eachCount()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindBadge(MatchKind.NEW, "${counts[MatchKind.NEW] ?: 0} new")
                KindBadge(MatchKind.MATCH_EXISTING, "${counts[MatchKind.MATCH_EXISTING] ?: 0} match entered")
                KindBadge(MatchKind.MATCH_SCHEDULED, "${counts[MatchKind.MATCH_SCHEDULED] ?: 0} match scheduled")
                KindBadge(MatchKind.DUPLICATE, "${counts[MatchKind.DUPLICATE] ?: 0} duplicates")
                FilterChip(st.hideDuplicates, { st.hideDuplicates = !st.hideDuplicates }, label = { Text("Hide duplicates") })
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
private fun ImportRowView(row: ImportRow, data: BudgetData, accountId: String?, onChange: (ImportRow) -> Unit) {
    val skip = row.action == ImportAction.SKIP
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).alpha(if (skip) 0.55f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.source.date.short(), Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
            Column(Modifier.weight(1f)) {
                Text(row.payee.ifBlank { "(no payee)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Money.format(row.source.amount), color = amountColor(row.source.amount))
        }
        FlowRow(
            Modifier.padding(start = 72.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val actions = buildList {
                if (row.kind == MatchKind.MATCH_EXISTING || row.kind == MatchKind.MATCH_SCHEDULED) add(ImportAction.MATCH to "Match")
                add(ImportAction.ADD to "Add as new")
                add(ImportAction.SKIP to "Skip")
            }
            actions.forEach { (action, label) ->
                FilterChip(row.action == action, { onChange(row.copy(action = action)) }, label = { Text(label) })
            }
            if (row.action == ImportAction.ADD) {
                Picker(
                    "Category",
                    categoryOptions(data, accountId),
                    Choice.of(row.categoryId, row.transferAccountId),
                    { onChange(row.copy(categoryId = Choice.categoryId(it), transferAccountId = Choice.transferId(it))) },
                    Modifier.width(260.dp),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
