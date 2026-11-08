package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.max

/** A card statement as the bank announced it by SMS (amounts may be missing: HDFC only says "Statement Generated"). */
data class CardStatement(
    val bank: String,
    /** Last digits printed in the SMS (SBI prints 2, others 4); null when the SMS has none. */
    val digits: String?,
    val statementDate: LocalDate,
    val totalDuePaise: Long?,
    val minDuePaise: Long?,
    val dueDate: LocalDate?,
)

/** "Available limit" printed in a card SMS (ICICI spends, HDFC payment confirmations, Axis, Yes, IDFC...). */
data class LimitSeen(val bank: String, val digits: String, val availablePaise: Long, val at: LocalDateTime)

/** Statement and limit facts from card SMS. These SMS move no money, so the ledger ignores them; credit health reads them. */
object CardFacts {

    private const val AMT = "([\\d,]+(?:\\.\\d{1,2})?)"
    private const val DATE = "(\\d{1,2}[-/ ](?:\\d{1,2}|[A-Za-z]{3})[-/ ]\\d{2,4})"
    private val I = RegexOption.IGNORE_CASE

    private val card = Regex("(?:card|cc)\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with\\s*)?)?[xX*]*\\s*(\\d{2,4})\\b", I)
    private val reminder = Regex("overdue|over due|past due|not been received|not received your payment|late fee|late payment|pay now|reminder", I)
    private val isStatement = Regex("statement|total (?:amt|amount) due|total of rs|total due", I)
    private val dated = Regex("(?:dated|statement date:?|generated on)\\s*$DATE", I)
    private val total = Regex("(?:total\\s*(?:amt\\.?|amount)?\\s*due|total of|total due)\\s*:?\\s*(?:rs\\.?|inr|₹)\\s*$AMT", I)
    private val minimum = Regex("(?:min(?:imum)?\\.?\\s*(?:amt\\.?|amount)?\\s*due|minimum of)\\s*:?\\s*(?:rs\\.?|inr|₹)\\s*$AMT", I)
    private val due = Regex("(?:due by|payable by|due on|due date:?|pay by)\\s*$DATE", I)
    private val available = Regex("(?:avl|avbl|available)\\.?\\s*(?:credit\\s*)?(?:lmt|limit)\\s*(?:is)?\\s*:?\\s*(?:rs\\.?|inr|₹)\\s*$AMT", I)

    fun statement(sender: String, body: String, at: LocalDateTime): CardStatement? {
        val bank = SmsReader.bankOf(sender) ?: return null
        val text = body.replace(Regex("\\s+"), " ")
        if (!isStatement.containsMatchIn(text) || !Regex("card", I).containsMatchIn(text)) return null
        // An overdue or "pay now" reminder repeats the bill; reading it as a new statement would move the statement date.
        if (reminder.containsMatchIn(text)) return null
        // "update your email to get the statement" and similar: no date, no amount, nothing to learn.
        val totalDue = total.find(text)?.let { paise(it.groupValues[1]) }
        val generated = Regex("statement (?:is )?(?:generated|sent|has been (?:mailed|sent|generated))|statement generated|e-statement of", I).containsMatchIn(text)
        if (totalDue == null && !generated) return null
        return CardStatement(
            bank = bank,
            digits = card.find(text)?.groupValues?.get(1),
            statementDate = dated.find(text)?.let { Dates.parse(it.groupValues[1].replace(' ', '-')) } ?: at.toLocalDate(),
            totalDuePaise = totalDue,
            minDuePaise = minimum.find(text)?.let { paise(it.groupValues[1]) },
            dueDate = due.find(text)?.let { Dates.parse(it.groupValues[1].replace(' ', '-')) },
        )
    }

    fun limit(sender: String, body: String, at: LocalDateTime): LimitSeen? {
        val bank = SmsReader.bankOf(sender) ?: return null
        val text = body.replace(Regex("\\s+"), " ")
        val avail = available.find(text) ?: return null
        val digits = card.find(text)?.groupValues?.get(1) ?: return null
        return LimitSeen(bank, digits, paise(avail.groupValues[1]) ?: return null, at)
    }

    private fun paise(raw: String): Long? {
        val parts = raw.replace(",", "").trimEnd('.').split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        return rupees * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L)
    }
}

/** Is this SMS fact about [card]? Banks print 2 or 4 digits; "*" cards (SBI bill payments) match any card of the bank. */
fun Source.matches(bank: String, digits: String?): Boolean =
    type == SourceType.CARD && this.bank == bank && (digits == null || last4 == "*" || last4.endsWith(digits) || digits.endsWith(last4))

/**
 * Where a credit card stands: what is owed now, against which limit, and what to pay before the statement so the
 * balance the bank reports to the credit bureaus stays under 30% (10% is best).
 */
data class CardHealth(
    val card: Source,
    val limitPaise: Long?,
    /** True when the limit was worked out from "available limit" SMS rather than set by the owner. */
    val limitEstimated: Boolean,
    val outstandingPaise: Long,
    val statement: CardStatement?,
    val nextStatement: LocalDate?,
    /** Card bill payments since the latest statement. */
    val paidSinceStatementPaise: Long,
    /** For a group of cards that share one statement: each card with its part of the balance. Empty for a single card. */
    val members: List<CardHealth> = emptyList(),
    /** For a group: false when each card has its own limit and the group's limit is their sum. */
    val sharedLimit: Boolean = true,
    /** The group's name ("ICICI cards", or one the owner chose); null for a single card. */
    val title: String? = null,
    /** The owner said this statement is paid (no payment SMS came). */
    val markedPaid: Boolean = false,
    /** The latest statement is older than a full cycle: a newer one was expected but hasn't arrived. */
    val statementStale: Boolean = false,
    /** When [statementStale], the date a newer statement was due ("missed since"). */
    val missedSince: LocalDate? = null,
    /** The balance is worked out without a current statement (missed or none yet), so it's an estimate. */
    val balanceEstimated: Boolean = false,
) {
    val isGroup: Boolean get() = members.isNotEmpty()

    /** The current statement, or null when the latest one is stale (shown as "last seen" instead). */
    val currentStatement: CardStatement? get() = statement?.takeUnless { statementStale }

    val utilization: Double? get() = limitPaise?.takeIf { it > 0 }?.let { outstandingPaise.toDouble() / it }

    /** Pay this much before [nextStatement] to report at or under [share] of the limit. */
    fun payToReach(share: Double): Long = limitPaise?.let { max(0L, outstandingPaise - (it * share).toLong()) } ?: 0L

    /** What is still unpaid of the latest statement (null when the statement SMS had no amount). */
    val billLeftPaise: Long? get() = statement?.totalDuePaise?.let { max(0L, it - paidSinceStatementPaise) }

    val minLeftPaise: Long? get() = statement?.minDuePaise?.let { max(0L, it - paidSinceStatementPaise) }
}

/**
 * Cards of one bank that share a statement (and usually one limit), shown together. A bank can have more than one
 * group (two pairs of cards with different limits). [id] is the group's stand-in "last digits", so its limit and
 * statement day are kept like a card's.
 */
data class CardGroup(
    val bank: String,
    val cards: List<Source>,
    val sharedLimit: Boolean = true,
    val id: String = DIGITS,
    val name: String? = null,
) {
    val anchor: Source get() = Source(bank, id, SourceType.CARD)

    companion object {
        /** The id of a bank's first group (and of every group saved before a bank could have several). */
        const val DIGITS = "group"

        fun isGroup(s: Source) = s.type == SourceType.CARD && s.last4.startsWith(DIGITS)
    }
}

/** "I paid this statement": set by the owner when no payment SMS came. */
data class MarkedPaid(val statementDate: LocalDate, val amountPaise: Long, val at: LocalDateTime)

/** The "last digits" of a card SMS that printed no card number. */
const val UNASSIGNED = "?"

object Credit {

    /** A statement may arrive a few days late before the app treats the last one as missed. */
    const val STALE_GRACE_DAYS = 6L

    /** Bureaus treat under 30% as healthy and under 10% as best. */
    const val HEALTHY = 0.30
    const val BEST = 0.10

    /** Limits are round numbers; the highest "available limit" seen, rounded up to ₹5,000, is a good first guess. */
    fun estimateLimit(seen: List<LimitSeen>): Long? {
        val top = seen.maxOfOrNull { it.availablePaise } ?: return null
        val step = 5_000_00L
        return ((top + step - 1) / step) * step
    }

    fun health(
        card: Source,
        txns: List<Txn>,
        statements: List<CardStatement>,
        limits: List<LimitSeen>,
        ownerLimitPaise: Long?,
        ownerStatementDay: Int?,
        today: LocalDate,
        marked: MarkedPaid? = null,
        /** Bank-account payments to this bank can only be for this card (the owner's only card there). */
        accountPaysHere: Boolean = false,
    ): CardHealth = measure(card, listOf(card), txns, statements, limits, ownerLimitPaise, ownerStatementDay, today, marked = marked, accountPaysHere = accountPaysHere)

    /**
     * Cards of one bank that share a statement (an add-on card, or two cards on one account). With [CardGroup.sharedLimit]
     * the bank's "available limit" SMS already speak for the whole group, so the group is measured as one card and each
     * card gets a part of the balance in proportion to what it spent lately. Without it, each card is measured on its own
     * and the group adds them up.
     */
    fun groupHealth(
        group: CardGroup,
        txns: List<Txn>,
        statements: List<CardStatement>,
        limits: List<LimitSeen>,
        /** Owner-set limit and statement day for the group, and per-card limits (used when the limit is not shared). */
        ownerLimitPaise: Long?,
        ownerStatementDay: Int?,
        cardLimits: Map<Source, Long>,
        today: LocalDate,
        /** Card SMS of this bank without a card number count here: the group holds all the owner's cards at the bank. */
        unassigned: Boolean = false,
        title: String? = null,
        marked: MarkedPaid? = null,
    ): CardHealth {
        // The group holds every card of the bank ([unassigned]): bank-account payments to that bank are its own.
        val whole = measure(group.anchor, group.cards, txns, statements, limits, ownerLimitPaise.takeIf { group.sharedLimit }, ownerStatementDay, today, unassigned, marked, accountPaysHere = unassigned)
            .copy(title = title)
        if (!group.sharedLimit) {
            val each = group.cards.map { measure(it, listOf(it), txns, statements, limits, cardLimits[it], ownerStatementDay, today) }
            val limit = if (each.all { it.limitPaise != null }) each.sumOf { it.limitPaise!! } else null
            return whole.copy(
                limitPaise = limit,
                limitEstimated = each.any { it.limitEstimated },
                // Each card is measured on its own; a "mark paid" on the group's statement comes off the total.
                outstandingPaise = (each.sumOf { it.outstandingPaise } - if (whole.markedPaid)
                    measure(group.anchor, group.cards, txns, statements, limits, null, ownerStatementDay, today, unassigned, null, unassigned).outstandingPaise -
                        measure(group.anchor, group.cards, txns, statements, limits, null, ownerStatementDay, today, unassigned, marked, unassigned).outstandingPaise
                else 0L).coerceAtLeast(0L),
                members = each.map { it.copy(statement = whole.statement, nextStatement = whole.nextStatement, paidSinceStatementPaise = 0) },
                sharedLimit = false,
            )
        }
        // Spend over the last 60 days (this cycle and the last one) decides who owes which part of the shared balance.
        val since = today.minusDays(60)
        val spent = group.cards.map { c ->
            txns.filter { it.parsed.source.matches(c.bank, c.last4) && it.parsed.direction == Direction.DEBIT && it.day > since }.sumOf { it.amountPaise }
        }
        val total = spent.sum()
        val parts = LongArray(group.cards.size) { i ->
            if (total == 0L) whole.outstandingPaise / group.cards.size else (whole.outstandingPaise.toDouble() * spent[i] / total).toLong()
        }
        // Rounding leftovers go to the card that spent most, so the parts add up to the group's balance exactly.
        if (parts.isNotEmpty()) parts[spent.indices.maxBy { spent[it] }] += whole.outstandingPaise - parts.sum()
        val members = group.cards.mapIndexed { i, c ->
            CardHealth(c, whole.limitPaise, whole.limitEstimated, parts[i], whole.statement, whole.nextStatement, 0)
        }
        return whole.copy(members = members)
    }

    private fun measure(
        card: Source,
        cards: List<Source>,
        txns: List<Txn>,
        statements: List<CardStatement>,
        limits: List<LimitSeen>,
        ownerLimitPaise: Long?,
        ownerStatementDay: Int?,
        today: LocalDate,
        unassigned: Boolean = false,
        marked: MarkedPaid? = null,
        accountPaysHere: Boolean = false,
    ): CardHealth {
        val bank = card.bank
        val mine = txns.filter { t ->
            cards.any { t.parsed.source.matches(it.bank, it.last4) } ||
                (unassigned && t.parsed.source.type == SourceType.CARD && t.parsed.source.bank == bank && t.parsed.source.last4 == UNASSIGNED)
        }
        val seen = limits.filter { l -> cards.any { it.matches(l.bank, l.digits) } }
        val stmt = statements.filter { st -> cards.any { it.matches(st.bank, st.digits) } }.maxByOrNull { it.statementDate }
        val limit = ownerLimitPaise ?: estimateLimit(seen)

        fun owed(t: Txn) = if (t.parsed.direction == Direction.DEBIT) t.amountPaise else -t.amountPaise
        val latestSeen = seen.maxByOrNull { it.at }
        val cardPays = mine.filter { it.kind == Kind.TRANSFER && it.parsed.direction == Direction.CREDIT }
        // Paid by UPI from a bank account ("SBI Cards and Payment"): often only the account SMS arrives.
        val accountPays = txns.filter {
            it.category == "Card bill" && it.parsed.direction == Direction.DEBIT && it.parsed.source.type == SourceType.ACCOUNT &&
                it.parsed.counterparty.orEmpty().uppercase().contains(bank)
        }
        // Account payments the card side never confirmed (no card credit of the same amount within 3 days). They lower
        // the balance too, prepayments before the statement included, when they can only be for these cards.
        val unconfirmed = if (!accountPaysHere) emptyList() else accountPays.filter { a ->
            cardPays.none { c -> c.amountPaise == a.amountPaise && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(a.day, c.day)) <= 3 }
        }
        fun paidAfter(after: (Txn) -> Boolean) = unconfirmed.filter(after).sumOf { it.amountPaise }
        // A statement that is newer than the latest available-limit SMS is the better anchor (self-heal on a new statement).
        val statementNewer = stmt?.totalDuePaise != null && (latestSeen == null || !stmt.statementDate.isBefore(latestSeen.at.toLocalDate()))
        var outstanding = when {
            statementNewer -> stmt!!.totalDuePaise!! + mine.filter { it.day > stmt.statementDate }.sumOf(::owed) -
                paidAfter { it.day > stmt.statementDate }
            // The bank told us the available limit more recently; add what happened after that SMS.
            limit != null && latestSeen != null -> limit - latestSeen.availablePaise + mine.filter { it.receivedAt > latestSeen.at }.sumOf(::owed) -
                paidAfter { it.receivedAt > latestSeen.at }
            // The last statement's total, plus spends and minus payments since.
            stmt?.totalDuePaise != null -> stmt.totalDuePaise + mine.filter { it.day > stmt.statementDate }.sumOf(::owed) -
                paidAfter { it.day > stmt.statementDate }
            // Otherwise: this month's card activity.
            else -> mine.filter { it.day > today.minusDays(30) }.sumOf(::owed) - paidAfter { it.day > today.minusDays(30) }
        }.coerceAtLeast(0L)

        var paid = if (stmt == null) 0L else {
            val cardSide = cardPays.filter { it.day >= stmt.statementDate }.sumOf { it.amountPaise }
            // The larger side wins, so a payment confirmed on both sides is not counted twice.
            val accountSide = accountPays.filter { it.day >= stmt.statementDate }.sumOf { it.amountPaise }
            max(cardSide, accountSide)
        }
        // "Mark paid": the owner paid but no SMS said so. Only what the SMS don't already show is added, so a payment
        // SMS that turns up later is not counted twice.
        if (stmt != null && marked != null && marked.statementDate == stmt.statementDate) {
            val extra = max(0L, marked.amountPaise - paid)
            paid += extra
            // A newer "available limit" SMS already includes the payment.
            if (latestSeen == null || marked.at > latestSeen.at) outstanding = (outstanding - extra).coerceAtLeast(0L)
        }
        val day = ownerStatementDay ?: stmt?.statementDate?.dayOfMonth
        // A newer statement than the last limit SMS should have arrived by now? Then the one we hold is stale, and the
        // balance is an estimate until a new statement (or an available-limit SMS) heals it.
        val expectedNext = stmt?.let { nextOn(day ?: it.statementDate.dayOfMonth, it.statementDate) }
        val healedByLimit = latestSeen != null && stmt != null && latestSeen.at.toLocalDate() >= stmt.statementDate
        val stale = stmt != null && expectedNext != null && today > expectedNext.plusDays(STALE_GRACE_DAYS) && !healedByLimit
        return CardHealth(
            card, limit, ownerLimitPaise == null && limit != null, outstanding, stmt, day?.let { nextOn(it, today) }, paid,
            markedPaid = marked != null && stmt != null && marked.statementDate == stmt.statementDate,
            statementStale = stale,
            missedSince = if (stale) expectedNext else null,
            balanceEstimated = stmt == null || stale,
        )
    }

    /**
     * Do cards [a] and [b] draw on one limit? Then their "available limit" SMS run as one balance: each is the one
     * before it, from either card, minus what either card spent in between (refunds and payments add back). Two cards
     * with limits of their own almost never line up like that to the rupee.
     */
    fun looksShared(a: Source, b: Source, limits: List<LimitSeen>, txns: List<Txn>): Boolean {
        fun either(bank: String, digits: String?) = a.matches(bank, digits) || b.matches(bank, digits)
        val seen = limits.filter { either(it.bank, it.digits) }.sortedBy { it.at }
        val mine = txns.filter { either(it.parsed.source.bank, it.parsed.source.last4) && it.parsed.source.type == SourceType.CARD }
        var hits = 0
        var misses = 0
        for ((p, n) in seen.zipWithNext()) {
            // Two SMS from the same card say nothing about sharing.
            if (a.matches(p.bank, p.digits) == a.matches(n.bank, n.digits)) continue
            val between = mine.filter { it.receivedAt > p.at && !it.receivedAt.isAfter(n.at) }
                .sumOf { if (it.parsed.direction == Direction.DEBIT) it.amountPaise else -it.amountPaise }
            if (kotlin.math.abs(p.availablePaise - between - n.availablePaise) <= 100) hits++ else misses++
        }
        return hits >= 1 && hits >= misses
    }

    /** The next date (after today) that falls on day-of-month [day], clamped to short months. */
    fun nextOn(day: Int, today: LocalDate): LocalDate {
        val ym = YearMonth.from(today)
        val thisMonth = ym.atDay(minOf(day, ym.lengthOfMonth()))
        if (thisMonth.isAfter(today)) return thisMonth
        val next = ym.plusMonths(1)
        return next.atDay(minOf(day, next.lengthOfMonth()))
    }
}
