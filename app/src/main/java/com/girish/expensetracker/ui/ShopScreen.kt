package com.girish.expensetracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.Shop
import com.girish.expensetracker.core.Shops
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shopMonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** The three ranges of the shop screen: this month, last month, all time. */
internal fun shopRange(shop: Shop, today: LocalDate, range: Int): ClosedRange<LocalDate> = when (range) {
    0 -> today.withDayOfMonth(1)..today
    1 -> YearMonth.from(today).minusMonths(1).let { it.atDay(1)..it.atEndOfMonth() }
    else -> minOf(shop.first, today)..today
}

/** One shop (Zomato, Swiggy, Blinkit…): spent, orders, average and biggest order, monthly bars and every payment. */
@Composable
internal fun ShopScreen(snap: Snapshot, key: String, isNew: (Row) -> Boolean, onBack: () -> Unit, onRow: (Row) -> Unit) {
    val t = LocalTones.current
    val shop = remember(snap, key) { Shops.find(snap.txns, key) }
    if (shop == null) { onBack(); return }
    var range by remember { mutableStateOf(0) }
    val window = shopRange(shop, snap.today, range)
    val ids = remember(shop) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()).apply { shop.txns.forEach { add(it.parsed) } } }
    val rows = remember(snap, shop) { snap.rows.filter { it.txn.parsed in ids } }
    val inRange = rows.filter { it.txn.day in window }
    val biggest = shop.biggestIn(window)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(shop.name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight(700)), color = t.ink, textAlign = TextAlign.Center)
                Text(
                    "Since ${shop.first.format(dayMonth)} ${shop.first.year} · ${count(shop.txns.size, "payment")} · ${shop.txns.first().category}",
                    style = MaterialTheme.typography.bodySmall, color = t.ink2,
                )
            }
        }
        item { Segmented(listOf("This month", "Last month", "All time"), range, { range = it }) }
        item {
            StatGrid(
                listOf(
                    Stat("Spent", rupees(shop.spentIn(window)), sub = window.start.format(dayMonth) + " – " + window.endInclusive.format(dayMonth), highlight = true),
                    Stat("Orders", "${shop.ordersIn(window)}", sub = "payments"),
                    Stat("Average order", rupees(shop.averageIn(window))),
                    Stat("Biggest order", biggest?.let { rupees(it.ownPaise) } ?: "–", sub = biggest?.day?.let { "${it.format(dayMonth)} ${it.year}" }),
                ),
            )
        }
        item { ShopMonths(shop, snap.today) }
        val byMonth = inRange.groupBy { YearMonth.from(it.txn.day) }.toSortedMap(compareByDescending { it })
        byMonth.forEach { (m, list) ->
            item {
                Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
                    PanelHeader(m.atDay(1).format(shopMonthYear), compact(list.sumOf { Ledger.monthAmount(it.txn) }))
                    list.sortedByDescending { it.txn.receivedAt }.forEach { TxnRow(it, isNew(it), onRow, showDate = true) }
                }
            }
        }
        if (inRange.isEmpty()) item { Panel { Text("Nothing at ${shop.name} in this period.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) } }
    }
}

/** All-time spend per calendar month at this shop, oldest on the left. */
@Composable
private fun ShopMonths(shop: Shop, today: LocalDate) {
    val t = LocalTones.current
    val months = remember(shop, today) { shop.monthly(today) }
    if (months.isEmpty()) return
    val max = months.maxOf { it.second }.coerceAtLeast(1L)
    Panel {
        PanelHeader("By month", "${months.count { it.second > 0 }} of ${months.size} months")
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Spend per month at ${shop.name}" }) {
            val slot = size.width / months.size
            val barW = (slot * 0.6f).coerceIn(1f, 18.dp.toPx())
            drawLine(t.hairline, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            months.forEachIndexed { i, (_, v) ->
                val h = size.height * v.coerceAtLeast(0) / max
                if (h > 0) drawRoundRect(t.accent, Offset(slot * i + (slot - barW) / 2, size.height - h), Size(barW, h), CornerRadius(minOf(barW / 2, 4.dp.toPx())))
            }
        }
        Gap(6.dp)
        Row(Modifier.fillMaxWidth()) {
            Text("${months.first().first.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${months.first().first.year}", style = DataLabel, color = t.ink3, modifier = Modifier.weight(1f))
            Text("Now", style = DataLabel, color = t.ink3)
        }
    }
}

/** A tappable "Shop · ₹X ›" line that opens the shop screen. */
@Composable
internal fun ShopLink(name: String, amount: Long?, onClick: () -> Unit) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().clip(CircleShape).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(t.accent))
        Text("  $name", style = MaterialTheme.typography.bodyMedium, color = t.accent, modifier = Modifier.weight(1f), maxLines = 1)
        amount?.let { Text(rupees(it), style = AmountStyle, color = t.ink2) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = t.ink3)
    }
}
