package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Something about a payment worth a second look: a double charge, an unusually big spend, a foreign charge. */
data class Unusual(val key: String, val title: String, val text: String, val day: LocalDate)

object UnusualSpend {

    private val foreign = Regex("\\b(USD|EUR|GBP|AED|SGD|AUD|CAD|JPY|CHF|THB|MYR)\\s?([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)
    private val spentWords = Regex("spent|debited|txn|transaction|purchase|charged", RegexOption.IGNORE_CASE)

    /** A bank SMS that charged in a foreign currency (the reader can't count it in rupees, so it is flagged instead). */
    fun foreignCharge(bank: String, body: String, at: LocalDateTime): Unusual? {
        val m = foreign.find(body) ?: return null
        if (!spentWords.containsMatchIn(body)) return null
        return Unusual("fx|${at.toLocalDate()}|${m.value}", "Foreign charge: ${m.groupValues[1].uppercase()} ${m.groupValues[2]}",
            "On your $bank card or account. Check it's yours; the rupee amount appears on the statement.", at.toLocalDate())
    }

    /** Recent payments (last [days] days) that look like a double charge or are far bigger than usual. */
    fun find(txns: List<Txn>, today: LocalDate, money: (Long) -> String, days: Long = 7): List<Unusual> {
        val spends = txns.filter { (it.kind == Kind.SPEND || it.kind == Kind.SUBSCRIPTION) && it.parsed.direction == Direction.DEBIT }
        val recent = spends.filter { !it.day.isBefore(today.minusDays(days)) }
        val out = mutableListOf<Unusual>()

        // Same payee, same amount, within 10 minutes, as two separate SMS.
        recent.sortedBy { it.receivedAt }.zipWithNext().forEach { (a, b) ->
            val same = a.amountPaise == b.amountPaise && !a.parsed.counterparty.isNullOrBlank() &&
                normalizeName(a.parsed.counterparty!!) == normalizeName(b.parsed.counterparty.orEmpty())
            if (same && ChronoUnit.MINUTES.between(a.receivedAt, b.receivedAt) <= 10) {
                out += Unusual("dup|${b.receivedAt}|${b.amountPaise}", "Charged twice? ${money(b.amountPaise)} at ${b.parsed.counterparty}",
                    "Two payments of the same amount ${ChronoUnit.MINUTES.between(a.receivedAt, b.receivedAt)} min apart.", b.day)
            }
        }

        // Far above the usual payment: more than 4x the typical spend, at least ₹2,000, and the biggest in 60 days.
        val history = spends.filter { it.day.isBefore(today.minusDays(days)) && it.day.isAfter(today.minusDays(90)) && it.category != "Rent" }
        if (history.size >= 20) {
            val typical = history.map { it.amountPaise }.sorted()[history.size / 2]
            val biggestBefore = history.filter { it.day.isAfter(today.minusDays(60)) }.maxOfOrNull { it.amountPaise } ?: 0L
            recent.filter { it.category != "Rent" && it.amountPaise >= 2_000_00 && it.amountPaise > typical * 4 && it.amountPaise > biggestBefore }
                .forEach { t ->
                    out += Unusual("big|${t.receivedAt}|${t.amountPaise}", "Unusually large: ${money(t.amountPaise)} at ${t.parsed.counterparty ?: t.category}",
                        "Your biggest payment in 2 months; a typical one is ${money(typical)}.", t.day)
                }
        }
        return out
    }
}

/** Salary in, spend out, and the share saved, for one salary month. */
data class SavingsMonth(val start: LocalDate, val salaryPaise: Long, val spentPaise: Long) {
    val savedPaise: Long get() = salaryPaise - spentPaise
    val rate: Double get() = if (salaryPaise <= 0) 0.0 else savedPaise.toDouble() / salaryPaise
}

object Savings {
    /** Salary months (newest last) that had a salary credit; months with no salary are left out rather than shown as -100%. */
    fun months(txns: List<Txn>, cycles: List<ClosedRange<LocalDate>>): List<SavingsMonth> = cycles.sortedBy { it.start }.mapNotNull { r ->
        val salary = txns.filter { it.isSalary && it.day in r }.sumOf { it.amountPaise }
        if (salary <= 0) null else SavingsMonth(r.start, salary, txns.filter { it.day in r }.sumOf(Ledger::monthAmount))
    }
}

/** A loan instalment: the same lender debiting about the same amount every month. */
data class Emi(val name: String, val monthlyPaise: Long, val paid: Int, val first: LocalDate, val last: LocalDate, val next: LocalDate)

object Emis {

    private val lenderWords = Regex(
        "\\bEMI\\b|\\bLOAN\\b|FINANCE|FINSERV|FIN SERV|BAJAJ FIN|HOME CREDIT|HDB FIN|TATA CAPITAL|CAPITAL FIRST|IDFC FIRST LOAN|" +
            "MUTHOOT|MANAPPURAM|KREDITBEE|MONEYVIEW|NAVI|CASHE|LAZYPAY|SIMPL|ZESTMONEY|NBFC|LENDING",
        RegexOption.IGNORE_CASE,
    )

    /** Only a lender's name or the word EMI/LOAN in the bank's own debit SMS counts; a monthly bill or premium is not a loan. */
    fun find(txns: List<Txn>, today: LocalDate): List<Emi> {
        val candidates = txns.filter {
            it.parsed.direction == Direction.DEBIT && it.kind != Kind.TRANSFER && it.kind != Kind.INVESTMENT_SIP && it.kind != Kind.INVESTMENT_LUMPSUM &&
                (lenderWords.containsMatchIn(it.parsed.counterparty.orEmpty()) || lenderWords.containsMatchIn(it.parsed.narration.orEmpty()))
        }
        return candidates.groupBy { normalizeName(it.parsed.counterparty ?: it.parsed.narration.orEmpty()).take(14) }.mapNotNull { (_, list) ->
            val sorted = list.sortedBy { it.day }
            val last = sorted.last()
            val same = sorted.filter { abs(it.amountPaise - last.amountPaise) <= last.amountPaise / 20 }
            val months = same.map { java.time.YearMonth.from(it.day) }.distinct()
            // Monthly, one per month, same amount, and still running.
            if (months.size < 2 || same.size > months.size + 1 || last.day.isBefore(today.minusDays(45))) return@mapNotNull null
            Emi(last.parsed.counterparty ?: "Loan", last.amountPaise, same.size, same.first().day, last.day, last.day.plusMonths(1))
        }.sortedBy { it.next }
    }
}

/** Money put into investments, per platform. */
data class Holding(val platform: String, val sipPaise: Long, val lumpPaise: Long, val thisMonthPaise: Long, val payments: Int, val last: LocalDate) {
    val totalPaise: Long get() = sipPaise + lumpPaise
}

object Investments {

    private val platforms = listOf(
        "GROWW" to "Groww", "GR0WW" to "Groww", "ZERODHA" to "Zerodha", "UPSTOX" to "Upstox", "ANGEL" to "Angel One", "DHAN" to "Dhan",
        "KUVERA" to "Kuvera", "PAYTM MONEY" to "Paytm Money", "COIN" to "Zerodha Coin", "INDIAN CLEARING" to "Stocks (ICCL)", "ICCL" to "Stocks (ICCL)",
        "NSE CLEARING" to "Stocks (NSE)", "BSE" to "BSE StAR MF", "MUTUAL FUND" to "Mutual fund", "SBI MF" to "SBI MF", "HDFC MF" to "HDFC MF",
        "PPF" to "PPF", "NPS" to "NPS",
    )

    fun platformOf(t: Txn): String {
        val text = (t.parsed.counterparty.orEmpty() + " " + t.parsed.narration.orEmpty()).uppercase()
        return platforms.firstOrNull { it.first in text }?.second ?: t.parsed.counterparty?.takeIf { it.isNotBlank() } ?: "Other"
    }

    fun holdings(txns: List<Txn>, cycleStart: LocalDate): List<Holding> =
        txns.filter { it.kind == Kind.INVESTMENT_SIP || it.kind == Kind.INVESTMENT_LUMPSUM }.groupBy(::platformOf).map { (p, list) ->
            Holding(
                platform = p,
                sipPaise = list.filter { it.kind == Kind.INVESTMENT_SIP }.sumOf { it.amountPaise },
                lumpPaise = list.filter { it.kind == Kind.INVESTMENT_LUMPSUM }.sumOf { it.amountPaise },
                thisMonthPaise = list.filter { !it.day.isBefore(cycleStart) }.sumOf { it.amountPaise },
                payments = list.size,
                last = list.maxOf { it.day },
            )
        }.sortedByDescending { it.totalPaise }
}

/** A named date range ("Goa trip") whose spend is shown on its own. */
data class Trip(val id: Long, val name: String, val start: LocalDate, val end: LocalDate)
