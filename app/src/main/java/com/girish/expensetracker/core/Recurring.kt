package com.girish.expensetracker.core

import java.time.YearMonth
import kotlin.math.abs

/** A person paid a similar large amount once a month: very likely rent (or a rent share to a flatmate). */
data class RentCandidate(
    val key: String,
    val name: String,
    val labels: List<String>,
    val typicalPaise: Long,
    val payments: List<Txn>,
)

object RentFinder {

    /** Smaller monthly transfers are usually splits, not rent. */
    const val MIN_RENT_PAISE = 5_000_00L
    private const val TOLERANCE = 0.15

    fun find(txns: List<Txn>): List<RentCandidate> {
        val big = txns.filter {
            it.kind == Kind.SPEND && it.payeeType == PayeeType.PERSON && it.category != "Rent" &&
                it.amountPaise >= MIN_RENT_PAISE && !it.parsed.counterparty.isNullOrBlank()
        }
        return People.group(big.map { it.parsed.counterparty!! }.distinct()).mapNotNull { group ->
            val set = group.toSet()
            val payments = big.filter { it.parsed.counterparty in set }
            val sorted = payments.map { it.amountPaise }.sorted()
            val median = sorted[sorted.size / 2]
            val close = payments.filter { abs(it.amountPaise - median) <= median * TOLERANCE }
            val months = close.map { YearMonth.from(it.day) }
            // Monthly: at least two different months, and not several such payments in the same month.
            if (months.distinct().size < 2 || close.size > months.distinct().size + 1) return@mapNotNull null
            val name = People.displayName(group)
            RentCandidate(People.key(name), name, group, median, close.sortedBy { it.day })
        }
    }

    /** Is this payment the rent of a payee the owner confirmed (similar size, same person)? */
    fun isRent(t: Txn, payeeLabel: String, typicalPaise: Long): Boolean =
        t.kind == Kind.SPEND && !t.parsed.counterparty.isNullOrBlank() &&
            People.same(t.parsed.counterparty!!, payeeLabel) &&
            abs(t.amountPaise - typicalPaise) <= typicalPaise * 0.3
}
