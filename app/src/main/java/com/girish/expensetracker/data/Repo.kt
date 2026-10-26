package com.girish.expensetracker.data

import com.girish.expensetracker.core.Classifier
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
) {
    val txns: List<Txn> by lazy { rows.map { it.txn } }
    val todayTotal: Long by lazy { Ledger.today(txns, today) }
    val monthTotal: Long by lazy { Ledger.month(txns, today) }
    val cycleStart: LocalDate by lazy { Ledger.cycleStart(today, txns) }
}

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

class Repo(val store: Store) {

    /** True while the first-run import is filling the inbox; detection waits until it is complete. */
    @Volatile var importing = false

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Store an SMS if it comes from a bank transaction sender. Returns true when it was new. */
    fun ingest(sender: String, body: String, receivedAt: Long): Boolean {
        return when (SmsReader.read(sender, body, profile())) {
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

    private fun key(s: Source) = "${s.bank}|${s.last4}|${s.type}"

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
        val dismissedRent = lines(KEY_RENT_DISMISSED)
        val rentPayees = lines(KEY_RENT).mapNotNull { l -> l.split('|').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }

        val rows = mutableListOf<Row>()
        val unparsed = mutableListOf<UnparsedSms>()
        val newSources = linkedMapOf<Source, String>()

        // Older installs may hold the same SMS twice (live + import, slightly different stamps): read it once.
        val lastSeen = HashMap<String, Long>()
        for (sms in store.smsSince(sinceMillis)) {
            val dupKey = sms.sender + "\u0000" + sms.body
            val prev = lastSeen[dupKey]
            lastSeen[dupKey] = sms.receivedAt
            if (prev != null && kotlin.math.abs(sms.receivedAt - prev) < 600_000L) continue
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(sms.receivedAt), zone)
            when (val r = SmsReader.read(sms.sender, sms.body, profile)) {
                is ReadResult.Parsed -> {
                    var t = Classifier.classify(r.parsed, at, profile, rules)
                    val forced = overrides[sms.id]
                    if (forced != null && t.kind == com.girish.expensetracker.core.Kind.SPEND) t = t.copy(category = forced.second, payeeType = forced.first ?: t.payeeType)
                    else if (forced == null && rentPayees.any { (label, typical) -> com.girish.expensetracker.core.RentFinder.isRent(t, label, typical) }) t = t.copy(category = "Rent")
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
            rows += Row(Classifier.classify(parsed, at, profile, rules), null, m.id, null)
        }

        rows.sortBy { it.txn.receivedAt }
        // Match by object identity: two real identical SIPs are equal as data but are separate payments.
        val rowByParsed = java.util.IdentityHashMap<ParsedSms, Row>().apply { rows.forEach { put(it.txn.parsed, it) } }
        val deduped = Dedupe.apply(rows.map { it.txn }).map { kept -> rowByParsed.getValue(kept.parsed).copy(txn = kept) }

        return Snapshot(
            rows = deduped,
            unparsed = unparsed.reversed(),
            newSources = newSources.map { (s, body) -> NewSource(s, body) },
            today = today,
            owner = owner(),
            rentCandidates = com.girish.expensetracker.core.RentFinder.find(deduped.map { it.txn }).filter { it.key !in dismissedRent },
        )
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
