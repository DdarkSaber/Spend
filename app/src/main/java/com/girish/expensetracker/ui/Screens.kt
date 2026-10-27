@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.girish.expensetracker.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.BuildConfig
import com.girish.expensetracker.SpendApp
import com.girish.expensetracker.UserConfig
import com.girish.expensetracker.core.Direction
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.PayeeType
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType
import com.girish.expensetracker.core.Template
import com.girish.expensetracker.core.normalizeName
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.notify.Notifier
import com.girish.expensetracker.sms.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

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

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val screenPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)

/** Rows that arrived after the user last opened the app are tagged NEW until the next visit. */
private object Seen {
    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
    fun get(c: Context): Long = prefs(c).getLong("lastSeen", Long.MAX_VALUE)
    fun markNow(c: Context) = prefs(c).edit().putLong("lastSeen", System.currentTimeMillis()).apply()
}

// ---------------------------------------------------------------- root & shell

@Composable
fun AppRoot(
    tick: Int,
    setup: Setup,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    theme: String = "system",
    onTheme: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = (context.applicationContext as SpendApp).repo
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var snap by remember { mutableStateOf<Snapshot?>(null) }
    var tab by remember { mutableStateOf(Tab.DAYS) }
    var editing by remember { mutableStateOf<Row?>(null) }
    var addingCash by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var seenBefore by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var openPerson by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf<Row?>(null) }
    var pickedContact by remember { mutableStateOf<String?>(null) }
    var cardSettings by remember { mutableStateOf<com.girish.expensetracker.core.CardHealth?>(null) }
    var editingBudgets by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf<String?>(null) } // "save" or "restore": asking for the password
    var password by remember { mutableStateOf<CharArray?>(null) }
    var csv by remember { mutableStateOf<com.girish.expensetracker.core.StatementCsv.Result?>(null) }

    fun reload() { reloadKey++ }
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    // System pickers: no storage or contacts permission is needed for a file or contact the owner picks.
    val pickContact = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) scope.launch {
            pickedContact = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
            }
        }
    }
    val saveBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = password
        password = null
        if (uri != null && pw != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { com.girish.expensetracker.data.Backup.write(repo.store, pw, it) }; "Backup saved. Keep the password safe." }
                    .getOrElse { "Couldn't save the backup: ${it.message}" }
            }
            pw.fill(' ')
            say(msg)
        }
    }
    val openBackup = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val pw = password
        password = null
        if (uri != null && pw != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { com.girish.expensetracker.data.Backup.read(repo.store, pw, it) }; "Backup restored" }
                    .getOrElse { if (it is com.girish.expensetracker.data.Backup.WrongPassword) "Wrong password, or not a Spend backup" else "Couldn't restore: ${it.message}" }
            }
            pw.fill(' ')
            say(msg)
            reload()
        }
    }
    var csvRows by remember { mutableStateOf<List<com.girish.expensetracker.core.CsvRow>>(emptyList()) }
    val openCsv = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { com.girish.expensetracker.core.StatementCsv.parse(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) }.getOrNull()
            }
            when {
                r == null -> say("Couldn't read that file")
                r.problem != null -> say(r.problem)
                else -> { csvRows = r.rows; csv = r }
            }
        }
    }

    // First run: once SMS permission is granted and nothing is stored yet, import recent history automatically.
    LaunchedEffect(setup.sms) {
        if (setup.sms && withContext(Dispatchers.IO) { repo.needsImport() }) {
            val r = withContext(Dispatchers.IO) { Importer.importInbox(context, UserConfig.IMPORT_DAYS) }
            if (r.stored > 0) say("Imported ${r.stored} bank SMS from the last ${UserConfig.IMPORT_DAYS} days")
            reload()
        }
    }
    LaunchedEffect(tick) {
        seenBefore = Seen.get(context)
        Seen.markNow(context)
    }
    LaunchedEffect(tick, reloadKey) {
        snap = withContext(Dispatchers.IO) { repo.snapshot() }
        if (setup.notifications) withContext(Dispatchers.IO) { Notifier.refresh(context) }
    }

    val isNew: (Row) -> Boolean = { row ->
        row.smsId != null && seenBefore != Long.MAX_VALUE &&
            row.txn.receivedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() > seenBefore
    }

    androidx.activity.compose.BackHandler(enabled = searching) { searching = false }
    androidx.activity.compose.BackHandler(enabled = !searching && openPerson != null) { openPerson = null }
    androidx.activity.compose.BackHandler(enabled = !searching && openPerson == null && tab != Tab.DAYS) { tab = Tab.DAYS }
    SpendShell(
        tab = tab, onTab = { tab = it; searching = false; openPerson = null }, showNav = setup.ready, snackbar = snackbar,
        fab = null,
    ) {
        val s = snap
        when {
            !setup.ready -> SetupScreen(setup, onRequestPermissions, onOpenAppSettings, onOpenBatterySettings)
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Reading your bank SMS…", color = LocalTones.current.ink2)
            }
            searching -> SearchScreen(s, isNew, onBack = { searching = false }) { editing = it }
            openPerson != null && people(s).any { it.key == openPerson } -> PersonScreen(
                snap = s, person = people(s).first { it.key == openPerson }, onBack = { openPerson = null }, onRow = { editing = it },
                onMarkShop = { p ->
                    scope.launch {
                        withContext(Dispatchers.IO) { p.labels.forEach { repo.retag(it, PayeeType.MERCHANT, "Uncategorised") } }
                        openPerson = null
                        say("${p.name} is now a shop. Set its category from Needs a category.")
                        reload()
                    }
                },
            )
            else -> AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith fadeOut(tween(120))
                },
                label = "tab",
            ) { current ->
                when (current) {
                    Tab.DAYS -> DaysScreen(
                        s, isNew, onRow = { editing = it },
                        onCash = { addingCash = true }, onMore = { tab = Tab.MORE }, onReview = { reviewing = true }, onSearch = { searching = true },
                        onLoadDemo = if (BuildConfig.DEBUG) ({
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { repo.loadDemo() }
                                say("Loaded $n demo bank SMS (debug build only)")
                                reload()
                            }
                        }) else null,
                    )
                    Tab.MONTH -> MonthScreen(
                        s, isNew, onBudgets = { editingBudgets = true },
                        onRent = { c, yes ->
                            scope.launch {
                                withContext(Dispatchers.IO) { if (yes) repo.markRent(c) else repo.dismissRent(c) }
                                say(if (yes) "Payments to ${c.name} of about ${rupees(c.typicalPaise)} are now Rent" else "OK, not rent")
                                reload()
                            }
                        },
                    ) { editing = it }
                    Tab.CARDS -> CardsScreen(s, isNew, onCardSettings = { cardSettings = it }) { editing = it }
                    Tab.PEOPLE -> PeopleScreen(
                        s,
                        onSettle = { d ->
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.settleDebt(d, s.rows) }
                                say("${d.name} is settled")
                                reload()
                            }
                        },
                    ) { openPerson = it.key }
                    Tab.MORE -> MoreScreen(
                        snap = s, setup = setup, isNew = isNew, onRow = { editing = it },
                        onImport = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Importer.importInbox(context, UserConfig.IMPORT_DAYS) }
                                say("Checked ${r.scanned} SMS · ${r.stored} new bank messages added")
                                reload()
                            }
                        },
                        onAddCash = { addingCash = true },
                        onExport = {
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    runCatching { "Saved to " + CsvExport.export(context, s) }
                                        .getOrElse { "Couldn't save the CSV: ${it.message}. Check free storage and try again." }
                                }
                                say(msg)
                            }
                        },
                        onTrack = { src ->
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.store.track(src) }
                                say("Now tracking ${sourceLabel(src)}")
                                reload()
                            }
                        },
                        onOpenBatterySettings = onOpenBatterySettings,
                        onReviewSetup = { reviewing = true },
                        onBudgets = { editingBudgets = true },
                        onBackup = { backupMode = "save" },
                        onRestore = { backupMode = "restore" },
                        onImportStatement = { openCsv.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) },
                        theme = theme, onTheme = onTheme,
                    )
                }
            }
        }
    }

    editing?.let { row ->
        RowSheet(
            row = row,
            onDismiss = { editing = null },
            onRetag = { type, category, onlyThis ->
                val payee = row.txn.parsed.counterparty.orEmpty()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (onlyThis) repo.overrideCategory(row.smsId!!, category, type) else repo.retag(payee, type, category)
                    }
                    editing = null
                    say(if (onlyThis) "This payment is now $category" else "Every payment to “$payee” is now $category")
                    reload()
                }
            },
            onDeleteCash = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.store.deleteManual(id) }
                    editing = null
                    say("Cash entry deleted")
                    reload()
                }
            },
            onSplit = { editing = null; splitting = row },
        )
    }
    splitting?.let { row ->
        SplitSheet(
            row = row,
            people = snap?.let { people(it) }.orEmpty(),
            pickedContact = pickedContact,
            onPickContact = { pickContact.launch(null) },
            onContactUsed = { pickedContact = null },
            onDismiss = { splitting = null },
            onSave = { shares ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.setSplit(row, shares) }
                    splitting = null
                    say(if (shares.isEmpty()) "Split removed" else "Split with ${shares.joinToString { it.name }} · your share ${rupees(row.txn.amountPaise - shares.sumOf { it.sharePaise })}")
                    reload()
                }
            },
        )
    }
    cardSettings?.let { h ->
        CardSettingsSheet(h, onDismiss = { cardSettings = null }) { limit, day ->
            scope.launch {
                withContext(Dispatchers.IO) { repo.setCardLimit(h.card, limit); repo.setStatementDay(h.card, day) }
                cardSettings = null
                say("Saved ${sourceLabel(h.card)}")
                reload()
            }
        }
    }
    if (editingBudgets) snap?.let { s ->
        BudgetSheet(s.plan, used = Ledger.byCategory(s.txns, s.today).filterValues { it > 0 }.keys.sorted(), onDismiss = { editingBudgets = false }) { total, perCat ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    repo.setBudget(null, total)
                    perCat.forEach { (c, v) -> repo.setBudget(c, v) }
                }
                editingBudgets = false
                say("Budgets saved")
                reload()
            }
        }
    }
    backupMode?.let { mode ->
        PasswordSheet(restoring = mode == "restore", onDismiss = { backupMode = null }) { pw ->
            password = pw
            backupMode = null
            if (mode == "save") saveBackup.launch("spend-backup-${LocalDate.now()}.spendbackup") else openBackup.launch(arrayOf("*/*"))
        }
    }
    csv?.let { r ->
        val sources = snap?.owner?.tracked?.filter { it.last4 != "*" }?.sortedBy { sourceLabel(it) }.orEmpty()
        ImportSheet(r.rows.size, r.skipped, sources, onDismiss = { csv = null }) { src ->
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    repo.store.addStatementRows(src, csvRows.map { com.girish.expensetracker.data.StatementRow(it.day, it.amountPaise, it.debit, it.description) })
                }
                csv = null
                say("Imported ${count(added, "new row")} into ${sourceLabel(src)}")
                reload()
            }
        }
    }
    val owner = snap?.owner
    if (reviewing && owner != null) {
        OwnerSheet(
            owner = owner,
            loadSources = { repo.detectedSources() },
            onDismiss = { reviewing = false },
            onSave = { keep, offered, name, day ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.saveOwner(keep, offered, name, day) }
                    reviewing = false
                    say("Saved. Totals now use ${keep.size} accounts and cards.")
                    reload()
                }
            },
        )
    }
    if (addingCash) {
        AddCashSheet(
            onDismiss = { addingCash = false },
            onSave = { day, paise, note, category ->
                scope.launch {
                    withContext(Dispatchers.IO) { repo.store.addManual(day, paise, note, category) }
                    addingCash = false
                    say("Added ${rupees(paise)} cash · $category")
                    reload()
                }
            },
        )
    }
}

@Composable
internal fun SpendShell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    showNav: Boolean,
    snackbar: SnackbarHostState?,
    fab: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val t = LocalTones.current
    Scaffold(
        containerColor = t.ground,
        snackbarHost = { snackbar?.let { SnackbarHost(it, Modifier.padding(bottom = 84.dp)) } },
        floatingActionButton = { fab?.invoke() },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            content()
            if (showNav) FloatingNav(tab, onTab, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Firnhold's floating pill tab bar: glassy dark capsule, the active tab sits on a raised chip. */
@Composable
private fun FloatingNav(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp)
            .clip(CircleShape).background(t.panel).border(1.dp, t.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.filter { it.inNav }.forEach { item ->
            val selected = tab == item
            val bg by animateColorAsState(if (selected) t.raised else Color.Transparent, tween(240, easing = Ease), label = "nav")
            Column(
                Modifier.clip(CircleShape).background(bg).clickable { onTab(item) }.padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = null, tint = if (selected) t.accent else t.ink3, modifier = Modifier.size(22.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = if (selected) t.ink else t.ink3)
            }
        }
    }
}

@Composable
internal fun CashFab(onClick: () -> Unit) {
    val t = LocalTones.current
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text("Cash", style = MaterialTheme.typography.labelLarge) },
        containerColor = t.ink, contentColor = t.ground, shape = RoundedCornerShape(12.dp),
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    )
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = LocalTones.current
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = t.ink, contentColor = t.ground, disabledContainerColor = t.raised, disabledContentColor = t.ink3),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    OutlinedButton(
        onClick = onClick, shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, t.edge),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.ink),
        modifier = modifier.heightIn(min = 48.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

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

// ---------------------------------------------------------------- days

@Composable
internal fun DaysScreen(
    snap: Snapshot,
    isNew: (Row) -> Boolean,
    onRow: (Row) -> Unit,
    onLoadDemo: (() -> Unit)? = null,
    onCash: () -> Unit = {},
    onMore: () -> Unit = {},
    onReview: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val t = LocalTones.current
    var range by remember { mutableStateOf(0) } // 0 = 7 days, 1 = salary month, 2 = 4 months
    var month by remember { mutableStateOf(YearMonth.from(snap.today)) }
    var selected by remember { mutableStateOf(snap.today) }
    val earliest = YearMonth.from(snap.today.minusDays(140))
    val perDay = remember(snap) { snap.rows.groupBy { it.txn.day } }
    val dayTotals = remember(snap) { perDay.mapValues { (_, rows) -> rows.sumOf { Ledger.todayAmount(it.txn) } } }
    val rows = perDay[selected].orEmpty().sortedByDescending { it.txn.receivedAt }
    val hero = remember(snap, range) { heroSeries(snap, range) }
    val p = remember(snap) { pace(snap) }
    val week = remember(snap) { dailySeries(snap, salaryMonth = false) }
    val monthSeries = remember(snap) { dailySeries(snap, salaryMonth = true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ScreenTitle("Spending") {
                Row(
                    Modifier.clip(CircleShape).border(1.dp, t.accent.copy(alpha = 0.35f), CircleShape).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RoundIcon(Icons.Outlined.Search, "Search payments", onSearch, size = 38.dp)
                    RoundIcon(Icons.Filled.Add, "Add cash spend", onCash, size = 38.dp)
                    RoundIcon(Icons.Filled.MoreHoriz, "More", onMore, size = 38.dp)
                }
                Box(Modifier.clip(CircleShape).clickable(onClick = onMore)) { Avatar(snap.owner?.displayName?.ifBlank { null } ?: "You", 44.dp) }
            }
        }
        snap.owner?.takeIf { it.state == "detected" && snap.rows.isNotEmpty() }?.let { o -> item { ReviewSetupCard(o, onReview) } }
        item { Segmented(listOf("7 Days", "Month", "4 Months"), range, { range = it }) }
        if (snap.rows.isEmpty()) {
            item { EmptyLedger(onLoadDemo) }
        } else {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingAmount(hero.now.sum(), MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), t.ink)
                    val prior = hero.prior?.sum()
                    Text(
                        if (prior != null && prior > 0) "vs ${rupees(prior)} ${if (range == 0) "the week before" else "last month"}" else hero.rangeLabel.lowercase(),
                        style = MaterialTheme.typography.bodySmall, color = t.ink2,
                    )
                }
            }
            item { HeroChart(hero) }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("Overview", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700)), color = t.ink)
                Text("${snap.cycleStart.format(dayMonth)} – ${snap.today.format(dayMonth)}".uppercase(), style = DataLabel, color = t.ink3)
            }
        }
        item {
            val avg = monthSeries.total / monthSeries.days.size.coerceAtLeast(1)
            val priorAvg = monthSeries.priorTotal / monthSeries.days.size.coerceAtLeast(1)
            val best = monthSeries.now.indices.maxByOrNull { monthSeries.now[it] }
            val paidBack = snap.rows.filter { it.txn.day >= snap.cycleStart && Ledger.monthAmount(it.txn) < 0 }.sumOf { -Ledger.monthAmount(it.txn) }
            val payments = snap.rows.count { it.txn.day >= snap.cycleStart && Ledger.monthAmount(it.txn) > 0 }
            val diff = p.nowToday - p.lastSameDay
            StatGrid(
                listOf(
                    Stat("Today", rupees(snap.todayTotal), sub = count(rowsToday(snap), "payment"), highlight = true),
                    Stat("Salary month", rupees(snap.monthTotal), sub = "since ${snap.cycleStart.format(dayMonth)}", up = if (p.hasLast) diff > 0 else null),
                    Stat("Avg per day", rupees(avg), sub = if (priorAvg > 0) "${rupees(priorAvg)} last month" else null, up = if (priorAvg > 0) avg > priorAvg else null),
                    Stat("This week", rupees(week.total), sub = if (week.priorTotal > 0) "${rupees(week.priorTotal)} week before" else null, up = if (week.priorTotal > 0) week.total > week.priorTotal else null),
                    Stat("Biggest day", best?.let { rupees(monthSeries.now[it]) } ?: "–", sub = best?.let { monthSeries.days[it].format(dayMonth) }),
                    lastSalary(snap)?.let { s -> Stat("Salary", rupees(s.txn.amountPaise), sub = "credited ${s.txn.day.format(dayMonth)}") }
                        ?: Stat("Paid back", rupees(paidBack), sub = count(payments, "payment") + " out"),
                ),
            )
        }
        item {
            Panel {
                PanelHeader("Calendar", chevron = true) {
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", { if (month > earliest) month = month.minusMonths(1) }, size = 34.dp)
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", { if (month < YearMonth.from(snap.today)) month = month.plusMonths(1) }, size = 34.dp)
                }
                Text(month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.year, style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(bottom = 10.dp))
                Calendar(month, dayTotals, selected, snap.today) { selected = it }
            }
        }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp)) {
                PanelHeader(
                    if (selected == snap.today) "Today" else selected.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)),
                    rupees(dayTotals[selected] ?: 0L),
                )
                if (rows.isEmpty()) {
                    Text(
                        if (selected == snap.today) "Nothing spent yet today." else "No transactions on this day.",
                        style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                rows.forEachIndexed { k, r ->
                    if (k > 0) Hairline()
                    TxnRow(r, isNew(r), onRow)
                }
            }
        }
    }
}

private fun rowsToday(snap: Snapshot) = snap.rows.count { it.txn.day == snap.today && Ledger.todayAmount(it.txn) > 0 }

@Composable
private fun Calendar(month: YearMonth, totals: Map<LocalDate, Long>, selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val t = LocalTones.current
    val lead = month.atDay(1).dayOfWeek.value - 1 // Monday first
    val cells = lead + month.lengthOfMonth()
    val max = (1..month.lengthOfMonth()).maxOfOrNull { totals[month.atDay(it)] ?: 0L }?.coerceAtLeast(1L) ?: 1L

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = DataLabel, color = t.ink3)
            }
        }
        for (week in 0 until (cells + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (dow in 0 until 7) {
                    val dayNum = week * 7 + dow - lead + 1
                    if (dayNum < 1 || dayNum > month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).aspectRatio(0.9f))
                        continue
                    }
                    val date = month.atDay(dayNum)
                    val amount = totals[date] ?: 0L
                    val shade = (amount.coerceAtLeast(0).toFloat() / max).coerceIn(0f, 1f)
                    val isSel = date == selected
                    val future = date.isAfter(today)
                    val fill by animateColorAsState(
                        when {
                            isSel -> t.ink
                            amount > 0 -> t.accent.copy(alpha = 0.10f + 0.55f * shade)
                            else -> t.section
                        }, tween(220), label = "day",
                    )
                    val ink = when {
                        isSel -> t.ground
                        future -> t.ink3.copy(alpha = 0.5f)
                        else -> t.ink
                    }
                    Column(
                        Modifier.weight(1f).aspectRatio(0.9f).clip(TileShape).background(fill)
                            .then(if (date == today && !isSel) Modifier.border(1.5.dp, t.accent, TileShape) else Modifier)
                            .clickable(enabled = !future) { onPick(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$dayNum", style = MaterialTheme.typography.labelLarge, color = ink)
                        if (amount != 0L) Text(compact(amount), style = DataLabel.copy(letterSpacing = 0.sp()), color = ink.copy(alpha = 0.75f), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Firnhold-style range card: pill switch, period total vs last, daily bars with last period faded, comparison rows. */
@Composable
private fun RangePanel(snap: Snapshot) {
    val t = LocalTones.current
    var range by remember { mutableStateOf(0) } // 0 = 7 days, 1 = salary month
    var picked by remember(range) { mutableStateOf<Int?>(null) }
    val series = remember(snap, range) { dailySeries(snap, range == 1) }
    val days = series.days
    val countIn = { from: LocalDate, to: LocalDate ->
        snap.rows.count { it.txn.day in from..to && Ledger.todayAmount(it.txn) > 0 }
    }
    val payments = countIn(days.first(), days.last())
    val priorPayments = if (range == 1) {
        val prev = Ledger.previousCycleStart(snap.cycleStart, snap.txns)
        countIn(prev, prev.plusDays(days.size - 1L))
    } else countIn(days.first().minusDays(7), days.last().minusDays(7))

    Panel {
        Segmented(listOf("7 days", "Salary month"), range, { range = it })
        Gap(18.dp)
        val shownValue = picked?.let { series.now[it] } ?: series.total
        val shownLabel = picked?.let { days[it].format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)) }
            ?: if (range == 0) "last 7 days" else "since ${snap.cycleStart.format(dayMonth)}"
        RollingAmount(shownValue, MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), t.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(shownLabel, style = MaterialTheme.typography.bodySmall, color = t.ink2)
            if (picked == null && series.priorTotal > 0) {
                val diff = series.total - series.priorTotal
                Spacer(Modifier.width(8.dp))
                Text(
                    "${if (diff > 0) "▲" else "▼"} ${rupees(abs(diff))} vs ${if (range == 0) "previous 7 days" else "last month"}",
                    style = MaterialTheme.typography.bodySmall, color = if (diff > 0) t.warn else t.good,
                )
            }
        }
        Gap(18.dp)
        DailyBars(series, { picked = it }, picked)
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { Swatch(t.accent); Spacer(Modifier.width(6.dp)); Text(if (range == 0) "This week" else "This month", style = MaterialTheme.typography.labelMedium, color = t.ink2) }
            Row(verticalAlignment = Alignment.CenterVertically) { Swatch(t.ink3.copy(alpha = 0.4f)); Spacer(Modifier.width(6.dp)); Text(if (range == 0) "Week before" else "Last month", style = MaterialTheme.typography.labelMedium, color = t.ink2) }
        }
        Gap(8.dp)
        Hairline()
        RangeStats(series, payments, priorPayments)
    }
}

/** First run or an empty emulator: say what will happen instead of zeros and a blank grid. */
@Composable
private fun EmptyLedger(onLoadDemo: (() -> Unit)?) {
    val t = LocalTones.current
    Panel {
        Text("No bank SMS yet", style = MaterialTheme.typography.titleLarge, color = t.ink)
        Text(
            "Spends appear here the moment your bank sends a transaction SMS. Older messages are imported once from the last ${UserConfig.IMPORT_DAYS} days.",
            style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(top = 6.dp),
        )
        if (onLoadDemo != null) {
            Gap(14.dp)
            SecondaryButton("Load demo data (debug build)", onLoadDemo)
        }
    }
}

// ---------------------------------------------------------------- month

@Composable
internal fun MonthScreen(
    snap: Snapshot,
    isNew: (Row) -> Boolean,
    onBudgets: () -> Unit = {},
    onRent: (com.girish.expensetracker.core.RentCandidate, Boolean) -> Unit = { _, _ -> },
    onRow: (Row) -> Unit,
) {
    val t = LocalTones.current
    var withInvestments by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }
    var openCat by remember { mutableStateOf<String?>(null) }
    val byCat = remember(snap) { Ledger.byCategory(snap.txns, snap.today) }
    val lastSoFar = remember(snap) { lastMonthSoFar(snap) }
    val shown = byCat.filterKeys { withInvestments || it != "Investment" }
    val slices = shown.filterValues { it > 0 }.toList().sortedByDescending { it.second }
    val gross = slices.sumOf { it.second }
    val negatives = shown.filterValues { it < 0 }
    val total = shown.values.sum()
    val ring = topWithOther(slices)
    val inOther = slices.map { it.first }.toSet() - ring.map { it.first }.toSet()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Month", "Salary month since ${snap.cycleStart.format(dayMonth)}") }
        item { SalaryCard(snap) }
        item { BudgetPanel(snap.budgets, onBudgets) }
        snap.rentCandidates.forEach { c -> item { RentCard(c, onMark = { onRent(c, true) }, onDismiss = { onRent(c, false) }) } }
        if (snap.rows.isNotEmpty()) item { PaceChart(snap) }
        item {
            Panel {
                PanelHeader("Categories", chevron = true) {
                    RoundIcon(
                        Icons.Outlined.Payments, if (withInvestments) "Hide investments" else "Show investments",
                        { withInvestments = !withInvestments; selected = null }, active = withInvestments, size = 36.dp,
                    )
                }
                RollingAmount(total, MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700), fontFeatureSettings = "tnum"), t.ink)
                Text(if (withInvestments) "including investments" else "excluding investments", style = MaterialTheme.typography.bodySmall, color = t.ink2)
                DonutChart(ring, total, selected) { selected = it }
                if (slices.isEmpty()) Text("Nothing spent yet this salary month.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
                slices.forEachIndexed { k, (cat, amt) ->
                    if (k > 0) Hairline()
                    val ringKey = if (cat in inOther) "Other" else cat
                    val dim = selected != null && selected != ringKey
                    Column(Modifier.clickable { openCat = if (openCat == cat) null else cat; selected = if (selected == ringKey) null else ringKey }.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Swatch(paintFor(cat).copy(alpha = if (dim) 0.3f else 1f))
                            Spacer(Modifier.width(10.dp))
                            Text(cat, style = MaterialTheme.typography.titleSmall, color = if (dim) t.ink3 else t.ink, modifier = Modifier.weight(1f))
                            Text(rupees(amt), style = AmountStyle, color = if (dim) t.ink3 else t.ink)
                        }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(t.raised)) {
                                Box(
                                    Modifier.fillMaxWidth(amt.toFloat() / gross.coerceAtLeast(1)).fillMaxHeight().clip(CircleShape)
                                        .background(paintFor(cat).copy(alpha = if (dim) 0.3f else 1f)),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            val before = lastSoFar[cat] ?: 0L
                            val diff = amt - before
                            Text(
                                when {
                                    before == 0L -> "new"
                                    diff == 0L -> "same"
                                    diff > 0 -> "▲ ${rupees(diff)}"
                                    else -> "▼ ${rupees(-diff)}"
                                },
                                style = DataLabel,
                                color = when {
                                    before == 0L || diff == 0L -> t.ink3
                                    diff > 0 -> t.warn
                                    else -> t.good
                                },
                            )
                        }
                        AnimatedVisibility(openCat == cat, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(Modifier.padding(top = 6.dp)) {
                                snap.rows.filter { it.txn.category == cat && it.txn.day >= snap.cycleStart && Ledger.monthAmount(it.txn) != 0L }
                                    .sortedByDescending { it.txn.receivedAt }
                                    .forEach { TxnRow(it, isNew(it), onRow) }
                            }
                        }
                    }
                }
            }
        }
        if (negatives.isNotEmpty()) item {
            Panel {
                PanelHeader("Taken off the total")
                negatives.forEach { (cat, amt) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(cat, style = MaterialTheme.typography.bodyMedium, color = t.ink, modifier = Modifier.weight(1f))
                        Text(rupees(amt), style = AmountStyle, color = t.good)
                    }
                }
            }
        }
        item { MonthsBars(snap, withInvestments) }
        if (snap.subscriptions.isNotEmpty()) item { SubscriptionsPanel(snap.subscriptions) }
        item { YearPanel(snap) }
    }
}

/** The latest salary credit (detected from the NEFT narration), if any. */
internal fun lastSalary(snap: Snapshot): Row? = snap.rows.filter { it.txn.isSalary }.maxByOrNull { it.txn.day }

/** Salary credited this salary month, what has gone out, and what is left; earlier salaries below. */
@Composable
private fun SalaryCard(snap: Snapshot) {
    val t = LocalTones.current
    val salaries = snap.rows.filter { it.txn.isSalary }.sortedByDescending { it.txn.day }
    val current = salaries.firstOrNull { it.txn.day >= snap.cycleStart }
    Panel {
        PanelHeader("Salary", chevron = true)
        if (salaries.isEmpty()) {
            Text("No salary credit found yet. It shows up here when your bank's NEFT SMS says SALARY.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
            return@Panel
        }
        val salary = (current ?: salaries.first()).txn
        val spent = snap.monthTotal
        RollingAmount(salary.amountPaise, MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700), fontFeatureSettings = "tnum"), t.ink)
        Text(
            (if (current != null) "credited " else "last credited ") + salary.day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + " · " + sourceLabel(salary.parsed.source),
            style = MaterialTheme.typography.bodySmall, color = t.ink2,
        )
        if (current != null) {
            Gap(16.dp)
            val used = (spent.toFloat() / salary.amountPaise).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(t.raised)) {
                Box(Modifier.fillMaxWidth(used).fillMaxHeight().clip(CircleShape).background(if (spent > salary.amountPaise) t.warn else t.accent))
            }
            Gap(10.dp)
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(rupees(spent), style = AmountStyle, color = t.ink)
                    Text("spent so far (${(used * 100).toInt()}%)", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rupees(salary.amountPaise - spent), style = AmountStyle, color = if (spent > salary.amountPaise) t.warn else t.good)
                    Text("left from salary", style = MaterialTheme.typography.labelMedium, color = t.ink2)
                }
            }
        }
        if (salaries.size > 1) {
            Gap(14.dp)
            Hairline()
            salaries.take(6).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(r.txn.day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)), style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.weight(1f))
                    Text(rupees(r.txn.amountPaise), style = AmountStyle, color = t.ink)
                }
            }
        }
    }
}

/** "Looks like rent" suggestion: confirm once and future payments of that size to that person are filed as Rent. */
@Composable
private fun RentCard(c: com.girish.expensetracker.core.RentCandidate, onMark: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalTones.current
    Panel {
        PanelHeader("Looks like rent", "${c.payments.size} months")
        Text(
            "About ${rupees(c.typicalPaise)} to ${c.name} every month: " + c.payments.joinToString(", ") { it.day.format(dayMonth) } + ".",
            style = MaterialTheme.typography.bodyMedium, color = t.ink2,
        )
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onMark, shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = Color(0xFF04211C)),
            ) { Text("Mark as rent", style = MaterialTheme.typography.labelLarge) }
            SecondaryButton("Not rent", onDismiss)
        }
    }
}

// ---------------------------------------------------------------- cards & accounts

@Composable
internal fun CardsScreen(
    snap: Snapshot,
    isNew: (Row) -> Boolean,
    onCardSettings: (com.girish.expensetracker.core.CardHealth) -> Unit = {},
    onRow: (Row) -> Unit,
) {
    val t = LocalTones.current
    var view by remember { mutableStateOf(0) } // 0 = credit cards, 1 = bank accounts
    val type = if (view == 0) SourceType.CARD else SourceType.ACCOUNT
    val start = snap.cycleStart
    val bySource = remember(snap) { Ledger.bySource(snap.txns, snap.today) }.filterKeys { it.type == type && it.bank != "CASH" }
    var open by remember { mutableStateOf<Source?>(null) }
    val billsPaid = snap.rows.filter { it.txn.kind == Kind.TRANSFER && it.txn.category == "Card bill" && it.txn.day >= start && it.txn.parsed.source.type == SourceType.CARD }
        .sortedByDescending { it.txn.receivedAt }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Cards & accounts", "Spend since ${start.format(dayMonth)}") }
        item { Segmented(listOf("Credit cards", "Bank accounts"), view, { view = it; open = null }) }
        // Utilisation and bills first: the number that moves your credit score.
        if (view == 0) snap.cards.forEach { h -> item { CardHealthPanel(h) { onCardSettings(h) } } }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)) {
                if (bySource.isEmpty()) {
                    Text(
                        if (view == 0) "No card spends this salary month." else "No UPI or debit spends this salary month.",
                        style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                bySource.toList().sortedByDescending { it.second }.forEachIndexed { k, (src, amt) ->
                    if (k > 0) Hairline()
                    val count = snap.rows.count { it.txn.parsed.source == src && it.txn.day >= start && it.txn.parsed.direction == Direction.DEBIT && Ledger.monthAmount(it.txn) > 0 }
                    ListRow(
                        title = sourceLabel(src),
                        subtitle = "${count(count, "payment")} · ${if (src.type == SourceType.CARD) "credit card" else "UPI & debit"}",
                        leading = { BankMark(src) },
                        trailing = { Text(rupees(amt), style = AmountStyle.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)), color = t.ink) },
                        onClick = { open = if (open == src) null else src },
                    )
                    AnimatedVisibility(open == src, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.padding(start = 12.dp)) {
                            snap.rows.filter { it.txn.parsed.source == src && it.txn.day >= start && it.txn.parsed.direction == Direction.DEBIT }
                                .sortedByDescending { it.txn.receivedAt }
                                .forEach { TxnRow(it, isNew(it), onRow) }
                        }
                    }
                }
            }
        }
        if (view == 0 && billsPaid.isNotEmpty()) item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("Bills paid", "not counted")
                billsPaid.forEachIndexed { k, r ->
                    if (k > 0) Hairline()
                    ListRow(
                        title = "${sourceLabel(r.txn.parsed.source)} bill",
                        subtitle = r.txn.day.format(dayMonth),
                        leading = { BankMark(r.txn.parsed.source, 36.dp) },
                        trailing = { Text(rupeesExact(r.txn.amountPaise), style = AmountStyle, color = t.ink2) },
                        onClick = { onRow(r) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- people

// ---------------------------------------------------------------- more

@Composable
internal fun MoreScreen(
    snap: Snapshot,
    setup: Setup,
    isNew: (Row) -> Boolean,
    onRow: (Row) -> Unit,
    onImport: () -> Unit,
    onAddCash: () -> Unit,
    onExport: () -> Unit,
    onTrack: (Source) -> Unit,
    onOpenBatterySettings: () -> Unit,
    onReviewSetup: () -> Unit = {},
    onBudgets: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    onImportStatement: () -> Unit = {},
    theme: String = "system",
    onTheme: (String) -> Unit = {},
) {
    val t = LocalTones.current
    val moneyIn = snap.rows.filter { it.txn.kind == Kind.INCOME }.sortedByDescending { it.txn.receivedAt }
    val unsorted = snap.rows.filter { it.txn.kind == Kind.SPEND && it.txn.category == "Uncategorised" }.sortedByDescending { it.txn.receivedAt }

    @Composable
    fun Action(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
        ListRow(title, sub, leading = {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(t.raised), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(20.dp))
            }
        }, onClick = onClick)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("More") }
        item {
            Panel(padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                Action(Icons.Outlined.CreditCard, "Accounts, name & salary day", "What the app learned from your SMS", onReviewSetup)
                Hairline()
                Action(Icons.Outlined.Payments, "Add a cash spend", "For the rare payment that sends no SMS", onAddCash)
                Hairline()
                Action(Icons.Outlined.Sms, "Import SMS history", "Re-reads the last ${UserConfig.IMPORT_DAYS} days; nothing is counted twice", onImport)
                Hairline()
                Action(Icons.Outlined.Savings, "Budgets", "Monthly and per category, with alerts", onBudgets)
                Hairline()
                Action(Icons.Outlined.UploadFile, "Import bank statement (CSV)", "Adds history from before the app was installed", onImportStatement)
                Hairline()
                Action(Icons.Outlined.FileDownload, "Export CSV", "Saves to Download/Spend", onExport)
                Hairline()
                Action(Icons.Outlined.Lock, "Back up", "Password-encrypted file, saved where you choose", onBackup)
                Hairline()
                Action(Icons.Outlined.Restore, "Restore a backup", "Replaces what's in the app now", onRestore)
                if (!setup.batteryUnrestricted) {
                    Hairline()
                    Action(Icons.Outlined.Bolt, "Allow running in background", "Keeps the notification from being cleared", onOpenBatterySettings)
                }
            }
        }
        item {
            Panel {
                PanelHeader("Appearance")
                ThemePicker(theme, onTheme)
            }
        }
        if (snap.newSources.isNotEmpty()) item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("New cards or accounts", "${snap.newSources.size}")
                snap.newSources.forEachIndexed { k, ns ->
                    if (k > 0) Hairline()
                    ListRow(sourceLabel(ns.source), ns.example, leading = { BankMark(ns.source, 36.dp) },
                        trailing = { TextButton(onClick = { onTrack(ns.source) }) { Text("Track", color = t.accent) } })
                }
            }
        }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("Needs a category", "${unsorted.size}")
                if (unsorted.isEmpty()) Text("Everything has a category.", style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(bottom = 10.dp))
                unsorted.take(40).forEachIndexed { k, r -> if (k > 0) Hairline(); TxnRow(r, isNew(r), onRow) }
            }
        }
        item {
            Panel(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
                PanelHeader("Money in", "not counted")
                if (moneyIn.isEmpty()) Text("No salary or transfers in yet.", style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(bottom = 10.dp))
                moneyIn.take(20).forEachIndexed { k, r -> if (k > 0) Hairline(); TxnRow(r, isNew(r), onRow) }
            }
        }
        item {
            Panel {
                PanelHeader("SMS I couldn't read", "${snap.unparsed.size}")
                if (snap.unparsed.isEmpty()) Text("Every bank SMS was understood.", style = MaterialTheme.typography.bodyMedium, color = t.ink2)
                else Text("Never counted. Send these to me to add the format.", style = MaterialTheme.typography.bodySmall, color = t.ink3)
                snap.unparsed.take(40).forEach { u ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("${u.sender} · ${u.receivedAt.toLocalDate().format(dayMonth)}".uppercase(), style = DataLabel, color = t.ink3)
                        Text(u.body, style = MaterialTheme.typography.bodySmall, color = t.ink2)
                    }
                }
            }
        }
    }
}

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
private fun RowSheet(row: Row, onDismiss: () -> Unit, onRetag: (PayeeType, String, Boolean) -> Unit, onDeleteCash: (Long) -> Unit, onSplit: () -> Unit) {
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
private fun AddCashSheet(onDismiss: () -> Unit, onSave: (LocalDate, Long, String, String) -> Unit) {
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
