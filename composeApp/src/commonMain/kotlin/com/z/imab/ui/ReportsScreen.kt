package com.z.imab.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.state.ToggleableState
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
internal object ChartColors {
    val seriesLight = Color(0xFF2A78D6)
    val seriesDark = Color(0xFF3987E5)
    val good = Color(0xFF0CA30C)
    val warning = Color(0xFFFAB219)
    val critical = Color(0xFFD03B3B)
}

@Composable
internal fun seriesColor() = if (MaterialTheme.colorScheme.background.let { it.red * 0.299f + it.green * 0.587f + it.blue * 0.114f } < 0.5f) ChartColors.seriesDark else ChartColors.seriesLight

private enum class ReportTab(val label: String) {
    SPENDING("Spending"), INCOME("Income"), NET_WORTH("Net worth"), RECURRING("Recurring payments"), DTI("Debt to income"),
}

private enum class PeriodMode(val label: String) {
    MONTH("Month"), LAST3("Last 3 months"), YTD("YTD"), LAST12("Last 12 months"), CUSTOM("Custom"),
}

@Composable
fun ReportsScreen(store: BudgetStore) {
    val data by store.data.collectAsState()
    var tab by remember { mutableStateOf(ReportTab.SPENDING) }
    // Kept while switching tabs and periods: which categories the spending report covers (null =
    // all), and bars or a pie for the breakdowns.
    var spendingFilter by remember { mutableStateOf<Set<String>?>(null) }
    var pie by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(PeriodMode.MONTH) }
    val thisMonth = YearMonth.of(today())
    var anchor by remember { mutableStateOf(thisMonth) }
    // A custom range can go back to the month the budget started.
    val start = remember(data) { minOf(ReportPeriod.budgetStart(data) ?: thisMonth, thisMonth) }
    var customFrom by remember { mutableStateOf<YearMonth?>(null) }
    var customTo by remember { mutableStateOf<YearMonth?>(null) }
    val period = when (mode) {
        PeriodMode.MONTH -> ReportPeriod.month(anchor)
        PeriodMode.LAST3 -> ReportPeriod.last3(anchor)
        PeriodMode.YTD -> ReportPeriod.yearToDate(thisMonth)
        PeriodMode.LAST12 -> ReportPeriod.last12(anchor)
        PeriodMode.CUSTOM -> ReportPeriod.between(customFrom ?: start, customTo ?: thisMonth)
    }
    // Short enough to stay on one line beside the menu on a phone.
    val label = when {
        mode == PeriodMode.MONTH -> shortMonth(anchor)
        period.from == period.to -> shortMonth(period.to)
        period.from.year == period.to.year -> "${period.from.label.take(3)} – ${shortMonth(period.to)}"
        else -> "${period.from.label.take(3)} ${period.from.year % 100} – ${period.to.label.take(3)} ${period.to.year % 100}"
    }
    // Year to date is always this year's, so only the other kinds step through time.
    val steps = mode == PeriodMode.MONTH || mode == PeriodMode.LAST3 || mode == PeriodMode.LAST12
    fun step(by: Int) { anchor += by }

    Column(Modifier.fillMaxSize()) {
        Text("Reports", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            ReportTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        // One time control above everything it scopes: what kind of period, then which one. The
        // menus have fixed widths on every platform; if text is set large, the ‹ period › control
        // moves under the menu rather than squeezing it.
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Picker(
                "Period", PeriodMode.entries.map { PickerOption(it, it.label) }, mode, { mode = it }, Modifier.width(PERIOD_MENU_WIDTH),
                contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            )
            when {
                steps -> Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { step(-1) }, Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier") }
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                    IconButton(onClick = { step(1) }, Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later") }
                }
                mode == PeriodMode.YTD -> Text(
                    label,
                    Modifier.align(Alignment.CenterVertically).padding(start = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (mode == PeriodMode.CUSTOM) {
            // Newest first, back to the month the budget started.
            val months = generateSequence(maxOf(thisMonth, period.to)) { it - 1 }.takeWhile { it >= minOf(start, period.from) }
                .map { PickerOption(it, shortMonth(it)) }.toList()
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Picker("From", months, period.from, { customFrom = it; if (it > period.to) customTo = it }, Modifier.width(MONTH_MENU_WIDTH))
                Picker("To", months, period.to, { customTo = it; if (it < period.from) customFrom = it }, Modifier.width(MONTH_MENU_WIDTH))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${period.monthCount} month${if (period.monthCount == 1) "" else "s"}",
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { customFrom = start; customTo = thisMonth }) { Text("Since the budget started") }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                ReportTab.SPENDING -> SpendingReportView(data, period, spendingFilter, { spendingFilter = it }, pie, { pie = it })
                ReportTab.INCOME -> IncomeReportView(data, period, pie, { pie = it })
                ReportTab.NET_WORTH -> NetWorthReportView(data, period)
                ReportTab.RECURRING -> RecurringReportView(data, period)
                ReportTab.DTI -> DebtToIncomeView(data, period)
            }
        }
    }
}

private fun shortMonth(m: YearMonth) = "${m.label.take(3)} ${m.year}"

/**
 * Fits "Last 12 months", and leaves room beside it for the ‹ Sep – Nov 2026 › control on a 384dp
 * phone. The same on every platform and for every choice.
 */
private val PERIOD_MENU_WIDTH = 156.dp
private val MONTH_MENU_WIDTH = 150.dp

/** "$2,594.99" for tiles; "$2.6K" for axis labels. */
internal fun compact(cents: Long): String {
    if (cents < 0) return "-" + compact(-cents)
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
internal fun StatTile(
    label: String,
    value: String,
    delta: Long? = null,
    deltaBase: Long = 0,
    upIsGood: Boolean = true,
    modifier: Modifier = Modifier,
    deltaSuffix: String = "vs previous",
) {
    Card(modifier.widthIn(min = 150.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (delta != null) {
                val good = if (delta == 0L) null else (delta > 0) == upIsGood
                val pct = if (deltaBase != 0L) " (${(delta * 100.0 / deltaBase).roundToInt().let { if (it > 0) "+$it" else "$it" }}%)" else ""
                Text(
                    (if (delta > 0) "▲ " else if (delta < 0) "▼ " else "") + Money.format(kotlin.math.abs(delta)) + pct + " " + deltaSuffix,
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
internal fun ChartTitle(title: String, subtitle: String? = null) {
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

/** Pie slice colors: the first five of the validated categorical order, then a neutral "Other". */
internal object PieColors {
    val light = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4))
    val dark = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181))
    const val SLICES = 5
}

/**
 * Share of the total as a donut: the five largest, then the rest as "Other" (more slices can't be
 * told apart). [colorOrder] fixes each id's color, so filtering doesn't repaint what's left. The
 * legend beside it names every slice with its amount and share; tap a slice or a legend row to
 * read it in the middle. Only positive amounts are shares, so net refunds are left out. A single
 * category is drawn as the whole ring (early in a month that's often all there is).
 */
@Composable
private fun PieChart(rows: List<AmountRow>, colorOrder: List<String>, groupLabel: Boolean = false) {
    val positive = rows.filter { it.amount > 0 }.sortedByDescending { it.amount }
    if (positive.isEmpty()) {
        Text("Nothing to show as a pie: refunds outweigh spending here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val dark = MaterialTheme.colorScheme.background.let { it.red * 0.299f + it.green * 0.587f + it.blue * 0.114f } < 0.5f
    val palette = if (dark) PieColors.dark else PieColors.light
    val otherColor = MaterialTheme.colorScheme.outline
    val top = positive.take(PieColors.SLICES)
    val rest = positive.drop(PieColors.SLICES)
    // Fixed slots for ids that hold one in the unfiltered order; the rest take the free ones in order.
    val fixed = colorOrder.take(PieColors.SLICES).withIndex().associate { (i, id) -> id to i }
    val free = (0 until PieColors.SLICES).filter { it !in top.mapNotNull { r -> fixed[r.id] } }.iterator()
    val colors = top.map { r -> palette[fixed[r.id] ?: free.next()] } + if (rest.isEmpty()) emptyList() else listOf(otherColor)
    val slices = top + if (rest.isEmpty()) emptyList() else listOf(AmountRow("other", "Other (${rest.size})", "", rest.sumOf { it.amount }))
    val total = slices.sumOf { it.amount }.toFloat()
    var selected by remember(rows) { mutableStateOf<Int?>(null) }

    @Composable
    fun Donut(modifier: Modifier) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(slices) {
                    detectTapGestures { pos ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val d = pos - c
                        val radius = minOf(size.width, size.height) / 2f
                        if (d.getDistance() > radius || d.getDistance() < radius * 0.55f) { selected = null; return@detectTapGestures }
                        // Angle from 12 o'clock, clockwise.
                        val deg = ((kotlin.math.atan2(d.y, d.x) * 180 / kotlin.math.PI).toFloat() + 90f + 360f) % 360f
                        var acc = 0f
                        selected = slices.indices.firstOrNull { i -> acc += slices[i].amount / total * 360f; deg < acc }
                    }
                },
            ) {
                val stroke = minOf(size.width, size.height) * 0.2f
                val d = minOf(size.width, size.height) - stroke
                val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                // A 2px gap of background between slices (none when one slice is the whole ring).
                val gap = if (slices.size == 1) 0f else (2f / (d / 2f) * 180f / kotlin.math.PI.toFloat())
                var start = -90f
                slices.forEachIndexed { i, sl ->
                    val sweep = sl.amount / total * 360f
                    val c = if (selected == null || selected == i) colors[i] else colors[i].copy(alpha = 0.35f)
                    drawArc(c, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, topLeft, Size(d, d), style = Stroke(stroke))
                    start += sweep
                }
            }
            val sel = selected?.let { slices[it] }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Money.format(sel?.amount ?: total.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    sel?.let { "${(it.amount * 100f / total).roundToInt()}% · ${it.name}" } ?: "Total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
            }
        }
    }

    @Composable
    fun Legend(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            slices.forEachIndexed { i, sl ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (selected == i) null else i }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(colors[i], RoundedCornerShape(3.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            sl.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected == i) FontWeight.SemiBold else null,
                        )
                        if (groupLabel && sl.group.isNotEmpty()) {
                            Text(sl.group, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.format(sl.amount), style = MaterialTheme.typography.bodyMedium)
                        Text("${(sl.amount * 100f / total).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    BoxWithConstraints {
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Donut(Modifier.fillMaxWidth().height(200.dp))
                Legend(Modifier.fillMaxWidth())
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Donut(Modifier.size(220.dp))
                Legend(Modifier.weight(1f))
            }
        }
    }
    if (rest.isNotEmpty()) {
        Text(
            "Other: " + rest.joinToString(", ") { "${it.name} ${Money.format(it.amount)}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
private fun MonthColumns(
    points: List<Pair<YearMonth, Long>>,
    valueName: String,
    /** An extra line for the selected month, under its value. */
    detail: ((Int) -> String)? = null,
) {
    if (points.isEmpty()) return
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    var table by remember { mutableStateOf(false) }
    val color = seriesColor()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Columns grow up from zero, or down from it for negative values.
    val max = points.maxOf { it.second }.coerceAtLeast(0)
    val min = points.minOf { it.second }.coerceAtMost(0)
    val span = (max - min).coerceAtLeast(1)
    val (m, v) = points[selected.coerceIn(points.indices)]

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Readout for the hovered / tapped month: value first, then the label.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Money.format(v), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("$valueName · ${m.label}", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        detail?.let { Text(it(selected.coerceIn(points.indices)), style = MaterialTheme.typography.bodySmall, color = muted) }
        Row {
            Text(compact(if (max > 0 || min == 0L) max else 0), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(44.dp))
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
            val zero = size.height * (max.toFloat() / span)
            // Gridlines at the top and bottom values, baseline at zero: solid hairlines.
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            drawLine(grid, Offset(0f, zero), Offset(size.width, zero), 1f)
            points.forEachIndexed { i, (_, value) ->
                val h = size.height * (kotlin.math.abs(value).toFloat() / span)
                if (h <= 0f) return@forEachIndexed
                val left = slot * i + (slot - w) / 2
                val r = CornerRadius(minOf(4.dp.toPx(), h), minOf(4.dp.toPx(), h))
                val c = if (i == selected) color else color.copy(alpha = 0.55f)
                // Rounded at the data end, square at zero.
                val rect = if (value > 0) {
                    RoundRect(left, zero - h, left + w, zero, topLeftCornerRadius = r, topRightCornerRadius = r,
                        bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero)
                } else {
                    RoundRect(left, zero, left + w, zero + h, topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = CornerRadius.Zero,
                        bottomLeftCornerRadius = r, bottomRightCornerRadius = r)
                }
                drawPath(Path().apply { addRoundRect(rect) }, c)
            }
        }
        if (min < 0) Text(compact(min), style = MaterialTheme.typography.labelSmall, color = muted)
        // Month labels under the columns: as many as fit, each starting under its column. Over more
        // than a year they carry the year too ("Jan 26").
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val withYear = points.size > 12
            val room = if (withYear) 48.dp else 32.dp
            val every = kotlin.math.ceil(points.size * room.value / maxWidth.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
            Row(Modifier.fillMaxWidth()) {
                points.chunked(every).forEachIndexed { c, chunk ->
                    val month = chunk.first().first
                    Text(
                        if (withYear) "${month.label.take(3)} ${month.year % 100}" else month.label.take(3),
                        Modifier.weight(chunk.size.toFloat()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected in c * every until c * every + chunk.size) MaterialTheme.colorScheme.onSurface else muted,
                        textAlign = if (every == 1) TextAlign.Center else TextAlign.Start,
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
private fun SpendingReportView(
    data: BudgetData,
    period: ReportPeriod,
    filter: Set<String>?,
    onFilter: (Set<String>?) -> Unit,
    pie: Boolean,
    onPie: (Boolean) -> Unit,
) {
    // Categories that no longer exist drop out of the filter; none left means all.
    val only = filter?.filter { it in data.categories }?.toSet()?.takeIf { it.isNotEmpty() }
    val everything = remember(data, period) { Reports.spending(data, period) }
    val r = remember(data, period, only) { if (only == null) everything else Reports.spending(data, period, only) }
    var byGroup by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { choosing = true }) {
            Text(
                when {
                    only == null -> "All categories"
                    only.size == 1 -> data.categoryName(only.first())
                    else -> "${only.size} categories"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        if (only != null) TextButton(onClick = { onFilter(null) }) { Text("Show all") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            if (only == null) "Total spent" else "Spent in ${if (only.size == 1) "this category" else "these categories"}",
            Money.format(r.total),
            delta = (r.total - r.previousTotal).takeIf { r.previousTotal != 0L }, deltaBase = r.previousTotal, upIsGood = false,
        )
        if (only != null && everything.total > 0) {
            StatTile("Share of all spending", "${(r.total * 100.0 / everything.total).roundToInt()}% of ${Money.format(everything.total)}")
        }
        if (period.monthCount > 1) StatTile("Average per month", Money.format(r.total / period.monthCount))
        if (only == null || only.size > 1) r.byCategory.firstOrNull()?.let { StatTile("Biggest category", it.name, modifier = Modifier) }
    }
    if (r.total == 0L) {
        Text(
            if (only == null) "No spending in this period." else "No spending in the chosen categories in this period.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        if (period.monthCount > 1) {
            ChartTitle("Spending by month")
            MonthColumns(r.byMonth, "Spent")
        }
        ChartTitle(if (byGroup) "Spending by category group" else "Spending by category", "Net of refunds; transfers and card payments aren't spending.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(!byGroup, { byGroup = false }, label = { Text("Categories") })
            FilterChip(byGroup, { byGroup = true }, label = { Text("Groups") })
            Spacer(Modifier.width(8.dp))
            ChartStyleChips(pie, onPie)
        }
        val rows = if (byGroup) r.byGroup else r.byCategory
        // Colors stay with each category (from the unfiltered ranking) when the filter changes.
        val colorOrder = (if (byGroup) everything.byGroup else everything.byCategory).map { it.id }
        if (pie) PieChart(rows, colorOrder, groupLabel = !byGroup) else RankedBars(rows, r.total, groupLabel = !byGroup)
    }
    if (choosing) {
        CategoryFilterDialog(
            data, period, only,
            onApply = { onFilter(it); choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

/** Bars or pie for a breakdown. */
@Composable
private fun ChartStyleChips(pie: Boolean, onPie: (Boolean) -> Unit) {
    FilterChip(!pie, { onPie(false) }, label = { Text("Bars") })
    FilterChip(pie, { onPie(true) }, label = { Text("Pie") })
}

/**
 * Picks the categories the spending report covers: a box per category, and one per group for all
 * of its categories. Each shows what it spent in the period. Picking everything means all.
 */
@Composable
private fun CategoryFilterDialog(
    data: BudgetData,
    period: ReportPeriod,
    current: Set<String>?,
    onApply: (Set<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    val spent = remember(data, period) { Reports.spending(data, period).byCategory.associate { it.id to it.amount } }
    val groups = data.sortedGroups.map { g -> g to data.categoriesIn(g.id) }.filter { it.second.isNotEmpty() }
    val allIds = groups.flatMap { (_, cats) -> cats.map { it.id } }.toSet()
    val picked = remember { mutableStateListOf<String>().apply { addAll(current ?: allIds) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories to include") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picked.clear(); picked.addAll(allIds) }) { Text("Select all") }
                    TextButton(onClick = { picked.clear() }) { Text("Clear") }
                }
                groups.forEach { (g, cats) ->
                    val inGroup = cats.count { it.id in picked }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (inGroup == cats.size) picked.removeAll(cats.map { it.id }) else cats.forEach { if (it.id !in picked) picked.add(it.id) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            when (inGroup) { 0 -> ToggleableState.Off; cats.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                            onClick = null,
                        )
                        Text(g.name, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                        Text(Money.format(cats.sumOf { spent[it.id] ?: 0 }), style = MaterialTheme.typography.bodySmall)
                    }
                    cats.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (c.id in picked) picked.remove(c.id) else picked.add(c.id) }.padding(start = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(c.id in picked, onCheckedChange = null)
                            Text(c.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                spent[c.id]?.let { Money.format(it) } ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(picked.toSet().takeIf { it != allIds }) }, enabled = picked.isNotEmpty()) {
                Text(if (picked.isEmpty()) "Pick at least one" else "Show ${if (picked.size == allIds.size) "all" else picked.size.toString()}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IncomeReportView(data: BudgetData, period: ReportPeriod, pie: Boolean, onPie: (Boolean) -> Unit) {
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
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChartStyleChips(pie, onPie) }
    if (pie) PieChart(r.bySource, r.bySource.map { it.id }) else RankedBars(r.bySource, r.total)
}

@Composable
private fun NetWorthReportView(data: BudgetData, period: ReportPeriod) {
    val r = remember(data, period) { Reports.netWorth(data, period, today()) }
    val end = r.end
    if (end == null) {
        Text("This period hasn't started yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            "Net worth", Money.format(end.netWorth),
            delta = r.change.takeIf { r.before != 0L || r.change != 0L }, deltaBase = kotlin.math.abs(r.before), upIsGood = true,
            deltaSuffix = "since ${shortMonth(period.from - 1)}",
        )
        StatTile("What you own", Money.format(end.assets))
        StatTile("What you owe", Money.format(-end.debts))
    }
    if (r.points.size > 1) {
        ChartTitle("Net worth by month", "At the end of each month (today, for this month)")
        MonthColumns(r.points.map { it.month to it.netWorth }, "Net worth") { i ->
            val p = r.points[i]
            "Own ${Money.format(p.assets)} · owe ${Money.format(-p.debts)}"
        }
    } else {
        Text(
            "Choose Last 3 months, Last 12 months or a custom period to see how it has changed over time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (r.assets.isNotEmpty()) {
        ChartTitle("What you own", "Accounts with money in them on ${shortMonth(end.month)}")
        RankedBars(r.assets, end.assets, groupLabel = true)
    }
    if (r.debts.isNotEmpty()) {
        ChartTitle("What you owe", "Cards, loans and overdrawn accounts")
        RankedBars(r.debts.map { it.copy(amount = -it.amount) }, -end.debts, groupLabel = true)
    }
    Text(
        "Every account counts, budget and tracking alike. Investments and property count at the balance recorded in IMAB, " +
            "so update them (a transaction or a reconcile) to keep this current.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
