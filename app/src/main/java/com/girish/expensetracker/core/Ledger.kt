package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * One real payment can arrive as 2-3 SMS (card spend + AutoPay success, ACH UPDATE + PAYMENT ALERT).
 * Those are collapsed. Two SMS of the SAME layout are always two real payments (two identical SIPs on one day both count).
 */
object Dedupe {

    private val families: List<Set<Template>> = listOf(
        setOf(Template.HDFC_CARD_SPENT, Template.HDFC_CARD_AMOUNT_SPENT, Template.HDFC_AUTOPAY_SUCCESS),
        setOf(Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT),
        // HDFC confirms one card bill payment twice ("PAYMENT ... RECEIVED" and "Payment ... was credited").
        setOf(Template.HDFC_CARD_PAYMENT_RECEIVED, Template.HDFC_CARD_PAYMENT_CREDITED),
        // SBI Card confirms one bill payment twice too: "We have received payment of Rs.X via UPI ... credited to your
        // SBI Credit Card" (no card number, read generically) and "Dear SBI Cardholder, payment of Rs. X ... processed".
        setOf(Template.SBI_CARD_PAYMENT, Template.GENERIC_CARD_PAYMENT),
    )

    // Which SMS of a family describes the payment best.
    private val preference = listOf(
        Template.HDFC_AUTOPAY_SUCCESS, Template.HDFC_CARD_AMOUNT_SPENT, Template.HDFC_CARD_SPENT,
        Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT,
        Template.SBI_CARD_PAYMENT, Template.GENERIC_CARD_PAYMENT,
    )

    // A card payment without a card number ("?", ask which card) and SBI's bank-wide one ("*") are the same payment.
    private fun familySource(s: Source): Source =
        if (s.type == SourceType.CARD && s.last4 == UNASSIGNED) s.copy(last4 = "*") else s

    private class Entry(val index: Int, var txn: Txn, val templates: MutableSet<Template>)

    private data class RefKey(val template: Template, val ref: String)
    private data class FamilyKey(val family: Int, val source: Source, val amountPaise: Long, val day: LocalDate)

    /**
     * O(n): hash maps instead of scanning everything kept so far. Same result as the original scan, in the same order:
     * a family partner is the earliest kept SMS of the family that does not already have this layout.
     */
    fun apply(txns: List<Txn>): List<Txn> {
        val kept = ArrayList<Entry>(txns.size)
        // (layout, bank reference) of every kept payment's current SMS, counted (two kept payments may share one).
        val refs = HashMap<RefKey, Int>()
        val byFamily = HashMap<FamilyKey, MutableList<Entry>>()
        fun countRef(t: Txn, delta: Int) {
            val ref = t.parsed.ref ?: return
            refs.merge(RefKey(t.parsed.template, ref), delta) { a, b -> (a + b).takeIf { it != 0 } }
        }
        for (t in txns) {
            val p = t.parsed
            // Same SMS delivered twice (same layout, same bank reference).
            if (p.ref != null && refs.containsKey(RefKey(p.template, p.ref))) continue

            val familyIndex = familyOf[p.template]
            val fKey = familyIndex?.let { FamilyKey(it, familySource(p.source), t.amountPaise, t.day) }
            val partner = fKey?.let { k -> byFamily[k]?.firstOrNull { p.template !in it.templates } }
            if (partner == null) {
                val e = Entry(kept.size, t, mutableSetOf(p.template))
                kept += e
                countRef(t, 1)
                if (fKey != null) byFamily.getOrPut(fKey) { mutableListOf() } += e
            } else {
                partner.templates += p.template
                countRef(partner.txn, -1)
                partner.txn = better(partner.txn, t)
                countRef(partner.txn, 1)
                // The kept SMS can take an earlier time and so, without a printed date, an earlier day: file it there,
                // still in kept order, so later partners are found exactly as a full scan would find them.
                val newKey = fKey!!.copy(day = partner.txn.day)
                if (newKey != fKey) {
                    byFamily[fKey]?.remove(partner)
                    val list = byFamily.getOrPut(newKey) { mutableListOf() }
                    val at = list.indexOfFirst { it.index > partner.index }
                    if (at < 0) list += partner else list.add(at, partner)
                }
            }
        }
        return kept.map { it.txn }
    }

    private val familyOf: Map<Template, Int> = buildMap { families.forEachIndexed { i, f -> f.forEach { put(it, i) } } }

    private fun better(a: Txn, b: Txn): Txn {
        val winner = if (preference.indexOf(b.parsed.template) < preference.indexOf(a.parsed.template)) b else a
        val earliest = minOf(a.receivedAt, b.receivedAt)
        return winner.copy(receivedAt = earliest)
    }
}

/** The two notification numbers and the in-app breakdowns. Amounts are in paise; positive = money spent. */
object Ledger {

    /** The owner's usual salary day (from settings); only a hint now that months are calendar months. */
    @Volatile var salaryDay = 28

    /** Today X: spends, subscriptions and SIPs count; reimbursements and refunds reduce it; lump-sum investments do not count. */
    fun todayAmount(t: Txn): Long = when (t.kind) {
        Kind.SPEND, Kind.SUBSCRIPTION, Kind.INVESTMENT_SIP -> t.ownPaise
        Kind.PERSON_CREDIT, Kind.REFUND -> -t.amountPaise
        Kind.INVESTMENT_LUMPSUM, Kind.TRANSFER, Kind.INCOME, Kind.LENT, Kind.REPAID -> 0
    }

    /** Month Y: same as today, plus lump-sum investments. */
    fun monthAmount(t: Txn): Long = when (t.kind) {
        Kind.INVESTMENT_LUMPSUM -> t.amountPaise
        else -> todayAmount(t)
    }

    fun today(txns: List<Txn>, day: LocalDate): Long =
        txns.filter { it.day == day }.sumOf(::todayAmount)

    fun month(txns: List<Txn>, day: LocalDate): Long {
        val start = cycleStart(day, txns)
        return txns.filter { it.day in start..day }.sumOf(::monthAmount)
    }

    /**
     * A month is the calendar month, 1st to last, everywhere (budgets, pace, forecast, charts). Salary-to-salary
     * spending lives only in [salaryPeriods]. [txns] is kept so callers need not change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun cycleStart(day: LocalDate, txns: List<Txn> = emptyList()): LocalDate = day.withDayOfMonth(1)

    /** The month before the one starting on [start]. */
    @Suppress("UNUSED_PARAMETER")
    fun previousCycleStart(start: LocalDate, txns: List<Txn> = emptyList()): LocalDate = start.withDayOfMonth(1).minusMonths(1)

    /** Up to [n] calendar months, newest first, each as 1st..last (the newest ends on [day]). */
    @Suppress("UNUSED_PARAMETER")
    fun cycles(day: LocalDate, txns: List<Txn>, n: Int): List<ClosedRange<LocalDate>> =
        (0 until n).map { k -> monthRange(day, k) }

    /** Every calendar month from the one holding [day] back to the one holding [first], newest first. */
    @Suppress("UNUSED_PARAMETER")
    fun cyclesSince(day: LocalDate, txns: List<Txn>, first: LocalDate?, max: Int = 600): List<ClosedRange<LocalDate>> {
        val firstMonth = first?.let { YearMonth.from(it) }
        val out = mutableListOf<ClosedRange<LocalDate>>()
        var k = 0
        while (out.size < max) {
            val r = monthRange(day, k++)
            out += r
            if (firstMonth == null || YearMonth.from(r.start) <= firstMonth) break
        }
        return out
    }

    /** The calendar month [back] months before the one holding [day]; the running month ends on [day]. */
    private fun monthRange(day: LocalDate, back: Int): ClosedRange<LocalDate> {
        val ym = YearMonth.from(day).minusMonths(back.toLong())
        return ym.atDay(1)..(if (back == 0) day else ym.atEndOfMonth())
    }

    /** A salary credit this soon after the previous one is an extra payout (joining pay, bonus), not a new period. */
    const val EXTRA_PAYOUT_DAYS = 25

    /**
     * Salary to salary, oldest first: each salary credit until the day before the next (the last until [today]).
     * A credit less than [EXTRA_PAYOUT_DAYS] after the previous one joins the period as an extra payout.
     */
    fun salaryPeriods(txns: List<Txn>, today: LocalDate): List<SalaryPeriod> {
        val salaries = txns.filter { it.isSalary }.sortedWith(compareBy({ it.day }, { it.receivedAt }))
        val groups = mutableListOf<MutableList<Txn>>()
        var prev: LocalDate? = null
        for (s in salaries) {
            val p = prev
            if (p == null || groups.isEmpty() || java.time.temporal.ChronoUnit.DAYS.between(p, s.day) >= EXTRA_PAYOUT_DAYS) groups += mutableListOf(s)
            else groups.last() += s
            prev = s.day
        }
        return groups.mapIndexed { i, g ->
            val end = groups.getOrNull(i + 1)?.first()?.day?.minusDays(1) ?: maxOf(today, g.first().day)
            SalaryPeriod(g.first(), g.drop(1), g.first().day..end)
        }
    }

    /** Month amount per category inside [range]. */
    fun byCategoryIn(txns: List<Txn>, range: ClosedRange<LocalDate>): Map<String, Long> =
        txns.filter { it.day in range && monthAmount(it) != 0L }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf(::monthAmount) }

    /** Recurring money (SIPs, lump sums, subscriptions) is left out of the pace line so the 2nd of the month doesn't spike it. */
    fun paceAmount(t: Txn): Long = when (t.kind) {
        Kind.SUBSCRIPTION, Kind.INVESTMENT_SIP, Kind.INVESTMENT_LUMPSUM -> 0
        else -> monthAmount(t)
    }

    /** Cumulative day-to-day spend from [start] for [days] days: element i is the total through day start+i. */
    fun pace(txns: List<Txn>, start: LocalDate, days: Int): LongArray {
        val perDay = LongArray(days)
        for (t in txns) {
            val i = java.time.temporal.ChronoUnit.DAYS.between(start, t.day).toInt()
            if (i in 0 until days) perDay[i] += paceAmount(t)
        }
        for (i in 1 until days) perDay[i] += perDay[i - 1]
        return perDay
    }

    /** In-app pie: month amount per category (only categories that count toward Y). */
    fun byCategory(txns: List<Txn>, day: LocalDate): Map<String, Long> {
        val start = cycleStart(day, txns)
        return txns.filter { it.day in start..day && monthAmount(it) != 0L }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf(::monthAmount) }
    }

    /** In-app cards/accounts view: gross spend per card or account this cycle (SBI primary and add-on stay separate). */
    fun bySource(txns: List<Txn>, day: LocalDate): Map<Source, Long> {
        val start = cycleStart(day, txns)
        return txns.filter { it.day in start..day && it.parsed.direction == Direction.DEBIT && monthAmount(it) > 0 }
            .groupBy { it.parsed.source }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }
    }
}

/** One salary and what it paid for, until the next salary. [extras] are payouts credited inside it (bonus, joining pay). */
data class SalaryPeriod(val salary: Txn, val extras: List<Txn>, val range: ClosedRange<LocalDate>) {
    val start: LocalDate get() = range.start
    val salaryPaise: Long get() = salary.amountPaise + extras.sumOf { it.amountPaise }
    fun contains(t: Txn): Boolean = t === salary || extras.any { it === t }
}
