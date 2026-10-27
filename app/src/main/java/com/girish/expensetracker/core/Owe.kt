package com.girish.expensetracker.core

import java.time.LocalDate

/** Money someone still owes the owner: from a loan ("Lent") or their unpaid share of a split. */
data class Debt(val name: String, val label: String?, val leftPaise: Long, val since: LocalDate)

/**
 * Loans and splits, settled against paybacks. When a person who owes money pays the owner, that payment settles the
 * debt (shown as Repaid) instead of reducing spend; their share was never counted as the owner's spend.
 */
object Owe {

    data class Settled(val txns: List<Txn>, val debts: List<Debt>)

    private class Open(val name: String, val label: String?, var left: Long, val since: LocalDate) {
        fun isFor(counterparty: String): Boolean =
            (label != null && (label.equals(counterparty, ignoreCase = true) || People.same(label, counterparty))) ||
                People.same(name, counterparty)
    }

    /** [txns] must be in time order. */
    fun settle(txns: List<Txn>): Settled {
        val open = mutableListOf<Open>()
        val out = txns.map { t ->
            val who = t.parsed.counterparty.orEmpty()
            when {
                t.kind == Kind.LENT -> { open += Open(People.displayName(listOf(who)), who, t.amountPaise, t.day); t }
                t.splitWith.isNotEmpty() -> {
                    t.splitWith.filter { !it.settled && it.sharePaise > 0 }.forEach { open += Open(it.name, it.label, it.sharePaise, t.day) }
                    t
                }
                t.kind == Kind.PERSON_CREDIT && who.isNotBlank() -> {
                    val theirs = open.filter { it.left > 0 && it.isFor(who) }
                    val owed = theirs.sumOf { it.left }
                    if (theirs.isEmpty()) t else {
                        var paid = t.amountPaise
                        for (d in theirs) {
                            val take = minOf(paid, d.left)
                            d.left -= take
                            paid -= take
                            if (paid == 0L) break
                        }
                        // Paying back more than they owe means the payment was (also) for something else: the debt is
                        // cleared, and the payment stays an ordinary payback so the rest still lowers spend.
                        if (t.amountPaise > owed + 100) t else t.copy(kind = Kind.REPAID, category = "Repaid")
                    }
                }
                else -> t
            }
        }
        val debts = open.filter { it.left > 0 }
            .groupBy { (it.label ?: it.name).lowercase() }
            .map { (_, list) -> Debt(list.first().name, list.first().label, list.sumOf { it.left }, list.minOf { it.since }) }
            .sortedByDescending { it.leftPaise }
        return Settled(out, debts)
    }

    /** Equal shares of [totalPaise] among the owner and [others] people; the owner absorbs the rounding. */
    fun equalShare(totalPaise: Long, others: Int): Long = if (others <= 0) 0 else totalPaise / (others + 1)
}
