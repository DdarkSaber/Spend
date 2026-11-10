package com.girish.expensetracker.core

import kotlin.math.abs

/**
 * Transfer pairing (after dedupe): money leaving one of the owner's accounts or cards and the same amount arriving on
 * another of them within 2 days is a transfer, not spend or income. Covers account -> account, account -> own card
 * (a bill paid from an account) and wallet top-ups. The classifier's own self-name, self-VPA and card-bill rules run
 * first; this only catches what they missed. Each debit pairs with at most one credit, the nearest in time.
 *
 * Two things are never a transfer, however the amounts line up:
 *  - a spend on a credit card (money never leaves a card for one of the owner's accounts);
 *  - a payment to one named party and money in from a different named party (paying one friend and being paid back
 *    by another). When both sides carry a name, they must read as the same party.
 */
object Transfers {

    const val MAX_DAYS = 2L

    private val debitKinds = setOf(Kind.SPEND, Kind.TRANSFER)
    private val creditKinds = setOf(Kind.INCOME, Kind.PERSON_CREDIT, Kind.TRANSFER)

    /**
     * [isOwn]: the owner's tracked accounts and cards. [locked]: payments the owner filed by hand (never changed).
     * Returns the same list with paired payments turned into transfers.
     */
    fun pair(txns: List<Txn>, isOwn: (Source) -> Boolean, locked: (Int) -> Boolean = { false }): List<Txn> {
        fun eligible(i: Int, t: Txn, kinds: Set<Kind>) =
            !locked(i) && t.kind in kinds && !t.isSalary && t.parsed.source.bank != "CASH" && isOwn(t.parsed.source) &&
                !(t.parsed.direction == Direction.DEBIT && t.parsed.source.type == SourceType.CARD)

        fun sameParty(a: Txn, b: Txn): Boolean {
            val x = a.parsed.counterparty?.trim().orEmpty()
            val y = b.parsed.counterparty?.trim().orEmpty()
            return x.isEmpty() || y.isEmpty() || x.equals(y, ignoreCase = true) || People.same(x, y)
        }

        val out = txns.toMutableList()
        // Credits waiting for their debit, by amount; and debits waiting for their credit.
        val credits = HashMap<Long, MutableList<Int>>()
        val debits = HashMap<Long, MutableList<Int>>()
        val order = txns.indices.sortedBy { txns[it].day }
        for (i in order) {
            val t = txns[i]
            val isDebit = t.parsed.direction == Direction.DEBIT
            if (!eligible(i, t, if (isDebit) debitKinds else creditKinds)) continue
            val other = if (isDebit) credits else debits
            val waiting = other[t.amountPaise]
            // Anything more than 2 days older can never pair again.
            waiting?.removeAll { j -> java.time.temporal.ChronoUnit.DAYS.between(txns[j].day, t.day) > MAX_DAYS }
            val j = waiting?.filter { txns[it].parsed.source != t.parsed.source && sameParty(txns[it], t) }
                ?.minByOrNull { abs(java.time.temporal.ChronoUnit.DAYS.between(txns[it].day, t.day)) }
            if (j == null) {
                (if (isDebit) debits else credits).getOrPut(t.amountPaise) { mutableListOf() } += i
                continue
            }
            waiting.remove(j)
            val credit = if (isDebit) txns[j] else t
            val category = if (credit.parsed.source.type == SourceType.CARD) "Card bill" else "Transfer"
            for (k in listOf(i, j)) {
                val x = out[k]
                if (x.kind != Kind.TRANSFER) out[k] = x.copy(kind = Kind.TRANSFER, category = category, payeeType = null)
            }
        }
        return out
    }
}
