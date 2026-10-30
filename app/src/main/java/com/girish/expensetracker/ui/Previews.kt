package com.girish.expensetracker.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import com.girish.expensetracker.data.Row
import java.time.LocalDateTime

// Previews render in Android Studio's design pane with synthetic DemoData; no emulator or phone needed.

private val demo by lazy { DemoData.snapshot() }
private val demoIsNew: (Row) -> Boolean = { it.txn.receivedAt.isAfter(LocalDateTime.now().minusHours(6)) }

@Composable
private fun Shell(tab: Tab, dark: Boolean = true, content: @Composable () -> Unit) {
    SpendTheme(dark = dark) {
        SpendShell(tab = tab, onTab = {}, showNav = true, snackbar = null, fab = if (tab == Tab.DAYS) ({ CashFab {} }) else null, content = content)
    }
}

@Preview(name = "Days", showSystemUi = true, device = Devices.PIXEL_7, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun DaysPreview() = Shell(Tab.DAYS) { DaysScreen(demo, demoIsNew, {}) }

@Preview(name = "Days · light", showSystemUi = true, device = Devices.PIXEL_7)
@Composable
fun DaysLightPreview() = Shell(Tab.DAYS, dark = false) { DaysScreen(demo, demoIsNew, {}) }

@Preview(name = "Month", showSystemUi = true, device = Devices.PIXEL_7, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun MonthPreview() = Shell(Tab.MONTH) { MonthScreen(demo, demoIsNew) {} }

@Preview(name = "Cards", showSystemUi = true, device = Devices.PIXEL_7)
@Composable
fun CardsPreview() = Shell(Tab.CARDS) { CardsScreen(demo, demoIsNew) {} }

@Preview(name = "People", showSystemUi = true, device = Devices.PIXEL_7)
@Composable
fun PeoplePreview() = Shell(Tab.PEOPLE) { PeopleScreen(demo) {} }

@Preview(name = "More", showSystemUi = true, device = Devices.PIXEL_7)
@Composable
fun MorePreview() = Shell(Tab.MORE) {
    val none = {}
    MoreScreen(
        demo, Setup(sms = true, notifications = true, batteryUnrestricted = false), demoIsNew, {},
        MoreActions(none, none, none, none, none, none, none, none, none, none, {}, {}, none, none, none),
        MoreSettings("dark", {}, false, {}, false, {}, com.girish.expensetracker.data.AutoBackup.State(false, 0L, null)),
    )
}

@Preview(name = "Setup", showSystemUi = true, device = Devices.PIXEL_7)
@Composable
fun SetupPreview() = SpendTheme {
    SpendShell(tab = Tab.DAYS, onTab = {}, showNav = false, snackbar = null, fab = null) {
        SetupScreen(Setup(sms = true, notifications = false, batteryUnrestricted = false), {}, {}, {})
    }
}
