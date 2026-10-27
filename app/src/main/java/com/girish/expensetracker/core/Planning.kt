package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One budget line for the salary month: the whole month (category null) or one category. */
data class BudgetLine(val category: String?, val limitPaise: Long, val spentPaise: Long, val projectedPaise: Long) {
    val share: Double get() = if (limitPaise <= 0) 0.0 else spentPaise.toDouble() / limitPaise
    val overPace: Boolean get() = projectedPaise > limitPaise
}

object Budgets {

    /**
     * Spent so far and where the month is heading. Recurring money (SIPs, subscriptions, lump sums) is counted as it
     * is, not projected; day-to-day spend is extended at its current daily rate to the end of the salary month.
     */
    fun lines(
        txns: List<Txn>,
        cycleStart: LocalDate,
        today: LocalDate,
        totalPaise: Long?,
        perCategory: Map<String, Long>,
    ): List<BudgetLine> {
        val inCycle = txns.filter { it.day in cycleStart..today }
        val cycleDays = ChronoUnit.DAYS.between(cycleStart, cycleStart.plusMonths(1)).toInt()
        val elapsed = (ChronoUnit.DAYS.between(cycleStart, today).toInt() + 1).coerceIn(1, cycleDays)

        fun line(category: String?, limit: Long): BudgetLine {
            val mine = if (category == null) inCycle else inCycle.filter { it.category == category }
            val spent = mine.sumOf(Ledger::monthAmount)
            val daily = mine.sumOf(Ledger::paceAmount)
            val projected = spent - daily + daily * cycleDays / elapsed
            return BudgetLine(category, limit, spent, projected)
        }
        return buildList {
            totalPaise?.takeIf { it > 0 }?.let { add(line(null, it)) }
            perCategory.filterValues { it > 0 }.toSortedMap().forEach { (cat, limit) -> add(line(cat, limit)) }
        }
    }
}

/** A charge that repeats: a mandate/autopay, or the same shop billing a similar amount every month or year. */
data class Subscription(
    val name: String,
    val category: String,
    val source: Source,
    val lastPaise: Long,
    val previousPaise: Long?,
    val lastDay: LocalDate,
    val nextDay: LocalDate,
    val yearly: Boolean,
    val payments: Int,
) {
    /** The latest charge is more than 2% above the one before. */
    val priceUp: Boolean get() = previousPaise != null && lastPaise > previousPaise * 102 / 100
    val monthlyPaise: Long get() = if (yearly) lastPaise / 12 else lastPaise
}

object Subscriptions {

    private val everyday = setOf("Food", "Groceries", "Fuel", "Transport", "Travel", "Shopping", "People", "Cash", "Personal care", "Rent")

    fun find(txns: List<Txn>, today: LocalDate): List<Subscription> {
        val charges = txns.filter {
            (it.kind == Kind.SUBSCRIPTION || it.kind == Kind.SPEND) && it.payeeType != PayeeType.PERSON && !it.parsed.counterparty.isNullOrBlank()
        }
        return charges.groupBy { normalizeName(it.parsed.counterparty!!).take(14) }.filterKeys { it.length >= 3 }.mapNotNull { (_, list) ->
            val sorted = list.sortedBy { it.day }
            val last = sorted.last()
            val flagged = sorted.any { it.kind == Kind.SUBSCRIPTION || it.category == "Subscriptions" }
            val gaps = sorted.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.day, b.day) }.filter { it > 3 }
            val gap = gaps.sorted().getOrNull(gaps.size / 2)
            val monthly = gap != null && gap in 25..35
            val yearly = gap != null && gap in 350..380
            // A mandate or a subscription-category charge counts from its first payment. Anything else must look like a
            // plan: the same price (within 2%) at least three times on a monthly or yearly rhythm, not much more often
            // than monthly, and not everyday spending (a favourite restaurant or a weekly grocery run is not a subscription).
            val samePrice = sorted.takeLast(3).all { abs(it.amountPaise - last.amountPaise) <= last.amountPaise / 50 }
            val notTooOften = sorted.size <= ChronoUnit.DAYS.between(sorted.first().day, last.day) / 20 + 2
            val recurring = (monthly || yearly) && samePrice && sorted.size >= 3 && notTooOften && last.category !in everyday
            if (!flagged && !recurring) return@mapNotNull null
            val next = last.day.plusDays(if (yearly) 365 else gap?.takeIf { monthly } ?: 30)
            // Gone quiet for more than one and a half cycles: probably cancelled.
            if (today.isAfter(next.plusDays(if (yearly) 60 else 15))) return@mapNotNull null
            Subscription(
                name = last.parsed.counterparty!!,
                category = last.category,
                source = last.parsed.source,
                lastPaise = last.amountPaise,
                previousPaise = sorted.dropLast(1).lastOrNull()?.amountPaise,
                lastDay = last.day,
                nextDay = next,
                yearly = yearly,
                payments = sorted.size,
            )
        }.sortedBy { it.nextDay }
    }
}

/** Something worth a notification. [key] is stable so the same alert is only ever posted once. */
data class Alert(val key: String, val title: String, val text: String)

object Alerts {

    fun credit(cards: List<CardHealth>, today: LocalDate, label: (Source) -> String, money: (Long) -> String, date: (LocalDate) -> String): List<Alert> =
        cards.flatMap { c ->
            buildList {
                val u = c.utilization
                if (u != null && u >= Credit.HEALTHY && c.nextStatement != null) {
                    add(
                        Alert(
                            "util30|${c.card}|${c.nextStatement}",
                            "${label(c.card)} is at ${(u * 100).toInt()}% of its limit",
                            "Pay ${money(c.payToReach(Credit.HEALTHY))} before ${date(c.nextStatement)} to report under 30%. Under 10% is best.",
                        ),
                    )
                }
                val s = c.statement
                val left = c.billLeftPaise
                if (s?.dueDate != null && left != null && left > 0) {
                    val days = ChronoUnit.DAYS.between(today, s.dueDate)
                    val stage = when {
                        days == 0L -> "today"
                        days in 1..3 -> "soon"
                        else -> null
                    }
                    if (stage != null) {
                        val min = c.minLeftPaise?.takeIf { it > 0 }?.let { " (minimum ${money(it)})" }.orEmpty()
                        add(
                            Alert(
                                "due|${c.card}|${s.dueDate}|$stage",
                                if (stage == "today") "${label(c.card)} bill is due today" else "${label(c.card)} bill due ${date(s.dueDate)}",
                                "${money(left)} left to pay$min.",
                            ),
                        )
                    }
                }
            }
        }

    /** A subscription that just charged more than last time (within the past week, so old rises don't alert). */
    fun subscriptions(subs: List<Subscription>, today: LocalDate, money: (Long) -> String): List<Alert> =
        subs.filter { it.priceUp && !it.lastDay.isBefore(today.minusDays(7)) }.map { s ->
            Alert(
                "price|${normalizeName(s.name)}|${s.lastDay}",
                "${s.name} went up to ${money(s.lastPaise)}",
                "Was ${money(s.previousPaise!!)}. That's ${money(if (s.yearly) s.lastPaise - s.previousPaise else (s.lastPaise - s.previousPaise) * 12)} more a year.",
            )
        }

    fun budgets(lines: List<BudgetLine>, cycleStart: LocalDate, money: (Long) -> String): List<Alert> = lines.flatMap { b ->
        val what = b.category ?: "This month"
        val id = "${b.category ?: "ALL"}|$cycleStart"
        buildList {
            when {
                b.share >= 1.0 -> add(Alert("budget100|$id", "$what is over budget", "${money(b.spentPaise)} spent of ${money(b.limitPaise)}."))
                b.share >= 0.8 -> add(Alert("budget80|$id", "$what is at ${(b.share * 100).toInt()}% of budget", "${money(b.limitPaise - b.spentPaise)} left for the rest of the salary month."))
                b.overPace && b.share >= 0.5 -> add(Alert("pace|$id", "$what is ahead of pace", "Heading for ${money(b.projectedPaise)} against a budget of ${money(b.limitPaise)}."))
            }
        }
    }
}
