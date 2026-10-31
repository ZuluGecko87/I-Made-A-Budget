package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.PREEXISTING_DEBT
import com.z.imab.model.READY_TO_ASSIGN
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** Light blue accents on cool greys. Amber stays for credit overspending, red for cash overspending. */
val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6F8),
    onPrimaryContainer = Color(0xFF0B3556),
    secondary = Color(0xFF5F6B76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E6EB),
    onSecondaryContainer = Color(0xFF1C252D),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261900),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF1A1F24),
    surface = Color(0xFFF4F7FA),
    onSurface = Color(0xFF1A1F24),
    surfaceVariant = Color(0xFFE3E8ED),
    onSurfaceVariant = Color(0xFF47515A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF2F6),
    surfaceContainer = Color(0xFFE8EDF2),
    surfaceContainerHigh = Color(0xFFE2E8EE),
    surfaceContainerHighest = Color(0xFFDCE3EA),
    outline = Color(0xFF77818B),
    outlineVariant = Color(0xFFC5CDD4),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBF5),
    onPrimary = Color(0xFF003256),
    primaryContainer = Color(0xFF1E4A70),
    onPrimaryContainer = Color(0xFFD3E6F8),
    secondary = Color(0xFFBAC4CE),
    onSecondary = Color(0xFF242E37),
    secondaryContainer = Color(0xFF3E4851),
    onSecondaryContainer = Color(0xFFDDE3E9),
    tertiary = Color(0xFFF3BF48),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF121518),
    onBackground = Color(0xFFE1E5E9),
    surface = Color(0xFF121518),
    onSurface = Color(0xFFE1E5E9),
    surfaceVariant = Color(0xFF2C3237),
    onSurfaceVariant = Color(0xFFC2C9D0),
    surfaceContainerLowest = Color(0xFF0D1013),
    surfaceContainerLow = Color(0xFF171B1F),
    surfaceContainer = Color(0xFF1B1F23),
    surfaceContainerHigh = Color(0xFF23282D),
    surfaceContainerHighest = Color(0xFF2C3237),
    outline = Color(0xFF8C959E),
    outlineVariant = Color(0xFF42494F),
)

object Amounts {
    val positive = Color(0xFF2E7D32)
    val positiveDark = Color(0xFF81C784)
}

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

fun LocalDate.short(): String =
    "${monthNumber.toString().padStart(2, '0')}/${dayOfMonth.toString().padStart(2, '0')}/${(year % 100).toString().padStart(2, '0')}"

@Composable
fun amountColor(cents: Long): Color = when {
    cents > 0 -> if (MaterialTheme.colorScheme.background.luminanceIsDark()) Amounts.positiveDark else Amounts.positive
    cents < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun Color.luminanceIsDark() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/** Rounded "available" pill: green when funded, red when overspent in cash, amber when overspent on credit. */
@Composable
fun AvailablePill(cents: Long, modifier: Modifier = Modifier, creditOverspending: Boolean = false) {
    val (bg, fg) = when {
        cents > 0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        // Overspent on a credit card: becomes card debt instead of coming out of next month.
        cents < 0 && creditOverspending -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        cents < 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            Money.format(cents),
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

private const val LONG_LIST = 10

/** Full list in a dialog, with a search box; section headers stay with the items that match. */
@Composable
private fun <T> PickerDialog(
    title: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    createLabel: String? = null,
    onCreate: ((query: String) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    // Keep a header only if something under it matches.
    val shown = remember(options, q) {
        if (q.isEmpty()) options else {
            val out = mutableListOf<PickerOption<T>>()
            var header: PickerOption<T>? = null
            for (o in options) {
                if (o.isHeader) { header = o; continue }
                if (o.label.lowercase().contains(q)) {
                    header?.let { out += it; header = null }
                    out += o
                }
            }
            out
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                    if (onCreate != null && createLabel != null) {
                        item {
                            Text(
                                "+ $createLabel" + (query.trim().takeIf { it.isNotEmpty() }?.let { " \"$it\"" } ?: "…"),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth().clickable { onCreate(query.trim()) }.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(shown.size) { i ->
                        val opt = shown[i]
                        if (opt.isHeader) {
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        } else {
                            Text(
                                opt.label,
                                fontWeight = if (opt.value == selected) FontWeight.Bold else null,
                                color = if (opt.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (opt.enabled) Modifier.clickable { onSelect(opt.value) } else Modifier)
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                    if (shown.none { !it.isHeader }) {
                        item { Text("Nothing matches.", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

data class PickerOption<T>(val value: T, val label: String, val isHeader: Boolean = false, val enabled: Boolean = true)

/** A dropdown that behaves the same on desktop and phone. */
@Composable
fun <T> Picker(
    label: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    createLabel: String? = null,
    onCreate: ((query: String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { !it.isHeader && it.value == selected }?.label ?: "Choose…"
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        // Long lists open as a dialog with search: a dropdown can run under the phone's navigation
        // bar, leaving its last items out of reach.
        if (open && options.count { !it.isHeader } > LONG_LIST) {
            PickerDialog(
                label, options, selected, onSelect = { onSelect(it); open = false }, onDismiss = { open = false },
                createLabel = createLabel, onCreate = onCreate?.let { create -> { q: String -> open = false; create(q) } },
            )
        }
        DropdownMenu(expanded = open && options.count { !it.isHeader } <= LONG_LIST, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                if (opt.isHeader) {
                    Text(
                        opt.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(opt.label, fontWeight = if (opt.value == selected) FontWeight.Bold else null) },
                        onClick = { onSelect(opt.value); open = false },
                        enabled = opt.enabled,
                    )
                }
            }
            if (onCreate != null && createLabel != null) {
                DropdownMenuItem(
                    text = { Text("+ $createLabel…", color = MaterialTheme.colorScheme.primary) },
                    onClick = { open = false; onCreate("") },
                )
            }
        }
    }
}

/**
 * Category choices as strings: "" = uncategorized, "c:<id>" = category, "t:<id>" = transfer.
 */
object Choice {
    const val NONE = ""
    fun category(id: String) = "c:$id"
    fun transfer(accountId: String) = "t:$accountId"
    fun categoryId(choice: String) = choice.takeIf { it.startsWith("c:") }?.substring(2)
    fun transferId(choice: String) = choice.takeIf { it.startsWith("t:") }?.substring(2)
    fun of(categoryId: String?, transferAccountId: String?) = when {
        transferAccountId != null -> transfer(transferAccountId)
        categoryId != null -> category(categoryId)
        else -> NONE
    }
}

fun categoryOptions(data: BudgetData, excludeAccountId: String?, allowTransfers: Boolean = true): List<PickerOption<String>> {
    val out = mutableListOf(
        PickerOption(Choice.NONE, "Uncategorized"),
        PickerOption("", "Inflow", isHeader = true),
        PickerOption(Choice.category(READY_TO_ASSIGN), "Ready to Assign"),
    )
    if (data.isCreditAccount(excludeAccountId)) {
        out += PickerOption("", "Credit card", isHeader = true)
        out += PickerOption(Choice.category(PREEXISTING_DEBT), "Pre-existing debt (not budgeted)")
    }
    for (g in data.sortedGroups) {
        val cats = data.categoriesIn(g.id)
        if (cats.isEmpty()) continue
        out += PickerOption("", g.name, isHeader = true)
        cats.forEach { out += PickerOption(Choice.category(it.id), it.name) }
    }
    if (allowTransfers) {
        val accounts = data.sortedAccounts.filter { !it.closed }
        if (accounts.any { it.id != excludeAccountId }) {
            out += PickerOption("", "Transfer to/from", isHeader = true)
            // The transaction's own account is shown but can't be picked, so every account is visible.
            accounts.forEach {
                val own = it.id == excludeAccountId
                out += PickerOption(Choice.transfer(it.id), "⇄ ${it.name}" + if (own) " (this account)" else "", enabled = !own)
            }
        }
    }
    return out
}

/**
 * Creates a category (name, group id) and returns its id; provided by the app so any category
 * picker can offer "+ New category". Null where categories can't be added.
 */
val LocalCreateCategory = staticCompositionLocalOf<((String, String) -> String)?> { null }

/** Category (or transfer) picker that can also create a new category on the spot. */
@Composable
fun CategoryPicker(
    label: String,
    data: BudgetData,
    excludeAccountId: String?,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val create = LocalCreateCategory.current
    var newName by remember { mutableStateOf<String?>(null) }
    Picker(
        label, categoryOptions(data, excludeAccountId), selected, onSelect, modifier,
        createLabel = if (create != null && data.groups.isNotEmpty()) "New category" else null,
        onCreate = { newName = it },
    )
    newName?.let { initial ->
        NewCategoryDialog(data, initial, onDismiss = { newName = null }) { name, groupId ->
            create?.let { onSelect(Choice.category(it(name, groupId))) }
            newName = null
        }
    }
}

@Composable
fun NewCategoryDialog(data: BudgetData, initialName: String, onDismiss: () -> Unit, onSave: (name: String, groupId: String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var groupId by remember { mutableStateOf(data.sortedGroups.firstOrNull()?.id.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Picker("Group", data.sortedGroups.map { PickerOption(it.id, it.name) }, groupId, { groupId = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), groupId) }, enabled = name.isNotBlank() && groupId.isNotEmpty()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(date.toString(), color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date)
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) {
            DatePicker(state)
        }
    }
}

@Composable
/**
 * Amount input. Pass [result] (the worked-out amount) for a field that takes arithmetic such as
 * "120 + 5"; it's shown under the field while the input is a calculation.
 */
fun MoneyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    result: Long? = null,
) {
    val showResult = result != null && Money.isExpression(value)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (showResult) ({ Text("= ${Money.format(result!!)}") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "Name",
    confirm: String = "Save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String = "Delete", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            trailing()
        }
        HorizontalDivider()
    }
}

/**
 * Payee chooser: a button that opens a pop-up with a search box, so the keyboard doesn't cover the
 * suggestions. Pick a payee you've used, or add whatever you typed as a new one.
 */
@Composable
fun PayeeField(value: String, onChange: (String) -> Unit, data: BudgetData, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Payee", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value.ifBlank { "Choose or add a payee" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Icon(Icons.Default.ArrowDropDown, null)
    }
    if (open) PayeeDialog(value, data, onPick = { onChange(it); open = false }, onDismiss = { open = false })
}

@Composable
private fun PayeeDialog(current: String, data: BudgetData, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(current) }
    val typed = query.trim()
    val matches = remember(data, typed) { data.searchPayees(typed, limit = 100) }
    val exact = matches.firstOrNull { it.equals(typed, ignoreCase = true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payee") },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search or type a new payee") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
                    // Enter picks the matching payee, or adds what was typed.
                    keyboardActions = KeyboardActions(onDone = { if (typed.isNotEmpty()) onPick(exact ?: typed) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp)) {
                    if (typed.isNotEmpty() && exact == null) {
                        item {
                            Text(
                                "+ Add \"$typed\" as a new payee",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(typed) }.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(matches.size) { i ->
                        val p = matches[i]
                        Text(
                            p,
                            fontWeight = if (p == current) FontWeight.Bold else null,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp),
                        )
                    }
                    if (matches.isEmpty() && typed.isEmpty()) {
                        item { Text("No payees yet. Type one above.", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                if (current.isNotBlank()) TextButton(onClick = { onPick("") }) { Text("No payee") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
