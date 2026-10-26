package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PeopleTest {
    @Test fun sameFriendAcrossHandleAndBankName() {
        // Shapes taken from real SMS, names changed.
        assertTrue(People.same("ksuresh2001@okaxis", "SURESH KUMAR RAMAN ANAND"))
        assertTrue(People.same("ksuresh2001@okaxis", "ksuresh20"))
        assertTrue(People.same("Mr ANIL V S", "vsanil2001@okhdfcbank"))
        assertTrue(People.same("P RAMESH", "p.ramesh2708@oksbi"))
        assertTrue(People.same("Mr Ramesh B", "RAMESH B"))
    }

    @Test fun differentPeopleStayApart() {
        assertFalse(People.same("Sanjaikumar S", "SATYENDRA KUMAR SO SHAMBH"))
        assertFalse(People.same("NAVEEN B", "PRAVEEN K"))
        assertFalse(People.same("KISHORE H", "VIKRAM MALHOTRA"))
    }

    @Test fun groupsAndNames() {
        val groups = People.group(listOf("ksuresh2001@okaxis", "NAVEEN B", "SURESH KUMAR RAMAN ANAND", "ksuresh20"))
        assertEquals(2, groups.size)
        val suresh = groups.first { it.size == 3 }
        assertEquals("Suresh Kumar Raman Anand", People.displayName(suresh))
        assertEquals("Anil V S", People.displayName(listOf("Mr ANIL V S", "vsanil2001@okhdfcbank")))
    }
}

/** Regression tests for the code-review findings (Oct 2026). */
class ReviewFixesTest {
    private val acct = Source("HDFC", "1234", SourceType.ACCOUNT)
    private val me = Profile(setOf(acct), emptySet(), setOf("RAVI KUMAR M P"), setOf("ravikm2007@oksbi"))

    private fun read(t: Template, dir: Direction, rs: Int, who: String) =
        Classifier.classify(ParsedSms(t, dir, rs * 100L, acct, who, java.time.LocalDate.of(2026, 10, 1), "r$who$rs", null), java.time.LocalDate.of(2026, 10, 1).atTime(9, 0), me)

    @Test fun friendOnPaytmHandleIsAPersonNotARefund() {
        assertEquals(Kind.PERSON_CREDIT, read(Template.HDFC_UPI_CREDIT, Direction.CREDIT, 500, "ravi@paytm").kind)
        assertEquals(Kind.REFUND, read(Template.HDFC_UPI_CREDIT, Direction.CREDIT, 500, "gpayrefund-online@axisbank").kind)
    }

    @Test fun paymentToOwnUpiIdIsATransfer() {
        assertEquals(Kind.TRANSFER, read(Template.HDFC_UPI_SENT, Direction.DEBIT, 10000, "ravikm2007-1@okhdfcbank").kind)
    }

    @Test fun commonNamesAreNotBrands() {
        listOf("SAGAR KUMAR", "NANDINI R", "NARAYANA S", "MASTER RAVI").forEach {
            assertEquals(it, PayeeType.PERSON, PayeeGuess.type(it))
        }
        assertEquals("Medical", PayeeGuess.category("MANIPAL HOSPITALS"))
        assertEquals("Groceries", PayeeGuess.category("NANDINI MILK PARLOUR"))
    }

    @Test fun retagOfANumericUpiIdStaysWithThatId() {
        val rules = PayeeRules.of("9876543210@ybl" to PayeeRules.Rule(PayeeType.MERCHANT, "Food"))
        assertEquals("Food", rules.lookup("9876543210@ybl")?.category)
        assertEquals(null, rules.lookup("9123456789@ybl"))
    }
}

class RentFinderTest {
    private fun pay(day: String, rs: Int, to: String) = Classifier.classify(
        ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, rs * 100L, Source("HDFC", "1234", SourceType.ACCOUNT), to, java.time.LocalDate.parse(day), "r$day$rs", null),
        java.time.LocalDate.parse(day).atTime(10, 0),
        Profile(setOf(Source("HDFC", "1234", SourceType.ACCOUNT)), emptySet(), emptySet(), emptySet()),
    )

    @Test fun monthlySimilarTransferToSamePersonIsRent() {
        // Shape of the real case: bank name one month, UPI handle the next, a small split in between.
        val txns = listOf(
            pay("2026-08-05", 21550, "SURESH KUMAR RAMAN ANAND"),
            pay("2026-08-20", 545, "ksuresh20"),
            pay("2026-09-05", 21550, "ksuresh2001@okaxis"),
            pay("2026-10-27", 22366, "ksuresh2001@okaxis"),
            pay("2026-10-10", 9000, "SOMEONE ELSE"),
        )
        val found = RentFinder.find(txns)
        assertEquals(1, found.size)
        assertEquals(3, found[0].payments.size)
        assertEquals(2155000L, found[0].typicalPaise)
        assertTrue(RentFinder.isRent(pay("2026-11-05", 22000, "ksuresh2001@okaxis"), "ksuresh2001@okaxis", found[0].typicalPaise))
        assertFalse(RentFinder.isRent(pay("2026-11-07", 545, "ksuresh2001@okaxis"), "ksuresh2001@okaxis", found[0].typicalPaise))
    }

    @Test fun oneOffBigPaymentIsNotRent() {
        assertTrue(RentFinder.find(listOf(pay("2026-09-05", 40000, "FRIEND A"))).isEmpty())
    }
}

/**
 * Golden check on a real SMS export (-Dcorpus=<tsv>, kept outside the repo): the profile detected from the inbox must
 * give exactly the same money as the profile that used to be hardcoded. Skipped unless both files are given.
 */
class DetectionGoldenTest {

    /**
     * The profile that used to be hardcoded, read from a local file outside the repo (-Dgolden=<file>), so no real
     * account digits or names live in source. Lines: accounts=BANK:last4,...  cards=...  names=a|b  vpas=a|b
     */
    private fun goldenProfile(path: String): Profile {
        val kv = File(path).readLines().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        fun sources(k: String, type: SourceType) = kv[k].orEmpty().split(',').filter { ':' in it }
            .map { Source(it.substringBefore(':').trim(), it.substringAfter(':').trim(), type) }.toSet()
        fun set(k: String) = kv[k].orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return Profile(sources("accounts", SourceType.ACCOUNT), sources("cards", SourceType.CARD), set("names"), set("vpas"))
    }

    private fun inbox(path: String) = File(path).readLines().map { line ->
        val (sender, body, ts) = line.split('\t')
        InboxSms(sender, body, LocalDateTime.ofInstant(Instant.ofEpochMilli(ts.toLong()), ZoneId.of("Asia/Kolkata")))
    }

    private fun ledger(inbox: List<InboxSms>, profile: Profile): List<Txn> = Dedupe.apply(
        inbox.mapNotNull { m ->
            (SmsReader.read(m.sender, m.body, profile) as? ReadResult.Parsed)?.let { Classifier.classify(it.parsed, m.at, profile) }
        },
    )

    @Test fun detectedProfileReproducesHardcodedNumbers() {
        val path = System.getProperty("corpus").orEmpty()
        val goldenPath = System.getProperty("golden").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists() && goldenPath.isNotEmpty() && File(goldenPath).exists())
        val messages = inbox(path)
        val detected = ProfileDetector.detect(messages)
        println("DETECTED sources=${detected.sources.map { "${it.source.bank}-${it.source.last4}-${it.source.type}x${it.count}" }}")
        println("DETECTED selfNames=${detected.selfNames} selfVpas=${detected.selfVpas} salaryDay=${detected.salaryDay} name=${detected.displayName}")

        val old = ledger(messages, goldenProfile(goldenPath))
        val new = ledger(messages, detected.toProfile())
        val today = messages.maxOf { it.at }.toLocalDate()

        fun perDay(t: List<Txn>) = t.groupBy { it.day }.mapValues { (_, l) -> l.sumOf(Ledger::todayAmount) }.filterValues { it != 0L }.toSortedMap()
        fun perCycle(t: List<Txn>) = Ledger.cycles(today, t, 5).associate { r -> r.start to t.filter { it.day in r }.sumOf(Ledger::monthAmount) }
        fun perCategory(t: List<Txn>) = t.groupBy { it.category }.mapValues { (_, l) -> l.sumOf(Ledger::monthAmount) }.filterValues { it != 0L }.toSortedMap()
        fun perSource(t: List<Txn>) = t.filter { Ledger.monthAmount(it) > 0 }.groupBy { it.parsed.source.toString() }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }.toSortedMap()

        assertEquals(perDay(old), perDay(new))
        assertEquals(perCycle(old), perCycle(new))
        assertEquals(perCategory(old), perCategory(new))
        assertEquals(perSource(old), perSource(new))
        assertEquals(28, detected.salaryDay)
    }
}
