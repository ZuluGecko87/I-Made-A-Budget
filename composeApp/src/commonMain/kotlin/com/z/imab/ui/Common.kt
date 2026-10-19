package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1CF),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF3D5A80),
    secondaryContainer = Color(0xFFD5E3FF),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFDEA6),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF98D5B4),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF115238),
    onPrimaryContainer = Color(0xFFB4F1CF),
    secondary = Color(0xFFA9C7FF),
    secondaryContainer = Color(0xFF244368),
    tertiary = Color(0xFFF3BF48),
    tertiaryContainer = Color(0xFF5C4200),
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

data class PickerOption<T>(val value: T, val label: String, val isHeader: Boolean = false)

/** A dropdown that behaves the same on desktop and phone. */
@Composable
fun <T> Picker(
    label: String,
    options: List<PickerOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
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
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
                    )
                }
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
        val accounts = data.sortedAccounts.filter { it.id != excludeAccountId && !it.closed }
        if (accounts.isNotEmpty()) {
            out += PickerOption("", "Transfer to/from", isHeader = true)
            accounts.forEach { out += PickerOption(Choice.transfer(it.id), "⇄ ${it.name}") }
        }
    }
    return out
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
fun MoneyField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
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
