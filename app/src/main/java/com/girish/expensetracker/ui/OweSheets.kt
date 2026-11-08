package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.data.Row
import java.time.LocalDate

@Composable
private fun oweFieldColors() = LocalTones.current.let { t ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = t.accent, unfocusedBorderColor = t.edge, focusedLabelColor = t.accent, unfocusedLabelColor = t.ink2,
        focusedTextColor = t.ink, unfocusedTextColor = t.ink, cursorColor = t.accent,
    )
}

/**
 * "They paid for me": no SMS exists when a friend pays for you. It counts as your spend (you had it) and you owe
 * them; your next UPI to them settles it (bulk included).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TheyPaidSheet(
    people: List<Person>,
    preset: Person?,
    onDismiss: () -> Unit,
    onSave: (day: LocalDate, paise: Long, name: String, label: String?, category: String, note: String) -> Unit,
) {
    val t = LocalTones.current
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Food") }
    var yesterday by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(preset) }
    var typed by remember { mutableStateOf("") }
    val paise = amount.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
    val name = picked?.name ?: typed.trim()
    val shown = people.filter { typed.isBlank() || it.name.contains(typed, true) || it.labels.any { l -> l.contains(typed, true) } }.take(8)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("They paid for me", style = MaterialTheme.typography.headlineSmall, color = t.ink)
                Text("Counts as your spend, and you owe them until you pay them back.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            }
            item {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount in ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = oweFieldColors(),
                    shape = RoundedCornerShape(10.dp), isError = amount.isNotEmpty() && (paise == null || paise <= 0),
                )
            }
            item { Text("Who paid", style = MaterialTheme.typography.titleSmall, color = t.ink) }
            if (picked == null) item {
                OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Search People or type a name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = oweFieldColors(), shape = RoundedCornerShape(10.dp))
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    picked?.let { p -> FilterChip(true, { picked = null }, { Text(p.name + "  ✕") }, colors = chipColors(), border = chipBorder(true)) }
                        ?: shown.forEach { p -> FilterChip(false, { picked = p; typed = "" }, { Text(p.name) }, colors = chipColors(), border = chipBorder(false)) }
                }
            }
            item {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("What for (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = oweFieldColors(), shape = RoundedCornerShape(10.dp))
            }
            item { Segmented(listOf("Today", "Yesterday"), if (yesterday) 1 else 0, { yesterday = it == 1 }) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORIES.forEach { c -> FilterChip(category == c, { category = c }, { Text(c) }, colors = chipColors(), border = chipBorder(category == c)) }
                }
            }
            item {
                PrimaryButton(
                    if (name.isBlank()) "Add" else "Add · you owe $name",
                    { onSave(LocalDate.now().minusDays(if (yesterday) 1 else 0), paise!!, name, picked?.labels?.firstOrNull(), category, note.trim()) },
                    enabled = paise != null && paise > 0 && name.isNotBlank(),
                )
            }
        }
    }
}

/** "Paid on their behalf": money in from someone else (a parent, a flatmate) that belongs to a person in People. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssignCreditSheet(row: Row, people: List<Person>, assigned: Boolean, onDismiss: () -> Unit, onPick: (Person?) -> Unit) {
    val t = LocalTones.current
    var typed by remember { mutableStateOf("") }
    val shown = people.filter { typed.isBlank() || it.name.contains(typed, true) || it.labels.any { l -> l.contains(typed, true) } }.take(30)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("Paid on behalf of", style = MaterialTheme.typography.headlineSmall, color = t.ink)
                Text(
                    "${rupeesExact(row.txn.amountPaise)} from ${row.txn.parsed.counterparty ?: "someone"}. Pick who it was for: it settles what they owe you.",
                    style = MaterialTheme.typography.bodyMedium, color = t.ink2,
                )
            }
            item {
                OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Search People") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = oweFieldColors(), shape = RoundedCornerShape(10.dp))
            }
            items(shown, key = { it.key }) { p ->
                ListRow(title = p.name, subtitle = p.labels.first(), leading = { Avatar(p.name, 32.dp) }, onClick = { onPick(p) })
            }
            if (assigned) item { SecondaryButton("Back to the bank's payer name", { onPick(null) }, Modifier.fillMaxWidth()) }
        }
    }
}
