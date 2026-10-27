package com.girish.expensetracker.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Local check against bank SMS collected from other open-source parsers' test files (sender \t body \t source file).
 * Prints what Spend reads from each so wrong reads can be spotted by eye. Skipped unless -Dsamples is set; the
 * samples themselves are kept out of this repo.
 */
class SamplesReport {
    @Test fun report() {
        val path = System.getProperty("samples").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val open = Profile(emptySet(), emptySet(), emptySet(), emptySet(), acceptAll = true)
        val counts = sortedMapOf<String, Int>()
        File(path).readLines().forEach { line ->
            val (sender, body) = line.split('\t')
            val r = SmsReader.read(sender, body, open)
            counts.merge(r::class.simpleName!!, 1, Int::plus)
            val what = when (r) {
                is ReadResult.Parsed -> r.parsed.let { p ->
                    val t = Classifier.classify(p, LocalDateTime.of(2026, 10, 1, 12, 0), open)
                    "${p.direction} ${p.amountPaise / 100}.${"%02d".format(p.amountPaise % 100)} ${p.source.bank}-${p.source.last4}-${p.source.type} " +
                        "to=${p.counterparty} kind=${t.kind} cat=${t.category}"
                }
                else -> r::class.simpleName
            }
            println("SAMPLE $what || ${body.take(170)}")
        }
        println("COUNTS $counts")
    }
}
