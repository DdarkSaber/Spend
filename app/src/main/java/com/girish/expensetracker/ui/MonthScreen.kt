package com.girish.expensetracker.ui

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
    val byCat = remember(snap) { Ledger.byCategory(snap.txns, snap.today) }
    val lastSoFar = remember(snap) { lastMonthSoFar(snap) }
    val shown = byCat.filterKeys { withInvestments || it != "Investment" }
    val slices = shown.filterValues { it > 0 }.toList().sortedByDescending { it.second }
    val gross = slices.sumOf { it.second }
    val negatives = shown.filterValues { it < 0 }
    val total = shown.values.sum()
    val ring = topWithOther(slices)
    val inOther = slices.map { it.first }.toSet() - ring.map { it.first }.toSet()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Month", "Salary month since ${snap.cycleStart.format(dayMonth)}") }
        item { Segmented(listOf("Overview", "Trends", "Recurring"), view, { view = it }) }
        if (view == 0) {
            item { SalaryCard(snap) }
            item { BudgetPanel(snap.budgets, onBudgets) }
            snap.rentCandidates.forEach { c -> item { RentCard(c, onMark = { onRent(c, true) }, onDismiss = { onRent(c, false) }) } }
            if (snap.rows.isNotEmpty()) item { PaceChart(snap) }
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
                    if (slices.isEmpty()) Text("Nothing spent yet this salary month.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
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
                                val before = lastSoFar[cat] ?: 0L
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
                                    snap.rows.filter { it.txn.category == cat && it.txn.day >= snap.cycleStart && Ledger.monthAmount(it.txn) != 0L }
                                        .sortedByDescending { it.txn.receivedAt }
                                        .forEach { TxnRow(it, isNew(it), onRow) }
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
            item { MonthsBars(snap, withInvestments) }
            item { SavingsPanel(snap.savings) }
            item { YearPanel(snap) }
        }
        if (view == 2) {
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

/** Salary credited this salary month, what has gone out, and what is left; earlier salaries below. */
@Composable
private fun SalaryCard(snap: Snapshot) {
    val t = LocalTones.current
    val salaries = snap.rows.filter { it.txn.isSalary }.sortedByDescending { it.txn.day }
    val current = salaries.firstOrNull { it.txn.day >= snap.cycleStart }
    Panel {
        PanelHeader("Salary", chevron = true)
        if (salaries.isEmpty()) {
            Text("No salary credit found yet. It shows up here when your bank's NEFT SMS says SALARY.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            return@Panel
        }
        val salary = (current ?: salaries.first()).txn
        val spent = snap.monthTotal
        RollingAmount(salary.amountPaise, MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700), fontFeatureSettings = "tnum"), t.ink)
        Text(
            (if (current != null) "credited " else "last credited ") + salary.day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + " · " + sourceLabel(salary.parsed.source),
            style = MaterialTheme.typography.bodySmall, color = t.ink2,
        )
        if (current != null) {
            Gap(16.dp)
            val used = (spent.toFloat() / salary.amountPaise).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(t.raised)) {
                Box(Modifier.fillMaxWidth(used).fillMaxHeight().clip(CircleShape).background(if (spent > salary.amountPaise) t.warn else t.accent))
            }
            Gap(10.dp)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(rupees(spent), style = AmountStyle, color = t.ink)
                    Text("spent so far (${(used * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rupees(salary.amountPaise - spent), style = AmountStyle, color = if (spent > salary.amountPaise) t.warn else t.good)
                    Text("left from salary", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                }
            }
        }
        if (salaries.size > 1) {
            Gap(14.dp)
            Hairline()
            salaries.take(6).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(r.txn.day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)), style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.weight(1f))
                    Text(rupees(r.txn.amountPaise), style = AmountStyle, color = t.ink)
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
