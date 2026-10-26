package com.girish.expensetracker.ui

import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType
import kotlin.math.abs
import kotlin.math.roundToLong

/** 1234567 -> "12,34,567" (Indian grouping, no locale dependency). */
fun indianGroup(n: Long): String {
    val s = abs(n).toString()
    if (s.length <= 3) return s
    val last3 = s.takeLast(3)
    var rest = s.dropLast(3)
    val parts = ArrayDeque<String>()
    while (rest.length > 2) {
        parts.addFirst(rest.takeLast(2))
        rest = rest.dropLast(2)
    }
    if (rest.isNotEmpty()) parts.addFirst(rest)
    return parts.joinToString(",") + "," + last3
}

/** Whole rupees, e.g. "₹1,420" or "−₹300". */
fun rupees(paise: Long): String {
    val r = (paise / 100.0).roundToLong()
    return (if (r < 0) "−" else "") + "₹" + indianGroup(r)
}

/** Rupees with paise, e.g. "₹135.34". */
fun rupeesExact(paise: Long): String {
    val sign = if (paise < 0) "−" else ""
    val p = abs(paise)
    return "$sign₹${indianGroup(p / 100)}.${(p % 100).toString().padStart(2, '0')}"
}

/** Short form for calendar cells: 950, 1.2k, 15k, 1.1L. */
fun compact(paise: Long): String {
    val r = abs(paise) / 100.0
    val s = when {
        r < 1000 -> r.roundToLong().toString()
        r < 10_000 -> String.format("%.1fk", r / 1000).replace(".0k", "k")
        r < 1_00_000 -> "${(r / 1000).roundToLong()}k"
        else -> String.format("%.1fL", r / 1_00_000).replace(".0L", "L")
    }
    return if (paise < 0) "−$s" else s
}

/** "1 payment", "3 payments". */
fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/** A salary month (28 Sep - 27 Oct) is named after the month most of its days fall in: Oct. */
fun cycleMonth(start: java.time.LocalDate): String =
    start.plusDays(15).month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)

fun sourceLabel(s: Source): String = when {
    s.bank == "CASH" -> "Cash"
    s.type == SourceType.CARD && s.last4 == "*" -> "${s.bank} card"
    s.type == SourceType.CARD -> "${s.bank} card ${s.last4}"
    else -> "${s.bank} a/c ${s.last4}"
}
