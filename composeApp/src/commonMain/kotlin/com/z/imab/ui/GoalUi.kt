package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.budget.CategoryMonth
import com.z.imab.budget.GoalMath
import com.z.imab.budget.GoalStatus
import com.z.imab.model.Category
import com.z.imab.model.Money
import com.z.imab.model.CategoryGoal
import com.z.imab.model.GoalType
import androidx.compose.material3.FilterChip
import com.z.imab.model.YearMonth
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.math.roundToInt

private fun shortMonth(m: YearMonth) = "${m.label.take(3)} ${m.year}"

/** One-line summary, e.g. "45% of $5,000 · on track for Mar 2027". */
fun goalHeadline(s: GoalStatus): String {
    if (s.isMonthly) {
        val spent = if (s.spent > 0) " · spent ${Money.format(s.spent)}" else ""
        val resets = if (s.goal.rollover) "" else " · resets monthly"
        return if (s.reached) {
            "Funded ${Money.format(s.goal.target)}/mo$spent$resets"
        } else {
            "${Money.format(s.saved)} of ${Money.format(s.goal.target)} allocated · ${Money.format(s.remaining)} to go$spent$resets"
        }
    }
    val pct = "${(s.progress * 100).roundToInt()}% of ${Money.format(s.goal.target)}"
    val target = s.goal.targetDate?.let { YearMonth.of(it) }
    return when {
        s.reached -> "Goal reached · ${Money.format(s.saved)} saved"
        s.overdue -> "$pct · target date passed, ${Money.format(s.remaining)} to go"
        target != null -> "$pct by ${shortMonth(target)} · ${Money.format(s.assignThisMonth)}/mo" +
            if (s.onTrack == true) " · on track" else ""
        s.etaAtPlan != null -> "$pct · ~${shortMonth(s.etaAtPlan)} at ${Money.format(s.goal.monthly!!)}/mo"
        s.etaAtPace != null -> "$pct · ~${shortMonth(s.etaAtPace)} at recent pace"
        else -> "$pct · no recent savings"
    }
}

/** Thin progress bar with the headline, shown under a category name. */
@Composable
fun GoalProgress(s: GoalStatus, wide: Boolean) {
    val behind = s.overdue || s.onTrack == false
    val color = when {
        s.reached -> MaterialTheme.colorScheme.primary
        behind -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        LinearProgressIndicator(
            progress = { s.progress },
            modifier = Modifier.fillMaxWidth(if (wide) 0.6f else 1f).height(5.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Text(
            goalHeadline(s),
            style = MaterialTheme.typography.labelSmall,
            color = if (behind) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GoalDialog(
    category: Category,
    month: YearMonth,
    history: List<CategoryMonth>,
    onSave: (CategoryGoal?) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = category.goal
    var targetText by remember { mutableStateOf(existing?.target?.let(Money::toInput).orEmpty()) }
    var hasDate by remember { mutableStateOf(existing?.targetDate != null) }
    var date by remember { mutableStateOf(existing?.targetDate ?: today().plus(1, DateTimeUnit.YEAR)) }
    var monthlyText by remember { mutableStateOf(existing?.monthly?.let(Money::toInput).orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: GoalType.SAVINGS) }
    var rollover by remember { mutableStateOf(existing?.rollover ?: true) }
    val isMonthly = type == GoalType.MONTHLY_SPENDING

    val target = Money.parse(targetText)?.takeIf { it > 0 }
    val monthly = if (monthlyText.isBlank()) null else Money.parse(monthlyText)
    val monthlyValid = isMonthly || monthlyText.isBlank() || (monthly != null && monthly > 0)
    val draft = target?.let {
        if (isMonthly) CategoryGoal(it, type = GoalType.MONTHLY_SPENDING, rollover = rollover)
        else CategoryGoal(it, date.takeIf { hasDate }, monthly?.takeIf { m -> m > 0 })
    }
    val preview = draft?.let { GoalMath.status(it, month, history) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Goal · ${category.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!isMonthly, { type = GoalType.SAVINGS }, label = { Text("Savings goal") })
                    FilterChip(isMonthly, { type = GoalType.MONTHLY_SPENDING }, label = { Text("Monthly spending") })
                }
                Text(
                    if (isMonthly) {
                        "Money to have available every month for things like rent or groceries. What you assign can be spent from this category."
                    } else {
                        "Save up a total over time. Money stays in the category from month to month until you spend it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyField(
                    if (isMonthly) "Amount needed each month" else "Target amount",
                    targetText, { targetText = it }, Modifier.fillMaxWidth(), isError = targetText.isNotBlank() && target == null,
                )
                if (isMonthly) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rollover, { rollover = it })
                        Column {
                            Text("Roll over month to month")
                            Text(
                                if (rollover) "Unspent money carries into next month and counts toward it."
                                else "Unspent money goes back to Ready to Assign at the end of each month.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(hasDate, { hasDate = it })
                        Text("Reach it by a date")
                    }
                    if (hasDate) DateField("Target date", date, { date = it }, Modifier.fillMaxWidth())
                    MoneyField(
                        "Planned monthly amount (optional)",
                        monthlyText,
                        { monthlyText = it },
                        Modifier.fillMaxWidth(),
                        isError = !monthlyValid,
                    )
                }
                preview?.let { if (it.isMonthly) MonthlyPreview(it, month) else GoalPreview(it, month) }
            }
        },
        confirmButton = {
            TextButton(enabled = draft != null && monthlyValid, onClick = { onSave(draft) }) { Text("Save goal") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove goal", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Progress and time-to-goal estimates, as of the month being viewed. */
@Composable
private fun GoalPreview(s: GoalStatus, month: YearMonth) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            Text(
                "${Money.format(s.saved)} saved of ${Money.format(s.goal.target)} (${(s.progress * 100).roundToInt()}%)",
                fontWeight = FontWeight.SemiBold,
            )
            if (s.reached) {
                Text("Goal reached!")
                return@Column
            }
            Text("${Money.format(s.remaining)} to go.", style = MaterialTheme.typography.bodySmall)

            val target = s.goal.targetDate?.let { YearMonth.of(it) }
            if (target != null) {
                Text(
                    when {
                        s.overdue -> "The target date has passed. Assign ${Money.format(s.assignThisMonth)} this month to finish."
                        else -> "To reach it by ${target.label}: ${Money.format(s.assignThisMonth)} a month for ${s.monthsLeft} month(s)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (s.onTrack == true) "On track for ${month.label}." else "Not yet assigned enough in ${month.label}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.onTrack == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
            }
            s.etaAtPlan?.let {
                Text("At ${Money.format(s.goal.monthly!!)} a month: reached around ${it.label}.", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (s.etaAtPace != null) {
                    "At your recent pace (${Money.format(s.recentPace)} a month, last 3 months): around ${s.etaAtPace.label}."
                } else {
                    "No savings in this category over the last 3 months, so there's no estimate from your pace yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthlyPreview(s: GoalStatus, month: YearMonth) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            Text("${Money.format(s.saved)} of ${Money.format(s.goal.target)} allocated for ${month.label}", fontWeight = FontWeight.SemiBold)
            Text(
                if (s.reached) "Fully funded for this month." else "Assign ${Money.format(s.assignThisMonth)} in total this month to fund it.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.spent > 0) Text("Spent so far: ${Money.format(s.spent)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
