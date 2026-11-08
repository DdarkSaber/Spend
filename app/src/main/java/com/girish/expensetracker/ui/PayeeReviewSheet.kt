package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.PayeeReview

/**
 * One-time "Shop or person?" list: each payee that is a person only by default, likely shops pre-ticked. Nothing
 * changes until Save; the answers are remembered as payee rules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PayeeReviewSheet(list: List<PayeeReview>, onDismiss: () -> Unit, onSave: (shops: List<String>, persons: List<String>) -> Unit) {
    val t = LocalTones.current
    val shop = remember(list) { mutableStateMapOf<String, Boolean>().apply { list.forEach { put(it.label, it.likelyShop) } } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Shop or person?", style = MaterialTheme.typography.headlineSmall, color = t.ink)
                Text(
                    "People count toward People and splits; shops get a category. Likely shops (3+ payments, uneven amounts, never paid you) are pre-ticked.",
                    style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            items(list, key = { it.label }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = MaterialTheme.typography.titleSmall, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(count(p.payments, "payment"), style = MaterialTheme.typography.bodySmall, color = t.ink2)
                    }
                    val isShop = shop[p.label] == true
                    FilterChip(isShop, { shop[p.label] = true }, { Text("Shop") }, colors = chipColors(), border = chipBorder(isShop))
                    FilterChip(!isShop, { shop[p.label] = false }, { Text("Person") }, colors = chipColors(), border = chipBorder(!isShop), modifier = Modifier.padding(start = 6.dp))
                }
            }
            item {
                PrimaryButton("Save ${list.size} answers", {
                    onSave(list.filter { shop[it.label] == true }.map { it.label }, list.filter { shop[it.label] != true }.map { it.label })
                }, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}
