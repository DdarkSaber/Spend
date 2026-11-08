package com.girish.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.SalaryPeriod
import com.girish.expensetracker.core.SalaryPeriodReports
import com.girish.expensetracker.core.Txn
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import java.time.LocalDate

/** The salary credits that fall in [range], each with the salary period it belongs to (an extra payout opens its period). */
internal fun salariesIn(snap: Snapshot, range: ClosedRange<LocalDate>): List<Pair<Txn, SalaryPeriod>> {
    val periods = Ledger.salaryPeriods(snap.txns, snap.today)
    return snap.txns.filter { it.isSalary && it.day in range }.sortedBy { it.day }
        .mapNotNull { s -> periods.firstOrNull { it.contains(s) }?.let { s to it } }
}

/** "Salary ₹1,15,065 · 28 Oct" rows for one calendar month; tap one to see the salary-to-salary view. */
@Composable
internal fun MonthSalaries(snap: Snapshot, range: ClosedRange<LocalDate>, onOpen: (LocalDate) -> Unit) {
    val t = LocalTones.current
    val list = remember(snap, range) { salariesIn(snap, range) }
    if (list.isEmpty()) return
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("Salary", if (list.size > 1) "${list.size} credits" else null)
        list.forEachIndexed { k, (s, p) ->
            if (k > 0) Hairline()
            val extra = s !== p.salary
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p.start) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Salary ${rupees(s.amountPaise)} · ${s.day.format(dayMonth)}", style = MaterialTheme.typography.titleSmall, color = t.ink)
                    Text(
                        if (extra) "extra payout · part of the ${p.start.format(dayMonth)} salary" else "see what it paid for until the next salary",
                        style = MaterialTheme.typography.bodySmall, color = t.ink2,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = t.ink3)
            }
        }
    }
}

/** Salary to salary: spent from one salary until the next (or today), by category, how long it lasted, what was left. */
@Composable
internal fun SalaryPeriodScreen(snap: Snapshot, start: LocalDate, isNew: (Row) -> Boolean, onBack: () -> Unit, onRow: (Row) -> Unit) {
    val t = LocalTones.current
    val period = remember(snap, start) { Ledger.salaryPeriods(snap.txns, snap.today).firstOrNull { it.start == start } }
    if (period == null) { onBack(); return }
    val r = remember(snap, period) { SalaryPeriodReports.of(snap.txns, period, snap.today) }
    var openCat by remember { mutableStateOf<String?>(null) }
    val slices = r.byCategory.filterValues { it > 0 }.toList().sortedByDescending { it.second }
    val gross = slices.sumOf { it.second }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            }
        }
        item {
            Column {
                Text("Salary of ${period.start.format(dayMonth)} ${period.start.year}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)), color = t.ink)
                Text(
                    "${period.range.start.format(dayMonth)} – ${period.range.endInclusive.format(dayMonth)} ${period.range.endInclusive.year}" +
                        if (r.running) " · until the next salary" else "",
                    style = MaterialTheme.typography.bodySmall, color = t.ink2,
                )
            }
        }
        item {
            StatGrid(
                listOf(
                    Stat("Salary", rupees(period.salaryPaise), sub = if (period.extras.isEmpty()) "credited ${period.start.format(dayMonth)}" else "incl. ${count(period.extras.size, "extra payout")}"),
                    Stat("Spent", rupees(r.spent), sub = r.spentShare?.let { "${(it * 100).toInt()}% of salary" }, highlight = true),
                    Stat(if (r.running) "Left so far" else "Left over", rupees(r.left), up = if (r.left < 0) true else null),
                    Stat(if (r.running) "So far" else "Lasted", count(r.days, "day"), sub = r.ranOutOn?.let { "spent past salary on ${it.format(dayMonth)}" }),
                ),
            )
        }
        if (period.extras.isNotEmpty()) item {
            Panel {
                PanelHeader("Credited in this period")
                (listOf(period.salary) + period.extras).forEachIndexed { k, s ->
                    if (k > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text((if (k == 0) "Salary" else "Extra payout") + " · ${s.day.format(dayMonth)}", style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                        Text(rupees(s.amountPaise), style = AmountStyle, color = t.good)
                    }
                }
            }
        }
        item {
            Panel {
                PanelHeader("Where it went")
                if (slices.isEmpty()) Text("Nothing spent in this period.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
                slices.forEachIndexed { k, (cat, amt) ->
                    if (k > 0) Hairline()
                    Column(Modifier.clickable { openCat = if (openCat == cat) null else cat }.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Swatch(paintFor(cat))
                            Spacer(Modifier.width(10.dp))
                            Text(cat, style = MaterialTheme.typography.titleSmall, color = t.ink, modifier = Modifier.weight(1f))
                            Text(rupees(amt), style = AmountStyle, color = t.ink)
                        }
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(t.raised)) {
                            Box(Modifier.fillMaxWidth(amt.toFloat() / gross.coerceAtLeast(1)).fillMaxHeight().clip(CircleShape).background(paintFor(cat)))
                        }
                        if (openCat == cat) Column(Modifier.padding(top = 6.dp)) {
                            snap.rows.filter { it.txn.category == cat && it.txn.day in period.range && Ledger.monthAmount(it.txn) != 0L }
                                .sortedByDescending { it.txn.receivedAt }
                                .forEach { TxnRow(it, isNew(it), onRow, showDate = true) }
                        }
                    }
                }
            }
        }
    }
}
