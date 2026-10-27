@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.girish.expensetracker.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Direction
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.PayeeType
import com.girish.expensetracker.core.Template
import com.girish.expensetracker.data.Row
import java.time.LocalDate

// ---------------------------------------------------------------- rows & sheets

@Composable
internal fun TxnRow(row: Row, isNew: Boolean, onClick: (Row) -> Unit) {
    val t = LocalTones.current
    val x = row.txn
    val month = Ledger.monthAmount(x)
    val counted = month != 0L
    val name = x.parsed.counterparty?.takeIf { it.isNotBlank() }
        ?: if (x.category == "Card bill") "${sourceLabel(x.parsed.source)} bill" else x.category
    val note = when {
        x.kind == Kind.INVESTMENT_LUMPSUM -> " · month only"
        !counted -> " · not counted"
        else -> ""
    }
    ListRow(
        title = name,
        subtitle = "${x.category} · ${sourceLabel(x.parsed.source)} · ${x.receivedAt.toLocalTime().withSecond(0).withNano(0)}$note",
        leading = {
            Box(Modifier.size(36.dp).clip(CircleShape).background(t.section), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (counted) paintFor(x.category) else t.edge))
            }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (x.parsed.direction == Direction.CREDIT) "+" else "") + rupeesExact(x.amountPaise),
                    style = AmountStyle,
                    color = when {
                        !counted -> t.ink3
                        month < 0 -> t.good
                        else -> t.ink
                    },
                )
                if (isNew) { Gap(4.dp); Tag("new") }
            }
        },
        onClick = { onClick(row) },
    )
}

@Composable
internal fun chipColors() = LocalTones.current.let { t ->
    FilterChipDefaults.filterChipColors(
        containerColor = t.section, labelColor = t.ink2,
        selectedContainerColor = t.accentSoft, selectedLabelColor = t.ink,
    )
}

@Composable
internal fun chipBorder(selected: Boolean) = LocalTones.current.let { t ->
    FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = t.hairline, selectedBorderColor = t.accent)
}

@Composable
internal fun RowSheet(
    row: Row,
    onDismiss: () -> Unit,
    onRetag: (PayeeType, String, Boolean) -> Unit,
    onDeleteCash: (Long) -> Unit,
    onSplit: () -> Unit,
    tag: String? = null,
    knownTags: List<String> = emptyList(),
    onTag: (String) -> Unit = {},
) {
    val t = LocalTones.current
    val x = row.txn
    val canRetag = (x.kind == Kind.SPEND || x.kind == Kind.LENT) && x.parsed.template != Template.MANUAL && !x.parsed.counterparty.isNullOrBlank()
    val canSplit = (x.kind == Kind.SPEND || x.kind == Kind.SUBSCRIPTION) && x.parsed.direction == Direction.DEBIT && (row.smsId != null || row.manualId != null)
    var type by remember { mutableStateOf(x.payeeType ?: PayeeType.PERSON) }
    var category by remember { mutableStateOf(x.category) }
    var onlyThis by remember { mutableStateOf(0) } // 0 = this payment only, 1 = every payment to this name

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(x.parsed.counterparty ?: x.category, style = MaterialTheme.typography.headlineSmall, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(rupeesExact(x.amountPaise), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), color = t.ink)
                Text(
                    "${x.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }} · ${sourceLabel(x.parsed.source)} · ${x.day.format(dayMonth)}, ${x.receivedAt.toLocalTime().withSecond(0).withNano(0)}",
                    style = MaterialTheme.typography.bodyMedium, color = t.ink2,
                )
                if (x.splitWith.isNotEmpty()) {
                    Text(
                        "Split with ${x.splitWith.joinToString { it.name }} · your share ${rupeesExact(x.ownPaise)}",
                        style = MaterialTheme.typography.bodyMedium, color = t.accent, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (canSplit) item {
                SecondaryButton(if (x.splitWith.isEmpty()) "Split this payment" else "Edit split", onSplit, Modifier.fillMaxWidth())
            }
            if (canRetag) {
                item { Gap(4.dp); Text("This payee is a", style = MaterialTheme.typography.titleSmall, color = t.ink) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(type == PayeeType.MERCHANT, { type = PayeeType.MERCHANT }, { Text("Shop or service") }, colors = chipColors(), border = chipBorder(type == PayeeType.MERCHANT))
                        FilterChip(type == PayeeType.PERSON, { type = PayeeType.PERSON }, { Text("Person") }, colors = chipColors(), border = chipBorder(type == PayeeType.PERSON))
                    }
                }
                item { Text("Category", style = MaterialTheme.typography.titleSmall, color = t.ink) }
                item {
                    // "Lent" makes a payment to a person a loan: not spend, and their payback settles it.
                    val choices = if (type == PayeeType.PERSON) CATEGORIES + com.girish.expensetracker.core.Classifier.LENT else CATEGORIES
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { c -> FilterChip(category == c, { category = c }, { Text(if (c == "Lent") "Lent (they'll pay back)" else c) }, colors = chipColors(), border = chipBorder(category == c)) }
                    }
                }
                if (row.smsId != null) item {
                    Gap(4.dp)
                    Text("Apply to", style = MaterialTheme.typography.titleSmall, color = t.ink)
                    Gap(8.dp)
                    Segmented(listOf("This payment only", "Every payment to this name"), onlyThis, { onlyThis = it })
                }
                item { Gap(4.dp); PrimaryButton(if (onlyThis == 0 && row.smsId != null) "Save for this payment" else "Save for every payment to this name", { onRetag(type, category, onlyThis == 0 && row.smsId != null) }) }
            }
            if (row.smsId != null || row.manualId != null) item { Gap(4.dp); TagEditor(tag, knownTags, onTag) }
            row.rawBody?.let { body ->
                item { Gap(4.dp); Text("Original SMS", style = MaterialTheme.typography.titleSmall, color = t.ink) }
                item { Text(body, style = MaterialTheme.typography.bodySmall, color = t.ink2) }
            }
            row.manualId?.let { id ->
                // Destructive action kept apart from everything else on the sheet.
                item { Gap(24.dp) }
                item {
                    OutlinedButton(
                        onClick = { onDeleteCash(id) }, shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Delete this cash entry", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddCashSheet(onDismiss: () -> Unit, onSave: (LocalDate, Long, String, String) -> Unit) {
    val t = LocalTones.current
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Food") }
    var yesterday by remember { mutableStateOf(false) }
    val paise = amount.toBigDecimalOrNull()?.movePointRight(2)?.toLong()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = t.accent, unfocusedBorderColor = t.edge, focusedLabelColor = t.accent, unfocusedLabelColor = t.ink2,
        focusedTextColor = t.ink, unfocusedTextColor = t.ink, cursorColor = t.accent,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Cash spend", style = MaterialTheme.typography.headlineSmall, color = t.ink) }
            item {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount in ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                    shape = RoundedCornerShape(10.dp),
                    isError = amount.isNotEmpty() && (paise == null || paise <= 0),
                    supportingText = { if (amount.isNotEmpty() && (paise == null || paise <= 0)) Text("Enter an amount like 120 or 45.50") },
                )
            }
            item {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("What for (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors, shape = RoundedCornerShape(10.dp))
            }
            item { Segmented(listOf("Today", "Yesterday"), if (yesterday) 1 else 0, { yesterday = it == 1 }) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORIES.forEach { c -> FilterChip(category == c, { category = c }, { Text(c) }, colors = chipColors(), border = chipBorder(category == c)) }
                }
            }
            item {
                PrimaryButton(
                    "Add cash spend",
                    { onSave(LocalDate.now().minusDays(if (yesterday) 1 else 0), paise!!, note.ifBlank { "Cash" }, category) },
                    enabled = paise != null && paise > 0,
                )
            }
        }
    }
}
