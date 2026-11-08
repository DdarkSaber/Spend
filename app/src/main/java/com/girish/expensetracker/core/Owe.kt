package com.girish.expensetracker.core

import java.time.LocalDate

/** Money between the owner and one person: what they still owe (loans, split shares) or what the owner owes them. */
data class Debt(val name: String, val label: String?, val leftPaise: Long, val since: LocalDate)

/**
 * A two-way ledger per person, netted. They owe the owner from loans ("Lent") and split shares; the owner owes them
 * from "They paid for me" entries. Both sides of one person cancel automatically, so each person ends up with one
 * balance. Paybacks settle debts instead of counting as spend or money in:
 * - their payment to the owner settles what they owe, oldest first (partial, bulk and overpay as before: an overpay
 *   clears the debt and stays ordinary money in);
 * - the owner's payment to them settles what the owner owes, oldest first; that part is not spend, any excess is an
 *   ordinary payment (or a loan, when marked Lent).
 */
object Owe {

    data class Settled(val txns: List<Txn>, val debts: List<Debt>, val iOwe: List<Debt> = emptyList())

    private class Open(val name: String, val label: String?, var left: Long, val since: LocalDate) {
        fun isFor(counterparty: String): Boolean =
            (label != null && (label.equals(counterparty, ignoreCase = true) || People.same(label, counterparty))) ||
                People.same(name, counterparty)
        fun samePerson(o: Open): Boolean =
            (o.label != null && isFor(o.label)) || isFor(o.name)
    }

    /** Takes up to [amount] from [items] that [match], oldest first; returns how much was taken. */
    private fun take(items: List<Open>, amount: Long, match: (Open) -> Boolean): Long {
        var rest = amount
        for (d in items) {
            if (rest == 0L) break
            if (d.left <= 0 || !match(d)) continue
            val x = minOf(rest, d.left)
            d.left -= x
            rest -= x
        }
        return amount - rest
    }

    /** Adds [item] to [side], first cancelling it against the same person's items on [other]. */
    private fun add(side: MutableList<Open>, other: List<Open>, item: Open) {
        item.left -= take(other, item.left) { it.samePerson(item) }
        if (item.left > 0) side += item
    }

    /** [txns] must be in time order. */
    fun settle(txns: List<Txn>): Settled {
        val theyOwe = mutableListOf<Open>()
        val iOwe = mutableListOf<Open>()
        val out = txns.map { t ->
            val who = t.parsed.counterparty.orEmpty()
            val toPerson = t.parsed.direction == Direction.DEBIT && who.isNotBlank() && t.payeeType == PayeeType.PERSON &&
                t.splitWith.isEmpty() && t.owedTo == null
            when {
                t.owedTo != null -> {
                    add(iOwe, theyOwe, Open(t.owedTo.name, t.owedTo.label, t.owedTo.sharePaise, t.day))
                    t
                }
                t.kind == Kind.LENT -> {
                    val settled = take(iOwe, t.amountPaise) { it.isFor(who) }
                    if (settled == t.amountPaise) paidBack(t)
                    else { add(theyOwe, iOwe, Open(People.displayName(listOf(who)), who, t.amountPaise - settled, t.day)); t.copy(settlesPaise = settled) }
                }
                t.splitWith.isNotEmpty() -> {
                    t.splitWith.filter { !it.settled && it.sharePaise > 0 }.forEach { add(theyOwe, iOwe, Open(it.name, it.label, it.sharePaise, t.day)) }
                    t
                }
                t.kind == Kind.SPEND && toPerson -> {
                    val settled = take(iOwe, t.amountPaise) { it.isFor(who) }
                    when (settled) {
                        0L -> t
                        t.amountPaise -> paidBack(t)
                        else -> t.copy(settlesPaise = settled)
                    }
                }
                t.kind == Kind.PERSON_CREDIT && who.isNotBlank() -> {
                    val owed = theyOwe.filter { it.left > 0 && it.isFor(who) }.sumOf { it.left }
                    if (owed == 0L) t else {
                        take(theyOwe, t.amountPaise) { it.isFor(who) }
                        // Paying back more than they owe means the payment was (also) for something else: the debt is
                        // cleared, and the payment stays an ordinary payback so the rest still lowers spend.
                        if (t.amountPaise > owed + 100) t else t.copy(kind = Kind.REPAID, category = "Repaid")
                    }
                }
                else -> t
            }
        }
        return Settled(out, summary(theyOwe), summary(iOwe))
    }

    /** The owner's payment that only paid back what they owed: not spend. */
    private fun paidBack(t: Txn) = t.copy(kind = Kind.REPAID, category = "Paid back", settlesPaise = t.amountPaise)

    private fun summary(items: List<Open>): List<Debt> = items.filter { it.left > 0 }
        .groupBy { (it.label ?: it.name).lowercase() }
        .map { (_, list) -> Debt(list.first().name, list.first().label, list.sumOf { it.left }, list.minOf { it.since }) }
        .sortedByDescending { it.leftPaise }

    /** Equal shares of [totalPaise] among the owner and [others] people; the owner absorbs the rounding. */
    fun equalShare(totalPaise: Long, others: Int): Long = if (others <= 0) 0 else totalPaise / (others + 1)
}
