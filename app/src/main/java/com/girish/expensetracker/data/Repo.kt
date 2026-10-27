package com.girish.expensetracker.data

import com.girish.expensetracker.core.BudgetLine
import com.girish.expensetracker.core.Budgets
import com.girish.expensetracker.core.CardFacts
import com.girish.expensetracker.core.CardHealth
import com.girish.expensetracker.core.CardStatement
import com.girish.expensetracker.core.Classifier
import com.girish.expensetracker.core.Credit
import com.girish.expensetracker.core.Emi
import com.girish.expensetracker.core.Emis
import com.girish.expensetracker.core.Holding
import com.girish.expensetracker.core.Investments
import com.girish.expensetracker.core.LearnedLayout
import com.girish.expensetracker.core.Savings
import com.girish.expensetracker.core.SavingsMonth
import com.girish.expensetracker.core.Trip
import com.girish.expensetracker.core.Unusual
import com.girish.expensetracker.core.UnusualSpend
import com.girish.expensetracker.core.Debt
import com.girish.expensetracker.core.Owe
import com.girish.expensetracker.core.SplitShare
import com.girish.expensetracker.core.Generic
import com.girish.expensetracker.core.Kind
import com.girish.expensetracker.core.LimitSeen
import com.girish.expensetracker.core.People
import com.girish.expensetracker.core.Subscription
import com.girish.expensetracker.core.Subscriptions
import com.girish.expensetracker.core.Dedupe
import com.girish.expensetracker.core.DetectedProfile
import com.girish.expensetracker.core.DetectedSource
import com.girish.expensetracker.core.InboxSms
import com.girish.expensetracker.core.Profile
import com.girish.expensetracker.core.ProfileDetector
import com.girish.expensetracker.core.Direction
import com.girish.expensetracker.core.Ledger
import com.girish.expensetracker.core.ParsedSms
import com.girish.expensetracker.core.PayeeType
import com.girish.expensetracker.core.ReadResult
import com.girish.expensetracker.core.SmsReader
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType
import com.girish.expensetracker.core.Template
import com.girish.expensetracker.core.Txn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val CASH = Source("CASH", "CASH", SourceType.ACCOUNT)

/** A transaction plus where it came from, so the UI can retag it or delete a cash entry. */
data class Row(val txn: Txn, val smsId: Long?, val manualId: Long?, val rawBody: String?)

data class UnparsedSms(val id: Long, val sender: String, val body: String, val receivedAt: LocalDateTime)
data class NewSource(val source: Source, val example: String)

/** Everything the screens and the notification need, computed from raw inputs in one pass. */
data class Snapshot(
    val rows: List<Row>,
    val unparsed: List<UnparsedSms>,
    val newSources: List<NewSource>,
    val today: LocalDate,
    val owner: Owner? = null,
    val rentCandidates: List<com.girish.expensetracker.core.RentCandidate> = emptyList(),
    val statements: List<CardStatement> = emptyList(),
    val limitsSeen: List<LimitSeen> = emptyList(),
    val plan: Plan = Plan(),
    /** Who still owes the owner money, from loans and splits. */
    val debts: List<Debt> = emptyList(),
    /** Foreign-currency charges seen in the last week (they can't be counted in rupees, so they are flagged). */
    val foreign: List<Unusual> = emptyList(),
    /** Row key -> the owner's tag ("Goa trip", "office"). */
    val tags: Map<String, String> = emptyMap(),
    val trips: List<Trip> = emptyList(),
) {
    val txns: List<Txn> by lazy { rows.map { it.txn } }
    val todayTotal: Long by lazy { Ledger.today(txns, today) }
    val monthTotal: Long by lazy { Ledger.month(txns, today) }
    val cycleStart: LocalDate by lazy { Ledger.cycleStart(today, txns) }

    /** Every tracked credit card (or, before setup, every card seen) with its utilisation and bill. */
    val cards: List<CardHealth> by lazy {
        val tracked = owner?.tracked?.filter { it.type == SourceType.CARD && it.last4 != "*" }
            ?: txns.map { it.parsed.source }.filter { it.type == SourceType.CARD && it.last4 != "*" }.distinct()
        tracked.map { c -> Credit.health(c, txns, statements, limitsSeen, plan.cardLimits[sourceKey(c)], plan.statementDays[sourceKey(c)], today) }
    }

    val budgets: List<BudgetLine> by lazy { Budgets.lines(txns, cycleStart, today, plan.budgetTotal, plan.budgetByCategory) }

    /** Recurring charges, without loan instalments (those are listed as EMIs). */
    val subscriptions: List<Subscription> by lazy {
        val loans = emis.map { com.girish.expensetracker.core.normalizeName(it.name) }.toSet()
        Subscriptions.find(txns, today).filterNot { com.girish.expensetracker.core.normalizeName(it.name) in loans }
    }

    /** Double charges, unusually large payments and foreign charges from the last week. */
    val unusual: List<Unusual> by lazy { (foreign + UnusualSpend.find(txns, today, money = { com.girish.expensetracker.ui.rupees(it) })).sortedByDescending { it.day } }

    val savings: List<SavingsMonth> by lazy { Savings.months(txns, Ledger.cycles(today, txns, 6)) }

    val emis: List<Emi> by lazy { Emis.find(txns, today) }

    val holdings: List<Holding> by lazy { Investments.holdings(txns, cycleStart) }

    fun tagOf(row: Row): String? = (row.smsId?.let { "sms:$it" } ?: row.manualId?.let { "cash:$it" })?.let(tags::get)
}

/** What the owner set by hand: card limits and statement days, and budgets. Amounts in paise. */
data class Plan(
    val cardLimits: Map<String, Long> = emptyMap(),
    val statementDays: Map<String, Int> = emptyMap(),
    val budgetTotal: Long? = null,
    val budgetByCategory: Map<String, Long> = emptyMap(),
)

fun sourceKey(s: Source) = "${s.bank}|${s.last4}|${s.type}"

/** Who owns this phone, as far as the app knows: "none" before the first import, "detected", then "confirmed". */
data class Owner(val state: String, val displayName: String, val salaryDay: Int, val tracked: Set<Source>)

private const val KEY_SETUP = "setup"
private const val KEY_NAME = "display_name"
private const val KEY_SALARY_DAY = "salary_day"
private const val KEY_SELF_NAMES = "self_names"
private const val KEY_SELF_VPAS = "self_vpas"
private const val KEY_IGNORED = "ignored_sources"
private const val KEY_RENT = "rent_payees"
private const val KEY_RENT_DISMISSED = "rent_dismissed"
private const val KEY_IMPORTED = "imported"
private const val KEY_CARD_LIMITS = "card_limits"
private const val KEY_STATEMENT_DAYS = "statement_days"
private const val KEY_BUDGET_TOTAL = "budget_total"
private const val KEY_BUDGETS = "budgets"
private const val KEY_ALERTS_SENT = "alerts_sent"
private const val KEY_SETTLED_LOANS = "settled_loans"

class Repo(val store: Store) {

    /** True while the first-run import is filling the inbox; detection waits until it is complete. */
    @Volatile var importing = false

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Store an SMS if it comes from a bank transaction sender. Returns true when it was new. */
    fun ingest(sender: String, body: String, receivedAt: Long): Boolean {
        return when (SmsReader.read(sender, body, profile(), store.learned())) {
            ReadResult.NotBank, ReadResult.Promo -> false
            // Informational bank SMS are kept too: a future layout may turn them into transactions.
            else -> store.insertSms(sender, body, receivedAt)
        }
    }

    /** The owner's profile, entirely from settings (detected from their own SMS, then confirmed or edited). */
    fun profile(): Profile {
        val tracked = store.tracked()
        Ledger.salaryDay = store.setting(KEY_SALARY_DAY)?.toIntOrNull() ?: 28
        return Profile(
            accounts = tracked.filter { it.type == SourceType.ACCOUNT }.toSet(),
            cards = tracked.filter { it.type == SourceType.CARD }.toSet(),
            selfNames = lines(KEY_SELF_NAMES),
            selfVpas = lines(KEY_SELF_VPAS),
        )
    }

    fun owner() = Owner(
        state = store.setting(KEY_SETUP) ?: "none",
        displayName = store.setting(KEY_NAME).orEmpty(),
        salaryDay = store.setting(KEY_SALARY_DAY)?.toIntOrNull() ?: 28,
        tracked = store.tracked(),
    )

    private fun key(s: Source) = sourceKey(s)

    private fun lines(key: String) = store.setting(key)?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun inbox() = store.smsSince(0).map { InboxSms(it.sender, it.body, LocalDateTime.ofInstant(Instant.ofEpochMilli(it.receivedAt), zone)) }

    /** First run (after the SMS import) or an upgrade from the hardcoded version: learn the owner from their inbox once. */
    @Synchronized
    fun ensureDetected() {
        // Learn the owner only from a complete inbox: after the first import, or on upgrade from a version that had one.
        if (importing || store.setting(KEY_SETUP) != null || store.smsCount() == 0L) return
        if (store.setting(KEY_IMPORTED) == null && store.setting(KEY_NAME) == null && !upgradedInstall()) return
        detectAndSave()
    }

    /** Detects accounts, cards, self names/UPI IDs and salary day; adds to (never removes from) what is already saved. */
    @Synchronized
    fun detectAndSave(): DetectedProfile {
        val d = ProfileDetector.detect(inbox())
        val ignored = lines(KEY_IGNORED)
        d.sources.filter { key(it.source) !in ignored }.forEach { store.track(it.source) }
        store.setSetting(KEY_SELF_NAMES, (lines(KEY_SELF_NAMES) + d.selfNames).joinToString("\n"))
        store.setSetting(KEY_SELF_VPAS, (lines(KEY_SELF_VPAS) + d.selfVpas).joinToString("\n"))
        d.salaryDay?.let { if (store.setting(KEY_SALARY_DAY) == null) store.setSetting(KEY_SALARY_DAY, it.toString()) }
        d.displayName?.let { if (store.setting(KEY_NAME).isNullOrBlank()) store.setSetting(KEY_NAME, it) }
        if (store.setting(KEY_SETUP) == null) store.setSetting(KEY_SETUP, "detected")
        return d
    }

    /** Everything the inbox shows, with message counts, for the review sheet. */
    fun detectedSources(): List<DetectedSource> = ProfileDetector.detect(inbox()).sources

    fun saveOwner(keep: Set<Source>, offered: Set<Source>, displayName: String, salaryDay: Int) {
        (offered - keep).forEach { store.untrack(it) }
        store.setSetting(KEY_IGNORED, ((lines(KEY_IGNORED) - keep.map(::key).toSet()) + (offered - keep).map(::key)).joinToString("\n"))
        keep.forEach { store.track(it) }
        store.setSetting(KEY_NAME, displayName.trim())
        store.setSetting(KEY_SALARY_DAY, salaryDay.coerceIn(1, 31).toString())
        store.setSetting(KEY_SETUP, "confirmed")
    }

    /** Recompute the ledger (all stored history by default, so per-person views can go back to the first SMS). */
    fun snapshot(days: Long = 3650): Snapshot {
        ensureDetected()
        val today = LocalDate.now(zone)
        val since = today.minusDays(days)
        val sinceMillis = since.atStartOfDay(zone).toInstant().toEpochMilli()
        val profile = profile()
        val rules = store.rules()
        val ignored = lines(KEY_IGNORED)
        val overrides = store.overrides()
        val splits = store.splits()
        val settledLoans = lines(KEY_SETTLED_LOANS)
        val learned = store.learned()
        val foreign = mutableListOf<Unusual>()
        val dismissedRent = lines(KEY_RENT_DISMISSED)
        val rentPayees = lines(KEY_RENT).mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }

        val rows = mutableListOf<Row>()
        val unparsed = mutableListOf<UnparsedSms>()
        val newSources = linkedMapOf<Source, String>()
        val statements = mutableListOf<CardStatement>()
        val limits = mutableListOf<LimitSeen>()

        // Older installs may hold the same SMS twice (live + import, slightly different stamps): read it once.
        val lastSeen = HashMap<String, Long>()
        for (sms in store.smsSince(sinceMillis)) {
            val dupKey = sms.sender + "\u0000" + sms.body
            val prev = lastSeen[dupKey]
            lastSeen[dupKey] = sms.receivedAt
            if (prev != null && kotlin.math.abs(sms.receivedAt - prev) < 600_000L) continue
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sms.receivedAt), zone)
            // Statement and limit SMS move no money but tell how each credit card stands.
            CardFacts.statement(sms.sender, sms.body, at)?.let { statements += it }
            CardFacts.limit(sms.sender, sms.body, at)?.let { limits += it }
            if (at.toLocalDate() >= today.minusDays(7)) {
                SmsReader.bankOf(sms.sender)?.let { bank -> UnusualSpend.foreignCharge(bank, sms.body, at)?.let { foreign += it } }
            }
            when (val r = SmsReader.read(sms.sender, sms.body, profile, learned)) {
                is ReadResult.Parsed -> {
                    var t = Classifier.classify(r.parsed, at, profile, rules)
                    val forced = overrides[sms.id]
                    if (forced != null && (t.kind == Kind.SPEND || t.kind == Kind.LENT)) {
                        t = t.copy(
                            category = forced.second, payeeType = forced.first ?: t.payeeType,
                            kind = if (forced.second == Classifier.LENT) Kind.LENT else Kind.SPEND,
                        )
                    }
                    else if (forced == null && rentPayees.any { (label, typical) -> com.girish.expensetracker.core.RentFinder.isRent(t, label, typical) }) t = t.copy(category = "Rent")
                    splits["sms:${sms.id}"]?.let { t = t.copy(splitWith = it) }
                    // A loan settled by hand: neither spend nor still owed.
                    if (t.kind == Kind.LENT && sms.id.toString() in settledLoans) t = t.copy(kind = Kind.REPAID, category = "Lent · settled")
                    rows += Row(t, sms.id, null, sms.body)
                }
                is ReadResult.Unparsed -> unparsed += UnparsedSms(sms.id, sms.sender, sms.body, at)
                is ReadResult.UnknownSource -> if (key(r.parsed.source) !in ignored) newSources.putIfAbsent(r.parsed.source, sms.body)
                else -> Unit
            }
        }

        for (m in store.manualSince(since)) {
            val parsed = ParsedSms(Template.MANUAL, Direction.DEBIT, m.amountPaise, CASH, m.note, m.day, null, m.category)
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.createdAt), zone)
            val t = Classifier.classify(parsed, at, profile, rules)
            rows += Row(splits["cash:${m.id}"]?.let { t.copy(splitWith = it) } ?: t, null, m.id, null)
        }

        // Imported statement rows fill history from before the app; a row the SMS already covers is skipped.
        val fromSms = rows.toList()
        for ((source, s) in store.statementRowsSince(since)) {
            val covered = fromSms.any { r ->
                r.txn.parsed.source == source && r.txn.amountPaise == s.amountPaise &&
                    kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(r.txn.day, s.day)) <= 2
            }
            if (covered) continue
            val dir = if (s.debit) Direction.DEBIT else Direction.CREDIT
            val other = com.girish.expensetracker.core.StatementCsv.payee(s.description, s.debit)
            val parsed = ParsedSms(if (s.debit) Template.STATEMENT_DEBIT else Template.STATEMENT_CREDIT, dir, s.amountPaise, source, other, s.day, null, s.description)
            rows += Row(Classifier.classify(parsed, s.day.atTime(12, 0), profile, rules), null, null, s.description)
        }

        rows.sortBy { it.txn.receivedAt }
        // Match by object identity: two real identical SIPs are equal as data but are separate payments.
        val rowByParsed = java.util.IdentityHashMap<ParsedSms, Row>().apply { rows.forEach { put(it.txn.parsed, it) } }
        val kept = Dedupe.apply(rows.map { it.txn }).map { k -> rowByParsed.getValue(k.parsed).copy(txn = k) }
        // Loans and splits: paybacks from someone who owes money settle it instead of reducing spend.
        val owe = Owe.settle(kept.map { it.txn })
        val deduped = kept.zip(owe.txns) { r, t -> r.copy(txn = t) }

        return Snapshot(
            rows = deduped,
            unparsed = unparsed.reversed(),
            newSources = newSources.map { (s, body) -> NewSource(s, body) },
            today = today,
            owner = owner(),
            rentCandidates = com.girish.expensetracker.core.RentFinder.find(deduped.map { it.txn }).filter { it.key !in dismissedRent },
            statements = statements,
            limitsSeen = limits,
            plan = plan(),
            debts = owe.debts,
            foreign = foreign,
            tags = store.tags(),
            trips = store.trips(),
        )
    }

    /** Save a layout taught from an unread SMS; that SMS and every later one like it are read from now on. */
    fun teach(layout: LearnedLayout) = store.addLearned(layout)

    fun setTag(row: Row, tag: String) { rowKey(row)?.let { store.setTag(it, tag) } }

    /** Row key for splits: SMS rows and cash entries can be split; imported statement rows cannot. */
    fun rowKey(row: Row): String? = row.smsId?.let { "sms:$it" } ?: row.manualId?.let { "cash:$it" }

    fun setSplit(row: Row, shares: List<SplitShare>) { rowKey(row)?.let { store.setSplit(it, shares) } }

    /** Mark everything [debt]'s person owes as paid (in cash, or anywhere the bank SMS can't see). */
    fun settleDebt(debt: Debt, rows: List<Row>) {
        for ((key, shares) in store.splits()) {
            if (shares.any { it.name == debt.name && it.label == debt.label && !it.settled }) {
                store.setSplit(key, shares.map { if (it.name == debt.name && it.label == debt.label) it.copy(settled = true) else it })
            }
        }
        val loans = rows.filter { it.txn.kind == Kind.LENT && it.smsId != null && it.txn.parsed.counterparty == debt.label }.map { it.smsId!! }
        if (loans.isNotEmpty()) store.setSetting(KEY_SETTLED_LOANS, (lines(KEY_SETTLED_LOANS) + loans.map(Long::toString)).joinToString("\n"))
    }

    // ---------------------------------------------------------------- owner's plan: limits, statement days, budgets

    private fun keyed(key: String): Map<String, Long> =
        lines(key).mapNotNull { l -> l.substringAfterLast('=').toLongOrNull()?.let { l.substringBeforeLast('=') to it } }.toMap()

    private fun saveKeyed(key: String, map: Map<String, Long>) =
        store.setSetting(key, map.entries.joinToString("\n") { "${it.key}=${it.value}" })

    fun plan() = Plan(
        cardLimits = keyed(KEY_CARD_LIMITS),
        statementDays = keyed(KEY_STATEMENT_DAYS).mapValues { it.value.toInt() },
        budgetTotal = store.setting(KEY_BUDGET_TOTAL)?.toLongOrNull()?.takeIf { it > 0 },
        budgetByCategory = keyed(KEY_BUDGETS),
    )

    /** null clears it (back to the estimate from SMS). */
    fun setCardLimit(card: Source, paise: Long?) =
        saveKeyed(KEY_CARD_LIMITS, keyed(KEY_CARD_LIMITS).let { if (paise == null) it - key(card) else it + (key(card) to paise) })

    fun setStatementDay(card: Source, day: Int?) =
        saveKeyed(KEY_STATEMENT_DAYS, keyed(KEY_STATEMENT_DAYS).let { if (day == null) it - key(card) else it + (key(card) to day.toLong()) })

    /** [category] null is the whole salary month; [paise] null or 0 removes the budget. */
    fun setBudget(category: String?, paise: Long?) {
        if (category == null) store.setSetting(KEY_BUDGET_TOTAL, (paise ?: 0L).toString())
        else saveKeyed(KEY_BUDGETS, keyed(KEY_BUDGETS).let { if (paise == null || paise <= 0) it - category else it + (category to paise) })
    }

    /** Alerts are posted once each; the newest 300 keys are remembered. */
    fun alertsSent(): Set<String> = lines(KEY_ALERTS_SENT)

    fun markAlertsSent(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val all = (store.setting(KEY_ALERTS_SENT)?.split('\n').orEmpty().filter { it.isNotBlank() } + keys).distinct().takeLast(300)
        store.setSetting(KEY_ALERTS_SENT, all.joinToString("\n"))
    }

    fun retag(payee: String, type: PayeeType, category: String) = store.setRule(payee, type, category)

    fun overrideCategory(smsId: Long, category: String, type: PayeeType?) = store.setOverride(smsId, category, type)

    /** True until the first inbox import has completed on this install. */
    fun needsImport(): Boolean = store.setting(KEY_IMPORTED) == null && !upgradedInstall()

    fun markImported() = store.setSetting(KEY_IMPORTED, "1")

    /** Installs from before the import flag existed already imported their history (they have SMS and a setup state). */
    private fun upgradedInstall() = store.setting(KEY_SETUP) != null

    /** Confirmed rent: these payments and future ones of a similar size to this person are filed as Rent. */
    fun markRent(c: com.girish.expensetracker.core.RentCandidate) {
        store.setSetting(KEY_RENT, (lines(KEY_RENT) + "${c.labels.first()}|${c.typicalPaise}").joinToString("\n"))
    }

    fun dismissRent(c: com.girish.expensetracker.core.RentCandidate) {
        store.setSetting(KEY_RENT_DISMISSED, (lines(KEY_RENT_DISMISSED) + c.key).joinToString("\n"))
    }

    /** Debug builds only: fill the database with synthetic SMS so the emulator shows every screen. */
    fun loadDemo(): Int {
        var n = 0
        for (s in com.girish.expensetracker.ui.DemoData.messages()) {
            if (store.insertSms(s.sender, s.body, s.at.atZone(zone).toInstant().toEpochMilli())) n++
        }
        return n
    }
}
