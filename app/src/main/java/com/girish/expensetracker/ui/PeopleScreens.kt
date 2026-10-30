package com.girish.expensetracker.ui

import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.PayeeType
import com.girish.expensetracker.core.People
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/** One real person, merged across their bank name and UPI handles. */
class Person(val key: String, val name: String, val labels: List<String>, val rows: List<Row>) {
    val paid get() = rows.filter { it.txn.kind == Kind.SPEND }.sumOf { it.txn.amountPaise }
    val got get() = rows.filter { it.txn.kind == Kind.PERSON_CREDIT }.sumOf { it.txn.amountPaise }
    val last: LocalDate get() = rows.maxOf { it.txn.day }
    fun paidIn(r: ClosedRange<LocalDate>) = rows.filter { it.txn.kind == Kind.SPEND && it.txn.day in r }.sumOf { it.txn.amountPaise }
    fun gotIn(r: ClosedRange<LocalDate>) = rows.filter { it.txn.kind == Kind.PERSON_CREDIT && it.txn.day in r }.sumOf { it.txn.amountPaise }
}

fun people(snap: Snapshot): List<Person> {
    val personal = snap.rows.filter { it.txn.payeeType == PayeeType.PERSON && !it.txn.parsed.counterparty.isNullOrBlank() }
    val labels = personal.map { it.txn.parsed.counterparty!! }.distinct()
    return People.group(labels).map { group ->
        val set = group.toSet()
        val rows = personal.filter { it.txn.parsed.counterparty in set }.sortedByDescending { it.txn.receivedAt }
        Person(People.key(People.displayName(group)), People.displayName(group), group, rows)
    }.sortedByDescending { it.last }
}

/** A stable colour per person from the validated palette, so a face is recognisable across screens. */
private fun personColor(key: String): Color = listOf(
    Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
    Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
)[Math.floorMod(key.hashCode(), 8)]

@Composable
private fun Face(p: Person, size: Int) {
    val c = personColor(p.key)
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)).border(1.5.dp, c, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initials = p.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
        Text(initials, style = if (size >= 64) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge, color = LocalTones.current.ink)
    }
}

// ---------------------------------------------------------------- list

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeopleScreen(
    snap: Snapshot,
    onSettle: (com.girish.expensetracker.core.Debt) -> Unit = {},
    onGroup: (com.girish.expensetracker.data.FriendGroup?) -> Unit = {},
    onOpen: (Person) -> Unit,
) {
    val t = LocalTones.current
    val all = remember(snap) { people(snap) }
    var query by remember { mutableStateOf("") }
    val shown = all.filter { query.isBlank() || it.labels.any { l -> l.contains(query, ignoreCase = true) } || it.name.contains(query, ignoreCase = true) }
    val cycle = snap.cycleStart..snap.today
    val paidMonth = all.sumOf { it.paidIn(cycle) }
    val gotMonth = all.sumOf { it.gotIn(cycle) }
    val recent = shown.filter { it.last >= snap.today.minusDays(30) }
    val earlier = shown - recent.toSet()
    var view by remember { mutableStateOf(0) } // 0 people, 1 groups

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("People", "${all.size} people from your UPI payments") }
        item { Segmented(listOf("People", "Groups"), view, { view = it }) }
        if (view == 1) {
            item { GroupsView(snap, all, onGroup) }
            return@LazyColumn
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(t.panel).border(1.dp, t.hairline, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = t.ink3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search a name or UPI ID", style = MaterialTheme.typography.bodyMedium, color = t.ink3)
                    BasicTextField(
                        query, { query = it }, singleLine = true, cursorBrush = SolidColor(t.accent),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.ink), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            StatGrid(
                listOf(
                    Stat("You paid people", rupees(paidMonth), sub = "this salary month", highlight = true),
                    Stat("They paid you back", rupees(gotMonth), sub = "this salary month"),
                ),
            )
        }
        if (snap.debts.isNotEmpty()) item { OwedPanel(snap.debts, onSettle) }
        if (all.isEmpty()) item {
            Panel { Text("No payments to people yet. UPI payments to a name appear here.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) }
        }
        if (recent.isNotEmpty()) item { PeopleGroup("Last 30 days", recent, snap.debts, onOpen) }
        if (earlier.isNotEmpty()) item { PeopleGroup("Earlier", earlier, snap.debts, onOpen) }
    }
}

/** What [p] still owes the owner from splits and loans. */
private fun owedBy(p: Person, debts: List<com.girish.expensetracker.core.Debt>) =
    debts.filter { d -> (d.label != null && d.label in p.labels) || People.same(d.name, p.name) }.sumOf { it.leftPaise }

@Composable
private fun PeopleGroup(title: String, list: List<Person>, debts: List<com.girish.expensetracker.core.Debt>, onOpen: (Person) -> Unit) {
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) {
        PanelHeader(title, "${list.size}")
        list.forEachIndexed { k, p ->
            if (k > 0) Hairline()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Face(p, 44)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${count(p.rows.size, "payment")} · last ${p.last.format(dm)}" + if (p.labels.size > 1) " · ${p.labels.size} IDs" else "",
                        style = MaterialTheme.typography.bodySmall, color = t.ink2, maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val owed = owedBy(p, debts)
                    val net = p.paid - p.got
                    if (owed > 0) {
                        Text(rupees(owed), style = AmountStyle, color = t.accent)
                        Text("owes you", style = DataLabel, color = t.accent)
                    } else {
                        Text(rupees(kotlin.math.abs(net)), style = AmountStyle, color = if (net < 0) t.good else t.ink)
                        Text(if (net < 0) "they paid more" else "you paid", style = DataLabel, color = t.ink3)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- detail

@Composable
internal fun PersonScreen(snap: Snapshot, person: Person, onBack: () -> Unit, onRow: (Row) -> Unit, onMarkShop: (Person) -> Unit) {
    val t = LocalTones.current
    var range by remember { mutableStateOf(0) } // 0 this month, 1 last 3 salary months, 2 all time
    val cycles = remember(snap) { Ledger.cycles(snap.today, snap.txns, 3) }
    val first = remember(person) { person.rows.minOf { it.txn.day } }
    val window: ClosedRange<LocalDate> = when (range) {
        0 -> cycles[0]
        1 -> cycles.last().start..snap.today
        else -> minOf(first, snap.cycleStart)..snap.today
    }
    val inRange = person.rows.filter { it.txn.day in window }
    val paid = person.paidIn(window)
    val got = person.gotIn(window)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Face(person, 76)
                Gap(12.dp)
                Text(person.name, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight(700)), color = t.ink, textAlign = TextAlign.Center)
                Text("Since ${first.format(dm)} ${first.year} · ${count(person.rows.size, "payment")}", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                if (person.labels.size > 1 || '@' in person.labels.first()) {
                    Gap(10.dp)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        person.labels.forEach { l ->
                            Text(l, style = DataLabel, color = t.ink2, modifier = Modifier.clip(CircleShape).background(t.raised).padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
        item { Segmented(listOf("This month", "3 months", "All time"), range, { range = it }) }
        item {
            StatGrid(
                listOf(
                    Stat("You paid", rupees(paid), sub = window.start.format(dm) + " – " + window.endInclusive.format(dm)),
                    Stat("They paid you", rupees(got), sub = count(inRange.count { it.txn.kind == Kind.PERSON_CREDIT }, "payment")),
                    Stat(if (paid >= got) "Net you paid" else "Net they paid", rupees(kotlin.math.abs(paid - got)), sub = count(inRange.size, "payment") + " in all", highlight = true),
                ),
            )
        }
        item { PersonMonths(snap, person, range) }
        val byMonth = inRange.groupBy { it.txn.day.withDayOfMonth(1) }.toSortedMap(compareByDescending { it })
        byMonth.forEach { (m, rows) ->
            item {
                Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
                    val out = rows.filter { it.txn.kind == Kind.SPEND }.sumOf { it.txn.amountPaise }
                    val back = rows.filter { it.txn.kind == Kind.PERSON_CREDIT }.sumOf { it.txn.amountPaise }
                    PanelHeader(m.format(monthYear), "−${compact(out)}  +${compact(back)}")
                    rows.forEachIndexed { k, r ->
                        if (k > 0) Hairline()
                        PersonTxn(r, onRow)
                    }
                }
            }
        }
        if (inRange.isEmpty()) item { Panel { Text("No payments with ${person.name} in this period.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) } }
        item {
            Text(
                "Not a person? Mark as a shop",
                style = MaterialTheme.typography.labelLarge, color = t.accent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onMarkShop(person) }.padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PersonTxn(r: Row, onRow: (Row) -> Unit) {
    val t = LocalTones.current
    val x = r.txn
    val incoming = x.kind == Kind.PERSON_CREDIT
    ListRow(
        title = if (incoming) "Paid you" else "You paid",
        subtitle = "${x.day.format(dm)}, ${x.receivedAt.toLocalTime().withSecond(0).withNano(0)} · ${sourceLabel(x.parsed.source)} · ${x.parsed.counterparty}",
        leading = {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (incoming) t.good.copy(alpha = 0.15f) else t.raised), contentAlignment = Alignment.Center) {
                Text(if (incoming) "↓" else "↑", style = MaterialTheme.typography.titleMedium, color = if (incoming) t.good else t.ink)
            }
        },
        trailing = { Text((if (incoming) "+" else "") + rupeesExact(x.amountPaise), style = AmountStyle, color = if (incoming) t.good else t.ink) },
        onClick = { onRow(r) },
    )
}

/** Paid vs paid back per salary month for this person (mint out, quiet grey in). */
@Composable
private fun PersonMonths(snap: Snapshot, person: Person, range: Int) {
    val t = LocalTones.current
    val first = person.rows.minOf { it.txn.day }
    val all = Ledger.cycles(snap.today, snap.txns, 24).filter { it.endInclusive >= first }.reversed()
    val cycles = when (range) {
        0 -> all.takeLast(1)
        1 -> all.takeLast(3)
        else -> all
    }
    if (cycles.isEmpty()) return
    val out = cycles.map { person.paidIn(it) }
    val back = cycles.map { person.gotIn(it) }
    val max = (out + back).maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Panel {
        PanelHeader("By salary month", "${cycles.size}")
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Paid and paid back per month with ${person.name}" }) {
            val slot = size.width / cycles.size
            val barW = minOf(slot * 0.28f, 18.dp.toPx())
            val r = CornerRadius(barW / 2)
            drawLine(t.hairline, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            cycles.indices.forEach { i ->
                val cx = slot * i + slot / 2
                val h1 = size.height * out[i] / max
                val h2 = size.height * back[i] / max
                if (h1 > 0) drawRoundRect(t.accent, Offset(cx - barW - 2.dp.toPx(), size.height - h1), Size(barW, h1), r)
                if (h2 > 0) drawRoundRect(t.ink3.copy(alpha = 0.6f), Offset(cx + 2.dp.toPx(), size.height - h2), Size(barW, h2), r)
            }
        }
        Gap(8.dp)
        Row(Modifier.fillMaxWidth()) {
            cycles.forEach { c ->
                Text(cycleMonth(c.start).uppercase(), style = DataLabel, color = t.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Swatch(t.accent); Spacer(Modifier.width(6.dp)); Text("You paid", style = MaterialTheme.typography.labelMedium, color = t.ink2) }
            Row(verticalAlignment = Alignment.CenterVertically) { Swatch(t.ink3.copy(alpha = 0.6f)); Spacer(Modifier.width(6.dp)); Text("Paid you", style = MaterialTheme.typography.labelMedium, color = t.ink2) }
        }
    }
}

// ---------------------------------------------------------------- groups

/** Named sets of people (flatmates, office lunch): split with all of them in one tap, and see the group's month. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupsView(snap: Snapshot, all: List<Person>, onGroup: (com.girish.expensetracker.data.FriendGroup?) -> Unit) {
    val t = LocalTones.current
    val groups = snap.plan.groups
    val cycle = snap.cycleStart..snap.today
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (groups.isEmpty()) {
            Panel {
                Text("No groups yet", style = MaterialTheme.typography.titleMedium, color = t.ink)
                Text(
                    "Make one for the people you split with most, like flatmates. When you split a payment, pick the group and everyone is added at once.",
                    style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        groups.forEach { g ->
            val members = all.filter { p -> g.members.any { (label, name) -> label in p.labels || People.same(name, p.name) } }
            val owed = snap.debts.filter { d -> g.members.any { (label, name) -> d.label == label || People.same(d.name, name) } }.sumOf { it.leftPaise }
            Panel {
                PanelHeader(g.name, count(g.members.size, "person")) {
                    TextButton(onClick = { onGroup(g) }) { Text("Edit", color = t.accent) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.members.forEach { (_, name) ->
                        Text(
                            name, style = MaterialTheme.typography.labelMedium, color = t.ink,
                            modifier = Modifier.clip(CircleShape).background(t.raised).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Gap(12.dp)
                StatGrid(
                    listOf(
                        Stat("You paid them", rupees(members.sumOf { it.paidIn(cycle) }), sub = "this salary month"),
                        Stat(
                            if (owed > 0) "They owe you" else "They paid you", rupees(if (owed > 0) owed else members.sumOf { it.gotIn(cycle) }),
                            sub = if (owed > 0) "from splits" else "this salary month", highlight = owed > 0,
                        ),
                    ),
                )
            }
        }
        PrimaryButton("New group", { onGroup(null) })
    }
}

/** Create or edit a group: a name and people picked from the ones you pay. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSheet(
    group: com.girish.expensetracker.data.FriendGroup?,
    people: List<Person>,
    onDismiss: () -> Unit,
    onSave: (com.girish.expensetracker.data.FriendGroup) -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTones.current
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var picked by remember { mutableStateOf(group?.members?.map { it.first }?.toSet().orEmpty()) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.panel,
    ) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(if (group == null) "New group" else "Edit group", style = MaterialTheme.typography.headlineSmall, color = t.ink) }
            item {
                androidx.compose.material3.OutlinedTextField(
                    name, { name = it }, label = { Text("Name, e.g. Flatmates") }, singleLine = true,
                    colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Text("Who is in it", style = MaterialTheme.typography.titleSmall, color = t.ink) }
            item {
                if (people.isEmpty()) Text("People you pay by UPI appear here.", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.take(30).forEach { p ->
                        val on = p.labels.first() in picked
                        androidx.compose.material3.FilterChip(
                            on, { picked = if (on) picked - p.labels.first() else picked + p.labels.first() },
                            { Text(p.name) }, colors = chipColors(), border = chipBorder(on),
                        )
                    }
                }
            }
            item {
                PrimaryButton("Save group", {
                    val members = people.filter { it.labels.first() in picked }.map { it.labels.first() to it.name }
                    onSave(com.girish.expensetracker.data.FriendGroup(name.trim(), members))
                }, enabled = name.isNotBlank() && picked.isNotEmpty())
            }
            if (group != null) item { TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete group", color = t.warn) } }
        }
    }
}
