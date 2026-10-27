package com.girish.expensetracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.data.Snapshot
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** 0 -> 1 once, so a chart draws itself in when it first appears (the one authored chart motion). */
@Composable
private fun drawIn(key: Any?): Float {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    return p.value
}

// ---------------------------------------------------------------- pace

/** Numbers behind the pace chart and the "vs last month" stat. */
class Pace(val now: LongArray, val last: LongArray, val cycleLen: Int, val elapsed: Int) {
    val hasLast get() = last.isNotEmpty() && last.last() > 0
    val nowToday get() = now.lastOrNull() ?: 0L
    val lastSameDay get() = if (last.isEmpty()) 0L else last[(elapsed - 1).coerceIn(0, last.size - 1)]
}

fun pace(snap: Snapshot): Pace {
    val start = snap.cycleStart
    val prevStart = Ledger.previousCycleStart(start, snap.txns)
    val cycleLen = ChronoUnit.DAYS.between(start, start.plusMonths(1)).toInt()
    val prevLen = ChronoUnit.DAYS.between(prevStart, start).toInt()
    val elapsed = (ChronoUnit.DAYS.between(start, snap.today).toInt() + 1).coerceIn(1, cycleLen)
    return Pace(Ledger.pace(snap.txns, start, elapsed), Ledger.pace(snap.txns, prevStart, prevLen), cycleLen, elapsed)
}

/**
 * Running spend this salary month (accent) against last month (quiet), with a dashed even-pace guide.
 * SIPs, lump sums and subscriptions are left out so the 2nd of the month does not spike it. Drag to read a day.
 */
@Composable
fun PaceChart(snap: Snapshot) {
    val t = LocalTones.current
    val p = remember(snap) { pace(snap) }
    val progress = drawIn(snap.cycleStart)
    var pick by remember(snap) { mutableStateOf<Int?>(null) }
    val i = (pick ?: (p.elapsed - 1)).coerceIn(0, p.cycleLen - 1)
    val nowAt = p.now.getOrNull(i)
    val lastAt = if (p.last.isEmpty()) 0L else p.last[i.coerceAtMost(p.last.size - 1)]
    val day = snap.cycleStart.plusDays(i.toLong())
    val lastTotal = if (p.hasLast) p.last.last() else 0L

    Panel {
        PanelHeader("Pace", "${snap.cycleStart.format(shortDate)} → ${snap.cycleStart.plusDays(p.cycleLen - 1L).format(shortDate)}")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rupees(nowAt ?: lastAt), style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), color = t.ink)
            Spacer(Modifier.width(8.dp))
            Text("by ${day.format(shortDate)}", style = MaterialTheme.typography.bodySmall, color = t.ink2, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (p.hasLast && nowAt != null) {
            val diff = nowAt - lastAt
            Text(
                "${rupees(abs(diff))} ${if (diff > 0) "ahead of" else "behind"} last month at this point",
                style = MaterialTheme.typography.bodySmall, color = if (diff > 0) t.warn else t.good,
            )
        }
        Gap(14.dp)
        val maxY = maxOf(lastTotal, p.now.maxOrNull() ?: 0L, 1L).toFloat() * 1.1f
        val minY = minOf(p.now.minOrNull() ?: 0L, p.last.minOrNull() ?: 0L, 0L).toFloat()
        Canvas(
            Modifier.fillMaxWidth().height(140.dp)
                .semantics { contentDescription = "Pace chart, ${rupees(nowAt ?: 0)} by ${day.format(shortDate)}" }
                .pointerInput(p.cycleLen) {
                    fun at(x: Float) = ((x / size.width) * (p.cycleLen - 1)).toInt().coerceIn(0, p.cycleLen - 1)
                    detectTapGestures(onPress = { pick = at(it.x); tryAwaitRelease() })
                }
                .pointerInput(p.cycleLen) {
                    fun at(x: Float) = ((x / size.width) * (p.cycleLen - 1)).toInt().coerceIn(0, p.cycleLen - 1)
                    detectDragGestures { change, _ -> pick = at(change.position.x) }
                },
        ) {
            val w = size.width
            val h = size.height
            fun x(d: Int) = if (p.cycleLen <= 1) 0f else w * d / (p.cycleLen - 1)
            fun y(v: Long) = h - h * ((v - minY) / (maxY - minY))
            for (k in 0..3) {
                val gy = h * k / 3f
                drawLine(t.hairline, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
            }
            if (p.hasLast) {
                drawLine(t.ink3.copy(alpha = 0.6f), Offset(0f, y(0)), Offset(w * progress, y(0) + (y(lastTotal) - y(0)) * progress),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
            }
            fun series(values: LongArray, color: Color, width: Float) {
                if (values.isEmpty()) return
                val n = (values.size * progress).toInt().coerceIn(1, values.size)
                val path = Path().apply {
                    moveTo(x(0), y(values[0]))
                    for (k in 1 until n) lineTo(x(k), y(values[k]))
                }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
            }
            if (p.hasLast) series(p.last.copyOf(minOf(p.last.size, p.cycleLen)), t.ink3, 1.5.dp.toPx())
            // Soft fill under this month's line, fading to the ground.
            if (p.now.isNotEmpty()) {
                val n = (p.now.size * progress).toInt().coerceIn(1, p.now.size)
                val area = Path().apply {
                    moveTo(x(0), y(0))
                    for (k in 0 until n) lineTo(x(k), y(p.now[k]))
                    lineTo(x(n - 1), y(0))
                    close()
                }
                drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(t.accent.copy(alpha = 0.22f), t.accent.copy(alpha = 0f))))
            }
            series(p.now, t.accent, 2.dp.toPx())
            if (progress >= 1f) {
                drawLine(t.ink2.copy(alpha = 0.35f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1f)
                nowAt?.let {
                    drawCircle(t.panel, 6.dp.toPx(), Offset(x(i), y(it)))
                    drawCircle(t.accent, 4.dp.toPx(), Offset(x(i), y(it)))
                }
            }
        }
        Gap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LineKey(t.accent, "This month")
            if (p.hasLast) { LineKey(t.ink3, "Last month"); LineKey(t.ink3, "Even pace", dashed = true) }
        }
        Text("Excludes SIPs and subscriptions · drag to read a day", style = MaterialTheme.typography.labelSmall, color = t.ink3, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun LineKey(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 2.dp)) {
            drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalTones.current.ink2)
    }
}

// ---------------------------------------------------------------- donut

/** At most five named slices; everything smaller folds into "Other". */
fun topWithOther(slices: List<Pair<String, Long>>, keep: Int = 5): List<Pair<String, Long>> {
    if (slices.size <= keep + 1) return slices
    return slices.take(keep) + ("Other" to slices.drop(keep).sumOf { it.second })
}

/** Thin ring with gaps between slices and the total (or the tapped slice) in the middle. */
@Composable
fun DonutChart(slices: List<Pair<String, Long>>, total: Long, selected: String?, onSelect: (String?) -> Unit) {
    val t = LocalTones.current
    val gross = slices.sumOf { it.second }
    val picked = slices.firstOrNull { it.first == selected }
    val progress = drawIn(slices.size)

    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(220.dp)
                .semantics { contentDescription = "Spending by category, total ${rupees(total)}" }
                .pointerInput(slices) {
                    detectTapGestures { pt ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = hypot(pt.x - cx, pt.y - cy)
                        if (r < size.width / 2f - 40.dp.toPx() || gross <= 0) { onSelect(null); return@detectTapGestures }
                        var deg = Math.toDegrees(atan2((pt.y - cy).toDouble(), (pt.x - cx).toDouble())).toFloat() + 90f
                        if (deg < 0) deg += 360f
                        var acc = 0f
                        for ((cat, amt) in slices) {
                            acc += 360f * amt / gross
                            if (deg <= acc) { onSelect(if (cat == selected) null else cat); return@detectTapGestures }
                        }
                    }
                },
        ) {
            val stroke = 18.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(t.section, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (gross > 0) {
                val gapDeg = if (slices.size > 1) 2.5f else 0f
                var start = -90f
                slices.forEach { (cat, amt) ->
                    val sweep = 360f * amt / gross * progress
                    val dim = selected != null && cat != selected
                    val color = (if (cat == "Other") OtherPaint else paintFor(cat)).let { if (dim) it.copy(alpha = 0.25f) else it }
                    drawArc(color, start + gapDeg / 2, (sweep - gapDeg).coerceAtLeast(0.5f), false, topLeft, arcSize,
                        style = Stroke(if (cat == selected) stroke + 6.dp.toPx() else stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(picked?.first ?: "Spent", style = MaterialTheme.typography.bodySmall, color = t.ink2)
            RollingAmount(picked?.second ?: total, MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), t.ink)
            if (picked != null && gross > 0) Text("${picked.second * 100 / gross}% of spend", style = DataLabel, color = t.ink3)
        }
    }
}

// ---------------------------------------------------------------- months

/** One stacked bar per salary month (newest on the right), split by the biggest categories plus Other. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthsBars(snap: Snapshot, withInvestments: Boolean) {
    val t = LocalTones.current
    val cycles = remember(snap) { Ledger.cycles(snap.today, snap.txns, 4).reversed() }
    val perCycle = remember(snap, withInvestments) {
        cycles.map { r -> Ledger.byCategoryIn(snap.txns, r).filter { (k, v) -> v > 0 && (withInvestments || k != "Investment") } }
    }
    val top = perCycle.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        .entries.sortedByDescending { it.value }.take(5).map { it.key }
    val stacks = perCycle.map { m -> top.map { it to (m[it] ?: 0L) } + ("Other" to m.filterKeys { it !in top }.values.sum()) }
    val totals = stacks.map { s -> s.sumOf { it.second } }
    val maxTotal = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val progress = drawIn(withInvestments)

    Panel {
        PanelHeader("Last four salary months", "from the ${Ledger.salaryDay}${if (Ledger.salaryDay in 11..13) "th" else when (Ledger.salaryDay % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }}")
        if (totals.none { it > 0 }) {
            Text("Not enough history yet.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            return@Panel
        }
        Row(Modifier.fillMaxWidth()) {
            totals.forEach { v ->
                Text(compact(v), style = AmountStyle, color = t.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Gap(8.dp)
        Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "Spend per salary month: " + totals.joinToString { rupees(it) } }) {
            val slot = size.width / stacks.size
            val barW = minOf(slot * 0.42f, 44.dp.toPx())
            drawLine(t.hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            stacks.forEachIndexed { b, stack ->
                val left = slot * b + (slot - barW) / 2
                var yy = size.height
                val nonEmpty = stack.filter { it.second > 0 }
                nonEmpty.forEachIndexed { k, (cat, amt) ->
                    val hgt = size.height * amt / maxTotal * progress
                    val top = yy - hgt
                    val color = if (cat == "Other") OtherPaint else paintFor(cat)
                    val isTop = k == nonEmpty.lastIndex
                    drawRoundRect(color, Offset(left, top + if (k > 0) 1.dp.toPx() else 0f), Size(barW, hgt - if (k > 0) 1.dp.toPx() else 0f),
                        cornerRadius = CornerRadius(if (isTop) 4.dp.toPx() else 1.dp.toPx()))
                    yy = top
                }
            }
        }
        Gap(8.dp)
        Row(Modifier.fillMaxWidth()) {
            cycles.forEachIndexed { k, r ->
                Text(
                    if (k == cycles.lastIndex) "NOW" else cycleMonth(r.start).uppercase(),
                    style = DataLabel, color = t.ink3, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                )
            }
        }
        Gap(14.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (top + "Other").forEach { cat ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(if (cat == "Other") OtherPaint else paintFor(cat))
                    Spacer(Modifier.width(6.dp))
                    Text(cat, style = MaterialTheme.typography.labelMedium, color = t.ink2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- daily bars (Firnhold)

/** Daily spend for a range plus the same days of the previous period, for the faded comparison bars. */
class DailySeries(val days: List<java.time.LocalDate>, val now: LongArray, val prior: LongArray) {
    val total get() = now.sum()
    val priorTotal get() = prior.sum()
}

fun dailySeries(snap: Snapshot, salaryMonth: Boolean): DailySeries {
    val byDay = snap.txns.groupBy { it.day }.mapValues { (_, l) -> l.sumOf(Ledger::todayAmount) }
    val days: List<java.time.LocalDate>
    val priorDays: List<java.time.LocalDate>
    if (salaryMonth) {
        val start = snap.cycleStart
        val prevStart = Ledger.previousCycleStart(start, snap.txns)
        val n = ChronoUnit.DAYS.between(start, snap.today).toInt() + 1
        days = (0 until n).map { start.plusDays(it.toLong()) }
        priorDays = (0 until n).map { prevStart.plusDays(it.toLong()) }
    } else {
        days = (6 downTo 0).map { snap.today.minusDays(it.toLong()) }
        priorDays = days.map { it.minusDays(7) }
    }
    return DailySeries(days, days.map { (byDay[it] ?: 0L).coerceAtLeast(0) }.toLongArray(), priorDays.map { (byDay[it] ?: 0L).coerceAtLeast(0) }.toLongArray())
}

/** Full-width daily bars: this period in mint (soft gradient), last period as faded grey bars behind. Tap a bar to read it. */
@Composable
fun DailyBars(series: DailySeries, onPick: (Int?) -> Unit, picked: Int?) {
    val t = LocalTones.current
    val progress = drawIn(series.days.firstOrNull() to series.days.size)
    val maxV = maxOf(series.now.maxOrNull() ?: 0L, series.prior.maxOrNull() ?: 0L, 1L).toFloat() * 1.12f
    val n = series.days.size
    Canvas(
        Modifier.fillMaxWidth().height(168.dp)
            .semantics { contentDescription = "Daily spend, ${rupees(series.total)} this period against ${rupees(series.priorTotal)} last period" }
            .pointerInput(n) {
                detectTapGestures { pt ->
                    val i = (pt.x / size.width * n).toInt().coerceIn(0, n - 1)
                    onPick(if (i == picked) null else i)
                }
            },
    ) {
        val slot = size.width / n
        val barW = (slot * if (n <= 7) 0.46f else 0.62f).coerceAtMost(28.dp.toPx())
        val r = CornerRadius(minOf(barW / 2, 6.dp.toPx()))
        for (k in 1..3) {
            val gy = size.height * k / 4f
            drawLine(t.hairline, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
        }
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val ph = size.height * series.prior[i] / maxV * progress
            if (ph > 0) drawRoundRect(t.ink3.copy(alpha = 0.28f), Offset(cx - barW / 2, size.height - ph), Size(barW, ph), r)
            val h = size.height * series.now[i] / maxV * progress
            if (h > 0) {
                val dim = picked != null && picked != i
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(t.accent.copy(alpha = if (dim) 0.35f else 1f), t.accent.copy(alpha = if (dim) 0.15f else 0.55f)),
                    startY = size.height - h, endY = size.height,
                )
                drawRoundRect(brush, Offset(cx - barW * 0.36f, size.height - h), Size(barW * 0.72f, h), r)
            }
        }
    }
    Gap(8.dp)
    Row(Modifier.fillMaxWidth()) {
        series.days.forEachIndexed { i, d ->
            val show = n <= 7 || i % 5 == 0 || i == n - 1
            Text(
                if (!show) "" else if (n <= 7) d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.ENGLISH) else "${d.dayOfMonth}",
                style = DataLabel, color = if (picked == i) t.ink else t.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1,
            )
        }
    }
}

/** Range readout plus comparison rows (Firnhold's stats card). */
@Composable
fun RangeStats(series: DailySeries, payments: Int, priorPayments: Int) {
    val t = LocalTones.current
    val n = series.days.size.coerceAtLeast(1)
    val avg = series.total / n
    val priorAvg = series.priorTotal / n
    val best = series.now.indices.maxByOrNull { series.now[it] }
    @Composable
    fun StatRow(label: String, value: String, compare: String?, up: Boolean?) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(value, style = AmountStyle, color = t.ink)
                if (compare != null) Text(compare, style = DataLabel, color = when (up) { true -> t.warn; false -> t.good; null -> t.ink3 })
            }
        }
    }
    fun cmp(now: Long, before: Long): Pair<String?, Boolean?> = when {
        before == 0L -> null to null
        now == before -> "same as last" to null
        now > before -> "▲ ${rupees(now - before)} vs last" to true
        else -> "▼ ${rupees(before - now)} vs last" to false
    }
    val (c1, u1) = cmp(avg, priorAvg)
    StatRow("Average per day", rupees(avg), c1, u1)
    Hairline()
    StatRow("Biggest day", best?.let { rupees(series.now[it]) } ?: "–", best?.let { series.days[it].format(shortDate) }, null)
    Hairline()
    val pd = payments - priorPayments
    StatRow("Payments", "$payments", if (priorPayments == 0) null else if (pd == 0) "same as last" else "${if (pd > 0) "▲" else "▼"} ${abs(pd)} vs last", if (pd == 0 || priorPayments == 0) null else pd > 0)
}

/** Same point in the previous salary month, for fair "vs last month" deltas mid-month. */
fun lastMonthSoFar(snap: Snapshot): Map<String, Long> {
    val start = snap.cycleStart
    val prevStart = Ledger.previousCycleStart(start, snap.txns)
    val elapsed = ChronoUnit.DAYS.between(start, snap.today)
    val end = minOf(prevStart.plusDays(elapsed), start.minusDays(1))
    return Ledger.byCategoryIn(snap.txns, prevStart..end)
}

// ---------------------------------------------------------------- hero chart (Firnhold)

/** Bucketed spend for the hero: bars per bucket, optional same-length prior period for the faded bars and grey line. */
class HeroSeries(val labels: List<String>, val now: LongArray, val prior: LongArray?, val rangeLabel: String)

fun heroSeries(snap: Snapshot, range: Int): HeroSeries = when (range) {
    0, 1 -> {
        val d = dailySeries(snap, salaryMonth = range == 1)
        val labels = d.days.mapIndexed { i, day ->
            if (range == 0) day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
            else if (i == 0 || (i + 1) % 7 == 0 || i == d.days.lastIndex) "${day.dayOfMonth}" else ""
        }
        HeroSeries(labels, d.now, d.prior, "${d.days.first().format(shortDate)} – ${d.days.last().format(shortDate)}".uppercase())
    }
    else -> {
        val cycles = Ledger.cycles(snap.today, snap.txns, 4).reversed()
        val totals = cycles.map { r -> snap.txns.filter { it.day in r }.sumOf(Ledger::monthAmount).coerceAtLeast(0) }
        HeroSeries(
            cycles.mapIndexed { k, r -> if (k == cycles.lastIndex) "Now" else cycleMonth(r.start) },
            totals.toLongArray(), null,
            "${cycles.first().start.format(shortDate)} – ${snap.today.format(shortDate)}".uppercase(),
        )
    }
}

/**
 * Firnhold hero, drawn straight on the canvas with a soft mint glow. Top band: running total this period (thick mint,
 * ring at the end) against the previous period (grey). Bottom band: bars per bucket with the previous period faded
 * beside them, plus an average line labelled on the right axis.
 */
@Composable
fun HeroChart(series: HeroSeries, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    val progress = drawIn(series.labels.size to series.now.sum())
    val n = series.now.size
    val cumNow = series.now.toList().runningFold(0L) { a, b -> a + b }.drop(1)
    val cumPrior = series.prior?.toList()?.runningFold(0L) { a, b -> a + b }?.drop(1)
    val lineMax = maxOf(cumNow.lastOrNull() ?: 0L, cumPrior?.lastOrNull() ?: 0L, 1L).toFloat() * 1.08f
    val barMax = maxOf(series.now.maxOrNull() ?: 0L, series.prior?.maxOrNull() ?: 0L, 1L).toFloat() * 1.1f
    val avg = if (n == 0) 0L else series.now.sum() / n
    val axis = DataLabel.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(300.dp).semantics { contentDescription = "Spend ${rupees(series.now.sum())} for ${series.rangeLabel}" }) {
            val axisW = 44.dp.toPx()
            val w = size.width - axisW
            val lineTop = 12.dp.toPx()
            val lineBottom = size.height * 0.52f
            val barTop = size.height * 0.60f
            val barBottom = size.height
            val slot = w / n.coerceAtLeast(1)
            fun cx(i: Int) = slot * i + slot / 2
            val glowCenter = Offset(w * 0.55f, lineBottom * 0.55f)
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(listOf(t.accent.copy(alpha = 0.16f), Color.Transparent), center = glowCenter, radius = size.width * 0.7f),
                radius = size.width * 0.7f, center = glowCenter,
            )
            fun ly(v: Long) = lineBottom - (lineBottom - lineTop) * (v / lineMax)
            fun by(v: Long) = barBottom - (barBottom - barTop) * (v / barMax)
            listOf(lineMax / 1.08f, lineMax / 1.08f / 2).forEach { v ->
                val yy = ly(v.toLong())
                drawLine(t.hairline, Offset(0f, yy), Offset(w, yy), strokeWidth = 1f)
                drawText(measurer, compact(v.toLong()), Offset(w + 8.dp.toPx(), yy - 7.dp.toPx()), axis.copy(color = t.ink3))
            }
            drawLine(t.hairline, Offset(0f, barBottom - 1f), Offset(w, barBottom - 1f), strokeWidth = 1f)
            fun line(values: List<Long>, color: Color, width: Float) {
                if (values.isEmpty()) return
                val k = (values.size * progress).toInt().coerceIn(1, values.size)
                val path = Path().apply {
                    moveTo(cx(0), ly(values[0]))
                    for (i in 1 until k) lineTo(cx(i), ly(values[i]))
                }
                drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            cumPrior?.let { line(it, t.ink3, 2.dp.toPx()) }
            line(cumNow, t.accent, 4.dp.toPx())
            if (progress >= 1f && cumNow.isNotEmpty()) {
                val end = Offset(cx(cumNow.lastIndex), ly(cumNow.last()))
                drawLine(t.ink3.copy(alpha = 0.5f), end, Offset(end.x, barBottom), strokeWidth = 1.dp.toPx())
                drawCircle(t.ground, 9.dp.toPx(), end)
                drawCircle(t.accent, 9.dp.toPx(), end, style = Stroke(3.dp.toPx()))
                cumPrior?.getOrNull(cumNow.lastIndex)?.let { drawCircle(t.ink3, 4.dp.toPx(), Offset(end.x, ly(it))) }
            }
            val pair = series.prior != null
            val barW = (slot * if (pair) 0.30f else 0.46f).coerceAtMost(14.dp.toPx())
            val r = CornerRadius(barW / 2)
            for (i in 0 until n) {
                val x = cx(i)
                series.prior?.let { pr ->
                    val h = (barBottom - by(pr[i])) * progress
                    if (h > 0) drawRoundRect(t.ink3.copy(alpha = 0.45f), Offset(x - barW - 1.dp.toPx(), barBottom - h), Size(barW, h), r)
                }
                val h = (barBottom - by(series.now[i])) * progress
                if (h > 0) drawRoundRect(t.accent, Offset(if (pair) x + 1.dp.toPx() else x - barW / 2, barBottom - h), Size(barW, h), r)
            }
            if (avg > 0) {
                val ay = by(avg)
                drawLine(t.accent.copy(alpha = 0.7f), Offset(0f, ay), Offset(w, ay), strokeWidth = 1.dp.toPx())
                drawText(measurer, compact(avg), Offset(w + 8.dp.toPx(), ay - 7.dp.toPx()), axis.copy(color = t.accent))
            }
        }
        Gap(8.dp)
        Row(Modifier.fillMaxWidth().padding(end = 44.dp)) {
            series.labels.forEach { l ->
                Text(l, style = DataLabel, color = t.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- stat grid (Firnhold overview)

class Stat(val label: String, val value: String, val unit: String? = null, val sub: String? = null, val up: Boolean? = null, val highlight: Boolean = false)

@Composable
fun StatGrid(stats: List<Stat>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { StatCard(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatCard(s: Stat, modifier: Modifier) {
    val t = LocalTones.current
    val onMint = Color(0xFF04211C)
    val bg = if (s.highlight) t.accent else t.panel
    val ink = if (s.highlight) onMint else t.ink
    val ink2 = if (s.highlight) onMint.copy(alpha = 0.7f) else t.ink2
    Row(
        modifier.clip(PanelShape).background(bg)
            .then(if (s.highlight) Modifier else Modifier.border(1.dp, t.hairline, PanelShape))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.up != null) {
            Box(
                Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (s.highlight) onMint.copy(alpha = 0.15f) else t.raised),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (s.up) "↑" else "↓", style = MaterialTheme.typography.labelLarge, color = if (s.highlight) ink else if (s.up) t.warn else t.good)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(s.label, style = MaterialTheme.typography.bodySmall, color = ink2, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"), color = ink, maxLines = 1)
                if (s.unit != null) Text(s.unit, style = MaterialTheme.typography.bodySmall, color = ink2, modifier = Modifier.padding(start = 3.dp, bottom = 2.dp))
            }
            if (s.sub != null) Text(s.sub, style = MaterialTheme.typography.labelMedium, color = ink2, maxLines = 1)
        }
    }
}
