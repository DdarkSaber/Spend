package com.girish.expensetracker.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A cost that comes every salary month (rent, maid, electricity, SIP, subscriptions): learned, never typed in. */
data class FixedCost(val key: String, val name: String, val category: String, val typicalPaise: Long, val usualDay: Int)

object FixedCosts {

    /** Categories where a monthly payment to the same payee is a bill, not a habit (food or shopping repeats by chance). */
    private val billLike = setOf("Rent", "Bills", "Bills & EMI", "Household", "Subscriptions", "Investment", "Education", "People", "Medical")

    fun key(t: Txn): String = normalizeName(t.parsed.counterparty ?: t.parsed.narration ?: "").take(14)

    /**
     * Learned from the last three finished salary months: a payee paid in at least two of them, about once a month,
     * a similar amount each time (within 25%) and at least ₹300, in a bill-like category or as a SIP / subscription.
     */
    fun find(txns: List<Txn>, finished: List<ClosedRange<LocalDate>>): List<FixedCost> {
        val months = finished.take(3)
        if (months.size < 2) return emptyList()
        val candidates = txns.filter { t ->
            Ledger.monthAmount(t) >= 300_00 && months.any { t.day in it } && key(t).length >= 3 &&
                (t.kind == Kind.INVESTMENT_SIP || t.kind == Kind.SUBSCRIPTION || t.category in billLike)
        }
        // One person often appears under two names (bank name one month, UPI ID the next): merge them, like People does.
        val personKeys = People.group(candidates.filter { it.payeeType == PayeeType.PERSON }.mapNotNull { it.parsed.counterparty }.distinct())
            .flatMap { g -> g.map { it to normalizeName(g.first()).take(14) } }.toMap()
        fun groupKey(t: Txn) = t.parsed.counterparty?.let(personKeys::get) ?: key(t)
        return candidates.groupBy(::groupKey).mapNotNull { (k, list) ->
            val perMonth = months.map { r -> list.filter { it.day in r } }
            val hit = perMonth.filter { it.isNotEmpty() }
            // Several SIPs on one day are fine; a payee paid five or more times a month is a habit, not a bill.
            if (hit.size < 2 || hit.any { it.size > 4 }) return@mapNotNull null
            // A person's month is their biggest payment (rent), not that plus small splits to the same flatmate.
            val person = list.first().payeeType == PayeeType.PERSON
            val all = hit.map { m -> if (person) m.maxOf(Ledger::monthAmount) else m.sumOf(Ledger::monthAmount) }
            // A short month (salary came early) can hold only a small split to that person: it says nothing about rent.
            val amounts = (if (person) all.filter { it >= all.max() / 2 } else all).sorted()
            if (amounts.size < 2) return@mapNotNull null
            val typical = amounts[amounts.size / 2]
            if (amounts.any { abs(it - typical) > typical / 4 }) return@mapNotNull null
            val main = hit.map { m -> m.maxBy(Ledger::monthAmount) }
            val last = main.maxBy { it.day }
            FixedCost(k, last.parsed.counterparty ?: last.category, last.category, typical, main.map { it.day.dayOfMonth }.sorted()[main.size / 2])
        }.sortedByDescending { it.typicalPaise }
    }
}

/** Where the running salary month is heading: fixed costs (paid and still to come) plus everyday spending at its pace. */
data class Forecast(
    val totalPaise: Long,
    val fixedPaise: Long,
    val stillToCome: List<FixedCost>,
    val everydaySoFar: Long,
    val perDay: Long,
    val daysLeft: Int,
) {
    val toComePaise: Long get() = stillToCome.sumOf { it.typicalPaise }
}

object Forecasts {

    fun of(txns: List<Txn>, cycleStart: LocalDate, today: LocalDate, fixed: List<FixedCost>): Forecast {
        val keys = fixed.associateBy { it.key }
        val end = cycleStart.plusMonths(1).minusDays(1)
        val inCycle = txns.filter { it.day in cycleStart..today }
        // A fixed cost to a person is matched by name across IDs, and only a payment of about the usual size counts
        // (a ₹545 split to the landlord-flatmate is everyday spending, not this month's rent).
        fun fixedOf(t: Txn): FixedCost? = fixed.firstOrNull { f ->
            val same = FixedCosts.key(t) == f.key || (t.payeeType == PayeeType.PERSON && t.parsed.counterparty?.let { People.same(it, f.name) } == true)
            same && Ledger.monthAmount(t) >= f.typicalPaise / 2
        }
        val fixedPaid = inCycle.filter { fixedOf(it) != null }
        val paidKeys = fixedPaid.mapNotNull { fixedOf(it)?.key }.toSet()
        val toCome = fixed.filter { it.key !in paidKeys }
        // Everyday spending: what's left after fixed costs and lump-sum investments (those don't repeat by the day).
        val everyday = inCycle.filter { fixedOf(it) == null && it.kind != Kind.INVESTMENT_LUMPSUM }.sumOf(Ledger::monthAmount).coerceAtLeast(0)
        val elapsed = (ChronoUnit.DAYS.between(cycleStart, today) + 1).toInt().coerceAtLeast(1)
        val left = ChronoUnit.DAYS.between(today, end).toInt().coerceAtLeast(0)
        val perDay = everyday / elapsed
        val lumps = inCycle.filter { it.kind == Kind.INVESTMENT_LUMPSUM }.sumOf(Ledger::monthAmount)
        val fixedPaidSum = fixedPaid.sumOf(Ledger::monthAmount)
        return Forecast(
            totalPaise = fixedPaidSum + toCome.sumOf { it.typicalPaise } + everyday + perDay * left + lumps,
            fixedPaise = fixedPaidSum + toCome.sumOf { it.typicalPaise },
            stillToCome = toCome,
            everydaySoFar = everyday,
            perDay = perDay,
            daysLeft = left,
        )
    }
}

/** Everyday spending (fixed costs and investments left out) by day, weekday and time of day. */
data class HabitsReport(
    val perDay: Map<LocalDate, Long>,
    val weekdayAvg: Long,
    val weekendAvg: Long,
    /** Morning, afternoon, evening, night: share of everyday spend. */
    val byTime: List<Pair<String, Long>>,
    val busiestWeekday: DayOfWeek?,
    val from: LocalDate,
    val to: LocalDate,
)

object Habits {

    fun everyday(t: Txn, fixedKeys: Set<String>) =
        (t.kind == Kind.SPEND || t.kind == Kind.SUBSCRIPTION) && FixedCosts.key(t) !in fixedKeys && t.category != "Rent"

    fun of(txns: List<Txn>, fixed: List<FixedCost>, today: LocalDate, days: Long = 84): HabitsReport {
        val from = today.minusDays(days - 1)
        val keys = fixed.map { it.key }.toSet()
        val spends = txns.filter { it.day in from..today && everyday(it, keys) }
        val perDay = spends.groupBy { it.day }.mapValues { (_, l) -> l.sumOf(Ledger::todayAmount) }
        val allDays = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        fun avg(pick: (LocalDate) -> Boolean) = allDays.filter(pick).let { d -> if (d.isEmpty()) 0L else d.sumOf { perDay[it] ?: 0L } / d.size }
        val weekend = { d: LocalDate -> d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY }
        val buckets = listOf("Morning" to 5..11, "Afternoon" to 12..16, "Evening" to 17..20, "Night" to 21..28)
        val byTime = buckets.map { (name, hours) ->
            name to spends.filter { val h = it.receivedAt.hour.let { x -> if (x < 5) x + 24 else x }; h in hours }.sumOf(Ledger::todayAmount)
        }
        val busiest = allDays.groupBy { it.dayOfWeek }.mapValues { (_, d) -> d.sumOf { perDay[it] ?: 0L } / d.size }.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
        return HabitsReport(perDay, avg { !weekend(it) }, avg(weekend), byTime, busiest, from, today)
    }
}

/** Days in a row with everyday spending at or under a daily limit. */
data class Streak(val limitPaise: Long, val current: Int, val best: Int, val todayPaise: Long)

object Streaks {

    /** A sensible first limit: the typical everyday spending day over the last 60 days, rounded up to ₹100. */
    fun suggest(perDay: Map<LocalDate, Long>, today: LocalDate): Long {
        val days = (0L until 60L).map { perDay[today.minusDays(it)] ?: 0L }.sorted()
        val median = days[days.size / 2].coerceAtLeast(100_00)
        return ((median + 99_99) / 100_00) * 100_00
    }

    /** Today counts once it's under the limit; a day over the limit breaks the run. */
    fun of(perDay: Map<LocalDate, Long>, limit: Long, today: LocalDate, since: LocalDate): Streak {
        var current = 0
        var d = today
        while (!d.isBefore(since) && (perDay[d] ?: 0L) <= limit) { current++; d = d.minusDays(1) }
        var best = 0
        var run = 0
        var x = since
        while (!x.isAfter(today)) {
            if ((perDay[x] ?: 0L) <= limit) { run++; best = maxOf(best, run) } else run = 0
            x = x.plusDays(1)
        }
        return Streak(limit, current, best, perDay[today] ?: 0L)
    }
}

/** A recurring charge that has only just started: a new autopay, or a tiny charge that usually means a free trial. */
data class NewRecurring(val name: String, val amountPaise: Long, val day: LocalDate, val trial: Boolean)

object NewRecurrings {

    fun find(txns: List<Txn>, today: LocalDate): List<NewRecurring> {
        val charges = txns.filter { (it.kind == Kind.SPEND || it.kind == Kind.SUBSCRIPTION) && !it.parsed.counterparty.isNullOrBlank() }
        val firstSeen = charges.groupBy { normalizeName(it.parsed.counterparty!!) }.mapValues { (_, l) -> l.minOf { it.day } }
        return charges.filter { t ->
            val new = firstSeen[normalizeName(t.parsed.counterparty!!)]?.let { !it.isBefore(today.minusDays(30)) } == true
            val tiny = t.amountPaise in 1..10_00 && t.payeeType != PayeeType.PERSON
            new && t.day >= today.minusDays(30) && (tiny || t.kind == Kind.SUBSCRIPTION)
        }.distinctBy { normalizeName(it.parsed.counterparty!!) }
            .map { NewRecurring(it.parsed.counterparty!!, it.amountPaise, it.day, trial = it.amountPaise <= 10_00) }
            .sortedByDescending { it.day }
    }
}
