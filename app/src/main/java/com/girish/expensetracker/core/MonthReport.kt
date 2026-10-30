package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One salary month, summed up for the Month screen: totals per category with the month before for comparison, and a
 * few figures worth a glance (salary, savings, busiest day, top places). Pure: built from the ledger only.
 */
data class MonthReport(
    val range: ClosedRange<LocalDate>,
    /** Month amount per category (refunds and paybacks are negative). */
    val byCategory: Map<String, Long>,
    /** The month before, over the same number of days when this month is still running. */
    val before: Map<String, Long>,
    val salaryPaise: Long,
    /** When the salary was credited this month (null when no salary SMS fell in it). */
    val salaryDay: LocalDate? = null,
    val payments: Int,
    val biggestDay: Pair<LocalDate, Long>?,
    val topPlaces: List<Pair<String, Long>>,
) {
    val total: Long get() = byCategory.values.sum()
    val totalBefore: Long get() = before.values.sum()
    val days: Int get() = (ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1).toInt()
    val perDay: Long get() = total / days.coerceAtLeast(1)
    val savingsRate: Double? get() = if (salaryPaise > 0) (salaryPaise - total).toDouble() / salaryPaise else null
}

object MonthReports {

    /**
     * [range] is the month to report; [previous] the salary month before it. A running month (one that ends today) is
     * compared with the same number of days of the month before, so the 10th isn't measured against a full month.
     */
    fun of(txns: List<Txn>, range: ClosedRange<LocalDate>, previous: ClosedRange<LocalDate>?, running: Boolean): MonthReport {
        val inMonth = txns.filter { it.day in range }
        val counted = inMonth.filter { Ledger.monthAmount(it) != 0L }
        val beforeRange = previous?.let { p ->
            if (!running) p else {
                val days = ChronoUnit.DAYS.between(range.start, range.endInclusive)
                p.start..minOf(p.endInclusive, p.start.plusDays(days))
            }
        }
        return MonthReport(
            range = range,
            byCategory = Ledger.byCategoryIn(txns, range),
            before = beforeRange?.let { Ledger.byCategoryIn(txns, it) }.orEmpty(),
            salaryPaise = inMonth.filter { it.isSalary }.sumOf { it.amountPaise },
            salaryDay = inMonth.filter { it.isSalary }.maxOfOrNull { it.day },
            payments = counted.count { Ledger.monthAmount(it) > 0 },
            biggestDay = counted.groupBy { it.day }.mapValues { (_, l) -> l.sumOf(Ledger::todayAmount) }.maxByOrNull { it.value }
                ?.takeIf { it.value > 0 }?.toPair(),
            topPlaces = counted.filter { (it.kind == Kind.SPEND || it.kind == Kind.SUBSCRIPTION) && !it.parsed.counterparty.isNullOrBlank() }
                .groupBy { normalizeName(it.parsed.counterparty!!) }
                .map { (_, l) -> l.first().parsed.counterparty!! to l.sumOf(Ledger::monthAmount) }
                .sortedByDescending { it.second }.take(3),
        )
    }
}
