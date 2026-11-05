package com.z.imab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.z.imab.budget.Amortization
import com.z.imab.budget.Amortizer
import com.z.imab.budget.LumpSum
import com.z.imab.budget.PayoffPlan
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.BudgetData
import com.z.imab.model.Money
import com.z.imab.model.YearMonth

/** Principal, interest and escrow: the first three of the validated categorical order. */
private object LoanColors {
    val light = PieColors.light.take(3)
    val dark = PieColors.dark.take(3)
}

@Composable
private fun loanColors(): List<Color> = if (seriesColor() == ChartColors.seriesDark) LoanColors.dark else LoanColors.light

private fun monthName(m: Int) = YearMonth(2000, m).label.substringBefore(' ')

private fun duration(months: Int) = when {
    months < 12 -> "$months mo"
    months % 12 == 0 -> "${months / 12} yr"
    else -> "${months / 12} yr ${months % 12} mo"
}

/** What a loan needs for a schedule: what's owed, the regular payment and the first month to plan from. */
internal data class LoanBasis(
    val owed: Long,
    val ratePct: Double,
    val principalAndInterest: Long,
    val escrow: Long,
    val start: YearMonth,
    /** Extra principal already set on the loan; the planner starts from it. */
    val plannedExtra: Long,
)

/**
 * The loan's numbers, or a reason it can't be planned yet. The regular payment is the usual monthly
 * payment from the loan details, else the last linked payment; escrow comes out of it.
 */
internal fun loanBasis(account: Account, data: BudgetData, now: YearMonth): Pair<LoanBasis?, String?> {
    val terms = account.loan ?: return null to "Set the interest rate and escrow on this loan first (the pencil at the top)."
    val owed = maxOf(0, -data.balance(account.id))
    if (owed == 0L) return null to "This loan is paid off."
    val payments = data.transactions.values.filter { it.loanSplit?.loanAccountId == account.id }.sortedBy { it.date }
    val usual = terms.monthlyPayment ?: payments.lastOrNull()?.let { -it.amount }
        ?: return null to "Set the usual monthly payment on this loan (the pencil at the top), or link a payment to it."
    val pi = usual - terms.monthlyEscrow
    // This month's payment already made: the schedule starts next month.
    val paidThisMonth = payments.any { YearMonth.of(it.date) == now }
    return LoanBasis(
        owed, terms.annualRatePct, pi, terms.monthlyEscrow, if (paidThisMonth) now + 1 else now,
        plannedExtra = if (terms.monthlyPayment != null) terms.extraPrincipal else 0,
    ) to null
}

/** One lump sum being typed in. */
private class LumpForm(month: YearMonth, amount: String) {
    var month by mutableStateOf(month)
    var amount by mutableStateOf(amount)
}

private enum class TableMode { YEARLY, MONTHLY }

/**
 * Amortization schedule for a mortgage or loan, compared with extra payments you try out: more
 * principal each month, a yearly extra, and lump sums. Nothing here changes the budget unless you
 * save the monthly extra as the loan's plan.
 */
@Composable
fun LoanPlannerScreen(store: BudgetStore, nav: NavState, accountId: String, wide: Boolean) {
    val data by store.data.collectAsState()
    val account = data.accounts[accountId] ?: return
    val now = YearMonth.of(today())
    val (basis, problem) = loanBasis(account, data, now)

    var extraText by remember { mutableStateOf(basis?.plannedExtra?.takeIf { it > 0 }?.let(Money::toInput).orEmpty()) }
    var extraFrom by remember { mutableStateOf<YearMonth?>(null) }
    var yearlyText by remember { mutableStateOf("") }
    var yearlyMonth by remember { mutableIntStateOf(4) }
    val lumps = remember { mutableStateListOf<LumpForm>() }
    var breakdownBase by remember { mutableStateOf(false) }
    var tableMode by remember { mutableStateOf(TableMode.YEARLY) }

    fun amount(text: String) = if (text.isBlank()) 0L else Money.eval(text)?.coerceAtLeast(0)
    val plan = PayoffPlan(
        extraMonthly = amount(extraText) ?: 0,
        extraFrom = extraFrom,
        yearlyExtra = amount(yearlyText) ?: 0,
        yearlyMonth = yearlyMonth,
        lumpSums = lumps.mapNotNull { l -> amount(l.amount)?.takeIf { it > 0 }?.let { LumpSum(l.month, it) } },
    )
    val base = basis?.let { Amortizer.schedule(it.owed, it.ratePct, it.principalAndInterest, it.escrow, it.start) }
    val mine = basis?.let { Amortizer.schedule(it.owed, it.ratePct, it.principalAndInterest, it.escrow, it.start, plan) }
    val comparing = !plan.isEmpty && base != null && mine != null
    val shown = if (comparing && !breakdownBase) mine else base

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.openAccount(accountId) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column {
                        Text("Payoff planner", style = MaterialTheme.typography.headlineSmall)
                        Text(account.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (basis == null || base == null) {
                    Text(
                        problem ?: "The usual payment doesn't cover the interest, so the loan wouldn't be paid off. Check the payment and rate in the loan details.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }
                Text(
                    "${Money.format(basis.owed)} owed at ${basis.ratePct}% · ${Money.format(basis.principalAndInterest)} principal and interest + " +
                        "${Money.format(basis.escrow)} escrow a month, from ${basis.start.label}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Summary(base, mine, comparing)
                PlanInputs(
                    basis, extraText, { extraText = it }, extraFrom, { extraFrom = it },
                    yearlyText, { yearlyText = it }, yearlyMonth, { yearlyMonth = it }, lumps,
                    valid = amount(extraText) != null && amount(yearlyText) != null && lumps.all { amount(it.amount) != null },
                    onReset = { extraText = ""; extraFrom = null; yearlyText = ""; lumps.clear() },
                    onSave = account.loan?.takeIf { it.monthlyPayment != null }?.let { terms ->
                        {
                            store.budget.saveAccount(account.copy(loan = terms.copy(extraPrincipal = plan.extraMonthly)))
                            Unit
                        }
                    }?.takeIf { plan.extraFrom == null && plan.extraMonthly != (account.loan?.extraPrincipal ?: 0) },
                    wide = wide,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartTitle("Balance over time", if (comparing) "What's owed, on the regular schedule and with your extra payments" else "What's owed after each payment")
                        BalanceChart(base, mine.takeIf { comparing })
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartTitle("Where the money goes", "Principal, interest and escrow each year: paid so far, then the schedule")
                        if (comparing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!breakdownBase, { breakdownBase = false }, label = { Text("With extras") })
                                FilterChip(breakdownBase, { breakdownBase = true }, label = { Text("Regular") })
                            }
                        }
                        BreakdownChart(paidSoFar(account, data, basis.start), shown!!)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (comparing && !breakdownBase) "Schedule with extra payments" else "Regular schedule",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    FilterChip(tableMode == TableMode.YEARLY, { tableMode = TableMode.YEARLY }, label = { Text("By year") })
                    FilterChip(tableMode == TableMode.MONTHLY, { tableMode = TableMode.MONTHLY }, label = { Text("By month") })
                }
                ScheduleHeader(wide)
            }
        }
        val s = shown
        if (basis != null && s != null) {
            if (tableMode == TableMode.YEARLY) {
                items(s.years, key = { "y${it.year}" }) { y ->
                    ScheduleLine(y.year.toString(), y.payment, y.principal, y.interest, y.escrow, y.extra, y.balance, wide)
                }
            } else {
                items(s.rows, key = { "m${it.month}" }) { r ->
                    ScheduleLine("${r.month.label.take(3)} ${r.month.year}", r.payment, r.principal, r.interest, r.escrow, r.extra, r.balance, wide)
                }
            }
            item {
                ScheduleLine("Total", s.totalPaid, s.totalPrincipal, s.totalInterest, s.totalEscrow, s.rows.sumOf { it.extra }, 0, wide, bold = true)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Payoff date, interest and total still to pay; with extra payments, what they save. */
@Composable
private fun Summary(base: Amortization, mine: Amortization?, comparing: Boolean) {
    val s = if (comparing) mine!! else base
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val sooner = base.months - s.months
        StatTile("Paid off", "${s.payoff!!.label} · ${duration(s.months)}")
        StatTile("Interest still to pay", Money.format(s.totalInterest))
        StatTile("Total still to pay", Money.format(s.totalPaid))
        if (comparing) {
            Card(Modifier.widthIn(min = 150.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Extra payments save", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.format(base.totalInterest - s.totalInterest), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (sooner > 0) "in interest, and it's paid off ${duration(sooner)} sooner (regular: ${base.payoff!!.label})"
                        else "in interest",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanInputs(
    basis: LoanBasis,
    extraText: String,
    onExtra: (String) -> Unit,
    extraFrom: YearMonth?,
    onExtraFrom: (YearMonth?) -> Unit,
    yearlyText: String,
    onYearly: (String) -> Unit,
    yearlyMonth: Int,
    onYearlyMonth: (Int) -> Unit,
    lumps: MutableList<LumpForm>,
    valid: Boolean,
    onReset: () -> Unit,
    onSave: (() -> Unit)?,
    wide: Boolean,
) {
    val months = (0 until 360).map { basis.start + it }
    // Two to a row on a phone (the lump sum's remove button goes on the next line there).
    val fieldW = if (wide) 220.dp else 156.dp
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartTitle("Try extra payments", "Only for planning: nothing here is added to your budget.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MoneyField("Extra each month", extraText, onExtra, Modifier.width(fieldW), isError = Money.eval(extraText.ifBlank { "0" }) == null)
                Picker(
                    "Starting",
                    listOf(PickerOption<YearMonth?>(null, "Now")) + months.drop(1).map { PickerOption<YearMonth?>(it, it.label) },
                    extraFrom, onExtraFrom, Modifier.width(fieldW),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MoneyField("Extra yearly", yearlyText, onYearly, Modifier.width(fieldW), isError = Money.eval(yearlyText.ifBlank { "0" }) == null)
                Picker("Every", (1..12).map { PickerOption(it, monthName(it)) }, yearlyMonth, onYearlyMonth, Modifier.width(fieldW))
            }
            lumps.forEachIndexed { i, l ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    MoneyField("Lump sum ${i + 1}", l.amount, { l.amount = it }, Modifier.width(fieldW), isError = Money.eval(l.amount.ifBlank { "0" }) == null)
                    Picker("In", months.map { PickerOption(it, it.label) }, l.month, { l.month = it }, Modifier.width(fieldW))
                    IconButton(onClick = { lumps.removeAt(i) }) { Icon(Icons.Default.Close, "Remove lump sum ${i + 1}") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { lumps += LumpForm(lumps.lastOrNull()?.month?.plus(12) ?: basis.start, "") }) { Text("+ Add lump sum") }
                TextButton(onClick = onReset) { Text("Clear") }
                if (onSave != null && valid) {
                    TextButton(onClick = onSave) {
                        Text(if (extraText.isBlank() || Money.eval(extraText) == 0L) "Remove the monthly extra from my loan" else "Make the monthly extra my plan")
                    }
                }
            }
            if (!valid) Text("One of the amounts isn't a number.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A swatch and a name, for chart legends. */
@Composable
private fun LegendItem(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            Canvas(Modifier.width(16.dp).height(10.dp)) {
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
        } else {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Year labels under a chart that spans [first]..[last], as many as fit. */
@Composable
private fun YearAxis(first: Int, last: Int) {
    val years = (first..last).toList()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val every = kotlin.math.ceil(years.size * 40f / maxWidth.value.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        Row(Modifier.fillMaxWidth()) {
            years.chunked(every).forEach { chunk ->
                Text(
                    if (chunk.size == every) chunk.first().toString() else "", Modifier.weight(chunk.size.toFloat()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (every == 1) TextAlign.Center else TextAlign.Start, maxLines = 1,
                )
            }
        }
    }
}

/**
 * What's owed month by month: the regular schedule (dashed gray) and, when comparing, the one with
 * extra payments (solid). Hover or tap reads both at that month.
 */
@Composable
private fun BalanceChart(base: Amortization, mine: Amortization?) {
    val months = base.rows.size
    var selected by remember(base, mine) { mutableIntStateOf(0) }
    val planColor = seriesColor()
    val baseColor = if (mine == null) planColor else MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val top = base.rows.first().let { it.balance + it.principal }
    val i = selected.coerceIn(0, months - 1)
    val month = base.rows[i].month
    val baseAt = base.rows[i].balance
    val mineAt = mine?.rows?.getOrNull(i)?.balance ?: 0L

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column {
            Text(Money.format(if (mine != null) mineAt else baseAt), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "owed after ${month.label}" + if (mine != null) " with extra payments · ${Money.format(baseAt)} on the regular schedule" else "",
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        if (mine != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(planColor, "With extra payments")
                LegendItem(baseColor, "Regular schedule", dashed = true)
            }
        }
        Text(compact(top), style = MaterialTheme.typography.labelSmall, color = muted)
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = "Balance over time chart. The table below lists every year." }
                .pointerInput(base, mine) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                                selected = (e.changes.first().position.x / size.width * months).toInt().coerceIn(0, months - 1)
                            }
                        }
                    }
                },
        ) {
            val stepX = size.width / months.coerceAtLeast(1)
            fun y(v: Long) = size.height * (1f - v.toFloat() / top.coerceAtLeast(1))
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1f)
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            fun line(s: Amortization): Path = Path().apply {
                moveTo(0f, y(top))
                s.rows.forEachIndexed { k, r -> lineTo(stepX * (k + 1), y(r.balance)) }
            }
            val stroke = 2.dp.toPx()
            drawPath(
                line(base), baseColor,
                style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (mine != null) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null),
            )
            if (mine != null) drawPath(line(mine), planColor, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Crosshair at the selected month, with a marker on each line.
            val x = stepX * (i + 1)
            drawLine(muted.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), 1f)
            drawCircle(baseColor, 4.dp.toPx(), Offset(x, y(baseAt)))
            if (mine != null) {
                drawCircle(planColor, 4.dp.toPx(), Offset(x, y(mineAt)))
            }
        }
        Text("$0", style = MaterialTheme.typography.labelSmall, color = muted)
        YearAxis(base.rows.first().month.year, base.rows.last().month.year)
    }
}

/** A year of payments: what was actually paid, what the schedule adds, or both. */
private data class YearSplit(val year: Int, val principal: Long, val interest: Long, val escrow: Long, val actual: Boolean, val projected: Boolean) {
    val total: Long get() = principal + interest + escrow
}

/** Linked payments before [start], by year. */
private fun paidSoFar(account: Account, data: BudgetData, start: YearMonth): List<YearSplit> =
    data.transactions.values
        .filter { it.loanSplit?.loanAccountId == account.id && YearMonth.of(it.date) < start }
        .groupBy { it.date.year }
        .map { (y, ts) ->
            YearSplit(y, ts.sumOf { it.loanSplit!!.principal }, ts.sumOf { it.loanSplit!!.interest }, ts.sumOf { it.loanSplit!!.escrow }, actual = true, projected = false)
        }

/**
 * Stacked columns for each year, principal at the bottom, then interest, then escrow. Years before
 * the schedule are what was actually paid. Hover or tap a year to read it.
 */
@Composable
private fun BreakdownChart(past: List<YearSplit>, schedule: Amortization) {
    val years = remember(past, schedule) {
        val future = schedule.years.associateBy { it.year }
        val all = (past.map { it.year } + future.keys).distinct().sorted()
        all.map { y ->
            val p = past.firstOrNull { it.year == y }
            val f = future[y]
            YearSplit(
                y, (p?.principal ?: 0) + (f?.principal ?: 0), (p?.interest ?: 0) + (f?.interest ?: 0), (p?.escrow ?: 0) + (f?.escrow ?: 0),
                actual = p != null, projected = f != null,
            )
        }
    }
    if (years.isEmpty()) return
    val colors = loanColors()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var selected by remember(years) { mutableIntStateOf(years.indexOfFirst { it.projected }.coerceAtLeast(0)) }
    val max = years.maxOf { it.total }.coerceAtLeast(1)
    val sel = years[selected.coerceIn(years.indices)]

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column {
            Text(Money.format(sel.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "in ${sel.year} · " + when {
                    sel.actual && sel.projected -> "paid so far + scheduled"
                    sel.actual -> "paid"
                    else -> "scheduled"
                },
                style = MaterialTheme.typography.bodySmall, color = muted,
            )
        }
        Text(
            "${Money.format(sel.principal)} principal · ${Money.format(sel.interest)} interest · ${Money.format(sel.escrow)} escrow",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(colors[0], "Principal")
            LegendItem(colors[1], "Interest")
            LegendItem(colors[2], "Escrow")
        }
        Text(compact(max), style = MaterialTheme.typography.labelSmall, color = muted)
        Canvas(
            Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = "Principal, interest and escrow by year. The table below lists every year." }
                .pointerInput(years) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                                selected = (e.changes.first().position.x / size.width * years.size).toInt().coerceIn(years.indices)
                            }
                        }
                    }
                },
        ) {
            val slot = size.width / years.size
            val w = minOf(24.dp.toPx(), slot * 0.7f)
            val gap = if (w >= 6.dp.toPx()) 2.dp.toPx() else 1f
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            years.forEachIndexed { k, y ->
                val left = slot * k + (slot - w) / 2
                val alpha = if (k == selected) 1f else 0.6f
                var bottom = size.height
                val parts = listOf(y.principal to colors[0], y.interest to colors[1], y.escrow to colors[2]).filter { it.first > 0 }
                parts.forEachIndexed { pi, (v, c) ->
                    val h = size.height * v.toFloat() / max
                    val topY = bottom - h
                    val isTop = pi == parts.lastIndex
                    val r = if (isTop) CornerRadius(minOf(4.dp.toPx(), h), minOf(4.dp.toPx(), h)) else CornerRadius.Zero
                    // A thin gap of the card's color between segments keeps them apart.
                    val segTop = if (isTop) topY else topY + gap
                    if (bottom - segTop > 0f) {
                        drawPath(
                            Path().apply {
                                addRoundRect(RoundRect(left, segTop, left + w, bottom, topLeftCornerRadius = r, topRightCornerRadius = r,
                                    bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero))
                            },
                            c.copy(alpha = alpha),
                        )
                    }
                    bottom = topY
                }
            }
        }
        YearAxis(years.first().year, years.last().year)
    }
}

@Composable
private fun ScheduleHeader(wide: Boolean) {
    Column {
        ScheduleCells(listOfNotNull("", "Payment".takeIf { wide }, "Principal", "Interest", "Escrow".takeIf { wide }, "Extra".takeIf { wide }, "Balance"), header = true)
        HorizontalDivider()
    }
}

@Composable
private fun ScheduleLine(
    label: String, payment: Long, principal: Long, interest: Long, escrow: Long, extra: Long, balance: Long, wide: Boolean, bold: Boolean = false,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        ScheduleCells(
            listOfNotNull(
                label, Money.format(payment).takeIf { wide }, Money.format(principal), Money.format(interest),
                Money.format(escrow).takeIf { wide }, (if (extra > 0) Money.format(extra) else "—").takeIf { wide },
                if (bold) "" else Money.format(balance),
            ),
            bold = bold,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun ScheduleCells(cells: List<String>, header: Boolean = false, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEachIndexed { k, c ->
            Text(
                c,
                Modifier.weight(if (k == 0) 1.1f else 1f),
                style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (bold) FontWeight.SemiBold else null,
                textAlign = if (k == 0) TextAlign.Start else TextAlign.End,
                maxLines = 1,
            )
        }
    }
}
