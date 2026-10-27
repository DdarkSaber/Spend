package com.girish.expensetracker.core

/**
 * A bank SMS layout the owner taught by tapping one example: the amount (and optionally the payee) they pointed at
 * become capture groups, the account digits and dates are found automatically, and every other word stays literal.
 * Works fully offline: it is a regular expression built on the phone.
 */
data class LearnedLayout(
    val bank: String,
    val pattern: String,
    /** One letter per capture group, in order: A amount, P payee, N account/card digits, D date. */
    val roles: String,
    val debit: Boolean,
    val card: Boolean,
)

object Teach {

    private val number = Regex("(?:a/?c|acct|account|card)(?:\\s*(?:no\\.?|number|ending(?:\\s*with)?))?\\s*[:.\\-]?\\s*[xX*.]*(\\d{3,})", RegexOption.IGNORE_CASE)
    private val dateToken = Regex(
        "\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{2,4}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[A-Za-z]{3}\\d{2})\\b",
    )
    private val digits = Regex("\\d[\\d,.:]*")

    private class Span(val range: IntRange, val role: Char?)

    /**
     * [text] is the SMS with whitespace collapsed (as [normalize] returns); [amount] and [payee] are the character
     * ranges the owner tapped. Returns null when the amount range doesn't hold a number.
     */
    fun learn(bank: String, text: String, amount: IntRange, payee: IntRange?, debit: Boolean, card: Boolean): LearnedLayout? {
        if (!Regex("\\d").containsMatchIn(text.substring(amount.first, amount.last + 1))) return null
        val spans = mutableListOf(Span(amount, 'A'))
        payee?.let { spans += Span(it, 'P') }
        fun free(r: IntRange) = spans.none { s -> r.first <= s.range.last && s.range.first <= r.last }
        number.findAll(text).map { it.groups[1]!!.range }.firstOrNull(::free)?.let { spans += Span(it, 'N') }
        dateToken.findAll(text).map { it.groups[1]!!.range }.firstOrNull(::free)?.let { spans += Span(it, 'D') }
        // Every other number (references, balances, times) changes from SMS to SMS: match any digits there.
        digits.findAll(text).map { it.range }.filter(::free).forEach { spans += Span(it, null) }
        spans.sortBy { it.range.first }

        val out = StringBuilder()
        val roles = StringBuilder()
        var at = 0
        for (s in spans) {
            out.append(literal(text.substring(at, s.range.first)))
            when (s.role) {
                'A' -> out.append("([\\d,]+(?:\\.\\d{1,2})?)")
                'P' -> out.append("(.+?)")
                'N' -> out.append("(\\d{3,})")
                'D' -> out.append("([0-9A-Za-z/\\-]+?)")
                else -> out.append("[\\d,.:]+")
            }
            s.role?.let { roles.append(it) }
            at = s.range.last + 1
        }
        // A few words after the last field anchor the match; the rest (links, helplines) changes from SMS to SMS.
        val tail = text.substring(at).split(Regex("\\s+")).takeWhile { "http" !in it.lowercase() && "www." !in it.lowercase() }.take(3)
        out.append(literal(tail.joinToString(" ")))
        return LearnedLayout(bank, out.toString(), roles.toString(), debit, card)
    }

    fun parse(l: LearnedLayout, text: String): ParsedSms? {
        val m = runCatching { Regex(l.pattern, RegexOption.IGNORE_CASE).find(text) }.getOrNull() ?: return null
        fun group(role: Char) = l.roles.indexOf(role).takeIf { it >= 0 }?.let { m.groupValues[it + 1] }
        val raw = group('A')?.replace(",", "") ?: return null
        val parts = raw.split('.')
        val paise = (parts[0].toLongOrNull() ?: return null) * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L)
        val digits = group('N')?.takeLast(4) ?: "?"
        val dir = if (l.debit) Direction.DEBIT else Direction.CREDIT
        val template = when {
            l.card && l.debit -> Template.GENERIC_CARD_SPENT
            l.card -> Template.GENERIC_CARD_REFUND
            l.debit -> Template.GENERIC_DEBIT
            else -> Template.GENERIC_CREDIT
        }
        return ParsedSms(
            template, dir, paise, Source(l.bank, digits, if (l.card) SourceType.CARD else SourceType.ACCOUNT),
            group('P')?.trim()?.trim('.', ':', '-')?.ifBlank { null }, group('D')?.let(Dates::parse), null, text,
        )
    }

    /** The text the owner taps on: whitespace collapsed, exactly as the reader sees it. */
    fun normalize(body: String) = body.replace(Regex("\\s+"), " ").trim()

    /** Words become literal text; any run of spaces matches any whitespace. */
    private fun literal(s: String): String =
        s.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it).let { e -> if (it.isEmpty()) "" else e } }
}
