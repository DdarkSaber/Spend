package com.girish.expensetracker.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Prints how the reader handles a real SMS export (sender \t body \t epochMillis). Skipped unless -Dcorpus is set. */
class CorpusReport {
    @Test fun report() {
        val path = System.getProperty("corpus").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val p = ProfileDetector.detect(File(path).readLines().map { l -> l.split('\t').let { InboxSms(it[0], it[1], LocalDateTime.ofInstant(Instant.ofEpochMilli(it[2].toLong()), ZoneId.of("Asia/Kolkata"))) } }).toProfile()
        val counts = sortedMapOf<String, Int>()
        val txns = mutableListOf<Txn>()
        File(path).readLines().forEach { line ->
            val (sender, body, ts) = line.split('\t')
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts.toLong()), ZoneId.of("Asia/Kolkata"))
            val r = SmsReader.read(sender, body, p)
            counts.merge(r::class.simpleName!!, 1, Int::plus)
            when (r) {
                is ReadResult.Parsed -> txns += Classifier.classify(r.parsed, at, p)
                is ReadResult.Unparsed -> println("UNPARSED $sender | ${body.take(150)}")
                is ReadResult.UnknownSource -> println("UNKNOWN-SOURCE ${r.parsed.source} | ${body.take(120)}")
                ReadResult.Informational ->
                    if (Regex("(?i)(rs\\.?|inr)\\s?[\\d,]+").containsMatchIn(body)) println("INFO-WITH-AMOUNT $sender | ${body.take(140)}")
                else -> Unit
            }
        }
        println("COUNTS $counts")
        Dedupe.apply(txns).filter { it.kind == Kind.SPEND || it.kind == Kind.REFUND || it.kind == Kind.SUBSCRIPTION }
            .groupBy { "${it.category} <- ${it.parsed.counterparty} [${it.payeeType}]" }
            .toSortedMap()
            .forEach { (k, v) -> println("CATEGORY $k x${v.size}") }
        Dedupe.apply(txns).filter { it.kind == Kind.PERSON_CREDIT || it.payeeType == PayeeType.PERSON }
            .groupBy { "${it.kind}/${it.parsed.counterparty}" }
            .forEach { (k, v) -> println("PERSONISH $k x${v.size} ${v.sumOf { it.amountPaise } / 100}") }
        Dedupe.apply(txns).filter { it.category == "Uncategorised" }.groupBy { it.parsed.counterparty }
            .forEach { (k, v) -> println("UNCATEGORISED $k x${v.size}") }

        // v0.3 detectors on real data: none of them may invent loans, alerts or subscriptions out of spam.
        val all = Dedupe.apply(txns)
        val today = all.maxOf { it.day }
        val raw = File(path).readLines().map { it.split('\t') }
        println("EMIS ${Emis.find(all, today).map { "${it.name} ${it.monthlyPaise / 100} x${it.paid}" }}")
        println("SUBSCRIPTIONS ${Subscriptions.find(all, today).map { "${it.name} ${it.lastPaise / 100}" }}")
        println("UNUSUAL ${UnusualSpend.find(all, today, money = { "${it / 100}" }, days = 120).map { it.title }}")
        println("FOREIGN ${raw.mapNotNull { (s, b, ts) -> SmsReader.bankOf(s)?.let { UnusualSpend.foreignCharge(it, b, LocalDateTime.ofInstant(Instant.ofEpochMilli(ts.toLong()), ZoneId.of("Asia/Kolkata"))) } }.map { it.title }}")
        println("INVESTMENTS ${Investments.holdings(all, today.minusDays(30)).map { "${it.platform} ${it.totalPaise / 100}" }}")
        // Every bank-sender SMS that mentions a loan or EMI, and what the reader made of it.
        raw.filter { (s, b, _) -> SmsReader.bankOf(s) != null && Regex("(?i)\\b(emi|loan)\\b").containsMatchIn(b) }
            .forEach { (s, b, _) -> println("LOANWORD ${SmsReader.read(s, b, p)::class.simpleName} $s | ${b.take(90)}") }
    }
}
