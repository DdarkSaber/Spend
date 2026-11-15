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
import com.girish.expensetracker.data.UnparsedSms

/** Alerts marked "Not a spend": never counted; Restore puts one back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenSheet(list: List<UnparsedSms>, onDismiss: () -> Unit, onRestore: (Long) -> Unit) {
    val t = LocalTones.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Hidden payments", style = MaterialTheme.typography.headlineSmall, color = t.ink) }
            if (list.isEmpty()) item { Text("Nothing hidden.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) }
            items(list, key = { it.id }) { s ->
                ListRow(
                    title = s.body.take(80), subtitle = "${s.sender} · ${s.receivedAt.toLocalDate()}",
                    trailing = { TextButton(onClick = { onRestore(s.id) }) { Text("Restore", color = t.accent) } },
                )
            }
        }
    }
}
