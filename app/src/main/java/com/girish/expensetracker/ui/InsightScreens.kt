@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.girish.expensetracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Emi
import com.girish.expensetracker.core.Holding
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.LearnedLayout
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.SavingsMonth
import com.girish.expensetracker.core.SmsReader
import com.girish.expensetracker.core.Teach
import com.girish.expensetracker.core.Trip
import com.girish.expensetracker.core.Unusual
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.data.UnparsedSms
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

// ---------------------------------------------------------------- app lock

/** Shown instead of the app until the owner unlocks with fingerprint, face or the phone's PIN. */
@Composable
internal fun LockScreen(onUnlock: () -> Unit) {
    val t = LocalTones.current
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(t.raised), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = t.accent, modifier = Modifier.size(32.dp))
        }
        Gap(20.dp)
        Text("Spend is locked", style = MaterialTheme.typography.headlineSmall, color = t.ink)
        Text("Unlock with your fingerprint, face or phone PIN.", style = MaterialTheme.typography.bodyMedium, color = t.ink2, textAlign = TextAlign.Center)
        Gap(24.dp)
        PrimaryButton("Unlock", onUnlock)
    }
}

// ---------------------------------------------------------------- worth a look

@Composable
internal fun WorthALookPanel(items: List<Unusual>) {
    if (items.isEmpty()) return
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("Worth a look", "${items.size}")
        items.take(5).forEachIndexed { k, u ->
            if (k > 0) Hairline()
            ListRow(
                title = u.title, subtitle = u.text,
                leading = {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(t.warn.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = t.warn, modifier = Modifier.size(20.dp))
                    }
                },
                trailing = { Text(u.day.format(dm).uppercase(), style = DataLabel, color = t.ink3) },
            )
        }
    }
}

// ---------------------------------------------------------------- savings

@Composable
internal fun SavingsPanel(months: List<SavingsMonth>) {
    if (months.isEmpty()) return
    val t = LocalTones.current
    val now = months.last()
    Panel {
        PanelHeader("Savings rate", "salary − spend")
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(now.rate * 100).toInt()}%", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight(700)), color = if (now.rate < 0) t.warn else t.ink)
            Spacer(Modifier.width(8.dp))
            Text("saved so far this salary month · ${rupees(now.savedPaise)}", style = MaterialTheme.typography.bodySmall, color = t.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (months.size > 1) {
            Gap(14.dp)
            Canvas(Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = "Savings rate per month: " + months.joinToString { "${(it.rate * 100).toInt()}%" } }) {
                val slot = size.width / months.size
                val barW = minOf(slot * 0.45f, 28.dp.toPx())
                months.forEachIndexed { i, m ->
                    val h = (size.height * m.rate.coerceIn(0.0, 1.0)).toFloat()
                    drawRoundRect(if (i == months.lastIndex) t.accent else t.ink3.copy(alpha = 0.55f), Offset(slot * i + (slot - barW) / 2, size.height - h), Size(barW, h), CornerRadius(4.dp.toPx()))
                }
            }
            Gap(6.dp)
            Row(Modifier.fillMaxWidth()) {
                months.forEach { m ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(m.rate * 100).toInt()}%", style = DataLabel, color = t.ink2)
                        Text(cycleMonth(m.start).uppercase(), style = DataLabel, color = t.ink3)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- investments

@Composable
internal fun InvestmentsPanel(holdings: List<Holding>) {
    if (holdings.isEmpty()) return
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("Investments", "${rupees(holdings.sumOf { it.thisMonthPaise })} this month")
        Text(rupees(holdings.sumOf { it.totalPaise }), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)), color = t.ink)
        Text("put in since the first SMS", style = MaterialTheme.typography.labelMedium, color = t.ink2, modifier = Modifier.padding(bottom = 6.dp))
        holdings.forEachIndexed { k, h ->
            if (k > 0) Hairline()
            ListRow(
                title = h.platform,
                subtitle = listOfNotNull(h.sipPaise.takeIf { it > 0 }?.let { "SIP ${rupees(it)}" }, h.lumpPaise.takeIf { it > 0 }?.let { "lump sum ${rupees(it)}" }, "last ${h.last.format(dm)}").joinToString(" · "),
                leading = { Swatch(paintFor("Investment"), 12.dp) },
                trailing = { Text(rupees(h.totalPaise), style = AmountStyle, color = t.ink) },
            )
        }
    }
}

// ---------------------------------------------------------------- EMIs

@Composable
internal fun EmiPanel(emis: List<Emi>) {
    if (emis.isEmpty()) return
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("EMIs", "${rupees(emis.sumOf { it.monthlyPaise })} / month")
        emis.forEachIndexed { k, e ->
            if (k > 0) Hairline()
            ListRow(
                title = e.name,
                subtitle = "${count(e.paid, "instalment")} paid since ${e.first.format(dm)} · next ${e.next.format(dm)}",
                trailing = { Text(rupees(e.monthlyPaise), style = AmountStyle, color = t.ink) },
            )
        }
    }
}

// ---------------------------------------------------------------- trips

/** Day-to-day spending inside the trip's dates, plus anything tagged with the trip's name (a flight booked weeks before). */
internal fun tripRows(snap: Snapshot, trip: Trip): List<Row> = snap.rows.filter { r ->
    val x = r.txn
    val counts = Ledger.monthAmount(x) != 0L && x.kind != Kind.INVESTMENT_SIP && x.kind != Kind.INVESTMENT_LUMPSUM
    val inDates = x.day in trip.start..trip.end && x.category !in setOf("Rent", "Bills", "Bills & EMI", "Subscriptions")
    counts && (inDates || snap.tagOf(r).equals(trip.name, ignoreCase = true))
}

@Composable
internal fun TripsScreen(snap: Snapshot, isNew: (Row) -> Boolean, onBack: () -> Unit, onNew: () -> Unit, onDelete: (Trip) -> Unit, onRow: (Row) -> Unit) {
    val t = LocalTones.current
    var open by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 16.dp)) { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) } }
        item { ScreenTitle("Trips", "Spend for a trip or event, apart from normal months") }
        item { PrimaryButton("New trip", onNew) }
        if (snap.trips.isEmpty()) item {
            Panel { Text("Add a trip with its dates. Payments in those days are grouped (rent and bills are left out), plus anything you tag with the trip's name, like a flight booked earlier.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) }
        }
        snap.trips.forEach { trip ->
            item(key = trip.id) {
                val rows = remember(snap, trip) { tripRows(snap, trip).sortedByDescending { it.txn.receivedAt } }
                val total = rows.sumOf { Ledger.monthAmount(it.txn) }
                val days = (java.time.temporal.ChronoUnit.DAYS.between(trip.start, trip.end) + 1).coerceAtLeast(1)
                Panel {
                    Row(Modifier.fillMaxWidth().clickable { open = if (open == trip.id) null else trip.id }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trip.name, style = MaterialTheme.typography.titleMedium, color = t.ink)
                            Text("${trip.start.format(dm)} – ${trip.end.format(dm)} · ${count(rows.size, "payment")} · ${rupees(total / days)}/day", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                        }
                        Text(rupees(total), style = AmountStyle, color = t.ink)
                    }
                    if (open == trip.id) {
                        Gap(10.dp)
                        rows.groupBy { it.txn.category }.mapValues { (_, r) -> r.sumOf { Ledger.monthAmount(it.txn) } }.toList().sortedByDescending { it.second }.forEach { (cat, amt) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Swatch(paintFor(cat)); Spacer(Modifier.width(8.dp))
                                Text(cat, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                                Text(rupees(amt), style = AmountStyle, color = t.ink)
                            }
                        }
                        Gap(6.dp)
                        Hairline()
                        rows.forEach { TxnRow(it, isNew(it), onRow) }
                        TextButton(onClick = { onDelete(trip) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = t.warn, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete trip", color = t.warn)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NewTripSheet(onDismiss: () -> Unit, onSave: (String, LocalDate, LocalDate) -> Unit) {
    val t = LocalTones.current
    var name by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    val state = rememberDateRangePickerState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New trip", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Goa trip") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            SecondaryButton(range?.let { "${it.first.format(dm)} – ${it.second.format(dm)}" } ?: "Pick dates", { picking = true }, Modifier.fillMaxWidth())
            PrimaryButton("Save trip", { range?.let { onSave(name.trim(), it.first, it.second) } }, enabled = name.isNotBlank() && range != null)
        }
    }
    if (picking) {
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    val s = state.selectedStartDateMillis
                    val e = state.selectedEndDateMillis ?: s
                    if (s != null && e != null) range = day(s) to day(e)
                    picking = false
                }) { Text("OK", color = t.accent) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = t.ink2) } },
        ) { DateRangePicker(state = state, modifier = Modifier.weight(1f)) }
    }
}

private fun day(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

// ---------------------------------------------------------------- tags

/** The tag field inside a payment's sheet, with the owner's existing tags one tap away. */
@Composable
internal fun TagEditor(current: String?, known: List<String>, onSave: (String) -> Unit) {
    val t = LocalTones.current
    var tag by remember(current) { mutableStateOf(current.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tag", style = MaterialTheme.typography.titleSmall, color = t.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(tag, { tag = it }, label = { Text("e.g. Goa trip, office, gift") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onSave(tag) }, enabled = tag != current.orEmpty()) { Text(if (tag.isBlank() && current != null) "Remove" else "Save", color = t.accent) }
        }
        if (known.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            known.take(8).forEach { k -> FilterChip(tag == k, { tag = k }, { Text(k) }, colors = chipColors(), border = chipBorder(tag == k)) }
        }
    }
}

// ---------------------------------------------------------------- teach an SMS

private class Word(val text: String, val range: IntRange)

/**
 * Teach Spend an SMS it couldn't read: tap the amount, optionally the payee words, say money out or in and card or
 * account. A preview shows what will be read before saving. Everything happens on the phone.
 */
@Composable
internal fun TeachSheet(sms: UnparsedSms, onDismiss: () -> Unit, onSave: (LearnedLayout) -> Unit) {
    val t = LocalTones.current
    val text = remember(sms) { Teach.normalize(sms.body) }
    val words = remember(text) { Regex("\\S+").findAll(text).map { Word(it.value, it.range) }.toList() }
    val bank = remember(sms) { SmsReader.bankOf(sms.sender) ?: "BANK" }
    var amountAt by remember { mutableStateOf<Int?>(null) }
    var payee by remember { mutableStateOf<IntRange?>(null) } // word indexes
    var debit by remember { mutableStateOf(true) }
    var card by remember { mutableStateOf(Regex("card", RegexOption.IGNORE_CASE).containsMatchIn(text) && !Regex("debit card", RegexOption.IGNORE_CASE).containsMatchIn(text)) }
    var step by remember { mutableStateOf(0) } // 0 amount, 1 payee

    val amountRange = amountAt?.let { i ->
        val w = words[i]
        Regex("\\d[\\d,]*(?:\\.\\d{1,2})?").find(w.text)?.let { m -> (w.range.first + m.range.first)..(w.range.first + m.range.last) }
    }
    val payeeRange = payee?.let { words[it.first].range.first..words[it.last].range.last.let { end -> if (words[it.last].text.endsWith('.')) end - 1 else end } }
    val layout = amountRange?.let { Teach.learn(bank, text, it, payeeRange, debit, card) }
    val preview = layout?.let { Teach.parse(it, text) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Teach this SMS", style = MaterialTheme.typography.headlineSmall, color = t.ink)
                Text("${sms.sender} · learned on this phone, nothing is sent anywhere", style = MaterialTheme.typography.bodySmall, color = t.ink2)
            }
            item { Segmented(listOf("1 · Tap the amount", "2 · Tap the payee"), step, { step = it }) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    words.forEachIndexed { i, w ->
                        val isAmount = i == amountAt
                        val isPayee = payee?.contains(i) == true
                        Text(
                            w.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isAmount || isPayee) t.ink else t.ink2,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (isAmount) t.accentSoft else if (isPayee) t.raised else t.section)
                                .border(1.dp, if (isAmount) t.accent else if (isPayee) t.ink3 else t.hairline, RoundedCornerShape(6.dp))
                                .clickable {
                                    if (step == 0) { amountAt = i; step = 1 } else {
                                        // Tapping extends the payee to a run of words; tapping its first word again clears it.
                                        payee = when {
                                            payee == null -> i..i
                                            i == payee!!.first && payee!!.first == payee!!.last -> null
                                            i < payee!!.first -> i..payee!!.last
                                            else -> payee!!.first..i
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            item { Segmented(listOf("Money out", "Money in"), if (debit) 0 else 1, { debit = it == 0 }) }
            item { Segmented(listOf("Bank account", "Credit card"), if (card) 1 else 0, { card = it == 1 }) }
            item {
                Panel(padding = PaddingValues(14.dp)) {
                    Text("Will read", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                    Text(
                        preview?.let { p ->
                            "${if (debit) "−" else "+"}${rupeesExact(p.amountPaise)}" + (p.counterparty?.let { " · $it" } ?: "") +
                                " · ${sourceLabel(p.source)}" + (p.date?.let { " · ${it.format(dm)}" } ?: "")
                        } ?: "Tap the amount first",
                        style = AmountStyle, color = if (preview == null) t.ink3 else t.ink,
                    )
                }
            }
            item { PrimaryButton("Save and read SMS like this", { layout?.let(onSave) }, enabled = preview != null) }
        }
    }
}
