@file:OptIn(ExperimentalMaterial3Api::class)

package com.girish.expensetracker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.data.Repo

/** Opens Android's notification access page, where the owner allows Spend to read notifications. */
internal fun openNotificationAccess(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Installed apps with a launcher icon: (package, label), A to Z. */
internal fun launcherApps(context: android.content.Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != context.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
}

/** More → Alert apps: which apps' notifications are read for bank alerts, and notification access. */
@Composable
internal fun AlertAppsSheet(apps: List<Repo.AlertApp>, accessOn: Boolean, onDismiss: () -> Unit, onSave: (List<Repo.AlertApp>) -> Unit) {
    val t = LocalTones.current
    val context = LocalContext.current
    val list = remember { mutableStateListOf<Repo.AlertApp>().apply { addAll(apps) } }
    var adding by remember { mutableStateOf(false) }
    val installed = remember(adding) { if (adding) launcherApps(context) else emptyList() }
    val switchColors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = Color.White, uncheckedTrackColor = t.section, uncheckedBorderColor = t.edge)

    ModalBottomSheet(onDismissRequest = { onSave(list.toList()); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Alert apps", style = MaterialTheme.typography.headlineSmall, color = t.ink)
            Text("Bank alerts that never come as SMS (Truecaller chats, RCS, bank emails) are read from these apps' notifications. Nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall, color = t.ink2)
            ListRow(
                "Notification access", if (accessOn) "On" else "Off · alerts from these apps are missed",
                trailing = { TextButton(onClick = { openNotificationAccess(context) }) { Text(if (accessOn) "Settings" else "Turn on", color = t.accent) } },
            )
            val hiddenAt = remember { runCatching { (context.applicationContext as com.girish.expensetracker.SpendApp).repo.store.setting(com.girish.expensetracker.data.KEY_SENSITIVE_HIDDEN_AT)?.toLongOrNull() }.getOrNull() }
            val granted = remember {
                runCatching {
                    context.checkSelfPermission("android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        context.getSystemService(android.app.AppOpsManager::class.java)
                            .unsafeCheckOpNoThrow("android:receive_sensitive_notifications", android.os.Process.myUid(), context.packageName) == android.app.AppOpsManager.MODE_ALLOWED
                }.getOrDefault(false)
            }
            val hiddenDay = hiddenAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")) }
            ListRow(
                "Sensitive alerts",
                when {
                    hiddenDay == null -> if (granted) "Allowed" else "No alert hidden by Android so far"
                    granted -> "Allowed · last hidden $hiddenDay (should stop now)"
                    else -> "Blocked · Android hid an alert's text on $hiddenDay"
                },
            )
            if (!granted) androidx.compose.foundation.text.selection.SelectionContainer {
                Text("Android hides some bank alerts (often Gmail) from other apps. To allow Spend, run once over wireless debugging:\ncmd appops set --user 0 com.girish.expensetracker RECEIVE_SENSITIVE_NOTIFICATIONS allow",
                    style = MaterialTheme.typography.bodySmall, color = t.ink2)
            }
            Hairline()
            list.forEachIndexed { i, a ->
                ListRow(a.label, a.pkg, trailing = { Switch(a.enabled, { on -> list[i] = a.copy(enabled = on) }, colors = switchColors) })
            }
            if (!adding) TextButton(onClick = { adding = true }) { Text("Add app", color = t.accent) }
            else {
                Text("Pick an app", style = MaterialTheme.typography.titleSmall, color = t.ink)
                installed.filter { (p, _) -> list.none { it.pkg == p } }.forEach { (pkg, label) ->
                    ListRow(label, pkg, onClick = { list += Repo.AlertApp(pkg, label, true); adding = false })
                }
            }
            PrimaryButton("Done", { onSave(list.toList()); onDismiss() })
        }
    }
}
