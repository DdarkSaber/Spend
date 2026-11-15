package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.data.TransferPair

/** More → Transfers found: payments the app paired as moves between your own accounts; "Not a transfer" undoes one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransfersSheet(list: List<TransferPair>, onDismiss: () -> Unit, onUndo: (TransferPair) -> Unit) {
    val t = LocalTones.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Transfers found", style = MaterialTheme.typography.headlineSmall, color = t.ink) }
            item {
                Text("The same amount left one of your accounts and arrived on another within 2 days, so neither counts as spend or money in. If a pair is really two different payments, undo it.",
                    style = MaterialTheme.typography.bodySmall, color = t.ink2)
            }
            if (list.isEmpty()) item { Text("No transfers paired.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) }
            items(list, key = { "${it.debit.smsId}-${it.credit.smsId}-${it.debit.txn.receivedAt}" }) { p ->
                val d = p.debit.txn
                val c = p.credit.txn
                ListRow(
                    title = "${rupees(d.amountPaise)} · ${d.parsed.source.bank} ${d.parsed.source.last4} → ${c.parsed.source.bank} ${c.parsed.source.last4}",
                    subtitle = listOfNotNull(d.day.toString(), d.parsed.counterparty?.take(30), c.parsed.counterparty?.take(30)).distinct().joinToString(" · "),
                    trailing = { if (p.undoable) TextButton(onClick = { onUndo(p) }) { Text("Not a transfer", color = t.accent) } },
                )
            }
        }
    }
}
