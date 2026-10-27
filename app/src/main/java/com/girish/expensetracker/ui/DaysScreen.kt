package com.girish.expensetracker.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.UserConfig
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------- days

@Composable
internal fun DaysScreen(
    snap: Snapshot,
    isNew: (Row) -> Boolean,
    onRow: (Row) -> Unit,
    onLoadDemo: (() -> Unit)? = null,
    onCash: () -> Unit = {},
    onMore: () -> Unit = {},
    onReview: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val t = LocalTones.current
    var range by remember { mutableStateOf(0) } // 0 = 7 days, 1 = salary month, 2 = 4 months
    var month by remember { mutableStateOf(YearMonth.from(snap.today)) }
    var selected by remember { mutableStateOf(snap.today) }
    val earliest = YearMonth.from(snap.today.minusDays(140))
    val perDay = remember(snap) { snap.rows.groupBy { it.txn.day } }
    val dayTotals = remember(snap) { perDay.mapValues { (_, rows) -> rows.sumOf { Ledger.todayAmount(it.txn) } } }
    val rows = perDay[selected].orEmpty().sortedByDescending { it.txn.receivedAt }
    val hero = remember(snap, range) { heroSeries(snap, range) }
    val p = remember(snap) { pace(snap) }
    val week = remember(snap) { dailySeries(snap, salaryMonth = false) }
    val monthSeries = remember(snap) { dailySeries(snap, salaryMonth = true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ScreenTitle("Spending") {
                Row(
                    Modifier.clip(CircleShape).border(1.dp, t.accent.copy(alpha = 0.35f), CircleShape).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RoundIcon(Icons.Outlined.Search, "Search payments", onSearch, size = 38.dp)
                    RoundIcon(Icons.Filled.Add, "Add cash spend", onCash, size = 38.dp)
                    RoundIcon(Icons.Filled.MoreHoriz, "More", onMore, size = 38.dp)
                }
                Box(Modifier.clip(CircleShape).clickable(onClick = onMore)) { Avatar(snap.owner?.displayName?.ifBlank { null } ?: "You", 44.dp) }
            }
        }
        snap.owner?.takeIf { it.state == "detected" && snap.rows.isNotEmpty() }?.let { o -> item { ReviewSetupCard(o, onReview) } }
        if (snap.unusual.isNotEmpty()) item { WorthALookPanel(snap.unusual) }
        item { Segmented(listOf("7 Days", "Month", "4 Months"), range, { range = it }) }
        if (snap.rows.isEmpty()) {
            item { EmptyLedger(onLoadDemo) }
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingAmount(hero.now.sum(), MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), t.ink)
                    val prior = hero.prior?.sum()
                    Text(
                        if (prior != null && prior > 0) "vs ${rupees(prior)} ${if (range == 0) "the week before" else "last month"}" else hero.rangeLabel.lowercase(),
                        style = MaterialTheme.typography.bodySmall, color = t.ink2,
                    )
                }
            }
            item { HeroChart(hero) }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("Overview", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700)), color = t.ink)
                Text("${snap.cycleStart.format(dayMonth)} – ${snap.today.format(dayMonth)}".uppercase(), style = DataLabel, color = t.ink3)
            }
        }
        item {
            val avg = monthSeries.total / monthSeries.days.size.coerceAtLeast(1)
            val priorAvg = monthSeries.priorTotal / monthSeries.days.size.coerceAtLeast(1)
            val best = monthSeries.now.indices.maxByOrNull { monthSeries.now[it] }
            val paidBack = snap.rows.filter { it.txn.day >= snap.cycleStart && Ledger.monthAmount(it.txn) < 0 }.sumOf { -Ledger.monthAmount(it.txn) }
            val payments = snap.rows.count { it.txn.day >= snap.cycleStart && Ledger.monthAmount(it.txn) > 0 }
            val diff = p.nowToday - p.lastSameDay
            StatGrid(
                listOf(
                    Stat("Today", rupees(snap.todayTotal), sub = count(rowsToday(snap), "payment"), highlight = true),
                    Stat("Salary month", rupees(snap.monthTotal), sub = "since ${snap.cycleStart.format(dayMonth)}", up = if (p.hasLast) diff > 0 else null),
                    Stat("Avg per day", rupees(avg), sub = if (priorAvg > 0) "${rupees(priorAvg)} last month" else null, up = if (priorAvg > 0) avg > priorAvg else null),
                    Stat("This week", rupees(week.total), sub = if (week.priorTotal > 0) "${rupees(week.priorTotal)} week before" else null, up = if (week.priorTotal > 0) week.total > week.priorTotal else null),
                    Stat("Biggest day", best?.let { rupees(monthSeries.now[it]) } ?: "–", sub = best?.let { monthSeries.days[it].format(dayMonth) }),
                    lastSalary(snap)?.let { s -> Stat("Salary", rupees(s.txn.amountPaise), sub = "credited ${s.txn.day.format(dayMonth)}") }
                        ?: Stat("Paid back", rupees(paidBack), sub = count(payments, "payment") + " out"),
                ),
            )
        }
        item {
            Panel {
                PanelHeader("Calendar", chevron = true) {
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { if (month > earliest) month = month.minusMonths(1) }, size = 34.dp)
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", { if (month < YearMonth.from(snap.today)) month = month.plusMonths(1) }, size = 34.dp)
                }
                Text(month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.year, style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(bottom = 10.dp))
                Calendar(month, dayTotals, selected, snap.today) { selected = it }
            }
        }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
                PanelHeader(
                    if (selected == snap.today) "Today" else selected.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)),
                    rupees(dayTotals[selected] ?: 0L),
                )
                if (rows.isEmpty()) {
                    Text(
                        if (selected == snap.today) "Nothing spent yet today." else "No transactions on this day.",
                        style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                rows.forEachIndexed { k, r ->
                    if (k > 0) Hairline()
                    TxnRow(r, isNew(r), onRow)
                }
            }
        }
    }
}

private fun rowsToday(snap: Snapshot) = snap.rows.count { it.txn.day == snap.today && Ledger.todayAmount(it.txn) > 0 }

@Composable
private fun Calendar(month: YearMonth, totals: Map<LocalDate, Long>, selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val t = LocalTones.current
    val lead = month.atDay(1).dayOfWeek.value - 1 // Monday first
    val cells = lead + month.lengthOfMonth()
    val max = (1..month.lengthOfMonth()).maxOfOrNull { totals[month.atDay(it)] ?: 0L }?.coerceAtLeast(1L) ?: 1L

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = DataLabel, color = t.ink3)
            }
        }
        for (week in 0 until (cells + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (dow in 0 until 7) {
                    val dayNum = week * 7 + dow - lead + 1
                    if (dayNum < 1 || dayNum > month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).aspectRatio(0.9f))
                        continue
                    }
                    val date = month.atDay(dayNum)
                    val amount = totals[date] ?: 0L
                    val shade = (amount.coerceAtLeast(0).toFloat() / max).coerceIn(0f, 1f)
                    val isSel = date == selected
                    val future = date.isAfter(today)
                    val fill by animateColorAsState(
                        when {
                            isSel -> t.ink
                            amount > 0 -> t.accent.copy(alpha = 0.10f + 0.55f * shade)
                            else -> t.section
                        }, tween(220), label = "day",
                    )
                    val ink = when {
                        isSel -> t.ground
                        future -> t.ink3.copy(alpha = 0.5f)
                        else -> t.ink
                    }
                    Column(
                        Modifier.weight(1f).aspectRatio(0.9f).clip(TileShape).background(fill)
                            .then(if (date == today && !isSel) Modifier.border(1.5.dp, t.accent, TileShape) else Modifier)
                            .clickable(enabled = !future) { onPick(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$dayNum", style = MaterialTheme.typography.labelLarge, color = ink)
                        if (amount != 0L) Text(compact(amount), style = DataLabel.copy(letterSpacing = 0.sp()), color = ink.copy(alpha = 0.75f), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Firnhold-style range card: pill switch, period total vs last, daily bars with last period faded, comparison rows. */
@Composable
private fun EmptyLedger(onLoadDemo: (() -> Unit)?) {
    val t = LocalTones.current
    Panel {
        Text("No bank SMS yet", style = MaterialTheme.typography.titleLarge, color = t.ink)
        Text(
            "Spends appear here the moment your bank sends a transaction SMS. Older messages are imported once from the last ${UserConfig.IMPORT_DAYS} days.",
            style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(top = 6.dp),
        )
        if (onLoadDemo != null) {
            Gap(14.dp)
            SecondaryButton("Load demo data (debug build)", onLoadDemo)
        }
    }
}
