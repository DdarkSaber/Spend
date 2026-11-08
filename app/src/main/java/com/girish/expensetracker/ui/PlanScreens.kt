@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.girish.expensetracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.BudgetLine
import com.girish.expensetracker.core.CardGroup
import com.girish.expensetracker.core.CardHealth
import com.girish.expensetracker.core.Credit
import com.girish.expensetracker.core.Debt
import com.girish.expensetracker.core.Direction
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.Owe
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SplitShare
import com.girish.expensetracker.core.Subscription
import com.girish.expensetracker.data.Plan
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dm = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** "1,250.50" -> paise; null when blank or not a number. */
internal fun parseRupees(text: String): Long? = text.replace(",", "").trim().toBigDecimalOrNull()?.movePointRight(2)?.toLong()

private fun rupeesField(paise: Long?) = paise?.let { (it / 100).toString() }.orEmpty()

@Composable
internal fun fieldColors() = LocalTones.current.let { t ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = t.accent, unfocusedBorderColor = t.edge, focusedLabelColor = t.accent, unfocusedLabelColor = t.ink2,
        focusedTextColor = t.ink, unfocusedTextColor = t.ink, cursorColor = t.accent,
    )
}

// ---------------------------------------------------------------- credit card health

/** How much of the limit is used, with the 10% and 30% marks, and what to pay before the statement. */
@Composable
internal fun CardHealthPanel(h: CardHealth, onEdit: () -> Unit, onMarkPaid: ((Boolean) -> Unit)? = null) {
    val t = LocalTones.current
    Panel {
        PanelHeader(h.title ?: sourceLabel(h.card), h.nextStatement?.let { "statement ${it.format(dm)}" }) {
            RoundIcon(Icons.Outlined.Edit, "Set limit and statement day", onEdit, size = 34.dp)
        }
        val u = h.utilization
        if (u == null || h.limitPaise == null) {
            Text("${rupees(h.outstandingPaise)} outstanding${if (h.balanceEstimated) " (estimated)" else ""}.", style = AmountStyle, color = t.ink)
            Text(
                "Set this card's credit limit to see how much of it you're using. Banks report the balance on the statement date, and under 30% of the limit keeps your credit score healthy.",
                style = MaterialTheme.typography.bodySmall, color = t.ink2, modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) { Text("Set limit", color = t.accent) }
        } else {
            val pct = (u * 100).toInt()
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$pct%", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight(700)), color = if (u >= Credit.HEALTHY) t.warn else t.ink)
                Spacer(Modifier.width(8.dp))
                Text(
                    "used · ${rupees(h.outstandingPaise)} of ${rupees(h.limitPaise)}" +
                        (if (h.limitEstimated) " (est. limit)" else "") + (if (h.balanceEstimated) " · estimated" else ""),
                    style = MaterialTheme.typography.bodySmall, color = t.ink2, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Gap(10.dp)
            UtilisationBar(u)
            Gap(10.dp)
            val by = h.nextStatement?.let { " before ${it.format(dm)}" }.orEmpty()
            val advice = when {
                u >= Credit.HEALTHY -> "Pay ${rupees(h.payToReach(Credit.HEALTHY))}$by so the bank reports under 30%. ${rupees(h.payToReach(Credit.BEST))} gets it under 10%."
                u >= Credit.BEST -> "Healthy. Paying ${rupees(h.payToReach(Credit.BEST))}$by gets it under 10%, the best band for your score."
                else -> "Under 10%: the best band for your credit score."
            }
            Text(advice, style = MaterialTheme.typography.bodyMedium, color = if (u >= Credit.HEALTHY) t.warn else t.ink2)
        }
        if (h.isGroup) GroupMembers(h)
        h.statement?.let { s ->
            Gap(12.dp)
            Hairline()
            Gap(10.dp)
            val left = h.billLeftPaise
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (h.statementStale) "Last statement seen ${s.statementDate.format(dm)}" else "Statement of ${s.statementDate.format(dm)}",
                        style = MaterialTheme.typography.titleSmall, color = t.ink,
                    )
                    Text(
                        if (h.statementStale) "A newer statement was due ${h.missedSince?.format(dm) ?: ""} but hasn't arrived — balance estimated."
                        else listOfNotNull(
                            s.totalDuePaise?.let { "total ${rupees(it)}" },
                            s.minDuePaise?.let { "min ${rupees(it)}" },
                            s.dueDate?.let { "due ${it.format(dm)}" },
                        ).joinToString(" · ").ifEmpty { "amount not in the SMS" },
                        style = MaterialTheme.typography.bodySmall, color = if (h.statementStale) t.warn else t.ink2,
                    )
                }
                when {
                    h.statementStale -> Tag("missed")
                    h.markedPaid -> Tag("paid")
                    left == null -> Unit
                    left == 0L -> Tag("paid")
                    else -> Text(rupees(left) + " due", style = AmountStyle, color = if (s.dueDate != null && !s.dueDate.isAfter(LocalDate.now().plusDays(3))) t.warn else t.ink)
                }
            }
            // The payment SMS didn't come (or never comes for this bank): the owner says it's paid, so it stops showing as due.
            if (onMarkPaid != null && !h.statementStale) when {
                h.markedPaid -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("You marked this paid.", style = MaterialTheme.typography.bodySmall, color = t.ink3, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onMarkPaid(false) }) { Text("Not paid", color = t.ink2) }
                }
                left != null && left > 0 -> TextButton(onClick = { onMarkPaid(true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Paid already? Mark paid", color = t.accent)
                }
                left == null && s.totalDuePaise == null -> TextButton(onClick = { onMarkPaid(true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Mark this statement paid", color = t.accent)
                }
            }
        }
    }
}

/** Each card of a group with its part of the balance; tap the row to open or close. */
@Composable
private fun GroupMembers(h: CardHealth) {
    val t = LocalTones.current
    var open by remember { mutableStateOf(false) }
    Gap(12.dp)
    Hairline()
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${h.members.size} cards" + if (h.sharedLimit) " · one shared limit" else " · limits added up",
            style = MaterialTheme.typography.titleSmall, color = t.ink, modifier = Modifier.weight(1f),
        )
        Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = t.ink2)
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            h.members.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Card ${m.card.last4}", style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                    val mu = m.utilization
                    if (mu != null) {
                        Text("${(mu * 100).toInt()}%", style = AmountStyle, color = if (!h.sharedLimit && mu >= Credit.HEALTHY) t.warn else t.ink)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(rupees(m.outstandingPaise), style = MaterialTheme.typography.bodySmall, color = t.ink2)
                }
            }
            if (h.sharedLimit) Text(
                "Split by what each card spent in the last 60 days.",
                style = MaterialTheme.typography.bodySmall, color = t.ink3, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * "Group cards that share a statement?": one compact panel, a row per bank. Cards whose limit SMS run as one balance
 * are named as very likely shared. "No" stops asking for that bank (it can be turned back on from More).
 */
@Composable
internal fun CardGroupSuggestions(
    suggestions: List<CardGroup>,
    likely: Map<String, List<Source>>,
    onGroup: (CardGroup) -> Unit,
    onNo: (String) -> Unit,
) {
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)) {
        PanelHeader("Cards on one statement?", "suggestion")
        Text(
            "Group cards that share a limit (an add-on card, or two cards on one account) to see the real usage once.",
            style = MaterialTheme.typography.bodySmall, color = t.ink2,
        )
        suggestions.forEach { g ->
            val shared = likely[g.bank]
            Gap(8.dp)
            Hairline()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${g.bank} ${g.cards.joinToString(" · ") { it.last4 }}", style = MaterialTheme.typography.titleSmall, color = t.ink)
                    Text(
                        if (shared != null) "${shared.joinToString(" & ") { it.last4 }} look like one shared limit" else "Same bank",
                        style = MaterialTheme.typography.bodySmall, color = if (shared != null) t.accent else t.ink3,
                    )
                }
                TextButton(onClick = { onNo(g.bank) }) { Text("No", color = t.ink2) }
                TextButton(onClick = { onGroup(if (shared != null) g.copy(cards = shared) else g) }) { Text("Group", color = t.accent) }
            }
        }
    }
}

/**
 * Card SMS without a card number: the owner says which card each was. Unanswered ones wait here. Tap a payment to
 * read its SMS; "always" remembers the card for that shop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WhichCardPanel(rows: List<Row>, cardsOf: (String) -> List<Source>, onPick: (Row, Source, Boolean) -> Unit) {
    val t = LocalTones.current
    var all by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Row?>(null) }
    val always = remember { mutableStateMapOf<Long, Boolean>() }
    Panel {
        PanelHeader("Which card was it?", "${rows.size} to sort")
        Text(
            "These SMS didn't say which card. Until you pick one, they stay under “not assigned”.",
            style = MaterialTheme.typography.bodySmall, color = t.ink2,
        )
        (if (all) rows else rows.take(3)).forEach { r ->
            Gap(10.dp)
            Hairline()
            Gap(10.dp)
            val tx = r.txn
            val refund = tx.parsed.direction == Direction.CREDIT
            // Money in on a card is usually a bill payment, sometimes a refund: say which.
            val inLabel = when (tx.kind) {
                com.girish.expensetracker.core.Kind.TRANSFER -> "Bill payment"
                com.girish.expensetracker.core.Kind.REFUND -> "Refund"
                else -> "Money in"
            }
            val shop = tx.parsed.counterparty?.takeIf { it.isNotBlank() }
            Row(Modifier.fillMaxWidth().clickable { reading = if (reading == r) null else r }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shop ?: if (refund) inLabel else "Card spend", style = MaterialTheme.typography.titleSmall, color = t.ink)
                    Text("${tx.parsed.source.bank} · ${if (refund) inLabel.lowercase() else "spend"} · ${tx.day.format(dm)} · ${if (reading == r) "hide SMS" else "see SMS"}", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                }
                Text((if (refund) "+" else "") + rupeesExact(tx.amountPaise), style = AmountStyle, color = if (refund) t.accent else t.ink)
            }
            AnimatedVisibility(reading == r && r.rawBody != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    r.rawBody.orEmpty(), style = MaterialTheme.typography.bodySmall, color = t.ink2,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().background(t.raised, RoundedCornerShape(8.dp)).padding(10.dp),
                )
            }
            Gap(6.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cardsOf(tx.parsed.source.bank).forEach { c ->
                    FilterChip(false, { onPick(r, c, always[r.smsId] == true) }, { Text("Card ${c.last4}") }, colors = chipColors(), border = chipBorder(false))
                }
                if (shop != null && r.smsId != null) {
                    val on = always[r.smsId] == true
                    FilterChip(on, { always[r.smsId] = !on }, { Text("Always for $shop") }, colors = chipColors(), border = chipBorder(on))
                }
            }
        }
        if (rows.size > 3) TextButton(onClick = { all = !all }, contentPadding = PaddingValues(0.dp)) {
            Text(if (all) "Show fewer" else "Show all ${rows.size}", color = t.accent)
        }
    }
}


@Composable
private fun UtilisationBar(u: Double) {
    val t = LocalTones.current
    Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = "${(u * 100).toInt()} percent of the limit used" }) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(t.raised, cornerRadius = r)
        val w = (size.width * u.coerceIn(0.0, 1.0)).toFloat()
        if (w > 0) drawRoundRect(if (u >= Credit.HEALTHY) t.warn else t.accent, size = Size(w, size.height), cornerRadius = r)
        listOf(Credit.BEST, Credit.HEALTHY).forEach { mark ->
            val x = (size.width * mark).toFloat()
            drawLine(t.ink2, Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 2f)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Spacer(Modifier.weight(0.085f))
        Text("10%", style = DataLabel, color = t.ink3)
        Spacer(Modifier.weight(0.17f))
        Text("30%", style = DataLabel, color = t.ink3)
        Spacer(Modifier.weight(0.745f))
    }
}

/** What the card settings sheet saves. [cards] has 2+ cards when they are to be grouped, else just the one card. */
internal data class CardSettings(
    val cards: List<Source>,
    /** The group's own name; blank = "ICICI cards". */
    val name: String,
    val sharedLimit: Boolean,
    val limitPaise: Long?,
    val statementDay: Int?,
    /** Each card's own limit, used for a group whose limits are not shared. */
    val cardLimits: Map<Source, Long?>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardSettingsSheet(
    h: CardHealth,
    /** Every tracked card of the same bank (this card, or the group's cards, included). */
    sameBank: List<Source>,
    /** Limits the owner set per card, for a group with separate limits. */
    ownLimits: Map<Source, Long>,
    /** The group's own name, if the owner gave one. */
    groupName: String? = null,
    /** Cards to start with ticked (from the "group your cards?" suggestion). */
    preselect: List<Source>? = null,
    onDismiss: () -> Unit,
    onSave: (CardSettings) -> Unit,
) {
    val t = LocalTones.current
    val inGroup = preselect ?: if (h.isGroup) h.members.map { it.card } else listOf(h.card)
    var name by remember { mutableStateOf(groupName.orEmpty()) }
    val chosen = remember { mutableStateListOf<Source>().apply { addAll(inGroup) } }
    var shared by remember { mutableStateOf(h.sharedLimit) }
    var limit by remember { mutableStateOf(if (h.limitEstimated || (h.isGroup && !h.sharedLimit)) "" else rupeesField(h.limitPaise)) }
    var day by remember { mutableStateOf(h.nextStatement?.dayOfMonth) }
    val perCard = remember { mutableStateMapOf<Source, String>().apply { sameBank.forEach { c -> put(c, ownLimits[c]?.let(::rupeesField).orEmpty()) } } }
    val grouping = chosen.size >= 2
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (grouping) name.ifBlank { "${h.card.bank} cards" } else sourceLabel(chosen.firstOrNull() ?: h.card), style = MaterialTheme.typography.headlineSmall, color = t.ink)
            if (sameBank.size >= 2) {
                Text("Cards on one statement", style = MaterialTheme.typography.titleSmall, color = t.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sameBank.forEach { c ->
                        val on = c in chosen
                        FilterChip(on, { if (on) chosen.remove(c) else chosen.add(c) }, { Text("${c.bank} ${c.last4}") }, colors = chipColors(), border = chipBorder(on))
                    }
                }
                Text(
                    if (grouping) "Shown together as one card, with each card inside it." else "Pick 2 or more to group cards that share a statement.",
                    style = MaterialTheme.typography.bodySmall, color = t.ink2,
                )
            }
            if (grouping) {
                OutlinedTextField(
                    name, { name = it.take(30) }, label = { Text("Group name (optional)") }, singleLine = true,
                    colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Like \"Mine\" or \"Brother's\". Empty shows \"${h.card.bank} cards\".") },
                )
                Row(Modifier.fillMaxWidth().clickable { shared = !shared }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("One shared limit", style = MaterialTheme.typography.titleSmall, color = t.ink)
                        Text(
                            if (shared) "The cards draw from the same limit." else "Each card has its own limit; the group adds them up.",
                            style = MaterialTheme.typography.bodySmall, color = t.ink2,
                        )
                    }
                    Switch(
                        shared, { shared = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White, uncheckedTrackColor = t.section, uncheckedBorderColor = t.edge),
                    )
                }
            }
            if (!grouping || shared) {
                OutlinedTextField(
                    limit, { limit = it.filter { c -> c.isDigit() } }, label = { Text(if (grouping) "Shared credit limit in ₹" else "Credit limit in ₹") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(if (h.limitEstimated && h.limitPaise != null && inGroup.toSet() == chosen.toSet()) "Estimated ${rupees(h.limitPaise)} from your SMS. Leave empty to keep the estimate." else "Shown in your card app or statement.")
                    },
                )
            } else {
                sameBank.filter { it in chosen }.forEach { c ->
                    OutlinedTextField(
                        perCard[c].orEmpty(), { v -> perCard[c] = v.filter { ch -> ch.isDigit() } }, label = { Text("Card ${c.last4} limit in ₹") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            DayOfMonthField(
                "Statement day", day, { day = it },
                "Pick the day your statement is made, e.g. 12 = the 12th of every month. The bank reports your balance to the credit bureaus then.",
            )
            PrimaryButton(
                if (h.isGroup && !grouping) "Ungroup" else "Save",
                {
                    onSave(
                        CardSettings(
                            cards = sameBank.filter { it in chosen }.ifEmpty { listOf(h.card) },
                            name = name.trim(),
                            sharedLimit = shared,
                            limitPaise = parseRupees(limit)?.takeIf { it > 0 },
                            statementDay = day,
                            cardLimits = sameBank.filter { it in chosen }.associateWith { c -> parseRupees(perCard[c].orEmpty())?.takeIf { it > 0 } },
                        ),
                    )
                },
                enabled = chosen.isNotEmpty(),
            )
        }
    }
}

// ---------------------------------------------------------------- budgets

@Composable
internal fun BudgetPanel(lines: List<BudgetLine>, onEdit: () -> Unit) {
    val t = LocalTones.current
    Panel {
        PanelHeader("Budgets") {
            RoundIcon(Icons.Outlined.Edit, "Edit budgets", onEdit, size = 34.dp)
        }
        if (lines.isEmpty()) {
            Text("Set a monthly budget, overall or per category. You get a notification at 80%, at 100%, and when you're spending faster than the budget allows.",
                style = MaterialTheme.typography.bodySmall, color = t.ink2)
            TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) { Text("Set a budget", color = t.accent) }
            return@Panel
        }
        lines.forEachIndexed { k, b ->
            if (k > 0) Gap(14.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b.category != null) { Swatch(paintFor(b.category)); Spacer(Modifier.width(8.dp)) }
                Text(b.category ?: "Whole month", style = MaterialTheme.typography.titleSmall, color = t.ink, modifier = Modifier.weight(1f))
                Text("${rupees(b.spentPaise)} / ${rupees(b.limitPaise)}", style = AmountStyle, color = if (b.share >= 1) t.warn else t.ink)
            }
            Gap(6.dp)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(t.raised)) {
                Box(Modifier.fillMaxWidth(b.share.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(if (b.share >= 0.8 || b.overPace) t.warn else t.accent))
            }
            Text(
                when {
                    b.share >= 1 -> "Over by ${rupees(b.spentPaise - b.limitPaise)}"
                    b.overPace -> "${rupees(b.limitPaise - b.spentPaise)} left · heading for ${rupees(b.projectedPaise)}"
                    b.share >= 0.8 -> "${rupees(b.limitPaise - b.spentPaise)} left · ${(b.share * 100).toInt()}% used"
                    else -> "${rupees(b.limitPaise - b.spentPaise)} left · on track"
                },
                style = MaterialTheme.typography.labelMedium, color = if (b.overPace || b.share >= 0.8) t.warn else t.ink2, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun BudgetSheet(plan: Plan, used: List<String>, onDismiss: () -> Unit, onSave: (Long?, Map<String, Long?>) -> Unit) {
    val t = LocalTones.current
    var total by remember { mutableStateOf(rupeesField(plan.budgetTotal)) }
    val cats = (used + plan.budgetByCategory.keys).distinct().filter { it in CATEGORIES }
    val values = remember { mutableStateListOf(*cats.map { rupeesField(plan.budgetByCategory[it]) }.toTypedArray()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Monthly budgets", style = MaterialTheme.typography.headlineSmall, color = t.ink) }
            item {
                OutlinedTextField(
                    total, { total = it.filter(Char::isDigit) }, label = { Text("Whole salary month, ₹") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Text("Per category (leave empty for none)", style = MaterialTheme.typography.titleSmall, color = t.ink2) }
            cats.forEachIndexed { i, cat ->
                item {
                    OutlinedTextField(
                        values[i], { values[i] = it.filter(Char::isDigit) }, label = { Text("$cat, ₹") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item { PrimaryButton("Save budgets", { onSave(parseRupees(total), cats.mapIndexed { i, c -> c to parseRupees(values[i]) }.toMap()) }) }
        }
    }
}

// ---------------------------------------------------------------- subscriptions

@Composable
internal fun SubscriptionsPanel(subs: List<Subscription>) {
    if (subs.isEmpty()) return
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("Subscriptions", "${rupees(subs.sumOf { it.monthlyPaise })} / month")
        subs.forEachIndexed { k, s ->
            if (k > 0) Hairline()
            ListRow(
                title = s.name,
                subtitle = "${if (s.yearly) "yearly" else "monthly"} · next ${s.nextDay.format(dm)} · ${sourceLabel(s.source)}",
                leading = { Box(Modifier.size(36.dp).clip(CircleShape).background(t.section), contentAlignment = Alignment.Center) { Swatch(paintFor(s.category)) } },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupees(s.lastPaise), style = AmountStyle, color = t.ink)
                        if (s.priceUp) Text("▲ from ${rupees(s.previousPaise!!)}", style = DataLabel, color = t.warn)
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------- year

/** Twelve salary months side by side, with the year's total and monthly average. */
@Composable
internal fun YearPanel(snap: Snapshot) {
    val t = LocalTones.current
    val cycles = remember(snap) { Ledger.cycles(snap.today, snap.txns, 12).reversed() }
    val first = remember(snap) { snap.txns.minOfOrNull { it.day } }
    val shown = cycles.filter { first != null && it.endInclusive >= first }
    val totals = remember(snap) { shown.map { r -> snap.txns.filter { it.day in r }.sumOf(Ledger::monthAmount) } }
    val byCat = remember(snap) {
        if (shown.isEmpty()) emptyList() else Ledger.byCategoryIn(snap.txns, shown.first().start..snap.today).filterValues { it > 0 }.toList().sortedByDescending { it.second }.take(6)
    }
    Panel {
        PanelHeader("Last 12 salary months", if (shown.isEmpty()) null else "${shown.size} with data")
        if (shown.size < 2) {
            Text("Year view fills in as months pass. Importing a bank statement adds older months.", style = MaterialTheme.typography.bodySmall, color = t.ink2)
            return@Panel
        }
        val total = totals.sum()
        Row {
            Column(Modifier.weight(1f)) { Text(rupees(total), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight(700)), color = t.ink); Text("in total", style = MaterialTheme.typography.labelMedium, color = t.ink2) }
            Column(horizontalAlignment = Alignment.End) { Text(rupees(total / shown.size), style = MaterialTheme.typography.headlineSmall, color = t.ink); Text("a month on average", style = MaterialTheme.typography.labelMedium, color = t.ink2) }
        }
        Gap(14.dp)
        val max = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val avg = total / shown.size
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Spend per salary month: " + totals.joinToString { rupees(it) } }) {
            val slot = size.width / totals.size
            val barW = minOf(slot * 0.55f, 22.dp.toPx())
            val ya = size.height - size.height * avg / max
            totals.forEachIndexed { i, v ->
                val h = size.height * v.coerceAtLeast(0) / max
                drawRoundRect(if (i == totals.lastIndex) t.accent else t.ink3.copy(alpha = 0.55f), Offset(slot * i + (slot - barW) / 2, size.height - h), Size(barW, h), CornerRadius(4.dp.toPx()))
            }
            drawLine(t.ink2, Offset(0f, ya), Offset(size.width, ya), strokeWidth = 1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        Gap(6.dp)
        Row(Modifier.fillMaxWidth()) {
            shown.forEachIndexed { i, r ->
                Text(if (i % 2 == shown.lastIndex % 2) cycleMonth(r.start).take(1) else "", style = DataLabel, color = t.ink3, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Gap(12.dp)
        Hairline()
        byCat.forEach { (cat, amt) ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(paintFor(cat)); Spacer(Modifier.width(8.dp))
                Text(cat, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                Text(rupees(amt), style = AmountStyle, color = t.ink)
            }
        }
    }
}

// ---------------------------------------------------------------- owed to you

@Composable
internal fun OwedPanel(debts: List<Debt>, onSettle: (Debt) -> Unit) {
    if (debts.isEmpty()) return
    val t = LocalTones.current
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader("Owed to you", rupees(debts.sumOf { it.leftPaise }))
        debts.forEachIndexed { k, d ->
            if (k > 0) Hairline()
            ListRow(
                title = d.name.ifBlank { "Someone" },
                subtitle = "since ${d.since.format(dm)} · clears when they pay you",
                leading = { Avatar(d.name.ifBlank { "?" }, 36.dp) },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupees(d.leftPaise), style = AmountStyle, color = t.ink)
                        Text("MARK PAID", style = DataLabel, color = t.accent, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onSettle(d) }.padding(4.dp))
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------- split a payment

private class Draft(name: String, label: String?, amount: String) {
    var name by mutableStateOf(name)
    var label by mutableStateOf(label)
    var amount by mutableStateOf(amount)
}

/**
 * Split one payment: equal or custom shares; each person typed, picked from People, or picked from contacts.
 * Only the owner's share stays in their spend; the rest is owed to them.
 */
@Composable
internal fun SplitSheet(
    row: Row,
    people: List<Person>,
    pickedContact: String?,
    onPickContact: () -> Unit,
    onContactUsed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<SplitShare>) -> Unit,
    groups: List<com.girish.expensetracker.data.FriendGroup> = emptyList(),
) {
    val t = LocalTones.current
    val total = row.txn.amountPaise
    val existing = row.txn.splitWith
    var equal by remember { mutableStateOf(existing.isEmpty() || existing.all { it.sharePaise == existing.first().sharePaise }) }
    val drafts = remember {
        mutableStateListOf<Draft>().apply {
            if (existing.isEmpty()) add(Draft("", null, "")) else existing.forEach { add(Draft(it.name, it.label, (it.sharePaise / 100.0).toString().removeSuffix(".0"))) }
        }
    }
    // A contact just came back from the picker: fill the first empty name, or add a person.
    if (pickedContact != null) {
        val empty = drafts.firstOrNull { it.name.isBlank() }
        if (empty != null) empty.name = pickedContact else drafts.add(Draft(pickedContact, null, ""))
        onContactUsed()
    }
    val equalShare = Owe.equalShare(total, drafts.size)
    val shares = drafts.mapIndexed { i, d ->
        SplitShare(d.name.trim().ifEmpty { "Person ${i + 1}" }, if (equal) equalShare else parseRupees(d.amount) ?: 0L, d.label)
    }
    val mine = total - shares.sumOf { it.sharePaise }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Split ${rupeesExact(total)}", style = MaterialTheme.typography.headlineSmall, color = t.ink)
                Text(row.txn.parsed.counterparty ?: row.txn.category, style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            }
            item { Segmented(listOf("Equally", "Custom amounts"), if (equal) 0 else 1, { equal = it == 0 }) }
            item { Text("With", style = MaterialTheme.typography.titleSmall, color = t.ink) }
            drafts.forEachIndexed { i, d ->
                item(key = "p$i") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            d.name, { d.name = it; d.label = null }, label = { Text("Name (optional)") }, singleLine = true,
                            colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (equal) Text(rupees(equalShare), style = AmountStyle, color = t.ink2, modifier = Modifier.width(76.dp), textAlign = TextAlign.End)
                        else OutlinedTextField(
                            d.amount, { d.amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("₹") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.width(96.dp),
                        )
                        if (drafts.size > 1) RoundIcon(Icons.Outlined.Close, "Remove", { drafts.removeAt(i) }, size = 32.dp)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("+ Person", { drafts.add(Draft("", null, "")) })
                    SecondaryButton("From contacts", onPickContact)
                }
            }
            if (groups.isNotEmpty()) item {
                Text("Groups", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                Gap(6.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { g ->
                        val all = g.members.all { (label, _) -> drafts.any { it.label == label } }
                        FilterChip(all, {
                            // Everyone in the group who isn't on the split yet; empty name rows are filled first.
                            g.members.filter { (label, _) -> drafts.none { it.label == label } }.forEach { (label, name) ->
                                val empty = drafts.firstOrNull { it.name.isBlank() }
                                if (empty != null) { empty.name = name; empty.label = label } else drafts.add(Draft(name, label, ""))
                            }
                        }, { Text("${g.name} · ${g.members.size}") }, colors = chipColors(), border = chipBorder(all))
                    }
                }
            }
            if (people.isNotEmpty()) item {
                Text("People you pay", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                Gap(6.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.take(10).forEach { p ->
                        val chosen = drafts.any { it.label in p.labels }
                        FilterChip(chosen, {
                            if (!chosen) {
                                val empty = drafts.firstOrNull { it.name.isBlank() }
                                if (empty != null) { empty.name = p.name; empty.label = p.labels.first() } else drafts.add(Draft(p.name, p.labels.first(), ""))
                            }
                        }, { Text(p.name) }, colors = chipColors(), border = chipBorder(chosen))
                    }
                }
            }
            item {
                Panel(padding = PaddingValues(14.dp)) {
                    Row {
                        Text("Your share", style = MaterialTheme.typography.titleSmall, color = t.ink, modifier = Modifier.weight(1f))
                        Text(rupeesExact(mine.coerceAtLeast(0)), style = AmountStyle, color = if (mine < 0) t.warn else t.ink)
                    }
                    Text(
                        if (mine < 0) "The shares add up to more than the payment." else "Only your share counts as spend. ${rupees(total - mine)} is owed to you until they pay you back.",
                        style = MaterialTheme.typography.bodySmall, color = if (mine < 0) t.warn else t.ink2,
                    )
                }
            }
            item { PrimaryButton("Save split", { onSave(shares) }, enabled = mine >= 0 && shares.all { it.sharePaise > 0 }) }
            if (existing.isNotEmpty()) item { TextButton(onClick = { onSave(emptyList()) }, modifier = Modifier.fillMaxWidth()) { Text("Remove split", color = t.warn) } }
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
internal fun SearchScreen(snap: Snapshot, isNew: (Row) -> Boolean, onBack: () -> Unit, onRow: (Row) -> Unit) {
    val t = LocalTones.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<Source?>(null) }
    val categories = remember(snap) { snap.rows.map { it.txn.category }.distinct().sorted() }
    val sources = remember(snap) { snap.rows.map { it.txn.parsed.source }.distinct().sortedBy { sourceLabel(it) } }
    val hits = remember(snap, query, category, source) {
        snap.rows.filter { r ->
            val x = r.txn
            (category == null || x.category == category) && (source == null || x.parsed.source == source) &&
                (query.isBlank() || listOfNotNull(x.parsed.counterparty, x.category, sourceLabel(x.parsed.source), r.rawBody).any { it.contains(query.trim(), ignoreCase = true) } ||
                    parseRupees(query)?.let { it == x.amountPaise || it / 100 == x.amountPaise / 100 } == true)
        }.sortedByDescending { it.txn.receivedAt }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 16.dp)) { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) } }
        item { ScreenTitle("Search", "Payee, category, card, amount or SMS text") }
        item {
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(t.panel).border(1.dp, t.hairline, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = t.ink3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Swiggy, 499, HDFC…", style = MaterialTheme.typography.bodyMedium, color = t.ink3)
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(t.accent), textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.ink), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c -> FilterChip(category == c, { category = if (category == c) null else c }, { Text(c) }, colors = chipColors(), border = chipBorder(category == c)) }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { s -> FilterChip(source == s, { source = if (source == s) null else s }, { Text(sourceLabel(s)) }, colors = chipColors(), border = chipBorder(source == s)) }
            }
        }
        item {
            val out = hits.filter { it.txn.parsed.direction == Direction.DEBIT }.sumOf { Ledger.monthAmount(it.txn).coerceAtLeast(0) }
            Text("${count(hits.size, "result")} · ${rupees(out)} counted as spend", style = MaterialTheme.typography.labelMedium, color = t.ink2)
        }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)) {
                if (hits.isEmpty()) Text("Nothing matches.", style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(vertical = 12.dp))
                hits.take(200).forEachIndexed { k, r -> if (k > 0) Hairline(); Column { Text(r.txn.day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).uppercase(), style = DataLabel, color = t.ink3, modifier = Modifier.padding(top = 10.dp)); TxnRow(r, isNew(r), onRow) } }
            }
        }
    }
}

// ---------------------------------------------------------------- backup password, statement import

@Composable
internal fun PasswordSheet(restoring: Boolean, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    val t = LocalTones.current
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = pw.length >= 6 && (restoring || pw == again)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (restoring) "Restore backup" else "Back up", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            Text(
                if (restoring) "Everything in the app is replaced by the backup." else "The file is encrypted with this password. Without it the backup can't be opened, by anyone, including you.",
                style = MaterialTheme.typography.bodyMedium, color = t.ink2,
            )
            OutlinedTextField(pw, { pw = it }, label = { Text("Password (6+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth())
            if (!restoring) OutlinedTextField(again, { again = it }, label = { Text("Same password again") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                isError = again.isNotEmpty() && again != pw)
            PrimaryButton(if (restoring) "Choose backup file" else "Choose where to save", { onDone(pw.toCharArray()) }, enabled = ok)
        }
    }
}

@Composable
internal fun ImportSheet(rows: Int, skipped: Int, sources: List<Source>, onDismiss: () -> Unit, onImport: (Source) -> Unit) {
    val t = LocalTones.current
    var chosen by remember { mutableStateOf(sources.firstOrNull()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import statement", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            Text("${count(rows, "transaction")} found" + if (skipped > 0) " (${count(skipped, "line")} skipped)." else ". Rows your SMS already have are not counted twice.",
                style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            Text("Which account or card is this statement for?", style = MaterialTheme.typography.titleSmall, color = t.ink)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { s -> FilterChip(chosen == s, { chosen = s }, { Text(sourceLabel(s)) }, colors = chipColors(), border = chipBorder(chosen == s)) }
            }
            PrimaryButton("Import ${count(rows, "transaction")}", { chosen?.let(onImport) }, enabled = chosen != null && rows > 0)
        }
    }
}

/** System / Dark / Light. */
@Composable
internal fun ThemePicker(theme: String, onTheme: (String) -> Unit) {
    Segmented(listOf("System", "Dark", "Light"), listOf("system", "dark", "light").indexOf(theme).coerceAtLeast(0), { onTheme(listOf("system", "dark", "light")[it]) })
}

