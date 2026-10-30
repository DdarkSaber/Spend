@file:OptIn(ExperimentalMaterial3Api::class)

package com.girish.expensetracker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.girish.expensetracker.core.Forecast
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.NewRecurring
import com.girish.expensetracker.core.Streak
import com.girish.expensetracker.core.normalizeName
import com.girish.expensetracker.data.Snapshot
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------- forecast (Month · Overview, running month)

/** Where this salary month is heading: fixed costs (paid and still to come) plus everyday spending at today's pace. */
@Composable
internal fun ForecastPanel(f: Forecast) {
    val t = LocalTones.current
    Panel {
        PanelHeader("Month-end forecast", "${count(f.daysLeft, "day")} left")
        Text(rupees(f.totalPaise), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight(700)), color = t.ink)
        Text("expected by the end of this salary month", style = MaterialTheme.typography.bodySmall, color = t.ink2)
        Gap(14.dp)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(rupees(f.fixedPaise), style = AmountStyle, color = t.ink)
                Text("fixed costs", style = MaterialTheme.typography.labelMedium, color = t.ink2)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("${rupees(f.perDay)}/day", style = AmountStyle, color = t.ink)
                Text("everyday spending so far", style = MaterialTheme.typography.labelMedium, color = t.ink2)
            }
        }
        if (f.stillToCome.isNotEmpty()) {
            Gap(12.dp)
            Hairline()
            Text("Still to come · ${rupees(f.toComePaise)}", style = MaterialTheme.typography.titleSmall, color = t.ink, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            f.stillToCome.take(5).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(paintFor(c.category)); Spacer(Modifier.width(8.dp))
                    Text(c.name, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("~${rupees(c.typicalPaise)} · ${c.usualDay}${ordinal(c.usualDay)}", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                }
            }
        }
    }
}

private fun ordinal(d: Int) = if (d in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

// ---------------------------------------------------------------- habits (its own page, from Home's calendar)

@Composable
internal fun HabitsScreen(snap: Snapshot, onBack: () -> Unit) {
    val t = LocalTones.current
    val h = snap.habits
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.padding(top = 16.dp)) { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) } }
        item { ScreenTitle("Habits", "Everyday spending, last 12 weeks · rent, bills and SIPs left out") }
        item {
            Panel {
                PanelHeader("Every day")
                Heatmap(h.perDay, h.from, h.to)
            }
        }
        item {
            StatGrid(
                listOf(
                    Stat("Weekdays", "${rupees(h.weekdayAvg)}", sub = "a day on average"),
                    Stat("Weekends", "${rupees(h.weekendAvg)}", sub = "a day on average", up = if (h.weekdayAvg > 0) h.weekendAvg > h.weekdayAvg else null),
                ),
            )
        }
        item {
            Panel {
                PanelHeader("Time of day", h.busiestWeekday?.let { "busiest: ${it.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}" })
                val total = h.byTime.sumOf { it.second }.coerceAtLeast(1)
                h.byTime.forEach { (name, amt) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                            Text("${rupees(amt)} · ${(amt * 100 / total)}%", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                        }
                        Gap(4.dp)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.raised)) {
                            Box(Modifier.fillMaxWidth(amt.toFloat() / total).fillMaxHeight().clip(CircleShape).background(t.accent))
                        }
                    }
                }
            }
        }
    }
}

/** Twelve weeks as a grid: one column per week, Monday at the top; darker means more spent that day. */
@Composable
private fun Heatmap(perDay: Map<LocalDate, Long>, from: LocalDate, to: LocalDate) {
    val t = LocalTones.current
    val start = from.minusDays((from.dayOfWeek.value - 1).toLong())
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(start, to) / 7) + 1).toInt()
    val max = perDay.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row {
        Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            DayOfWeek.entries.forEach { d ->
                Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                    Text(if (d.value % 2 == 1) d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) else "", style = DataLabel, color = t.ink3)
                }
            }
        }
        Canvas(Modifier.weight(1f).height((7 * 21).dp).semantics { contentDescription = "Spending per day for 12 weeks" }) {
            val gap = 3.dp.toPx()
            val cell = minOf((size.width - gap * (weeks - 1)) / weeks, 18.dp.toPx())
            for (w in 0 until weeks) for (d in 0 until 7) {
                val day = start.plusDays((w * 7 + d).toLong())
                if (day.isBefore(from) || day.isAfter(to)) continue
                val v = perDay[day] ?: 0L
                val color = if (v <= 0) t.section else t.accent.copy(alpha = 0.2f + 0.8f * (v.toFloat() / max))
                drawRoundRect(color, Offset(w * (cell + gap), d * (cell + gap)), Size(cell, cell), CornerRadius(3.dp.toPx()))
            }
        }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("less", style = DataLabel, color = t.ink3)
        listOf(0f, 0.3f, 0.6f, 1f).forEach { a ->
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(12.dp).aspectRatio(1f).clip(RoundedCornerShape(2.dp)).background(if (a == 0f) t.section else t.accent.copy(alpha = 0.2f + 0.8f * a)))
        }
        Spacer(Modifier.width(4.dp))
        Text("more", style = DataLabel, color = t.ink3)
    }
}

// ---------------------------------------------------------------- low-spend streak (More)

@Composable
internal fun StreakSheet(s: Streak, custom: Boolean, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    val t = LocalTones.current
    var limit by remember { mutableStateOf(if (custom) (s.limitPaise / 100).toString() else "") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Low-spend streak", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            Text("Days in a row with everyday spending at or under your limit. Rent, bills and SIPs don't count against it.",
                style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            StatGrid(listOf(Stat("Now", count(s.current, "day"), highlight = true), Stat("Best", count(s.best, "day"), sub = "last 12 weeks")))
            OutlinedTextField(
                limit, { limit = it.filter(Char::isDigit) }, label = { Text("Daily limit, ₹") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(if (custom) "Leave empty to go back to the suggestion." else "Suggested from your typical day: ${plainRupees(s.limitPaise)}.") },
            )
            PrimaryButton("Save", { onSave(parseRupees(limit)?.takeIf { it > 0 }) })
        }
    }
}

// ---------------------------------------------------------------- new recurring (Month · Recurring)

@Composable
internal fun NewRecurringPanel(items: List<NewRecurring>) {
    if (items.isEmpty()) return
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("New recurring", "${items.size}")
        items.forEachIndexed { k, n ->
            if (k > 0) Hairline()
            ListRow(
                title = n.name,
                subtitle = if (n.trial) "${rupeesExact(n.amountPaise)} on ${n.day.format(dayMonth)} · often a free trial that renews" else "New autopay since ${n.day.format(dayMonth)}",
                trailing = { Tag(if (n.trial) "trial?" else "new") },
            )
        }
    }
}

// ---------------------------------------------------------------- year in Spend (More)

/** The last 12 salary months in one page, shareable as an image (through Android's share sheet: no internet). */
@Composable
internal fun YearScreen(snap: Snapshot, onBack: () -> Unit, onShare: () -> Unit) {
    val t = LocalTones.current
    val y = remember(snap) { yearOf(snap) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.padding(top = 16.dp)) { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) } }
        item { ScreenTitle("Your year", "${y.from.format(dayMonth)} ${y.from.year} – ${y.to.format(dayMonth)} ${y.to.year}") }
        item {
            StatGrid(
                listOfNotNull(
                    Stat("Spent", rupees(y.spent), sub = "${rupees(y.perMonth)} a month", highlight = true),
                    Stat("Saved", y.savedRate?.let { "${(it * 100).toInt()}%" } ?: "–", sub = y.salary.takeIf { it > 0 }?.let { "of ${rupees(it)} salary" }),
                    Stat("Biggest month", y.biggestMonth?.let { rupees(it.second) } ?: "–", sub = y.biggestMonth?.first),
                    Stat("Salary growth", y.salaryGrowth?.let { (if (it >= 0) "+" else "") + rupees(it) } ?: "–", sub = "first to latest credit"),
                ),
            )
        }
        item {
            Panel {
                PanelHeader("Top categories")
                y.categories.forEach { (cat, amt) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Swatch(paintFor(cat)); Spacer(Modifier.width(8.dp))
                        Text(cat, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                        Text(rupees(amt), style = AmountStyle, color = t.ink)
                    }
                }
            }
        }
        item {
            Panel {
                PanelHeader("Top places")
                y.places.forEachIndexed { k, (name, amt) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${k + 1}", style = DataLabel, color = t.ink3, modifier = Modifier.width(22.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(rupees(amt), style = AmountStyle, color = t.ink)
                    }
                }
            }
        }
        item { PrimaryButton("Share as image", onShare) }
    }
}

internal class YearSummary(
    val from: LocalDate,
    val to: LocalDate,
    val spent: Long,
    val perMonth: Long,
    val salary: Long,
    val savedRate: Double?,
    val biggestMonth: Pair<String, Long>?,
    val salaryGrowth: Long?,
    val categories: List<Pair<String, Long>>,
    val places: List<Pair<String, Long>>,
)

internal fun yearOf(snap: Snapshot): YearSummary {
    val first = snap.txns.minOfOrNull { it.day }
    val months = Ledger.cycles(snap.today, snap.txns, 12).filter { first == null || it.endInclusive >= first }
    val from = months.lastOrNull()?.start ?: snap.cycleStart
    val inYear = snap.txns.filter { it.day in from..snap.today }
    val spent = inYear.sumOf(Ledger::monthAmount)
    val salaries = inYear.filter { it.isSalary }.sortedBy { it.day }
    val salary = salaries.sumOf { it.amountPaise }
    val perMonth = months.map { r -> cycleMonth(r.start) + " " + r.endInclusive.year to inYear.filter { it.day in r }.sumOf(Ledger::monthAmount) }
    return YearSummary(
        from, snap.today, spent, spent / months.size.coerceAtLeast(1), salary,
        if (salary > 0) (salary - spent).toDouble() / salary else null,
        perMonth.maxByOrNull { it.second },
        if (salaries.size >= 2) salaries.last().amountPaise - salaries.first().amountPaise else null,
        Ledger.byCategoryIn(snap.txns, from..snap.today).filterValues { it > 0 }.toList().sortedByDescending { it.second }.take(5),
        inYear.filter { Ledger.monthAmount(it) > 0 && !it.parsed.counterparty.isNullOrBlank() && it.kind != com.girish.expensetracker.core.Kind.INVESTMENT_SIP && it.kind != com.girish.expensetracker.core.Kind.INVESTMENT_LUMPSUM }
            .groupBy { normalizeName(it.parsed.counterparty!!) }.map { (_, l) -> l.first().parsed.counterparty!! to l.sumOf(Ledger::monthAmount) }
            .sortedByDescending { it.second }.take(5),
    )
}

/** Draws the year card as a picture and opens Android's share sheet. Real amounts, even while they're hidden on screen. */
internal fun shareYear(context: Context, snap: Snapshot, tones: Tones) {
    val y = yearOf(snap)
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(tones.ground.toArgb())
    fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    val ink = tones.ink.toArgb()
    val ink2 = tones.ink2.toArgb()
    val accent = tones.accent.toArgb()
    var yy = 140f
    c.drawText("My year in Spend", 80f, yy, paint(64f, ink, bold = true)); yy += 60f
    c.drawText("${y.from.format(dayMonth)} ${y.from.year} – ${y.to.format(dayMonth)} ${y.to.year}", 80f, yy, paint(36f, ink2)); yy += 120f
    c.drawText(plainRupees(y.spent), 80f, yy, paint(110f, accent, bold = true)); yy += 56f
    c.drawText("spent · ${plainRupees(y.perMonth)} a month", 80f, yy, paint(36f, ink2)); yy += 90f
    y.savedRate?.let { c.drawText("Saved ${(it * 100).toInt()}% of salary", 80f, yy, paint(46f, ink, bold = true)); yy += 80f }
    c.drawText("Top categories", 80f, yy, paint(40f, ink2)); yy += 64f
    y.categories.forEach { (cat, amt) ->
        c.drawText(cat, 80f, yy, paint(44f, ink))
        val txt = plainRupees(amt); val p = paint(44f, ink, bold = true)
        c.drawText(txt, w - 80f - p.measureText(txt), yy, p); yy += 64f
    }
    c.drawText("Made with Spend · offline, from bank SMS", 80f, h - 80f, paint(32f, ink2))
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "spend-year.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share your year").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

