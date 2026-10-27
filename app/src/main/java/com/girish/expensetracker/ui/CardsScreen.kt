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
    onRow: (Row) -> Unit,
) {
    val t = LocalTones.current
    var view by remember { mutableStateOf(0) } // 0 = credit cards, 1 = bank accounts
    val type = if (view == 0) SourceType.CARD else SourceType.ACCOUNT
    val start = snap.cycleStart
    val bySource = remember(snap) { Ledger.bySource(snap.txns, snap.today) }.filterKeys { it.type == type && it.bank != "CASH" }
    var open by remember { mutableStateOf<Source?>(null) }
    val billsPaid = snap.rows.filter { it.txn.kind == Kind.TRANSFER && it.txn.category == "Card bill" && it.txn.day >= start && it.txn.parsed.source.type == SourceType.CARD }
        .sortedByDescending { it.txn.receivedAt }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Cards & accounts", "Spend since ${start.format(dayMonth)}") }
        item { Segmented(listOf("Credit cards", "Bank accounts"), view, { view = it; open = null }) }
        // Utilisation and bills first: the number that moves your credit score.
        if (view == 0) snap.cards.forEach { h -> item { CardHealthPanel(h) { onCardSettings(h) } } }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)) {
                if (bySource.isEmpty()) {
                    Text(
                        if (view == 0) "No card spends this salary month." else "No UPI or debit spends this salary month.",
                        style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                bySource.toList().sortedByDescending { it.second }.forEachIndexed { k, (src, amt) ->
                    if (k > 0) Hairline()
                    val count = snap.rows.count { it.txn.parsed.source == src && it.txn.day >= start && it.txn.parsed.direction == Direction.DEBIT && Ledger.monthAmount(it.txn) > 0 }
                    ListRow(
                        title = sourceLabel(src),
                        subtitle = "${count(count, "payment")} · ${if (src.type == SourceType.CARD) "credit card" else "UPI & debit"}",
                        leading = { BankMark(src) },
                        trailing = { Text(rupees(amt), style = AmountStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.ink) },
                        onClick = { open = if (open == src) null else src },
                    )
                    AnimatedVisibility(open == src, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(start = 12.dp)) {
                            snap.rows.filter { it.txn.parsed.source == src && it.txn.day >= start && it.txn.parsed.direction == Direction.DEBIT }
                                .sortedByDescending { it.txn.receivedAt }
                                .forEach { TxnRow(it, isNew(it), onRow) }
                        }
                    }
                }
            }
        }
        if (view == 0 && billsPaid.isNotEmpty()) item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("Bills paid", "not counted")
                billsPaid.forEachIndexed { k, r ->
                    if (k > 0) Hairline()
                    ListRow(
                        title = "${sourceLabel(r.txn.parsed.source)} bill",
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
