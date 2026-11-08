package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.LocalDateTime

/** Everything reading one stored alert gives: the transaction (or why not), plus card statement, limit and foreign-charge facts. */
data class Reading(val result: ReadResult, val statement: CardStatement?, val limit: LimitSeen?, val foreign: Unusual?)

/**
 * Readings are saved in the database so a large history is read once, not on every refresh. A saved reading is used
 * only while its version matches: [PARSER_VERSION] plus the owner's accounts, cards, self names and taught layouts.
 * The raw text stays the source of truth, so a parser fix (bump [PARSER_VERSION]) re-reads all of history.
 */
object ParseCache {

    /** Bump whenever SmsReader, Generic, Teach, CardFacts or UnusualSpend.foreignCharge read any text differently. */
    const val PARSER_VERSION = 1

    /** Stable across runs (no enum or object hash codes), so a restart keeps the cache. */
    fun version(profile: Profile, learned: List<LearnedLayout>, zoneId: String): Long {
        val text = buildString {
            append(zoneId).append('\n')
            fun sources(s: Set<Source>) = s.map { "${it.bank}|${it.last4}|${it.type.name}" }.sorted().joinToString(",")
            append(sources(profile.accounts)).append('\n')
            append(sources(profile.cards)).append('\n')
            append(profile.selfNames.sorted().joinToString(",")).append('\n')
            append(profile.selfVpas.sorted().joinToString(",")).append('\n')
            append(profile.acceptAll).append('\n')
            learned.forEach { append(it.bank).append('|').append(it.pattern).append('|').append(it.roles).append('|').append(it.debit).append('|').append(it.card).append('\n') }
        }
        // FNV-1a 32-bit over the text, in the low half; the parser version in the high half.
        var h = 0x811c9dc5.toInt()
        for (ch in text) { h = h xor ch.code; h *= 0x01000193 }
        return (PARSER_VERSION.toLong() shl 32) or (h.toLong() and 0xffffffffL)
    }

    // ------------------------------------------------------------------ encoding: length-prefixed fields, "~" = null

    fun encode(r: Reading): String = W().apply {
        when (val x = r.result) {
            ReadResult.NotBank -> s("N")
            ReadResult.Promo -> s("P")
            ReadResult.Informational -> s("I")
            is ReadResult.Unparsed -> { s("X"); s(x.body) }
            is ReadResult.UnknownSource -> { s("U"); parsed(x.parsed) }
            is ReadResult.Parsed -> { s("R"); parsed(x.parsed) }
        }
        val st = r.statement
        if (st == null) s(null) else {
            s("S"); s(st.bank); s(st.digits); s(st.statementDate.toString()); l(st.totalDuePaise); l(st.minDuePaise); s(st.dueDate?.toString())
        }
        val lim = r.limit
        if (lim == null) s(null) else { s("L"); s(lim.bank); s(lim.digits); l(lim.availablePaise); s(lim.at.toString()) }
        val f = r.foreign
        if (f == null) s(null) else { s("F"); s(f.key); s(f.title); s(f.text); s(f.day.toString()) }
    }.out.toString()

    /** Null when [text] is not a reading this version wrote (then the alert is simply read again). */
    fun decode(text: String): Reading? = runCatching {
        val r = R(text)
        val result = when (r.s()) {
            "N" -> ReadResult.NotBank
            "P" -> ReadResult.Promo
            "I" -> ReadResult.Informational
            "X" -> ReadResult.Unparsed(r.s()!!)
            "U" -> ReadResult.UnknownSource(r.parsed())
            "R" -> ReadResult.Parsed(r.parsed())
            else -> error("bad result")
        }
        val statement = r.s()?.let {
            CardStatement(r.s()!!, r.s(), LocalDate.parse(r.s()), r.l(), r.l(), r.s()?.let(LocalDate::parse))
        }
        val limit = r.s()?.let { LimitSeen(r.s()!!, r.s()!!, r.l()!!, LocalDateTime.parse(r.s())) }
        val foreign = r.s()?.let { Unusual(r.s()!!, r.s()!!, r.s()!!, LocalDate.parse(r.s())) }
        check(r.done())
        Reading(result, statement, limit, foreign)
    }.getOrNull()

    private class W {
        val out = StringBuilder()
        fun s(v: String?) { if (v == null) out.append('~') else out.append(v.length).append(':').append(v) }
        fun l(v: Long?) = s(v?.toString())
        fun parsed(p: ParsedSms) {
            s(p.template.name); s(p.direction.name); l(p.amountPaise)
            s(p.source.bank); s(p.source.last4); s(p.source.type.name)
            s(p.counterparty); s(p.date?.toString()); s(p.ref); s(p.narration)
        }
    }

    private class R(val text: String) {
        var i = 0
        fun done() = i == text.length
        fun s(): String? {
            if (text[i] == '~') { i++; return null }
            val colon = text.indexOf(':', i)
            val n = text.substring(i, colon).toInt()
            val v = text.substring(colon + 1, colon + 1 + n)
            i = colon + 1 + n
            return v
        }
        fun l(): Long? = s()?.toLong()
        fun parsed() = ParsedSms(
            Template.valueOf(s()!!), Direction.valueOf(s()!!), l()!!,
            Source(s()!!, s()!!, SourceType.valueOf(s()!!)),
            s(), s()?.let(LocalDate::parse), s(), s(),
        )
    }
}
