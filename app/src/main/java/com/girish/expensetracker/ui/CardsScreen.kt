package com.girish.expensetracker.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Direction
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot

// ---------------------------------------------------------------- cards & accounts

@Composable
internal fun CardsScreen(
    snap: Snapshot,
    isNew: (Row) -> Boolean,
    onCardSettings: (com.girish.expensetracker.core.CardHealth) -> Unit = {},
    /** Group these cards (the ones that look shared, or all the bank's cards). */
    onGroupCards: (com.girish.expensetracker.core.CardGroup) -> Unit = {},
    onDeclineGroup: (String) -> Unit = {},
    onPickCard: (Row, Source, Boolean) -> Unit = { _, _, _ -> },
    onMarkPaid: (com.girish.expensetracker.core.CardHealth, Boolean) -> Unit = { _, _ -> },
    onRow: (Row) -> Unit,
) {
    val t = LocalTones.current
    var view by remember { mutableStateOf(0) } // 0 = credit cards, 1 = bank accounts
    val type = if (view == 0) SourceType.CARD else SourceType.ACCOUNT
    val start = snap.cycleStart
    val bySource = remember(snap) { Ledger.bySource(snap.txns, snap.today) }.filterKeys { it.type == type && it.bank != "CASH" }
    var open by remember { mutableStateOf<Source?>(null) }
    // Worked out once per data load, not on every redraw (opening a row redraws the screen).
    val billsPaid = remember(snap) {
        snap.rows.filter { it.txn.kind == Kind.TRANSFER && it.txn.category == "Card bill" && it.txn.day >= start && it.txn.parsed.source.type == SourceType.CARD }
            .sortedByDescending { it.txn.receivedAt }
    }
    val spendsBySource = remember(snap) {
        snap.rows.filter { it.txn.day >= start && it.txn.parsed.direction == Direction.DEBIT }.sortedByDescending { it.txn.receivedAt }.groupBy { it.txn.parsed.source }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title") { ScreenTitle("Cards & accounts", "Spend since ${start.format(dayMonth)}") }
        item(key = "tabs") { Segmented(listOf("Credit cards", "Bank accounts"), view, { view = it; open = null }) }
        // Utilisation and bills first: the number that moves your credit score.
        if (view == 0 && snap.unassignedCardRows.isNotEmpty()) item(key = "which-card") {
            WhichCardPanel(snap.unassignedCardRows, { bank -> snap.trackedCards.filter { it.bank == bank } }, onPickCard)
        }
        if (view == 0 && snap.cardGroupSuggestions.isNotEmpty()) item(key = "suggest") {
            CardGroupSuggestions(snap.cardGroupSuggestions, snap.likelyShared, onGroupCards, onDeclineGroup)
        }
        if (view == 0) snap.cards.forEach { h -> item(key = "health-${h.card.bank}-${h.card.last4}") { CardHealthPanel(h, { onCardSettings(h) }, { paid -> onMarkPaid(h, paid) }) } }
        item(key = "spend") {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)) {
                if (bySource.isEmpty()) {
                    Text(
                        if (view == 0) "No card spends this month." else "No UPI or debit spends this month.",
                        style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                bySource.toList().sortedByDescending { it.second }.forEachIndexed { k, (src, amt) ->
                    if (k > 0) Hairline()
                    val count = spendsBySource[src].orEmpty().count { Ledger.monthAmount(it.txn) > 0 }
                    ListRow(
                        title = sourceLabel(src),
                        subtitle = "${count(count, "payment")} · ${if (src.type == SourceType.CARD) "credit card" else "UPI & debit"}",
                        leading = { BankMark(src) },
                        trailing = { Text(rupees(amt), style = AmountStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.ink) },
                        onClick = { open = if (open == src) null else src },
                    )
                    AnimatedVisibility(open == src, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(start = 12.dp)) {
                            spendsBySource[src].orEmpty().forEach { TxnRow(it, isNew(it), onRow) }
                        }
                    }
                }
            }
        }
        if (view == 0 && billsPaid.isNotEmpty()) item(key = "bills") {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("Bills paid", "not counted")
                billsPaid.forEachIndexed { k, r ->
                    if (k > 0) Hairline()
                    ListRow(
                        // Cards on one statement have one bill: name it after their group.
                        title = "${snap.groupOf(r.txn.parsed.source)?.let(snap::groupTitle) ?: sourceLabel(r.txn.parsed.source)} bill",
                        subtitle = r.txn.day.format(dayMonth),
                        leading = { BankMark(r.txn.parsed.source, 36.dp) },
                        trailing = { Text(rupeesExact(r.txn.amountPaise), style = AmountStyle, color = t.ink2) },
                        onClick = { onRow(r) },
                    )
                }
            }
        }
    }
}
