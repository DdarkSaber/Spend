package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ParseCacheTest {

    private val profile = Profile(
        accounts = setOf(Source("HDFC", "1234", SourceType.ACCOUNT), Source("SBI", "5678", SourceType.ACCOUNT)),
        cards = setOf(Source("HDFC", "4321", SourceType.CARD), Source("ICICI", "9999", SourceType.CARD)),
        selfNames = setOf("TEST USER"),
        selfVpas = setOf("testuser@okhdfcbank"),
    )
    private val at = LocalDateTime.of(2026, 9, 3, 10, 15, 30)

    private fun roundTrip(r: Reading) = assertEquals(r, ParseCache.decode(ParseCache.encode(r)))

    private fun reading(sender: String, body: String) = Reading(
        SmsReader.read(sender, body, profile),
        CardFacts.statement(sender, body, at),
        CardFacts.limit(sender, body, at),
        SmsReader.bankOf(sender)?.let { UnusualSpend.foreignCharge(it, body, at) },
    )

    @Test fun realReadingsSurviveTheCache() {
        listOf(
            "VM-HDFCBK-S" to "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SOME SHOP\nOn 19/08/26\nRef 123456789012\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
            "JD-HDFCBK-S" to "Spent Rs.100 On HDFC Bank Card 4321 At SHOP On 2026-08-19:00:16:16.",
            "VA-ICICIT-S" to "USD 11.80 spent using ICICI Bank Card XX9999 on 03-Sep-26 on SOMEAPP. Avl Limit: INR 1,95,899.53.",
            "VM-SBICRD-S" to "Dear SBI Cardholder, payment of Rs. 27951.76 for your SBI Credit Card has been successfully processed. ref no : EKT00000000000.",
            "AX-HDFCBN-P" to "Unlimited Cover: Say goodbye to claim limits. Rs.500 credited as bonus",
            "+919876543210" to "Paid Rs.120 for your ride",
            "AD-HDFCBK-S" to "Alert: INR.649.00 will be debited on 24/10/2026 from HDFC Bank Card 4321 for NETFLIX ID:Xx1",
        ).forEach { (s, b) -> roundTrip(reading(s, b)) }
    }

    @Test fun everyShapeAndAwkwardTextSurvive() {
        val p = ParsedSms(Template.GENERIC_DEBIT, Direction.DEBIT, 12_345, Source("AXIS", "0007", SourceType.ACCOUNT), "A:B ~ 12:x\n₹ शुभ", LocalDate.of(2026, 1, 2), "~", "")
        listOf(
            Reading(ReadResult.NotBank, null, null, null),
            Reading(ReadResult.Promo, null, null, null),
            Reading(ReadResult.Informational, CardStatement("HDFC", null, LocalDate.of(2026, 2, 3), null, 100, null), null, null),
            Reading(ReadResult.Unparsed("3:abc~"), null, LimitSeen("ICICI", "9999", 5, at), null),
            Reading(ReadResult.UnknownSource(p.copy(counterparty = null, date = null, ref = null, narration = null)), null, null, null),
            Reading(ReadResult.Parsed(p), CardStatement("SBI", "52", LocalDate.of(2026, 2, 3), 99, 9, LocalDate.of(2026, 2, 23)),
                LimitSeen("SBI", "52", 0, at), Unusual("fx|k", "Foreign: USD 1", "text: ~", LocalDate.of(2026, 2, 4))),
        ).forEach(::roundTrip)
    }

    @Test fun garbageReadsAgainInsteadOfCrashing() {
        assertNull(ParseCache.decode(""))
        assertNull(ParseCache.decode("R"))
        assertNull(ParseCache.decode("1:Z~~~"))
        assertNull(ParseCache.decode(ParseCache.encode(Reading(ReadResult.NotBank, null, null, null)) + "x"))
    }

    @Test fun versionIsStableAndFollowsTheProfile() {
        val v = ParseCache.version(profile, emptyList(), "Asia/Kolkata")
        // Same inputs in another order give the same version; it does not depend on hash codes of this run.
        val reordered = profile.copy(accounts = profile.accounts.reversed().toSet(), cards = profile.cards.reversed().toSet())
        assertEquals(v, ParseCache.version(reordered, emptyList(), "Asia/Kolkata"))
        assertEquals(ParseCache.PARSER_VERSION.toLong(), v ushr 32)
        assertNotEquals(v, ParseCache.version(profile.copy(selfNames = setOf("OTHER")), emptyList(), "Asia/Kolkata"))
        assertNotEquals(v, ParseCache.version(profile, listOf(LearnedLayout("AXIS", "p", "r", true, false)), "Asia/Kolkata"))
        assertNotEquals(v, ParseCache.version(profile, emptyList(), "UTC"))
    }
}
