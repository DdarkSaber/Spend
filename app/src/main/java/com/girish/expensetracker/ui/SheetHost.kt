@file:OptIn(ExperimentalMaterial3Api::class)

package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.data.AutoBackup
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.data.StatementRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Draws whichever [Sheet] is open and turns its result into one controller action. */
@Composable
internal fun SheetHost(ctl: AppController, snap: Snapshot, pickers: Pickers) {
    val close = { ctl.sheet = null }
    when (val sheet = ctl.sheet) {
        null -> Unit

        is Sheet.Payment -> {
            val row = sheet.row
            RowSheet(
                row = row, onDismiss = close,
                onRetag = { type, category, onlyThis ->
                    val payee = row.txn.parsed.counterparty.orEmpty()
                    ctl.act {
                        if (onlyThis) overrideCategory(row.smsId!!, category, type) else retag(payee, type, category)
                        if (onlyThis) "This payment is now $category" else "Every payment to “$payee” is now $category"
                    }
                },
                onDeleteCash = { id -> ctl.act { store.deleteManual(id); "Cash entry deleted" } },
                onSplit = { ctl.sheet = Sheet.Split(row) },
                tag = snap.tagOf(row), knownTags = (snap.tags.values + snap.trips.map { it.name }).distinct().sorted(),
                onTag = { tag -> ctl.act(closeSheet = false) { setTag(row, tag); if (tag.isBlank()) "Tag removed" else "Tagged “${tag.trim()}”" } },
            )
        }

        is Sheet.Split -> SplitSheet(
            row = sheet.row, people = people(snap),
            pickedContact = pickers.contactName(), onPickContact = pickers.pickContact, onContactUsed = pickers.contactUsed,
            onDismiss = close,
            onSave = { shares ->
                ctl.act {
                    setSplit(sheet.row, shares)
                    if (shares.isEmpty()) "Split removed" else "Split with ${shares.joinToString { it.name }} · your share ${rupees(sheet.row.txn.amountPaise - shares.sumOf { it.sharePaise })}"
                }
            },
        )

        is Sheet.CardSettings -> CardSettingsSheet(sheet.card, onDismiss = close) { limit, day ->
            ctl.act { setCardLimit(sheet.card.card, limit); setStatementDay(sheet.card.card, day); "Saved ${sourceLabel(sheet.card.card)}" }
        }

        Sheet.Budgets -> BudgetSheet(snap.plan, used = Ledger.byCategory(snap.txns, snap.today).filterValues { it > 0 }.keys.sorted(), onDismiss = close) { total, perCat ->
            ctl.act { setBudget(null, total); perCat.forEach { (c, v) -> setBudget(c, v) }; "Budgets saved" }
        }

        is Sheet.Password -> PasswordSheet(restoring = sheet.purpose == Sheet.Password.Purpose.RESTORE, onDismiss = close) { pw ->
            ctl.sheet = null
            when (sheet.purpose) {
                Sheet.Password.Purpose.SAVE -> pickers.saveBackup(pw)
                Sheet.Password.Purpose.RESTORE -> pickers.restoreBackup(pw)
                Sheet.Password.Purpose.AUTO -> pickers.startAutoBackup(pw)
            }
        }

        is Sheet.ImportCsv -> {
            val sources = snap.owner?.tracked?.filter { it.last4 != "*" }?.sortedBy { sourceLabel(it) }.orEmpty()
            ImportSheet(sheet.result.rows.size, sheet.result.skipped, sources, onDismiss = close) { src ->
                ctl.act {
                    val added = store.addStatementRows(src, sheet.result.rows.map { StatementRow(it.day, it.amountPaise, it.debit, it.description) })
                    "Imported ${count(added, "new row")} into ${sourceLabel(src)}"
                }
            }
        }

        Sheet.Owner -> snap.owner?.let { owner ->
            OwnerSheet(owner = owner, loadSources = { ctl.repo.detectedSources() }, onDismiss = close) { keep, offered, name, day ->
                ctl.act { saveOwner(keep, offered, name, day); "Saved. Totals now use ${keep.size} accounts and cards." }
            }
        }

        Sheet.Cash -> AddCashSheet(onDismiss = close) { day, paise, note, category ->
            ctl.act { store.addManual(day, paise, note, category); "Added ${rupees(paise)} cash · $category" }
        }

        Sheet.NewTrip -> NewTripSheet(onDismiss = close) { name, start, end ->
            ctl.act { store.addTrip(name, start, end); "Added $name" }
        }

        is Sheet.Teach -> TeachSheet(sheet.sms, onDismiss = close) { layout ->
            ctl.act { teach(layout); "Learned. SMS like this are read from now on." }
        }

        Sheet.AutoBackup -> AutoBackupSheet(
            state = AutoBackup.state(ctl.context), onDismiss = close,
            onNow = { ctl.act { AutoBackup.runNow(ctl.context, store); AutoBackup.state(ctl.context).lastError?.let { "Backup failed: $it" } ?: "Backed up" } },
            onOff = { AutoBackup.disable(ctl.context); ctl.sheet = null; ctl.say("Automatic backup off"); ctl.reload() },
        )
    }
}

private val whenFormat = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

/** Status of the weekly backup, with "back up now" and "turn off". */
@Composable
private fun AutoBackupSheet(state: AutoBackup.State, onDismiss: () -> Unit, onNow: () -> Unit, onOff: () -> Unit) {
    val t = LocalTones.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Automatic backup", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            Text(
                "Every week an encrypted copy is saved to the file you picked (Google Drive uploads it). " +
                    "Last backup: " + (if (state.lastMillis > 0) Instant.ofEpochMilli(state.lastMillis).atZone(ZoneId.systemDefault()).format(whenFormat) else "never") + ".",
                style = MaterialTheme.typography.bodyMedium, color = t.ink2,
            )
            state.lastError?.let { Text("Last attempt failed: $it", style = MaterialTheme.typography.bodySmall, color = t.warn) }
            PrimaryButton("Back up now", onNow)
            TextButton(onClick = onOff, modifier = Modifier.fillMaxWidth()) { Text("Turn off", color = t.warn) }
        }
    }
}
