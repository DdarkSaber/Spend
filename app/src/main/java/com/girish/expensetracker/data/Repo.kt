package com.girish.expensetracker.data

import com.girish.expensetracker.core.BudgetLine
import com.girish.expensetracker.core.Budgets
import com.girish.expensetracker.core.CardFacts
import com.girish.expensetracker.core.CardGroup
import com.girish.expensetracker.core.UNASSIGNED
import com.girish.expensetracker.core.normalizeName
import com.girish.expensetracker.core.CardHealth
import com.girish.expensetracker.core.CardStatement
import com.girish.expensetracker.core.Classifier
import com.girish.expensetracker.core.Credit
import com.girish.expensetracker.core.Emi
import com.girish.expensetracker.core.FixedCost
import com.girish.expensetracker.core.FixedCosts
import com.girish.expensetracker.core.Forecast
import com.girish.expensetracker.core.Forecasts
import com.girish.expensetracker.core.Habits
import com.girish.expensetracker.core.HabitsReport
import com.girish.expensetracker.core.NewRecurring
import com.girish.expensetracker.core.NewRecurrings
import com.girish.expensetracker.core.Streak
import com.girish.expensetracker.core.Streaks
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
import com.girish.expensetracker.core.PayeeRules
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
data class Row(
    val txn: Txn, val smsId: Long?, val manualId: Long?, val rawBody: String?,
    /** Where it came from: "sms", "notif:{package}", "archive:{name}", "stmt" (statement row) or "cash". */
    val channel: String = CHANNEL_SMS,
    /** Other channels that reported the same payment (collapsed by dedupe level 2). */
    val alsoVia: List<String> = emptyList(),
)

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
    /** Who still owes the owner money, from loans and splits (netted against what the owner owes them). */
    val debts: List<Debt> = emptyList(),
    /** Whom the owner still owes, from "They paid for me" (netted against what they owe the owner). */
    val iOwe: List<Debt> = emptyList(),
    /** Foreign-currency charges seen in the last week (they can't be counted in rupees, so they are flagged). */
    val foreign: List<Unusual> = emptyList(),
    /** Row key -> the owner's tag ("Goa trip", "office"). */
    val tags: Map<String, String> = emptyMap(),
    val trips: List<Trip> = emptyList(),
    /** What the owner said about each account or card. */
    val sourceMeta: Map<Source, com.girish.expensetracker.core.SourceMeta> = emptyMap(),
    /** Each account or card's latest transaction day (debit cards under their own number). */
    val lastActivity: Map<Source, LocalDate> = emptyMap(),
    /** Dedupe level 2 collapses (kept, dropped), for the audit log. */
    val collapses: List<Pair<Row, Row>> = emptyList(),
    /** Accounts and cards the owner chose to ignore: never counted. */
    val ignored: Set<Source> = emptySet(),
    /** Payee label -> phone contact name, for payees matched to exactly one contact. */
    val contactNames: Map<String, String> = emptyMap(),
    /** Payees that are a person only by default, for the one-time "Shop or person?" review. */
    val payeeReview: List<com.girish.expensetracker.core.PayeeReview> = emptyList(),
) {
    fun statusOf(s: Source): com.girish.expensetracker.core.SourceStatus =
        com.girish.expensetracker.core.SourceMeta.effective(sourceMeta[s], lastActivity[s], today)

    /** Active, the owner's own, and not a debit card shown under its account: the cards credit health is about. */
    private fun healthCard(s: Source): Boolean {
        val m = sourceMeta[s]
        return statusOf(s) == com.girish.expensetracker.core.SourceStatus.ACTIVE && m?.isAddOn != true && m?.linkedTo == null
    }

    val txns: List<Txn> by lazy { rows.map { it.txn } }
    val todayTotal: Long by lazy { Ledger.today(txns, today) }
    val monthTotal: Long by lazy { Ledger.month(txns, today) }
    val cycleStart: LocalDate by lazy { Ledger.cycleStart(today, txns) }

    /** Every tracked credit card (or, before setup, every card seen), bank A to Z then last digits. */
    val trackedCards: List<Source> by lazy {
        // "*" and "?" stand for "some card of this bank", not a card of their own.
        fun real(s: Source) = s.type == SourceType.CARD && s.last4 != "*" && s.last4 != UNASSIGNED
        (owner?.tracked?.filter(::real) ?: txns.map { it.parsed.source }.filter(::real).distinct())
            .filter(::healthCard)
            .sortedWith(compareBy({ it.bank }, { it.last4 }))
    }

    /** The owner's card groups, keeping only cards still tracked; a group left with one card is just that card. */
    val cardGroups: List<CardGroup> by lazy {
        plan.cardGroups.map { g -> g.copy(cards = trackedCards.filter { c -> g.cards.any { it.bank == c.bank && it.last4 == c.last4 } }) }
            .filter { it.cards.size >= 2 }
    }

    /** Banks with 2+ ungrouped cards that the owner hasn't said "no" to: offer to group them. */
    val cardGroupSuggestions: List<CardGroup> by lazy {
        val grouped = cardGroups.flatMap { it.cards }.toSet()
        trackedCards.filter { it !in grouped && it.bank !in plan.cardGroupsDeclined && cardGroups.none { g -> g.bank == it.bank } }
            .groupBy { it.bank }.filterValues { it.size >= 2 }.map { (bank, cs) -> CardGroup(bank, cs) }
    }

    /** For each suggested bank, the cards whose "available limit" SMS run as one balance: very likely one shared limit. */
    val likelyShared: Map<String, List<Source>> by lazy {
        cardGroupSuggestions.associate { g ->
            g.bank to g.cards.filter { c -> g.cards.any { o -> o != c && Credit.looksShared(c, o, limitsSeen, txns) } }
        }.filterValues { it.size >= 2 }
    }

    /** Banks where the owner said no to grouping but still has 2+ ungrouped cards: they can ask again from More. */
    val cardGroupsOff: List<String> by lazy {
        val grouped = cardGroups.flatMap { it.cards }.toSet()
        plan.cardGroupsDeclined.filter { bank -> trackedCards.count { it.bank == bank && it !in grouped } >= 2 }.sorted()
    }

    /** The group a card is in; a "*" or "?" card (no digits) is the group's when it holds every card of the bank. */
    fun groupOf(card: Source): CardGroup? =
        cardGroups.firstOrNull { card in it.cards } ?: if (card.last4 == "*" || card.last4 == UNASSIGNED)
            cardGroups.firstOrNull { g -> g.bank == card.bank && trackedCards.filter { it.bank == card.bank }.all { it in g.cards } } else null

    /** "ICICI cards", or "ICICI cards 1111 + 9999" when the bank has more than one group, or the owner's own name. */
    fun groupTitle(g: CardGroup): String =
        g.name ?: if (cardGroups.count { it.bank == g.bank } > 1) "${g.bank} cards ${g.cards.joinToString(" + ") { it.last4 }}" else "${g.bank} cards"

    /** Card spends and refunds whose SMS had no card number, at a bank where the owner has more than one card: ask which. */
    val unassignedCardRows: List<Row> by lazy {
        rows.filter { r ->
            val s = r.txn.parsed.source
            r.smsId != null && s.type == SourceType.CARD && s.last4 == UNASSIGNED && trackedCards.count { it.bank == s.bank } >= 2 &&
                // A bank whose cards are all one group (one statement) needs no answer: the group counts these already.
                cardGroups.none { g -> g.bank == s.bank && trackedCards.filter { it.bank == s.bank }.all { it in g.cards } }
        }.sortedByDescending { it.txn.receivedAt }
    }

    /** Utilisation and bill per card, with grouped cards shown once as their group; bank A to Z. */
    val cards: List<CardHealth> by lazy {
        val grouped = cardGroups.flatMap { it.cards }.toSet()
        val single = trackedCards.filter { it !in grouped }.map { c ->
            Credit.health(
                c, txns, statements, limitsSeen, plan.cardLimits[sourceKey(c)], plan.statementDays[sourceKey(c)], today,
                marked = plan.markedPaid[sourceKey(c)],
                accountPaysHere = trackedCards.count { it.bank == c.bank } == 1,
            )
        }
        val groups = cardGroups.map { g ->
            val key = sourceKey(g.anchor)
            Credit.groupHealth(
                g, txns, statements, limitsSeen, plan.cardLimits[key], plan.statementDays[key],
                g.cards.mapNotNull { c -> plan.cardLimits[sourceKey(c)]?.let { c to it } }.toMap(), today,
                // Not yet put on a card, but it can only be this group's: it holds every card of the bank.
                unassigned = trackedCards.filter { it.bank == g.bank }.all { it in g.cards },
                title = groupTitle(g),
                marked = plan.markedPaid[key],
            )
        }
        (single + groups).sortedWith(compareBy({ it.card.bank }, { if (it.isGroup) it.members.first().card.last4 else it.card.last4 }))
    }


    val budgets: List<BudgetLine> by lazy { Budgets.lines(txns, cycleStart, today, plan.budgetTotal, plan.budgetByCategory) }

    /** Recurring charges, without loan instalments (those are listed as EMIs). */
    /** Rent, maid, bills, SIPs: learned from the last three finished months. */
    val fixedCosts: List<FixedCost> by lazy { FixedCosts.find(txns, Ledger.cycles(today, txns, 4).drop(1)) }

    val forecast: Forecast by lazy { Forecasts.of(txns, cycleStart, today, fixedCosts) }

    val habits: HabitsReport by lazy { Habits.of(txns, fixedCosts, today) }

    val streak: Streak by lazy {
        Streaks.of(habits.perDay, plan.streakLimit ?: Streaks.suggest(habits.perDay, today), today, habits.from)
    }

    val newRecurring: List<NewRecurring> by lazy { NewRecurrings.find(txns, today) }

    val subscriptions: List<Subscription> by lazy {
        val loans = emis.map { com.girish.expensetracker.core.normalizeName(it.name) }.toSet()
        Subscriptions.find(txns, today).filterNot { com.girish.expensetracker.core.normalizeName(it.name) in loans }
    }

    /** Double charges, unusually large payments and foreign charges from the last week. */
    val unusual: List<Unusual> by lazy { (foreign + UnusualSpend.find(txns, today, money = { com.girish.expensetracker.ui.plainRupees(it) })).sortedByDescending { it.day } }

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
    /** Daily limit for the low-spend streak; null = suggested from the owner's own days. */
    val streakLimit: Long? = null,
    val groups: List<FriendGroup> = emptyList(),
    /** Cards of one bank that share a statement (and usually a limit). */
    val cardGroups: List<CardGroup> = emptyList(),
    /** Banks where the owner said their cards are not to be grouped: no more suggestions. */
    val cardGroupsDeclined: Set<String> = emptySet(),
    /** Card (or group) key -> the statement the owner marked as paid. */
    val markedPaid: Map<String, com.girish.expensetracker.core.MarkedPaid> = emptyMap(),
)

/** A named set of people (flatmates) to split with in one tap. Labels are how each appears in bank SMS. */
data class FriendGroup(val name: String, val members: List<Pair<String, String>>)

fun sourceKey(s: Source) = "${s.bank}|${s.last4}|${s.type}"

/** Who owns this phone, as far as the app knows: "none" before the first import, "detected", then "confirmed". */
data class Owner(val state: String, val displayName: String, val salaryDay: Int, val tracked: Set<Source>)

private const val KEY_SETUP = "setup"
private const val KEY_CONTACT_MATCHES = "contact_matches"
private const val KEY_SETTLED_OWED = "settled_owed"
private const val KEY_CREDIT_PERSON = "credit_person"
private const val KEY_PAYEE_REVIEW_DISMISSED = "payee_review_dismissed"
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
private const val KEY_STREAK_LIMIT = "streak_limit"
private const val KEY_GROUPS = "groups"
private const val KEY_CARD_GROUPS = "card_groups"
private const val KEY_CARD_GROUPS_DECLINED = "card_groups_declined"
private const val KEY_CARD_OF_SMS = "card_of_sms"
private const val KEY_CARD_OF_SHOP = "card_of_shop"
private const val KEY_MARKED_PAID = "marked_paid"
private const val KEY_ALERT_APPS = "alert_apps"

/** Google Messages (RCS), Truecaller (Business Chat) and Gmail (bank emails, all accounts). */
val DEFAULT_ALERT_APPS = listOf(
    Repo.AlertApp("com.google.android.apps.messaging", "Google Messages", true),
    Repo.AlertApp("com.truecaller", "Truecaller", true),
    Repo.AlertApp("com.google.android.gm", "Gmail", true),
)
private val NL = 10.toChar().toString()

class Repo(val store: Store) {

    /** True while the first-run import is filling the inbox; detection waits until it is complete. */
    @Volatile var importing = false

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Store an SMS if it comes from a bank transaction sender. Returns true when it was new. */
    fun ingest(sender: String, body: String, receivedAt: Long): Boolean = ingestId(sender, body, receivedAt) != null

    /** One alert to store: sender, text, when it arrived and where from. */
    data class Incoming(val sender: String, val body: String, val receivedAt: Long, val channel: String = CHANNEL_SMS)

    /** Stores many alerts in one transaction, reading the profile once; returns how many were new. */
    fun ingestAll(items: List<Incoming>): Int {
        if (items.isEmpty()) return 0
        val profile = profile()
        val learned = store.learned()
        return store.inTransaction { items.count { store(it, profile, learned) } }
    }

    // True when [m] is a bank alert that was not stored before.
    private fun store(m: Incoming, profile: Profile, learned: List<LearnedLayout>): Boolean =
        when (SmsReader.read(m.sender, m.body, profile, learned)) {
            ReadResult.NotBank, ReadResult.Promo -> false
            else -> store.insertSmsId(m.sender, m.body, m.receivedAt, m.channel) != null
        }

    /** Per channel of a history file: what it held and how much of it was new. */
    data class ArchiveCount(val channel: String, val alerts: Int, val alertsNew: Int, val rows: Int, val rowsNew: Int)
    data class ArchiveResult(val counts: List<ArchiveCount>, val rejected: List<com.girish.expensetracker.core.Archive.Rejected>)

    /**
     * Stores a parsed history file: alerts like SMS (with their archive channel), rows as statement rows. 500 items per
     * transaction, with (done, total) progress. Importing the same file again adds nothing.
     */
    fun importArchive(file: com.girish.expensetracker.core.Archive.Parsed, onProgress: (Int, Int) -> Unit = { _, _ -> }): ArchiveResult {
        val profile = profile()
        val learned = store.learned()
        val counts = linkedMapOf<String, IntArray>() // alerts, alertsNew, rows, rowsNew
        var done = 0
        for (chunk in file.items.chunked(500)) {
            store.inTransaction {
                for (item in chunk) {
                    val c = counts.getOrPut(item.channel) { IntArray(4) }
                    when (item) {
                        is com.girish.expensetracker.core.Archive.Item.Alert -> {
                            c[0]++
                            if (store(Incoming(item.sender, item.body, item.atMillis, item.channel), profile, learned)) c[1]++
                        }
                        is com.girish.expensetracker.core.Archive.Item.Row -> {
                            c[2]++
                            c[3] += store.addStatementRows(item.source, listOf(StatementRow(item.day, item.amountPaise, item.debit, item.description)))
                        }
                    }
                }
            }
            done += chunk.size
            onProgress(done, file.items.size)
        }
        return ArchiveResult(counts.map { (ch, c) -> ArchiveCount(ch, c[0], c[1], c[2], c[3]) }, file.rejected)
    }

    // ---------------------------------------------------------------- alerts from other apps' notifications

    /** An app whose notifications are read for bank alerts. */
    data class AlertApp(val pkg: String, val label: String, val enabled: Boolean)

    fun alertApps(): List<AlertApp> = store.setting(KEY_ALERT_APPS)?.split(NL)?.mapNotNull { l ->
        l.split('|').takeIf { it.size == 3 }?.let { AlertApp(it[0], it[1], it[2] == "1") }
    } ?: DEFAULT_ALERT_APPS

    fun setAlertApps(apps: List<AlertApp>) =
        store.setSetting(KEY_ALERT_APPS, apps.distinctBy { it.pkg }.joinToString(NL) { "${it.pkg}|${it.label.replace('|', ' ')}|${if (it.enabled) 1 else 0}" })

    /** What happened to one notification text, for the audit log. */
    data class NotifOutcome(
        val text: String, val sender: String?, val gate: String, val decision: String, val smsId: Long?,
        val result: ReadResult?,
    )

    /**
     * One notification text from [pkg]: bank from the text (the title is a hint), gate, re-post and same-text checks,
     * then stored like an SMS with channel "notif:{pkg}".
     */
    fun ingestNotification(pkg: String, title: String?, text: String, at: Long, from: String? = null): NotifOutcome {
        val channel = "notif:$pkg"
        if (!store.firstSeen(sha(pkg + "\u0000" + text), at)) return NotifOutcome(text, null, "-", "repost", null, null)
        // The notification of an SMS must pass the checks the SMS itself passes. A conversation under a phone number
        // is a person or an unregistered sender (banks never alert from one), and a text the SMS reader already
        // turned away stays turned away.
        val name = from?.trim().orEmpty()
        if (phoneNumber.matches(name)) return NotifOutcome(text, null, "phone-sender", "gate", null, null)
        if (store.seen(rejectedKey(text))) return NotifOutcome(text, null, "sms-rejected", "gate", null, null)
        // Titled with a sender header ("AX-SBICRD-P"): that is the real sender, promo suffix and all.
        val sender = if (SmsReader.isSenderHeader(name)) name.uppercase().takeIf { SmsReader.bankOf(it) != null }
            else com.girish.expensetracker.core.BankFromText.sender(text, title)
        sender ?: return NotifOutcome(text, null, "-", "no-bank", null, null)
        val profile = profile()
        val learned = store.learned()
        val gate = com.girish.expensetracker.core.NotifGate.check(sender, text, profile, learned)
        if (!gate.pass) return NotifOutcome(text, sender, gate.reason, "gate", null, null)
        val result = SmsReader.read(sender, text, profile, learned)
        if (store.sameTextElsewhere(text, channel, at)) return NotifOutcome(text, sender, gate.reason, "dup-text", null, result)
        val id = store.insertSmsId(sender, text, at, channel)
        return NotifOutcome(text, sender, gate.reason, if (id != null) "stored" else "dup-same-channel", id, result)
    }

    /** Saves what the owner said about [m]'s account or card; ignoring it stops it being counted anywhere. */
    fun saveSourceMeta(m: com.girish.expensetracker.core.SourceMeta) {
        val k = key(m.source)
        val wasIgnored = k in lines(KEY_IGNORED)
        store.setSourceMeta(m)
        if (m.status == com.girish.expensetracker.core.SourceStatus.IGNORED) {
            store.untrack(m.source)
            store.setSetting(KEY_IGNORED, (lines(KEY_IGNORED) + k).joinToString(NL))
        } else if (wasIgnored || m.source !in store.tracked()) {
            store.setSetting(KEY_IGNORED, (lines(KEY_IGNORED) - k).joinToString(NL))
            store.track(m.source)
        }
    }

    /** After a history import: keep [keep] (tracked, shown as past when old) and ignore [drop]. */
    fun keepOlderSources(keep: Set<Source>, drop: Set<Source>) {
        val metas = store.sourceMetas()
        keep.forEach { saveSourceMeta((metas[it] ?: com.girish.expensetracker.core.SourceMeta(it)).copy(status = com.girish.expensetracker.core.SourceStatus.AUTO)) }
        drop.forEach { saveSourceMeta((metas[it] ?: com.girish.expensetracker.core.SourceMeta(it)).copy(status = com.girish.expensetracker.core.SourceStatus.IGNORED)) }
    }

    private val phoneNumber = Regex("^\\+?[\\d\\s()\\-]{7,}$")

    private fun sha(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // Only a hash is kept (for 7 days), never the text: most turned-away SMS are personal.
    private fun rejectedKey(body: String) = "sms:" + sha(com.girish.expensetracker.core.SameText.normalize(body))

    /** Bumped when a notification copy is removed because its SMS was turned away, so the totals get refreshed. */
    @Volatile var removedCopies = 0
        private set

    /** Like [ingest], returning the stored SMS's id (for the quick-tag notification). */
    fun ingestId(sender: String, body: String, receivedAt: Long): Long? {
        return when (SmsReader.read(sender, body, profile(), store.learned())) {
            ReadResult.NotBank, ReadResult.Promo -> {
                // Its notification (Google Messages, Truecaller) must not get in by the other door: remember the text
                // as turned away, and remove a copy that got stored before this SMS was read.
                store.firstSeen(rejectedKey(body), receivedAt)
                if (store.dropNotifCopies(body, receivedAt) > 0) removedCopies++
                null
            }
            // Informational bank SMS are kept too: a future layout may turn them into transactions.
            else -> store.insertSmsId(sender, body, receivedAt)
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
        val ownRules = store.rules()
        // A payee matched to one phone contact is a person (the owner's own retags still win).
        val contactNames = contactMatches()
        val rules = ownRules.withDefaults(contactNames.keys.associateWith { PayeeRules.Rule(PayeeType.PERSON, "People") })
        val ignored = lines(KEY_IGNORED)
        val overrides = store.overrides()
        val splits = store.splits()
        val settledLoans = lines(KEY_SETTLED_LOANS)
        val settledOwed = lines(KEY_SETTLED_OWED)
        val creditPerson = creditPersons()
        val learned = store.learned()
        val foreign = mutableListOf<Unusual>()
        val dismissedRent = lines(KEY_RENT_DISMISSED)
        val rentPayees = lines(KEY_RENT).mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }

        val cardOfSms = cardOfSms()
        val cardOfShop = cardOfShop()
        val metas = store.sourceMetas()
        // Each account or card's latest transaction day, before debit cards are moved under their accounts.
        val lastActivity = HashMap<Source, LocalDate>()
        val parserVersion = com.girish.expensetracker.core.ParseCache.version(profile, learned, zone.id)
        val readings = readings(parserVersion)
        val fresh = HashMap<Long, String>()
        val rows = mutableListOf<Row>()
        val unparsed = mutableListOf<UnparsedSms>()
        val newSources = linkedMapOf<Source, String>()
        val statements = mutableListOf<CardStatement>()
        val limits = mutableListOf<LimitSeen>()

        // Level 0: the same alert stored twice (live + import with slightly different stamps, or an SMS and its
        // notification copy) is read once.
        val alerts = com.girish.expensetracker.core.SameText.keep(store.smsSince(sinceMillis), { it.sender }, { it.body }, { it.receivedAt }, { it.channel })
        for (sms in alerts) {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sms.receivedAt), zone)
            // Reading an SMS is the slow part, and its result only changes when the owner's cards or taught layouts do.
            val read = readings[sms.id] ?: com.girish.expensetracker.core.Reading(
                SmsReader.read(sms.sender, sms.body, profile, learned),
                CardFacts.statement(sms.sender, sms.body, at),
                CardFacts.limit(sms.sender, sms.body, at),
                SmsReader.bankOf(sms.sender)?.let { bank -> UnusualSpend.foreignCharge(bank, sms.body, at) },
            ).also { readings[sms.id] = it; fresh[sms.id] = com.girish.expensetracker.core.ParseCache.encode(it) }
            // Statement and limit SMS move no money but tell how each credit card stands.
            read.statement?.let { statements += it }
            read.limit?.let { limits += it }
            if (at.toLocalDate() >= today.minusDays(7)) read.foreign?.let { foreign += it }
            when (val r = read.result) {
                is ReadResult.Parsed -> {
                    var t = Classifier.classify(r.parsed, at, profile, rules)
                    // A card SMS without a card number, put on a card by the owner.
                    if (t.parsed.source.type == SourceType.CARD && t.parsed.source.last4 == UNASSIGNED) {
                        val bank = t.parsed.source.bank
                        (cardOfSms[sms.id] ?: t.parsed.counterparty?.let { cardOfShop["$bank|${normalizeName(it)}"] })
                            ?.let { d -> t = t.copy(parsed = t.parsed.copy(source = Source(bank, d, SourceType.CARD))) }
                    }
                    lastActivity.merge(t.parsed.source, t.day) { a, b -> maxOf(a, b) }
                    // A debit card linked to its account: shown under the account and counted once there.
                    metas[t.parsed.source]?.linkedTo?.let { acc -> t = t.copy(parsed = t.parsed.copy(source = acc)) }
                    val forced = overrides[sms.id]
                    if (forced != null && (t.kind == Kind.SPEND || t.kind == Kind.LENT)) {
                        t = t.copy(
                            category = forced.second, payeeType = forced.first ?: t.payeeType,
                            kind = if (forced.second == Classifier.LENT) Kind.LENT else Kind.SPEND,
                        )
                    }
                    else if (forced == null && rentPayees.any { (label, typical) -> com.girish.expensetracker.core.RentFinder.isRent(t, label, typical) }) t = t.copy(category = "Rent")
                    splits["sms:${sms.id}"]?.let { t = t.copy(splitWith = it) }
                    // "Paid on their behalf": a credit the owner put under a person (the payer's name differs).
                    creditPerson[sms.id]?.let { label ->
                        if (t.parsed.direction == Direction.CREDIT) t = t.copy(
                            parsed = t.parsed.copy(counterparty = label), kind = Kind.PERSON_CREDIT, payeeType = PayeeType.PERSON, category = "Paid back",
                        )
                    }
                    // A loan settled by hand: neither spend nor still owed.
                    if (t.kind == Kind.LENT && sms.id.toString() in settledLoans) t = t.copy(kind = Kind.REPAID, category = "Lent · settled")
                    rows += Row(t, sms.id, null, sms.body, sms.channel)
                }
                is ReadResult.Unparsed -> unparsed += UnparsedSms(sms.id, sms.sender, sms.body, at)
                is ReadResult.UnknownSource -> if (key(r.parsed.source) !in ignored) newSources.putIfAbsent(r.parsed.source, sms.body)
                else -> Unit
            }
        }

        // New readings are saved, so the next start (and every refresh) skips reading them again.
        if (fresh.isNotEmpty()) store.saveParsed(parserVersion, fresh)

        for (m in store.manualSince(since)) {
            val parsed = ParsedSms(Template.MANUAL, Direction.DEBIT, m.amountPaise, CASH, m.note, m.day, null, m.category)
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.createdAt), zone)
            var t = Classifier.classify(parsed, at, profile, rules)
            splits["cash:${m.id}"]?.let { t = t.copy(splitWith = it) }
            // "They paid for me": the owner's spend, and owed to that person until paid back (or marked paid).
            if (m.owedTo != null && m.id.toString() !in settledOwed) t = t.copy(owedTo = SplitShare(m.owedTo, m.amountPaise, m.owedLabel))
            rows += Row(t, null, m.id, null, "cash")
        }

        // Imported statement rows fill history from before the app; a row the SMS already covers is skipped.
        // (source, amount) -> days with such a payment, so each row is checked in O(1) instead of against every SMS.
        val smsDays = HashMap<Pair<Source, Long>, MutableSet<LocalDate>>()
        for (r in rows) smsDays.getOrPut(r.txn.parsed.source to r.txn.amountPaise) { HashSet() } += r.txn.day
        for ((rowSource, s) in store.statementRowsSince(since)) {
            lastActivity.merge(rowSource, s.day) { a, b -> maxOf(a, b) }
            val source = metas[rowSource]?.linkedTo ?: rowSource
            // Covered when an SMS for the same source and amount is within 2 days.
            val covered = smsDays[source to s.amountPaise]?.let { days -> (-2L..2L).any { s.day.plusDays(it) in days } } == true
            if (covered) continue
            val dir = if (s.debit) Direction.DEBIT else Direction.CREDIT
            val other = com.girish.expensetracker.core.StatementCsv.payee(s.description, s.debit)
            val parsed = ParsedSms(if (s.debit) Template.STATEMENT_DEBIT else Template.STATEMENT_CREDIT, dir, s.amountPaise, source, other, s.day, null, s.description)
            rows += Row(Classifier.classify(parsed, s.day.atTime(12, 0), profile, rules), null, null, s.description, "stmt")
        }

        rows.sortBy { it.txn.receivedAt }
        // Match by object identity: two real identical SIPs are equal as data but are separate payments.
        val rowByParsed = java.util.IdentityHashMap<ParsedSms, Row>().apply { rows.forEach { put(it.txn.parsed, it) } }
        val l1 = Dedupe.apply(rows.map { it.txn }).map { k -> rowByParsed.getValue(k.parsed).copy(txn = k) }
        // Level 2: the same payment reported on two channels is kept once, from the best channel.
        val l2 = com.girish.expensetracker.core.FactDedupe.apply(l1, { it.txn }, { it.channel })
        val kept = l2.kept.mapIndexed { i, r -> l2.alsoVia[i]?.let { r.copy(alsoVia = it) } ?: r }
        // Money moving between the owner's own accounts and cards is a transfer, not spend or income.
        val paired = com.girish.expensetracker.core.Transfers.pair(
            kept.map { it.txn },
            isOwn = profile::isKnown,
            locked = { i -> kept[i].smsId?.let(overrides::containsKey) == true },
        )
        // Loans and splits: paybacks from someone who owes money settle it instead of reducing spend.
        val owe = Owe.settle(paired)
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
            iOwe = owe.iOwe,
            foreign = foreign,
            tags = store.tags(),
            trips = store.trips(),
            sourceMeta = metas,
            collapses = l2.collapses.map { it.kept to it.dropped },
            lastActivity = lastActivity,
            contactNames = contactNames,
            payeeReview = if (store.setting(KEY_PAYEE_REVIEW_DISMISSED) == "1") emptyList()
                else com.girish.expensetracker.core.PayeeReviews.of(deduped.map { it.txn }, { ownRules.lookup(it) != null }, contactNames.keys),
            ignored = (ignored.mapNotNull(com.girish.expensetracker.core.SourceMeta::parseKey) +
                metas.values.filter { it.status == com.girish.expensetracker.core.SourceStatus.IGNORED }.map { it.source }).toSet(),
        )
    }

    /** Payee label -> the phone contact's saved name (matched on the device; only matches are kept). */
    fun contactMatches(): Map<String, String> = com.girish.expensetracker.core.ContactMatch.decode(store.setting(KEY_CONTACT_MATCHES))

    fun saveContactMatches(m: Map<String, String>) = store.setSetting(KEY_CONTACT_MATCHES, com.girish.expensetracker.core.ContactMatch.encode(m))

    /** "Shop or person?" answers: each saved as a payee rule; the review card is gone once dismissed. */
    fun answerPayees(shops: Collection<String>, persons: Collection<String>) {
        shops.forEach { store.setRule(it, PayeeType.MERCHANT, com.girish.expensetracker.core.PayeeGuess.category(it) ?: "Uncategorised") }
        persons.forEach { store.setRule(it, PayeeType.PERSON, "People") }
    }

    fun dismissPayeeReview() = store.setSetting(KEY_PAYEE_REVIEW_DISMISSED, "1")

    /** Save a layout taught from an unread SMS; that SMS and every later one like it are read from now on. */
    fun teach(layout: LearnedLayout) = store.addLearned(layout)

    fun setTag(row: Row, tag: String) { rowKey(row)?.let { store.setTag(it, tag) } }

    /** Row key for splits: SMS rows and cash entries can be split; imported statement rows cannot. */
    fun rowKey(row: Row): String? = row.smsId?.let { "sms:$it" } ?: row.manualId?.let { "cash:$it" }

    fun setSplit(row: Row, shares: List<SplitShare>) { rowKey(row)?.let { store.setSplit(it, shares) } }

    /** "They paid for me": [name] paid [paise] for the owner (counts as the owner's spend; the owner owes them). */
    fun addTheyPaid(day: LocalDate, paise: Long, name: String, label: String?, category: String, note: String) =
        store.addManual(day, paise, note.ifBlank { "Paid by $name" }, category, name, label)

    /** smsId -> person label for credits the owner put under a person ("paid on their behalf"). */
    fun creditPersons(): Map<Long, String> = lines(KEY_CREDIT_PERSON).mapNotNull { l ->
        val i = l.indexOf('|'); if (i <= 0) null else l.substring(0, i).toLongOrNull()?.let { it to l.substring(i + 1) }
    }.toMap()

    /** Put the credit [smsId] under [label] (null: back to the bank's payer name). */
    fun assignCredit(smsId: Long, label: String?) {
        val m = creditPersons().toMutableMap()
        if (label == null) m.remove(smsId) else m[smsId] = label.replace('\n', ' ')
        store.setSetting(KEY_CREDIT_PERSON, m.entries.joinToString("\n") { "${it.key}|${it.value}" })
    }

    /** Mark everything the owner owes [debt]'s person as paid (in cash, or anywhere the bank SMS can't see). */
    fun settleIOwe(debt: Debt, rows: List<Row>) {
        val ids = rows.filter { r ->
            val o = r.txn.owedTo ?: return@filter false
            r.manualId != null && ((debt.label != null && o.label == debt.label) || o.name == debt.name)
        }.map { it.manualId!!.toString() }
        if (ids.isNotEmpty()) store.setSetting(KEY_SETTLED_OWED, (lines(KEY_SETTLED_OWED) + ids).joinToString("\n"))
    }

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
        streakLimit = store.setting(KEY_STREAK_LIMIT)?.toLongOrNull()?.takeIf { it > 0 },
        groups = groups(),
        cardGroups = cardGroups(),
        cardGroupsDeclined = lines(KEY_CARD_GROUPS_DECLINED),
        markedPaid = markedPaid(),
    )

    // "Mark paid", one per card or group: key=statementDate|amountPaise|markedAtMillis. A newer statement replaces it.
    private fun markedPaid(): Map<String, com.girish.expensetracker.core.MarkedPaid> = lines(KEY_MARKED_PAID).mapNotNull { l ->
        val parts = l.substringAfterLast('=').split('|')
        if (parts.size != 3) return@mapNotNull null
        val day = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
        val at = parts[2].toLongOrNull()?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) } ?: return@mapNotNull null
        l.substringBeforeLast('=') to com.girish.expensetracker.core.MarkedPaid(day, parts[1].toLongOrNull() ?: 0L, at)
    }.toMap()

    /** The owner paid [h]'s latest statement but no payment SMS came; null [amountPaise] takes the mark back. */
    fun markPaid(h: CardHealth, amountPaise: Long?) {
        val key = sourceKey(h.card)
        val others = lines(KEY_MARKED_PAID).filterNot { it.substringBeforeLast('=') == key }
        val stmt = h.statement
        val mine = if (amountPaise == null || stmt == null) null else "$key=${stmt.statementDate}|$amountPaise|${System.currentTimeMillis()}"
        store.setSetting(KEY_MARKED_PAID, (others + listOfNotNull(mine)).joinToString(NL))
    }

    // Card groups are stored one per line: BANK|shared (1 or 0)|last4;last4|id|name. Lines saved before a bank could
    // have several groups have only the first three parts; their id is "group".
    private fun cardGroups(): List<CardGroup> = lines(KEY_CARD_GROUPS).mapNotNull { l ->
        val parts = l.split('|')
        if (parts.size < 3) return@mapNotNull null
        CardGroup(
            bank = parts[0],
            cards = parts[2].split(';').filter { it.isNotBlank() }.map { Source(parts[0], it, SourceType.CARD) },
            sharedLimit = parts[1] != "0",
            id = parts.getOrNull(3)?.takeIf { it.startsWith(CardGroup.DIGITS) } ?: CardGroup.DIGITS,
            name = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Saves group [id] (null = a new group) with [cards] of one bank. Fewer than two cards removes the group. A card
     * can be in one group only, so it leaves any other group. Returns the group's id, or null when it was removed.
     */
    fun saveCardGroup(id: String?, bank: String, cards: List<Source>, sharedLimit: Boolean, name: String? = null): String? {
        val mine = cards.filter { it.bank == bank }.map { it.last4 }.distinct()
        val all = cardGroups()
        val others = all.filterNot { it.bank == bank && it.id == id }
            .map { g -> g.copy(cards = g.cards.filterNot { g.bank == bank && it.last4 in mine }) }
            .filter { it.cards.size >= 2 }
        val newId = id ?: generateSequence(0) { it + 1 }.map { if (it == 0) CardGroup.DIGITS else "${CardGroup.DIGITS}$it" }
            .first { c -> all.none { it.bank == bank && it.id == c } }
        val kept = if (mine.size >= 2) CardGroup(bank, mine.map { Source(bank, it, SourceType.CARD) }, sharedLimit, newId, name?.let(::clean)?.ifBlank { null }) else null
        store.setSetting(KEY_CARD_GROUPS, (others + listOfNotNull(kept)).joinToString("\n") { g ->
            listOf(g.bank, if (g.sharedLimit) "1" else "0", g.cards.joinToString(";") { it.last4 }, g.id, g.name.orEmpty()).joinToString("|")
        })
        // Grouped or split up by hand: either way the owner has decided, so stop suggesting it.
        declineCardGroup(bank)
        return kept?.id
    }

    // Readings of stored alerts by SMS id, for one parser version: loaded from the parse cache once, then kept in memory.
    private var readingsMemo = java.util.concurrent.ConcurrentHashMap<Long, com.girish.expensetracker.core.Reading>()
    private var readingsVersion: Long? = null
    private var readingsGeneration = -1

    /** The readings saved for parser [version]; any other version's are forgotten. */
    @Synchronized
    private fun readings(version: Long): MutableMap<Long, com.girish.expensetracker.core.Reading> {
        if (readingsVersion != version || readingsGeneration != store.generation) {
            val memo = java.util.concurrent.ConcurrentHashMap<Long, com.girish.expensetracker.core.Reading>()
            for ((id, text) in store.parsedCache(version)) com.girish.expensetracker.core.ParseCache.decode(text)?.let { memo[id] = it }
            readingsMemo = memo
            readingsVersion = version
            readingsGeneration = store.generation
        }
        return readingsMemo
    }

    // Shop -> card for card SMS without a card number: "BANK|shop=last4".
    private fun cardOfShop(): Map<String, String> =
        lines(KEY_CARD_OF_SHOP).filter { '=' in it }.associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }

    /** Always put [shop]'s card SMS without a card number on [card]; null [card] forgets it. */
    fun setCardOfShop(shop: String, card: Source?) {
        val key = "${card?.bank ?: ""}|${normalizeName(shop)}"
        val all = cardOfShop().filterKeys { it.substringAfter('|') != normalizeName(shop) || (card != null && it.substringBefore('|') != card.bank) }
        store.setSetting(KEY_CARD_OF_SHOP, (if (card == null) all else all + (key to card.last4)).entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    /** Undo for [setCardOfSms]. */
    fun clearCardOfSms(smsId: Long) =
        store.setSetting(KEY_CARD_OF_SMS, (cardOfSms() - smsId).entries.joinToString("\n") { "${it.key}=${it.value}" })

    /** Undo for [declineCardGroup]: suggest grouping this bank's cards again. */
    fun allowCardGroupSuggestion(bank: String) =
        store.setSetting(KEY_CARD_GROUPS_DECLINED, (lines(KEY_CARD_GROUPS_DECLINED) - bank).joinToString("\n"))

    /** Puts a removed group back exactly as it was (undo for ungroup). */
    fun restoreCardGroup(g: CardGroup) { saveCardGroup(g.id, g.bank, g.cards, g.sharedLimit, g.name) }

    private fun cardOfSms(): Map<Long, String> =
        lines(KEY_CARD_OF_SMS).mapNotNull { l -> l.substringBefore('=').toLongOrNull()?.let { it to l.substringAfter('=') } }.toMap()

    /** Puts a card SMS that printed no card number on one of the owner's cards. */
    fun setCardOfSms(smsId: Long, card: Source) =
        store.setSetting(KEY_CARD_OF_SMS, (cardOfSms() + (smsId to card.last4)).entries.joinToString("\n") { "${it.key}=${it.value}" })

    /** "No" on the suggestion: these cards have separate statements. */

    fun declineCardGroup(bank: String) =
        store.setSetting(KEY_CARD_GROUPS_DECLINED, (lines(KEY_CARD_GROUPS_DECLINED) + bank).joinToString("\n"))

    /** null goes back to the suggested limit. */
    fun setStreakLimit(paise: Long?) = store.setSetting(KEY_STREAK_LIMIT, (paise ?: 0L).toString())

    // Groups are stored one per line: name|label=display;label=display
    private fun groups(): List<FriendGroup> = lines(KEY_GROUPS).mapNotNull { l ->
        val name = l.substringBefore('|').ifBlank { return@mapNotNull null }
        FriendGroup(name, l.substringAfter('|').split(';').filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') })
    }

    private fun clean(s: String) = s.replace(Regex("[|;=\n]"), " ").trim()

    fun saveGroup(g: FriendGroup, replacing: String? = null) {
        val others = groups().filter { it.name != g.name && it.name != replacing }
        store.setSetting(KEY_GROUPS, (others + g).joinToString("\n") { x -> clean(x.name) + "|" + x.members.joinToString(";") { "${clean(it.first)}=${clean(it.second)}" } })
    }

    fun deleteGroup(name: String) = store.setSetting(KEY_GROUPS, lines(KEY_GROUPS).filterNot { it.substringBefore('|') == name }.joinToString("\n"))

    /** null clears it (back to the estimate from SMS). */
    fun setCardLimit(card: Source, paise: Long?) =
        saveKeyed(KEY_CARD_LIMITS, keyed(KEY_CARD_LIMITS).let { if (paise == null) it - key(card) else it + (key(card) to paise) })

    fun setStatementDay(card: Source, day: Int?) =
        saveKeyed(KEY_STATEMENT_DAYS, keyed(KEY_STATEMENT_DAYS).let { if (day == null) it - key(card) else it + (key(card) to day.toLong()) })

    /** [category] null is the whole month; [paise] null or 0 removes the budget. */
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
