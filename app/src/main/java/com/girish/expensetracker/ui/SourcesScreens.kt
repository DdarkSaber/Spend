@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.CardGroup
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceMeta
import com.girish.expensetracker.core.SourceOwner
import com.girish.expensetracker.core.SourceStatus
import com.girish.expensetracker.core.SourceType
import com.girish.expensetracker.core.UNASSIGNED
import com.girish.expensetracker.data.Repo
import com.girish.expensetracker.data.Snapshot
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dmy = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** Every real account and card the app knows: tracked, ignored, or described by the owner. */
internal fun allSources(snap: Snapshot): List<Source> =
    ((snap.owner?.tracked.orEmpty()) + snap.ignored + snap.sourceMeta.keys)
        .filter { it.bank != "CASH" && it.last4 != "*" && it.last4 != UNASSIGNED && !CardGroup.isGroup(it) }
        .distinct()
        .sortedWith(compareBy({ it.bank }, { it.type }, { it.last4 }))

internal fun sourceTitle(snap: Snapshot, s: Source): String =
    sourceLabel(s) + (SourceMeta.describe(snap.sourceMeta[s])?.let { " · $it" } ?: "")

private fun sourceSubtitle(snap: Snapshot, s: Source): String {
    val m = snap.sourceMeta[s]
    return listOfNotNull(
        snap.lastActivity[s]?.let { "last used ${it.format(dmy)}" } ?: "no transactions",
        m?.linkedTo?.let { "under ${sourceLabel(it)}" },
        if (s.type == SourceType.CARD) snap.groupOf(s)?.let { snap.groupTitle(it) } else null,
        if (m != null && m.status != SourceStatus.AUTO && m.status != SourceStatus.IGNORED) "set by you" else null,
    ).joinToString(" · ")
}

/** More → Cards and accounts: Active, Past and Ignored, each row opens its settings. */
@Composable
internal fun SourcesScreen(snap: Snapshot, onBack: () -> Unit, onSource: (Source) -> Unit) {
    val t = LocalTones.current
    val bySection = remember(snap) {
        allSources(snap).groupBy { if (it in snap.ignored) SourceStatus.IGNORED else snap.statusOf(it) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 16.dp)) { RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) } }
        item { ScreenTitle("Cards and accounts", "Active ones have a payment in the last ${SourceMeta.ACTIVE_DAYS} days. Old ones stay in history.") }
        listOf(
            SourceStatus.ACTIVE to "Active",
            SourceStatus.PAST to "Past",
            SourceStatus.IGNORED to "Ignored · never counted",
        ).forEach { (status, title) ->
            val list = bySection[status].orEmpty()
            if (list.isNotEmpty()) item(key = "sources-$status") {
                Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                    PanelHeader(title, "${list.size}")
                    list.forEachIndexed { k, s ->
                        if (k > 0) Hairline()
                        ListRow(sourceTitle(snap, s), sourceSubtitle(snap, s), leading = { BankMark(s, 36.dp) }, onClick = { onSource(s) })
                    }
                }
            }
        }
        if (bySection.isEmpty()) item {
            Panel { Text("Accounts and cards appear here once their SMS arrive.", style = MaterialTheme.typography.bodyMedium, color = t.ink2) }
        }
    }
}

/** One account or card: status, add-on owner, the account a debit card draws from, and a name. */
@Composable
internal fun SourceSheet(snap: Snapshot, source: Source, onDismiss: () -> Unit, onSave: (SourceMeta) -> Unit) {
    val t = LocalTones.current
    val old = snap.sourceMeta[source] ?: SourceMeta(source, status = if (source in snap.ignored) SourceStatus.IGNORED else SourceStatus.AUTO)
    var status by remember { mutableStateOf(old.status) }
    var addOn by remember { mutableStateOf(old.isAddOn) }
    var label by remember { mutableStateOf(old.label.orEmpty()) }
    var linked by remember { mutableStateOf(old.linkedTo) }
    val isCard = source.type == SourceType.CARD
    val accounts = remember(snap) { allSources(snap).filter { it.bank == source.bank && it.type == SourceType.ACCOUNT && it !in snap.ignored } }
    val auto = SourceMeta.effective(null, snap.lastActivity[source], snap.today)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(sourceLabel(source), style = MaterialTheme.typography.headlineSmall, color = t.ink)
            Text(sourceSubtitle(snap, source), style = MaterialTheme.typography.bodySmall, color = t.ink2)

            Text("Status", style = MaterialTheme.typography.titleSmall, color = t.ink)
            val options = listOf(SourceStatus.AUTO, SourceStatus.ACTIVE, SourceStatus.PAST, SourceStatus.IGNORED)
            Segmented(listOf("Auto", "Active", "Past", "Ignore"), options.indexOf(status), { status = options[it] })
            Text(
                when (status) {
                    SourceStatus.AUTO -> "Now ${if (auto == SourceStatus.ACTIVE) "active" else "past"}: active while it has a payment in the last ${SourceMeta.ACTIVE_DAYS} days."
                    SourceStatus.ACTIVE -> "Always shown on Home and in card health."
                    SourceStatus.PAST -> "Kept in history; left out of Home and card health."
                    SourceStatus.IGNORED -> "Not counted anywhere, past or present."
                },
                style = MaterialTheme.typography.bodySmall, color = t.ink3,
            )

            if (isCard) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Add-on card, bills paid by someone else", style = MaterialTheme.typography.titleSmall, color = t.ink)
                        Text("Its spends count; it's left out of bills, utilisation and warnings.", style = MaterialTheme.typography.bodySmall, color = t.ink3)
                    }
                    Switch(addOn, { addOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = Color.White, uncheckedTrackColor = t.section, uncheckedBorderColor = t.edge))
                }
            }
            OutlinedTextField(
                label, { label = it.take(30) }, singleLine = true,
                label = { Text(if (addOn) "Paid by (e.g. father)" else "Name (optional)") },
                colors = fieldColors(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
            )

            if (isCard && accounts.isNotEmpty()) {
                Text("Debit card of an account?", style = MaterialTheme.typography.titleSmall, color = t.ink)
                if (linked == null && accounts.size == 1) {
                    Text("If this is the debit card of ${sourceLabel(accounts.single())}, link it so its spends show under the account and count once.",
                        style = MaterialTheme.typography.bodySmall, color = t.ink3)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(linked == null, { linked = null }, { Text("No, a credit card") }, colors = chipColors(), border = chipBorder(linked == null))
                    accounts.forEach { a ->
                        FilterChip(linked == a, { linked = a }, { Text("Of ${sourceLabel(a)}") }, colors = chipColors(), border = chipBorder(linked == a))
                    }
                }
            }

            PrimaryButton("Save", {
                onSave(old.copy(
                    status = status,
                    owner = if (isCard && addOn) SourceOwner.ADDON_PAID_BY_OTHER else SourceOwner.MINE,
                    linkedTo = if (isCard) linked else null,
                    label = label.trim().ifEmpty { null },
                ))
            })
        }
    }
}

/**
 * After a history import: what came in per source, then the one-time questions. Older cards found in the file are
 * kept for history by default; banks with several cards are asked whether they are one account.
 */
@Composable
internal fun OlderSourcesSheet(
    snap: Snapshot,
    result: Repo.ArchiveResult,
    onDismiss: () -> Unit,
    onDone: (keep: Set<Source>, drop: Set<Source>, oneAccount: List<CardGroup>, separate: List<String>) -> Unit,
) {
    val t = LocalTones.current
    val found = remember { snap.newSources.map { it.source } }
    val suggestions = remember { snap.cardGroupSuggestions }
    val keep = remember { mutableStateMapOf<Source, Boolean>().apply { found.forEach { put(it, true) } } }
    val sameAccount = remember { mutableStateMapOf<String, Boolean?>().apply { suggestions.forEach { put(it.bank, null) } } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("History imported", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            result.counts.forEach { c ->
                val parts = listOfNotNull(
                    if (c.alerts > 0) "${count(c.alerts, "alert")}, ${c.alertsNew} new" else null,
                    if (c.rows > 0) "${count(c.rows, "statement row")}, ${c.rowsNew} new" else null,
                )
                ListRow(c.channel.removePrefix("archive:").replaceFirstChar { it.uppercase() }, parts.joinToString(" · "))
            }
            if (result.rejected.isNotEmpty()) {
                Text("${count(result.rejected.size, "line")} skipped: " + result.rejected.groupBy { it.reason }.entries.joinToString("; ") { "${it.value.size} ${it.key}" },
                    style = MaterialTheme.typography.bodySmall, color = t.ink3)
            }

            if (found.isNotEmpty()) {
                Text("Keep older cards for history?", style = MaterialTheme.typography.titleMedium, color = t.ink)
                Text("Kept ones count in the months they were used and show as past. Unticked ones are ignored.", style = MaterialTheme.typography.bodySmall, color = t.ink3)
                found.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(keep[s] == true, { keep[s] = it }, colors = CheckboxDefaults.colors(checkedColor = t.accent))
                        ListRow(sourceLabel(s), snap.newSources.firstOrNull { it.source == s }?.example, leading = { BankMark(s, 32.dp) }, onClick = { keep[s] = keep[s] != true })
                    }
                }
            }

            suggestions.forEach { g ->
                Text("Are ${g.bank} cards ${g.cards.joinToString(", ") { it.last4 }} one account?", style = MaterialTheme.typography.titleMedium, color = t.ink)
                Text("One account means one statement and one shared limit.", style = MaterialTheme.typography.bodySmall, color = t.ink3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val answer = sameAccount[g.bank]
                    FilterChip(answer == true, { sameAccount[g.bank] = true }, { Text("Yes, one account") }, colors = chipColors(), border = chipBorder(answer == true))
                    FilterChip(answer == false, { sameAccount[g.bank] = false }, { Text("No, separate") }, colors = chipColors(), border = chipBorder(answer == false))
                }
            }

            PrimaryButton("Done", {
                onDone(
                    keep.filterValues { it }.keys,
                    keep.filterValues { !it }.keys,
                    suggestions.filter { sameAccount[it.bank] == true },
                    suggestions.filter { sameAccount[it.bank] == false }.map { it.bank },
                )
            })
        }
    }
}
