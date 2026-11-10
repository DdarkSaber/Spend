package com.girish.expensetracker.core

import kotlin.math.abs

/**
 * Dedupe level 2: one payment reported on two channels (the SMS and a Truecaller or Gmail copy worded differently,
 * an archive alert and a statement row). Fact key (bank, last4, direction, amount, day ±1).
 *  - Only matches across different channels: two equal payments on one channel are both real (two metro taps).
 *  - An SMS and a notification both carry a real time, so they must also be within [TIMED_HOURS] of each other.
 *  - A card alert without digits ("?", a Truecaller card) matches any card of that bank; the copy with digits is kept.
 *  - Otherwise keeps the copy from the best channel: sms, then notification, then archive alert, then statement row.
 *  - Every collapse is returned, so it can be audited (two genuinely equal spends could arrive on two channels).
 * O(n): hash map on the key; each key's list only holds payments within a day of the current one.
 */
object FactDedupe {

    /** Lower is better. Cash entries never take part. */
    fun rank(channel: String): Int = when {
        channel == "sms" -> 0
        channel.startsWith("notif:") -> 1
        channel.startsWith("archive:") -> 2
        channel == "stmt" -> 3
        else -> 4
    }

    data class Collapse<T>(val kept: T, val dropped: T)
    data class Result<T>(val kept: List<T>, val alsoVia: Map<Int, List<String>>, val collapses: List<Collapse<T>>)

    private data class Key(val bank: String, val direction: Direction, val amountPaise: Long)
    private class Entry<T>(val index: Int, var item: T, val channels: MutableList<String>)

    /** Two copies that both carry a real time (an SMS, a notification) must be this close to be one payment. */
    const val TIMED_HOURS = 6L

    // SMS and notifications are stamped when they arrive; archive alerts and statement rows only reliably carry a day.
    private fun timed(channel: String) = rank(channel) <= 1

    /** The same account or card: equal digits, or a card alert without digits ("?") against any card of the bank. */
    private fun sameSource(a: Source, b: Source): Boolean =
        a.last4 == b.last4 || (a.type == SourceType.CARD && b.type == SourceType.CARD && (a.last4 == UNASSIGNED || b.last4 == UNASSIGNED))

    /**
     * [items] in time order. [alsoVia] in the result is keyed by position in the kept list: the other channels that
     * reported that payment.
     */
    fun <T> apply(items: List<T>, txn: (T) -> Txn, channel: (T) -> String): Result<T> {
        val kept = ArrayList<Entry<T>>(items.size)
        val byKey = HashMap<Key, MutableList<Entry<T>>>()
        val collapses = mutableListOf<Collapse<T>>()
        for (item in items) {
            val t = txn(item)
            val ch = channel(item)
            val s = t.parsed.source
            if (s.bank == "CASH" || rank(ch) > 3) { kept += Entry(kept.size, item, mutableListOf(ch)); continue }
            val key = Key(s.bank, t.parsed.direction, t.amountPaise)
            val list = byKey.getOrPut(key) { mutableListOf() }
            val day = t.day
            val match = list.firstOrNull { e ->
                val other = txn(e.item)
                ch !in e.channels && sameSource(other.parsed.source, s) &&
                    abs(java.time.temporal.ChronoUnit.DAYS.between(other.day, day)) <= 1 &&
                    // Two equal spends a day apart, one per channel, are two payments: timed copies must be hours apart.
                    (!timed(ch) || !timed(channel(e.item)) ||
                        abs(java.time.Duration.between(other.receivedAt, t.receivedAt).toHours()) < TIMED_HOURS)
            }
            if (match == null) {
                val e = Entry(kept.size, item, mutableListOf(ch))
                kept += e
                list += e
                continue
            }
            match.channels += ch
            // The copy that names the card wins over one without digits; otherwise the better channel.
            val known = s.last4 != UNASSIGNED
            val matchKnown = txn(match.item).parsed.source.last4 != UNASSIGNED
            val better = if (known != matchKnown) known else rank(ch) < rank(channel(match.item))
            if (better) {
                collapses += Collapse(item, match.item)
                match.item = item
            } else {
                collapses += Collapse(match.item, item)
            }
        }
        val alsoVia = HashMap<Int, List<String>>()
        kept.forEachIndexed { i, e -> if (e.channels.size > 1) alsoVia[i] = e.channels.filter { it != channel(e.item) }.distinct() }
        return Result(kept.map { it.item }, alsoVia, collapses)
    }
}
