package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HistoryFixesTest {

    private val me = Profile(
        setOf(Source("SBI", "5678", SourceType.ACCOUNT)),
        setOf(Source("SBI", "1111", SourceType.CARD), Source("SBI", "2222", SourceType.CARD)), emptySet(), emptySet(),
    )

    private fun read(sender: String, body: String, at: LocalDateTime): Txn =
        Classifier.classify((SmsReader.read(sender, body, me) as ReadResult.Parsed).parsed, at, me)

    @Test fun cyclesSinceGoesBackToTheFirstPaymentAndMatchesCycles() {
        val today = LocalDate.of(2026, 11, 8)
        val first = LocalDate.of(2022, 4, 18)
        val all = Ledger.cyclesSince(today, emptyList(), first)
        assertTrue("covers the first payment", all.last().start <= first)
        assertTrue("about 55 salary months", all.size in 54..56)
        assertEquals(Ledger.cycles(today, emptyList(), 12), all.take(12))
        assertEquals(12, Ledger.cyclesSince(today, emptyList(), first, max = 12).size)
    }

    @Test fun sbiCardBillPaymentConfirmedTwiceCountsOnce() {
        val at = LocalDateTime.of(2026, 10, 12, 10, 0)
        val viaUpi = read("TC-SBICRD-T", "We have received payment of Rs.27,951.76 via UPI & the same has been credited to your SBI Credit Card. Your available limit is Rs.332,000.00.", at)
        val processed = read("TC-SBICRD-T", "Dear SBI Cardholder, payment of Rs. 27951.76 for your SBI Credit Card has been successfully processed. ref no : EKT53GV1Z4WTSX.", at.plusMinutes(3))
        assertEquals(listOf(Kind.TRANSFER, Kind.TRANSFER), listOf(viaUpi.kind, processed.kind))
        val kept = Dedupe.apply(listOf(viaUpi, processed))
        assertEquals(1, kept.size)
        assertEquals("*", kept.single().parsed.source.last4)   // the bank-wide one, so no "which card?" question
        // Two real payments of the same amount on different days stay two.
        assertEquals(2, Dedupe.apply(listOf(viaUpi, processed.copy(receivedAt = at.plusDays(3)))).size)
    }

    @Test fun cutOffShopNamesAreShopsNotPeople() {
        listOf("ZEESHAN RESTAURA", "SRI SAKTHI AGENC", "CHALLENGER COMPU", "BIGTREE ENTERTAI", "POWER ELECTRONI",
            "VASANTHAM CATRING", "KAAVYA HOME NEEDS", "NIRMALA INDUSTRIES", "VETTRI THEATRES", "HUNGERBOX", "MAX RETAIL",
            "CLASSIC MALL DEV", "V DAMODARAN CO", "INDIANCLEARINGCORPORATION", "FIKA CROSSFIT FI", "ADYAR ANANDA BHA", "VI")
            .forEach { assertEquals(it, PayeeType.MERCHANT, PayeeGuess.type(it)) }
        assertEquals("Food", PayeeGuess.category("ZEESHAN RESTAURA"))
        assertEquals("Entertainment", PayeeGuess.category("BIGTREE ENTERTAI"))
        // People's names, including long cut-off ones, stay people.
        listOf("SIMITHIRA S", "ASHWIN SRINATH GOPINATH", "ANGELINSINDHU S", "MOHAMMED RAFIQ M", "ASWIN SREEKUMAR", "ADITYA KUMAR SIN")
            .forEach { assertEquals(it, PayeeType.PERSON, PayeeGuess.type(it)) }
    }
}
