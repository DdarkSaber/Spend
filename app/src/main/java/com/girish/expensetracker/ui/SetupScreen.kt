package com.girish.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.UserConfig
import com.girish.expensetracker.data.Row

// ---------------------------------------------------------------- setup

@Composable
internal fun SetupScreen(setup: Setup, onRequest: () -> Unit, onAppSettings: () -> Unit, onBattery: () -> Unit) {
    val t = LocalTones.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Gap(24.dp)
            Text("Spend", style = MaterialTheme.typography.displayMedium, color = t.ink)
            Text(
                "Your spending, read from bank SMS on this phone. Nothing leaves it: the app has no internet access.",
                style = MaterialTheme.typography.bodyLarge, color = t.ink2, modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Panel {
                PanelHeader("How it works")
                listOf(
                    "1" to "Your bank already texts you every payment. Spend reads those SMS: UPI, cards, salary, refunds.",
                    "2" to "It works out what counts: card bills, transfers between your own accounts and repeats are left out.",
                    "3" to "“Today · Month” stays in your notification shade. Everything else is one tap away.",
                ).forEach { (n, text) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Text(n, style = DataLabel, color = t.accent, modifier = Modifier.width(22.dp).padding(top = 3.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = t.ink2)
                    }
                }
                Gap(6.dp)
                Text("No internet permission, no account, no ads. Your SMS never leave the phone.", style = MaterialTheme.typography.labelMedium, color = t.ink)
            }
        }
        item {
            Panel {
                Step(setup.sms, "Allow SMS", "Reads bank transaction messages as they arrive, and imports the last ${UserConfig.IMPORT_DAYS} days once.")
                Hairline()
                Step(setup.notifications, "Allow notifications", "Keeps “Today · Month” pinned in your notification shade.")
                Hairline()
                Step(setup.batteryUnrestricted, "Battery: unrestricted", "Recommended on OnePlus so the notification is never cleared.")
            }
        }
        item { PrimaryButton("Grant permissions", onRequest) }
        if (!setup.batteryUnrestricted) item { SecondaryButton("Allow running in background", onBattery, Modifier.fillMaxWidth()) }
        item {
            Text(
                "SMS greyed out or blocked? Android restricts it for apps installed outside the Play Store: open App info, tap ⋮ (top right), choose “Allow restricted settings”, then tap Grant permissions again.",
                style = MaterialTheme.typography.bodySmall, color = t.ink3,
            )
            TextButton(onClick = onAppSettings) { Text("Open App info", color = t.accent) }
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String) {
    val t = LocalTones.current
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done", tint = if (done) t.good else t.ink3,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = t.ink)
            Text(body, style = MaterialTheme.typography.bodySmall, color = t.ink2)
        }
    }
}
