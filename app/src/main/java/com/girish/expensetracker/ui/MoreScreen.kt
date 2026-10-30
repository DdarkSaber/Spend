package com.girish.expensetracker.ui

import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.UserConfig
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.data.AutoBackup
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.data.UnparsedSms
import java.time.Instant
import java.time.ZoneId

/** Everything the More screen can trigger. Kept together so the screen only lays out rows. */
internal class MoreActions(
    val reviewSetup: () -> Unit,
    val addCash: () -> Unit,
    val importSms: () -> Unit,
    val importStatement: () -> Unit,
    val exportCsv: () -> Unit,
    val budgets: () -> Unit,
    val trips: () -> Unit,
    val backup: () -> Unit,
    val restore: () -> Unit,
    val autoBackup: () -> Unit,
    val track: (Source) -> Unit,
    val teach: (UnparsedSms) -> Unit,
    val battery: () -> Unit,
    val streak: () -> Unit,
    val year: () -> Unit,
)

/** Switches and their current values. */
internal class MoreSettings(
    val theme: String,
    val onTheme: (String) -> Unit,
    val lockOn: Boolean,
    val onLock: (Boolean) -> Unit,
    val digestOn: Boolean,
    val onDigest: (Boolean) -> Unit,
    val autoBackup: AutoBackup.State,
)

@Composable
internal fun MoreScreen(snap: Snapshot, setup: Setup, isNew: (Row) -> Boolean, onRow: (Row) -> Unit, actions: MoreActions, settings: MoreSettings) {
    val t = LocalTones.current
    val unsorted = snap.rows.filter { it.txn.kind == Kind.SPEND && it.txn.category == "Uncategorised" }.sortedByDescending { it.txn.receivedAt }
    val moneyIn = snap.rows.filter { it.txn.kind == Kind.INCOME }.sortedByDescending { it.txn.receivedAt }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("More") }

        item {
            Section("Your money") {
                Action(Icons.Outlined.CreditCard, "Accounts, name & salary day", "What the app learned from your SMS", actions.reviewSetup)
                Hairline()
                Action(Icons.Outlined.Savings, "Budgets", "Monthly and per category, with alerts", actions.budgets)
                Hairline()
                Action(Icons.Outlined.Flight, "Trips", if (snap.trips.isEmpty()) "Spend for a trip, apart from normal months" else count(snap.trips.size, "trip"), actions.trips)
                Hairline()
                Action(Icons.Outlined.Payments, "Add a cash spend", "For a payment that sends no SMS", actions.addCash)
            }
        }

        item {
            Section("Safety") {
                Toggle(Icons.Outlined.Lock, "App lock", "Fingerprint, face or PIN", settings.lockOn, settings.onLock)
                Hairline()
                val auto = settings.autoBackup
                Action(
                    Icons.Outlined.CloudUpload, "Automatic backup",
                    when {
                        !auto.on -> "Weekly to Google Drive or a folder"
                        auto.lastError != null -> "Last attempt failed: tap to check"
                        auto.lastMillis > 0 -> "Weekly · last ${Instant.ofEpochMilli(auto.lastMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(dayMonth)}"
                        else -> "Weekly"
                    },
                    actions.autoBackup,
                    trailing = if (auto.on) ({ Tag(if (auto.lastError != null) "failed" else "on") }) else null,
                )
                Hairline()
                Action(Icons.Outlined.Save, "Back up now", "Encrypted file, saved where you choose", actions.backup)
                Hairline()
                Action(Icons.Outlined.Restore, "Restore a backup", "Replaces what's in the app now", actions.restore)
            }
        }

        item {
            Section("Insights") {
                Action(
                    Icons.Outlined.LocalFireDepartment, "Low-spend streak",
                    "${count(snap.streak.current, "day")} under ${rupees(snap.streak.limitPaise)} a day · best ${snap.streak.best}",
                    actions.streak,
                )
                Hairline()
                Action(Icons.Outlined.EmojiEvents, "Your year", "Last 12 salary months in one page, shareable", actions.year)
            }
        }

        item {
            Section("Notifications") {
                Toggle(Icons.Outlined.NotificationsNone, "Evening summary", "Quiet 9 pm notification, no sound", settings.digestOn, settings.onDigest)
                if (!setup.batteryUnrestricted) {
                    Hairline()
                    Action(Icons.Outlined.Bolt, "Allow running in background", "Keeps the notification from being cleared", actions.battery)
                }
            }
        }

        item {
            Section("Data") {
                Action(Icons.Outlined.Sms, "Import SMS history", "Re-reads the last ${UserConfig.IMPORT_DAYS} days", actions.importSms)
                Hairline()
                Action(Icons.Outlined.UploadFile, "Import bank statement (CSV)", "History from before the app", actions.importStatement)
                Hairline()
                Action(Icons.Outlined.FileDownload, "Export CSV", "Saves to Download/Spend", actions.exportCsv)
            }
        }

        item {
            Panel {
                PanelHeader("Appearance")
                ThemePicker(settings.theme, settings.onTheme)
            }
        }

        // Things that need a look: shown only when there is something in them.
        if (snap.newSources.isNotEmpty()) item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("New cards or accounts", "${snap.newSources.size}")
                snap.newSources.forEachIndexed { k, ns ->
                    if (k > 0) Hairline()
                    ListRow(sourceLabel(ns.source), ns.example, leading = { BankMark(ns.source, 36.dp) },
                        trailing = { TextButton(onClick = { actions.track(ns.source) }) { Text("Track", color = t.accent) } })
                }
            }
        }
        if (unsorted.isNotEmpty()) item { RowList("Needs a category", unsorted, isNew, onRow) }
        if (snap.unparsed.isNotEmpty()) item {
            Panel {
                PanelHeader("SMS I couldn't read", "${snap.unparsed.size}")
                Text("Never counted. Tap one to teach it: point at the amount and the payee.", style = MaterialTheme.typography.bodySmall, color = t.ink3)
                snap.unparsed.take(20).forEach { u ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { actions.teach(u) }.padding(vertical = 10.dp)) {
                        Text("${u.sender} · ${u.receivedAt.toLocalDate().format(dayMonth)}".uppercase(), style = DataLabel, color = t.ink3)
                        Text(u.body, style = MaterialTheme.typography.bodySmall, color = t.ink2, maxLines = 3)
                        Text("Teach this SMS ›", style = MaterialTheme.typography.labelMedium, color = t.accent, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (moneyIn.isNotEmpty()) item { RowList("Money in · not counted", moneyIn, isNew, onRow) }
    }
}

/** A titled group of rows, so the screen reads as a few short lists instead of one long one. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val t = LocalTones.current
    Column {
        Text(title.uppercase(), style = DataLabel, color = t.ink3, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Panel(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    val t = LocalTones.current
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(t.raised), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Action(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    ListRow(title, sub, leading = { IconTile(icon) }, trailing = trailing?.let { content -> { content() } }, onClick = onClick)
}

@Composable
private fun Toggle(icon: ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalTones.current
    ListRow(
        title, sub, leading = { IconTile(icon) },
        trailing = {
            Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = Color.White, uncheckedTrackColor = t.section, uncheckedBorderColor = t.edge))
        },
        onClick = { onChange(!on) },
    )
}

/** Up to five payments, with the rest one tap away. */
@Composable
private fun RowList(title: String, rows: List<Row>, isNew: (Row) -> Boolean, onRow: (Row) -> Unit) {
    val t = LocalTones.current
    var all by remember { mutableStateOf(false) }
    Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        PanelHeader(title, "${rows.size}")
        (if (all) rows.take(60) else rows.take(5)).forEachIndexed { k, r -> if (k > 0) Hairline(); TxnRow(r, isNew(r), onRow) }
        if (rows.size > 5) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${rows.size}", color = t.accent) }
    }
}
