package com.z.imab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.z.imab.budget.AmountRow
import com.z.imab.budget.RecurringSource
import com.z.imab.budget.ReportPeriod
import com.z.imab.budget.Reports
import com.z.imab.data.BudgetStore
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.YearMonth
import kotlin.math.roundToInt

/** Chart colors (validated against the app's light and dark backgrounds; one series per chart). */
private object ChartColors {
    val seriesLight = Color(0xFF2A78D6)
    val seriesDark = Color(0xFF3987E5)
    val good = Color(0xFF0CA30C)
    val warning = Color(0xFFFAB219)
    val critical = Color(0xFFD03B3B)
}

@Composable
private fun seriesColor() = if (MaterialTheme.colorScheme.background.let { it.red * 0.299f + it.green * 0.587f + it.blue * 0.114f } < 0.5f) ChartColors.seriesDark else ChartColors.seriesLight

private enum class ReportTab(val label: String) { SPENDING("Spending"), INCOME("Income"), RECURRING("Recurring payments"), DTI("Debt to income") }

private enum class PeriodMode(val label: String) { MONTH("Month"), YEAR("Year"), LAST12("Last 12 months") }

@Composable
fun ReportsScreen(store: BudgetStore) {
    val data by store.data.collectAsState()
    var tab by remember { mutableStateOf(ReportTab.SPENDING) }
    var mode by remember { mutableStateOf(PeriodMode.MONTH) }
    var anchor by remember { mutableStateOf(YearMonth.of(today())) }
    val period = when (mode) {
        PeriodMode.MONTH -> ReportPeriod.month(anchor)
        PeriodMode.YEAR -> ReportPeriod.year(anchor.year)
        PeriodMode.LAST12 -> ReportPeriod.last12(anchor)
    }
    val label = when (mode) {
        PeriodMode.MONTH -> anchor.label
        PeriodMode.YEAR -> anchor.year.toString()
        PeriodMode.LAST12 -> "${shortMonth(period.from)} – ${shortMonth(period.to)}"
    }
    fun step(by: Int) {
        anchor = when (mode) {
            PeriodMode.YEAR -> anchor + 12 * by
            else -> anchor + by
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Reports", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            ReportTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        // One time control above everything it scopes.
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PeriodMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, label = { Text(m.label) }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { step(-1) }) { Text("‹") }
                Text(label, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { step(1) }) { Text("›") }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                ReportTab.SPENDING -> SpendingReportView(data, period)
                ReportTab.INCOME -> IncomeReportView(data, period)
                ReportTab.RECURRING -> RecurringReportView(data, period)
                ReportTab.DTI -> DebtToIncomeView(data, period)
            }
        }
    }
}

private fun shortMonth(m: YearMonth) = "${m.label.take(3)} ${m.year}"

/** "$2,594.99" for tiles; "$2.6K" for axis labels. */
private fun compact(cents: Long): String {
    val dollars = cents / 100.0
    return when {
        kotlin.math.abs(dollars) >= 1_000_000 -> "$" + ((dollars / 100_000).roundToInt() / 10.0) + "M"
        kotlin.math.abs(dollars) >= 1_000 -> "$" + ((dollars / 100).roundToInt() / 10.0) + "K"
        else -> "$" + dollars.roundToInt()
    }
}

// ------------------------------------------------------------------ building blocks

/** Stat tile: label, value, optional change vs the previous period (colored by whether up is good). */
@Composable
private fun StatTile(label: String, value: String, delta: Long? = null, deltaBase: Long = 0, upIsGood: Boolean = true, modifier: Modifier = Modifier) {
    Card(modifier.widthIn(min = 150.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (delta != null) {
                val good = if (delta == 0L) null else (delta > 0) == upIsGood
                val pct = if (deltaBase != 0L) " (${(delta * 100.0 / deltaBase).roundToInt().let { if (it > 0) "+$it" else "$it" }}%)" else ""
                Text(
                    (if (delta > 0) "▲ " else if (delta < 0) "▼ " else "") + Money.format(kotlin.math.abs(delta)) + pct + " vs previous",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (good) {
                        true -> if (isSystemInDarkTheme()) ChartColors.good else Color(0xFF006300)
                        false -> ChartColors.critical
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ChartTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A bar with a 4px rounded data end and a square end at the baseline (left). */
private fun DrawScope.hBar(width: Float, color: Color) {
    if (width <= 0f) return
    val r = minOf(4.dp.toPx(), width)
    val path = Path().apply {
        addRoundRect(
            RoundRect(0f, 0f, width, size.height, topLeftCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero,
                topRightCornerRadius = CornerRadius(r, r), bottomRightCornerRadius = CornerRadius(r, r)),
        )
    }
    drawPath(path, color)
}

/**
 * Ranked horizontal bars: label, bar scaled to the largest, value at the end. Shows [limit] rows
 * with a "show all" toggle; the list itself doubles as the table view.
 */
@Composable
private fun RankedBars(rows: List<AmountRow>, total: Long, limit: Int = 10, groupLabel: Boolean = false) {
    var all by remember(rows) { mutableStateOf(false) }
    val shown = if (all) rows else rows.take(limit)
    val max = rows.maxOfOrNull { it.amount }?.coerceAtLeast(1) ?: 1
    val color = seriesColor()
    BoxWithConstraints {
        val labelWidth = if (maxWidth < 500.dp) 120.dp else 200.dp
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(labelWidth).padding(end = 8.dp)) {
                        Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (groupLabel && r.group.isNotEmpty()) {
                            Text(r.group, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Canvas(Modifier.weight(1f).height(14.dp)) {
                        hBar(size.width * (maxOf(0, r.amount).toFloat() / max), color)
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        Text(Money.format(r.amount), style = MaterialTheme.typography.bodyMedium)
                        if (total > 0) Text("${(r.amount * 100.0 / total).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (rows.size > limit) {
                TextButton(onClick = { all = !all }) { Text(if (all) "Show fewer" else "Show all ${rows.size}") }
            }
        }
    }
}

/**
 * Monthly columns: one series, columns capped at 24dp with a rounded top, a baseline and one
 * gridline at the top value. Hover or tap a month to read it; the latest month is selected
 * first. A table of the same values is one tap away.
 */
@Composable
private fun MonthColumns(points: List<Pair<YearMonth, Long>>, valueName: String) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    var table by remember { mutableStateOf(false) }
    val color = seriesColor()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val max = points.maxOf { it.second }.coerceAtLeast(1)
    val (m, v) = points[selected.coerceIn(points.indices)]

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Readout for the hovered / tapped month: value first, then the label.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Money.format(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("$valueName · ${m.label}", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        Row {
            Text(compact(max), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(44.dp))
            Spacer(Modifier.weight(1f))
        }
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).pointerInput(points) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                            val x = e.changes.first().position.x
                            val slot = size.width.toFloat() / points.size
                            selected = (x / slot).toInt().coerceIn(points.indices)
                        }
                    }
                }
            },
        ) {
            val slot = size.width / points.size
            val w = minOf(24.dp.toPx(), slot * 0.6f)
            // Gridline at the top value, baseline at zero: solid hairlines.
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            points.forEachIndexed { i, (_, value) ->
                val h = size.height * (maxOf(0, value).toFloat() / max)
                if (h <= 0f) return@forEachIndexed
                val left = slot * i + (slot - w) / 2
                val r = minOf(4.dp.toPx(), h)
                val c = if (i == selected) color else color.copy(alpha = 0.55f)
                drawPath(
                    Path().apply {
                        addRoundRect(
                            RoundRect(left, size.height - h, left + w, size.height,
                                topLeftCornerRadius = CornerRadius(r, r), topRightCornerRadius = CornerRadius(r, r),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero),
                        )
                    },
                    c,
                )
            }
        }
        // Month labels under each column (every other one when there's no room).
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val every = if (maxWidth / points.size < 32.dp) 2 else 1
            Row(Modifier.fillMaxWidth()) {
                points.forEachIndexed { i, (month, _) ->
                    Text(
                        if (i % every == 0) month.label.take(3) else "",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        TextButton(onClick = { table = !table }) { Text(if (table) "Hide table" else "Show as table") }
        if (table) {
            points.forEach { (month, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(month.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(Money.format(value), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ reports

@Composable
private fun SpendingReportView(data: BudgetData, period: ReportPeriod) {
    val r = remember(data, period) { Reports.spending(data, period) }
    var byGroup by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Total spent", Money.format(r.total), delta = (r.total - r.previousTotal).takeIf { r.previousTotal != 0L }, deltaBase = r.previousTotal, upIsGood = false)
        if (period.monthCount > 1) StatTile("Average per month", Money.format(r.total / period.monthCount))
        r.byCategory.firstOrNull()?.let { StatTile("Biggest category", it.name, modifier = Modifier) }
    }
    if (r.total == 0L) {
        Text("No spending in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (period.monthCount > 1) {
        ChartTitle("Spending by month")
        MonthColumns(r.byMonth, "Spent")
    }
    ChartTitle(if (byGroup) "Spending by category group" else "Spending by category", "Net of refunds; transfers and card payments aren't spending.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!byGroup, { byGroup = false }, label = { Text("Categories") })
        FilterChip(byGroup, { byGroup = true }, label = { Text("Groups") })
    }
    RankedBars(if (byGroup) r.byGroup else r.byCategory, r.total, groupLabel = !byGroup)
}

@Composable
private fun IncomeReportView(data: BudgetData, period: ReportPeriod) {
    val r = remember(data, period) { Reports.income(data, period) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Total income", Money.format(r.total), delta = (r.total - r.previousTotal).takeIf { r.previousTotal != 0L }, deltaBase = r.previousTotal, upIsGood = true)
        if (period.monthCount > 1) StatTile("Average per month", Money.format(r.total / period.monthCount))
        val spent = remember(data, period) { Reports.spending(data, period).total }
        StatTile("Left after spending", Money.format(r.total - spent))
    }
    if (r.total == 0L) {
        Text(
            "No income in this period. Income is money categorized as Ready to Assign (starting balances don't count).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (period.monthCount > 1) {
        ChartTitle("Income by month")
        MonthColumns(r.byMonth, "Income")
    }
    ChartTitle("Income by source")
    RankedBars(r.bySource, r.total)
}

@Composable
private fun RecurringReportView(data: BudgetData, period: ReportPeriod) {
    val r = remember(data, period) { Reports.recurring(data, period.to) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Per month", Money.format(r.monthlyTotal))
        StatTile("Per year", Money.format(r.yearlyTotal))
        StatTile("Recurring payments", r.items.size.toString())
    }
    Text(
        "Scheduled payments, plus payees you paid in at least 3 of the 6 months up to ${period.to.label} that aren't scheduled. " +
            "Each is converted to a monthly cost.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (r.items.isEmpty()) {
        Text("No recurring payments found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // A table: there are usually more items than a chart can carry well.
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("PAYMENT", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("EACH", Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            Text("PER MONTH", Modifier.width(110.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        HorizontalDivider()
        r.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.frequency} · ${item.categoryOrAccount} · ${if (item.source == RecurringSource.SCHEDULED) "scheduled" else "found in transactions"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Text(Money.format(item.typical), Modifier.width(96.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
                Text(Money.format(item.monthly), Modifier.width(110.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun DebtToIncomeView(data: BudgetData, period: ReportPeriod) {
    val r = remember(data, period) { Reports.debtToIncome(data, period) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Debt payments / month", Money.format(r.monthlyDebt))
        StatTile("Income / month", Money.format(r.monthlyIncome))
        StatTile("Total owed now", Money.format(r.totalOwed))
    }
    val ratio = r.ratio
    if (ratio == null) {
        Text("No income recorded in this period, so there's no ratio to show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        RatioMeter("Debt-to-income ratio", ratio)
        r.housingRatio?.let { RatioMeter("Housing (mortgage) ratio", it, healthy = 0.28, high = 0.36) }
    }
    Text(
        "Rule of thumb: 36% or less is healthy, over 43% is high (the usual limit for a mortgage). " +
            "This uses the income deposited in your accounts (take-home pay), so it reads higher than a lender's figure based on gross pay.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (r.lines.isNotEmpty()) {
        ChartTitle("Debt payments per month")
        r.lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(l.name)
                    Text(l.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.format(l.monthly), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    } else {
        Text("No payments to cards or loans in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Meter: the fill carries severity (status color), the track is a lighter step of the same color,
 * with ticks at the healthy and high limits. The status is also spelled out with an icon.
 */
@Composable
private fun RatioMeter(title: String, ratio: Double, healthy: Double = 0.36, high: Double = 0.43) {
    val (word, icon, color) = when {
        ratio <= healthy -> Triple("Healthy", "✓", ChartColors.good)
        ratio <= high -> Triple("Getting high", "!", ChartColors.warning)
        else -> Triple("High", "▲", ChartColors.critical)
    }
    val scale = maxOf(0.6, ratio * 1.1)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tick = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(ratio * 100).roundToInt()}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text("$icon $word", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 5.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val r = 4.dp.toPx()
            drawRoundRect(color.copy(alpha = 0.2f), size = size, cornerRadius = CornerRadius(r, r))
            drawRoundRect(color, size = Size(size.width * (ratio / scale).toFloat().coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(r, r))
            listOf(healthy, high).forEach { t ->
                val x = size.width * (t / scale).toFloat()
                drawLine(tick, Offset(x, -2f), Offset(x, size.height + 2f), 1.5f)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth
            listOf(healthy, high).forEach { t ->
                Text(
                    "${(t * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.padding(start = (w * (t / scale).toFloat() - 12.dp).coerceAtLeast(0.dp)),
                )
            }
        }
    }
}
