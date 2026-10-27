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
) {
    val utilization: Double? get() = limitPaise?.takeIf { it > 0 }?.let { outstandingPaise.toDouble() / it }

    /** Pay this much before [nextStatement] to report at or under [share] of the limit. */
    fun payToReach(share: Double): Long = limitPaise?.let { max(0L, outstandingPaise - (it * share).toLong()) } ?: 0L

    /** What is still unpaid of the latest statement (null when the statement SMS had no amount). */
    val billLeftPaise: Long? get() = statement?.totalDuePaise?.let { max(0L, it - paidSinceStatementPaise) }

    val minLeftPaise: Long? get() = statement?.minDuePaise?.let { max(0L, it - paidSinceStatementPaise) }
}

object Credit {

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
    ): CardHealth {
        val mine = txns.filter { it.parsed.source.matches(card.bank, card.last4) }
        val seen = limits.filter { card.matches(it.bank, it.digits) }
        val stmt = statements.filter { card.matches(it.bank, it.digits) }.maxByOrNull { it.statementDate }
        val limit = ownerLimitPaise ?: estimateLimit(seen)

        fun owed(t: Txn) = if (t.parsed.direction == Direction.DEBIT) t.amountPaise else -t.amountPaise
        val latestSeen = seen.maxByOrNull { it.at }
        val outstanding = when {
            // Best: the bank told us the available limit; add what happened after that SMS.
            limit != null && latestSeen != null -> limit - latestSeen.availablePaise + mine.filter { it.receivedAt > latestSeen.at }.sumOf(::owed)
            // Next best: the last statement's total, plus spends and minus payments since.
            stmt?.totalDuePaise != null -> stmt.totalDuePaise + mine.filter { it.day > stmt.statementDate }.sumOf(::owed)
            // Otherwise: this month's card activity.
            else -> mine.filter { it.day > today.minusDays(30) }.sumOf(::owed)
        }.coerceAtLeast(0L)

        val paid = if (stmt == null) 0L else {
            val cardSide = mine.filter { it.kind == Kind.TRANSFER && it.parsed.direction == Direction.CREDIT && it.day >= stmt.statementDate }.sumOf { it.amountPaise }
            // Paid by UPI from a bank account ("SBI Cards and Payment"): only the account SMS may arrive. The larger
            // side wins, so a payment confirmed on both sides is not counted twice.
            val accountSide = txns.filter {
                it.category == "Card bill" && it.parsed.direction == Direction.DEBIT && it.parsed.source.type == SourceType.ACCOUNT &&
                    it.day >= stmt.statementDate && it.parsed.counterparty.orEmpty().uppercase().contains(card.bank)
            }.sumOf { it.amountPaise }
            max(cardSide, accountSide)
        }
        val day = ownerStatementDay ?: stmt?.statementDate?.dayOfMonth
        return CardHealth(card, limit, ownerLimitPaise == null && limit != null, outstanding, stmt, day?.let { nextOn(it, today) }, paid)
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
