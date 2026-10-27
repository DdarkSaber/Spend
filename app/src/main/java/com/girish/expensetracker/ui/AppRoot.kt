package com.girish.expensetracker.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.BuildConfig
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.UserConfig
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.PayeeType
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.notify.Notifier
import com.girish.expensetracker.notify.Digest
import com.girish.expensetracker.data.AutoBackup
import com.girish.expensetracker.sms.Importer
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Short dates used across screens ("3 Oct"). */
internal val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Side gutters, and room at the bottom for the floating tab bar. */
internal val screenPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)

val CATEGORIES = listOf(
    "Food", "Groceries", "Fuel", "Transport", "Travel", "Medical", "Personal care", "Entertainment", "Shopping",
    "Bills", "Bills & EMI", "Rent", "Household", "Subscriptions", "Vehicle", "Education", "Gifts", "Cash", "People", "Uncategorised",
)

internal enum class Tab(val label: String, val icon: ImageVector, val inNav: Boolean = true) {
    DAYS("Home", Icons.Outlined.CalendarMonth),
    MONTH("Month", Icons.Outlined.PieChart),
    CARDS("Cards", Icons.Outlined.CreditCard),
    PEOPLE("People", Icons.Outlined.Group),
    MORE("More", Icons.Filled.MoreHoriz, inNav = false),
}


/** Rows that arrived after the user last opened the app are tagged NEW until the next visit. */
private object Seen {
    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun get(c: Context): Long = prefs(c).getLong("lastSeen", Long.MAX_VALUE)
    fun markNow(c: Context) = prefs(c).edit().putLong("lastSeen", System.currentTimeMillis()).apply()
}

// ---------------------------------------------------------------- root

@Composable
fun AppRoot(
    tick: Int,
    setup: Setup,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    theme: String = "system",
    onTheme: (String) -> Unit = {},
    lockOn: Boolean = false,
    onLock: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = (context.applicationContext as SpendApp).repo
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ctl = remember { AppController(context, repo, scope, snackbar) }
    val pickers = rememberPickers(ctl)

    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var tab by remember { mutableStateOf(Tab.DAYS) }
    var seenBefore by remember { mutableLongStateOf(Long.MAX_VALUE) }

    // First run: once SMS permission is granted and nothing is stored yet, import recent history automatically.
    LaunchedEffect(setup.sms) {
        if (setup.sms && withContext(Dispatchers.IO) { repo.needsImport() }) {
            val r = withContext(Dispatchers.IO) { Importer.importInbox(context, UserConfig.IMPORT_DAYS) }
            if (r.stored > 0) ctl.say("Imported ${r.stored} bank SMS from the last ${UserConfig.IMPORT_DAYS} days")
            ctl.reload()
        }
    }
    LaunchedEffect(tick) {
        seenBefore = Seen.get(context)
        Seen.markNow(context)
    }
    LaunchedEffect(tick, ctl.reloads) {
        snap = withContext(Dispatchers.IO) { repo.snapshot() }
        if (setup.notifications) withContext(Dispatchers.IO) { Notifier.refresh(context) }
    }

    val isNew: (Row) -> Boolean = { row ->
        row.smsId != null && seenBefore != Long.MAX_VALUE &&
            row.txn.receivedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() > seenBefore
    }
    val openRow: (Row) -> Unit = { ctl.sheet = Sheet.Payment(it) }

    androidx.activity.compose.BackHandler(enabled = ctl.page != null) { ctl.page = null }
    androidx.activity.compose.BackHandler(enabled = ctl.page == null && tab != Tab.DAYS) { tab = Tab.DAYS }
    SpendShell(tab = tab, onTab = { tab = it; ctl.page = null }, showNav = setup.ready, snackbar = snackbar, fab = null) {
        val s = snap
        when {
            !setup.ready -> SetupScreen(setup, onRequestPermissions, onOpenAppSettings, onOpenBatterySettings)
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Reading your bank SMS…", color = LocalTones.current.ink2)
            }
            ctl.page != null -> PageHost(ctl, s, isNew, openRow)
            else -> AnimatedContent(
                targetState = tab,
                transitionSpec = { (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith fadeOut(tween(120)) },
                label = "tab",
            ) { current ->
                when (current) {
                    Tab.DAYS -> DaysScreen(
                        s, isNew, onRow = openRow,
                        onCash = { ctl.sheet = Sheet.Cash }, onMore = { tab = Tab.MORE }, onReview = { ctl.sheet = Sheet.Owner },
                        onSearch = { ctl.page = Page.Search },
                        onLoadDemo = if (BuildConfig.DEBUG) ({ ctl.act { "Loaded ${loadDemo()} demo bank SMS (debug build only)" } }) else null,
                    )
                    Tab.MONTH -> MonthScreen(
                        s, isNew, onBudgets = { ctl.sheet = Sheet.Budgets },
                        onRent = { c, yes ->
                            ctl.act {
                                if (yes) markRent(c) else dismissRent(c)
                                if (yes) "Payments to ${c.name} of about ${rupees(c.typicalPaise)} are now Rent" else "OK, not rent"
                            }
                        },
                        onRow = openRow,
                    )
                    Tab.CARDS -> CardsScreen(s, isNew, onCardSettings = { ctl.sheet = Sheet.CardSettings(it) }, onRow = openRow)
                    Tab.PEOPLE -> PeopleScreen(s, onSettle = { d -> ctl.act { settleDebt(d, s.rows); "${d.name} is settled" } }) { ctl.page = Page.Person(it.key) }
                    Tab.MORE -> MoreScreen(
                        snap = s, setup = setup, isNew = isNew, onRow = openRow,
                        actions = moreActions(ctl, s, pickers, onOpenBatterySettings),
                        settings = MoreSettings(
                            theme = theme, onTheme = onTheme, lockOn = lockOn, onLock = onLock,
                            digestOn = Digest.enabled(context),
                            onDigest = { on ->
                                Digest.setEnabled(context, on)
                                ctl.say(if (on) "Evening summary on: a quiet notification at 9 pm" else "Evening summary off")
                                ctl.reload()
                            },
                            autoBackup = AutoBackup.state(context),
                        ),
                    )
                }
            }
        }
    }
    snap?.let { SheetHost(ctl, it, pickers) }
}

/** Search, trips and a person's page: full screens over the tabs. */
@Composable
private fun PageHost(ctl: AppController, s: Snapshot, isNew: (Row) -> Boolean, openRow: (Row) -> Unit) {
    when (val p = ctl.page) {
        Page.Search -> SearchScreen(s, isNew, onBack = { ctl.page = null }, onRow = openRow)
        Page.Trips -> TripsScreen(
            s, isNew, onBack = { ctl.page = null }, onNew = { ctl.sheet = Sheet.NewTrip },
            onDelete = { trip -> ctl.act { store.deleteTrip(trip.id); "Deleted ${trip.name}" } }, onRow = openRow,
        )
        is Page.Person -> {
            val person = people(s).firstOrNull { it.key == p.key }
            if (person == null) ctl.page = null
            else PersonScreen(
                snap = s, person = person, onBack = { ctl.page = null }, onRow = openRow,
                onMarkShop = { who ->
                    ctl.page = null
                    ctl.act {
                        who.labels.forEach { retag(it, PayeeType.MERCHANT, "Uncategorised") }
                        "${who.name} is now a shop. Set its category from Needs a category."
                    }
                },
            )
        }
        null -> Unit
    }
}

/** What the More screen can do: each a one-line request to the controller or a system picker. */
private fun moreActions(ctl: AppController, s: Snapshot, pickers: Pickers, onBattery: () -> Unit) = MoreActions(
    reviewSetup = { ctl.sheet = Sheet.Owner },
    addCash = { ctl.sheet = Sheet.Cash },
    importSms = {
        ctl.act {
            val r = Importer.importInbox(ctl.context, UserConfig.IMPORT_DAYS)
            "Checked ${r.scanned} SMS · ${r.stored} new bank messages added"
        }
    },
    importStatement = pickers.openCsv,
    exportCsv = { ctl.act(closeSheet = false) { runCatching { "Saved to " + CsvExport.export(ctl.context, s) }.getOrElse { "Couldn't save the CSV: ${it.message}" } } },
    budgets = { ctl.sheet = Sheet.Budgets },
    trips = { ctl.page = Page.Trips },
    backup = { ctl.sheet = Sheet.Password(Sheet.Password.Purpose.SAVE) },
    restore = { ctl.sheet = Sheet.Password(Sheet.Password.Purpose.RESTORE) },
    autoBackup = { ctl.sheet = if (AutoBackup.state(ctl.context).on) Sheet.AutoBackup else Sheet.Password(Sheet.Password.Purpose.AUTO) },
    track = { src -> ctl.act { store.track(src); "Now tracking ${sourceLabel(src)}" } },
    teach = { sms -> ctl.sheet = Sheet.Teach(sms) },
    battery = onBattery,
)
