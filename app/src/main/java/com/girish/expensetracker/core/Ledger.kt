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
    )

    // Which SMS of a family describes the payment best.
    private val preference = listOf(
        Template.HDFC_AUTOPAY_SUCCESS, Template.HDFC_CARD_AMOUNT_SPENT, Template.HDFC_CARD_SPENT,
        Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT,
    )

    private class Entry(var txn: Txn, val templates: MutableSet<Template>)

    fun apply(txns: List<Txn>): List<Txn> {
        val kept = mutableListOf<Entry>()
        for (t in txns) {
            val p = t.parsed
            // Same SMS delivered twice (same layout, same bank reference).
            if (p.ref != null && kept.any { it.txn.parsed.template == p.template && it.txn.parsed.ref == p.ref }) continue

            val family = families.firstOrNull { p.template in it }
            val partner = family?.let { fam ->
                kept.firstOrNull { e ->
                    e.txn.parsed.template in fam && p.template !in e.templates &&
                        e.txn.parsed.source == p.source && e.txn.amountPaise == t.amountPaise && e.txn.day == t.day
                }
            }
            if (partner == null) {
                kept += Entry(t, mutableSetOf(p.template))
            } else {
                partner.templates += p.template
                partner.txn = better(partner.txn, t)
            }
        }
        return kept.map { it.txn }
    }

    private fun better(a: Txn, b: Txn): Txn {
        val winner = if (preference.indexOf(b.parsed.template) < preference.indexOf(a.parsed.template)) b else a
        val earliest = minOf(a.receivedAt, b.receivedAt)
        return winner.copy(receivedAt = earliest)
    }
}

/** The two notification numbers and the in-app breakdowns. Amounts are in paise; positive = money spent. */
object Ledger {

    /** Fallback start of the salary month when no salary SMS is found; set from the owner's settings at startup. */
    @Volatile var salaryDay = 28

    /** Today X: spends, subscriptions and SIPs count; reimbursements and refunds reduce it; lump-sum investments do not count. */
    fun todayAmount(t: Txn): Long = when (t.kind) {
        Kind.SPEND, Kind.SUBSCRIPTION, Kind.INVESTMENT_SIP -> t.amountPaise
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
     * Month runs salary to salary. Normally from the 28th; if the salary SMS arrived up to 5 days early
     * (weekend/holiday) or later than the 28th, the cycle starts on the actual salary date.
     */
    fun cycleStart(day: LocalDate, txns: List<Txn>): LocalDate {
        val fallback = lastSalaryDayOnOrBefore(day)
        val salary = txns.filter { it.isSalary && !it.day.isAfter(day) }.maxOfOrNull { it.day }
        return if (salary != null && !salary.isBefore(fallback.minusDays(5))) salary else fallback
    }

    private fun lastSalaryDayOnOrBefore(day: LocalDate): LocalDate {
        val thisMonth = clampedSalaryDay(YearMonth.from(day))
        return if (!thisMonth.isAfter(day)) thisMonth else clampedSalaryDay(YearMonth.from(day).minusMonths(1))
    }

    private fun clampedSalaryDay(ym: YearMonth): LocalDate = ym.atDay(minOf(salaryDay, ym.lengthOfMonth()))

    /** The salary month that ends where [start] begins. */
    fun previousCycleStart(start: LocalDate, txns: List<Txn>): LocalDate = cycleStart(start.minusDays(1), txns)

    /** Up to [n] salary months, newest first, each as start..end (the newest ends on [day]). */
    fun cycles(day: LocalDate, txns: List<Txn>, n: Int): List<ClosedRange<LocalDate>> {
        val out = mutableListOf<ClosedRange<LocalDate>>()
        var end = day
        repeat(n) {
            val start = cycleStart(end, txns)
            out += start..end
            end = start.minusDays(1)
        }
        return out
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
