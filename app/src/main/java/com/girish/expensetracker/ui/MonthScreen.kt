package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import com.girish.expensetracker.core.MonthReports
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- month

@Composable
internal fun MonthScreen(
    snap: Snapshot,
    isNew: (Row) -> Boolean,
    onBudgets: () -> Unit = {},
    onRent: (com.girish.expensetracker.core.RentCandidate, Boolean) -> Unit = { _, _ -> },
    onRow: (Row) -> Unit,
) {
    val t = LocalTones.current
    var withInvestments by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf(0) } // 0 overview, 1 trends, 2 recurring
    var selected by remember { mutableStateOf<String?>(null) }
    var openCat by remember { mutableStateOf<String?>(null) }

    // Salary months, newest first, back to the first one with any payment. 0 is the running month.
    val months = remember(snap) {
        val first = snap.txns.minOfOrNull { it.day }
        Ledger.cycles(snap.today, snap.txns, 12).filter { first == null || it.endInclusive >= first }.ifEmpty { listOf(snap.cycleStart..snap.today) }
    }
    var monthIndex by remember(snap) { mutableStateOf(0) }
    val range = months[monthIndex.coerceIn(0, months.lastIndex)]
    val report = remember(snap, range) { MonthReports.of(snap.txns, range, months.getOrNull(monthIndex + 1), running = monthIndex == 0) }
    fun showMonth(i: Int) { monthIndex = i.coerceIn(0, months.lastIndex); selected = null; openCat = null }

    val shown = report.byCategory.filterKeys { withInvestments || it != "Investment" }
    val slices = shown.filterValues { it > 0 }.toList().sortedByDescending { it.second }
    val gross = slices.sumOf { it.second }
    val negatives = shown.filterValues { it < 0 }
    val total = shown.values.sum()
    val ring = topWithOther(slices)
    val inOther = slices.map { it.first }.toSet() - ring.map { it.first }.toSet()
    val running = monthIndex == 0

    // Switching view (including "Open full month" from Trends) starts at the top, not wherever the last view was.
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(view) { list.scrollToItem(0) }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Month", "Salary month since ${snap.cycleStart.format(dayMonth)}") }
        item { Segmented(listOf("Overview", "Trends", "Recurring"), view, { view = it }) }
        if (view == 0) {
            item { MonthSwitcher(range, running, canBack = monthIndex < months.lastIndex, onBack = { showMonth(monthIndex + 1) }, onNext = { showMonth(monthIndex - 1) }) }
            // The same summary for every month, salary included; the running month adds its budget and pace.
            item { MonthSummary(report, running) }
            if (running) {
                item { ForecastPanel(snap.forecast) }
                item { BudgetPanel(snap.budgets, onBudgets) }
                snap.rentCandidates.forEach { c -> item { RentCard(c, onMark = { onRent(c, true) }, onDismiss = { onRent(c, false) }) } }
                if (snap.rows.isNotEmpty()) item { PaceChart(snap) }
            }
            item {
                Panel {
                    PanelHeader("Categories", chevron = true) {
                        RoundIcon(
                            Icons.Outlined.Payments, if (withInvestments) "Hide investments" else "Show investments",
                            { withInvestments = !withInvestments; selected = null }, active = withInvestments, size = 36.dp,
                        )
                    }
                    RollingAmount(total, MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700), fontFeatureSettings = "tnum"), t.ink)
                    Text(if (withInvestments) "including investments" else "excluding investments", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                    DonutChart(ring, total, selected) { selected = it }
                    if (slices.isEmpty()) Text(if (running) "Nothing spent yet this salary month." else "Nothing spent in this month.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
                    slices.forEachIndexed { k, (cat, amt) ->
                        if (k > 0) Hairline()
                        val ringKey = if (cat in inOther) "Other" else cat
                        val dim = selected != null && selected != ringKey
                        Column(Modifier.clickable { openCat = if (openCat == cat) null else cat; selected = if (selected == ringKey) null else ringKey }.padding(vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Swatch(paintFor(cat).copy(alpha = if (dim) 0.3f else 1f))
                                Spacer(Modifier.width(10.dp))
                                Text(cat, style = MaterialTheme.typography.titleSmall, color = if (dim) t.ink3 else t.ink, modifier = Modifier.weight(1f))
                                Text(rupees(amt), style = AmountStyle, color = if (dim) t.ink3 else t.ink)
                            }
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(t.raised)) {
                                    Box(
                                        Modifier.fillMaxWidth(amt.toFloat() / gross.coerceAtLeast(1)).fillMaxHeight().clip(CircleShape)
                                            .background(paintFor(cat).copy(alpha = if (dim) 0.3f else 1f)),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                val before = report.before[cat] ?: 0L
                                val diff = amt - before
                                Text(
                                    when {
                                        before == 0L -> "new"
                                        diff == 0L -> "same"
                                        diff > 0 -> "▲ ${rupees(diff)}"
                                        else -> "▼ ${rupees(-diff)}"
                                    },
                                    style = DataLabel,
                                    color = when {
                                        before == 0L || diff == 0L -> t.ink3
                                        diff > 0 -> t.warn
                                        else -> t.good
                                    },
                                )
                            }
                            AnimatedVisibility(openCat == cat, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Column(Modifier.padding(top = 6.dp)) {
                                    snap.rows.filter { it.txn.category == cat && it.txn.day in range && Ledger.monthAmount(it.txn) != 0L }
                                        .sortedByDescending { it.txn.receivedAt }
                                        .forEach { TxnRow(it, isNew(it), onRow, showDate = true) }
                                }
                            }
                        }
                    }
                }
            }
            if (negatives.isNotEmpty()) item {
                Panel {
                    PanelHeader("Taken off the total")
                    negatives.forEach { (cat, amt) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(cat, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                            Text(rupees(amt), style = AmountStyle, color = t.good)
                        }
                    }
                }
            }
        }
        if (view == 1) {
            // Trends bars are oldest-left; "open" jumps Overview to that month.
            item { MonthsBars(snap, withInvestments, isNew, onRow, onOpenMonth = { start -> months.indexOfFirst { it.start == start }.takeIf { it >= 0 }?.let { showMonth(it); view = 0 } }) }
            item { SalaryHistory(snap) }
            item { SavingsPanel(snap.savings) }
            item { YearPanel(snap) }
        }
        if (view == 2) {
            if (snap.newRecurring.isNotEmpty()) item { NewRecurringPanel(snap.newRecurring) }
            item { SubscriptionsPanel(snap.subscriptions) }
            item { EmiPanel(snap.emis) }
            item { InvestmentsPanel(snap.holdings) }
            if (snap.subscriptions.isEmpty() && snap.emis.isEmpty() && snap.holdings.isEmpty()) item {
                Panel { Text("Subscriptions, EMIs and investments show up here once they have charged at least once.", style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.ink2) }
            }
        }
    }
}

/** The latest salary credit (detected from the NEFT narration), if any. */
internal fun lastSalary(snap: Snapshot): Row? = snap.rows.filter { it.txn.isSalary }.maxByOrNull { it.txn.day }

/** Every salary credit, newest first, with the change from the one before (a raise shows up here). */
@Composable
private fun SalaryHistory(snap: Snapshot) {
    val t = LocalTones.current
    val salaries = remember(snap) { snap.rows.filter { it.txn.isSalary }.sortedByDescending { it.txn.day } }
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("Salary history", salaries.firstOrNull()?.let { sourceLabel(it.txn.parsed.source) })
        if (salaries.isEmpty()) {
            Text("No salary credit found yet. It shows up here when your bank's NEFT SMS says SALARY.", style = MaterialTheme.typography.bodyMedium, color = t.ink2,
                modifier = Modifier.padding(bottom = 10.dp))
            return@Panel
        }
        salaries.take(12).forEachIndexed { k, r ->
            if (k > 0) Hairline()
            val before = salaries.getOrNull(k + 1)?.txn?.amountPaise
            val change = before?.let { r.txn.amountPaise - it }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cycleMonth(r.txn.day.plusDays(1)) + " " + r.txn.day.year, style = MaterialTheme.typography.titleSmall, color = t.ink)
                    Text("credited ${r.txn.day.format(dayMonth)}", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rupees(r.txn.amountPaise), style = AmountStyle, color = t.ink)
                    when {
                        change == null || change == 0L -> Unit
                        change > 0 -> Text("▲ ${rupees(change)}", style = DataLabel, color = t.good)
                        else -> Text("▼ ${rupees(-change)}", style = DataLabel, color = t.warn)
                    }
                }
            }
        }
    }
}

/** "Looks like rent" suggestion: confirm once and future payments of that size to that person are filed as Rent. */
@Composable
private fun RentCard(c: com.girish.expensetracker.core.RentCandidate, onMark: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalTones.current
    Panel {
        PanelHeader("Looks like rent", "${c.payments.size} months")
        Text(
            "About ${rupees(c.typicalPaise)} to ${c.name} every month: " + c.payments.joinToString(", ") { it.day.format(dayMonth) } + ".",
            style = MaterialTheme.typography.bodyMedium, color = t.ink2,
        )
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onMark, shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = Color(0xFF04211C)),
            ) { Text("Mark as rent", style = MaterialTheme.typography.labelLarge) }
            SecondaryButton("Not rent", onDismiss)
        }
    }
}

/** ‹ Sep 2026 · 28 Aug – 27 Sep ›: steps through salary months; the running month is the default. */
@Composable
private fun MonthSwitcher(range: ClosedRange<java.time.LocalDate>, running: Boolean, canBack: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    val t = LocalTones.current
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(t.panel).border(1.dp, t.hairline, CircleShape).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier month", { if (canBack) onBack() }, size = 38.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                (if (running) "This month · " else "") + cycleMonth(range.start) + " " + range.endInclusive.year,
                style = MaterialTheme.typography.titleSmall, color = if (running) t.ink else t.accent,
            )
            Text("${range.start.format(dayMonth)} – ${range.endInclusive.format(dayMonth)}", style = DataLabel, color = t.ink3)
        }
        RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later month", { if (!running) onNext() }, size = 38.dp)
    }
}

/**
 * One month at a glance, the same six tiles for every month: spent (vs the month before), salary, what's left or
 * saved, per day, the biggest day and the number of payments. The running month says "left"; past months "saved".
 */
@Composable
private fun MonthSummary(r: com.girish.expensetracker.core.MonthReport, running: Boolean) {
    val t = LocalTones.current
    val diff = r.total - r.totalBefore
    val hasSalary = r.salaryPaise > 0
    StatGrid(
        listOf(
            Stat("Spent", rupees(r.total), sub = if (r.totalBefore > 0) "vs ${rupees(r.totalBefore)} before" else null,
                up = if (r.totalBefore > 0) diff > 0 else null, highlight = true),
            Stat("Salary", if (hasSalary) rupees(r.salaryPaise) else "–", sub = r.salaryDay?.let { "credited ${it.format(dayMonth)}" } ?: "no salary SMS"),
            if (hasSalary) Stat(if (running) "Left" else "Saved", rupees(r.salaryPaise - r.total), sub = "${((r.savingsRate ?: 0.0) * 100).toInt()}% of salary")
            else Stat(if (running) "Left" else "Saved", "–", sub = "needs a salary"),
            Stat("Per day", rupees(r.perDay), sub = "over ${count(r.days, "day")}"),
            r.biggestDay?.let { (d, v) -> Stat("Biggest day", rupees(v), sub = d.format(dayMonth)) } ?: Stat("Biggest day", "–"),
            Stat("Payments", "${r.payments}", sub = "money out"),
        ),
    )
    if (r.topPlaces.isNotEmpty()) {
        Gap(4.dp)
        Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)) {
            PanelHeader("Where it went most")
            r.topPlaces.forEachIndexed { k, (name, amt) ->
                if (k > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${k + 1}", style = DataLabel, color = t.ink3, modifier = Modifier.width(22.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(rupees(amt), style = AmountStyle, color = t.ink)
                }
            }
        }
    }
}
