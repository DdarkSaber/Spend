package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fixes from the Nov 2026 audit. Made-up amounts, cards and references in the banks' real wording. */
class AuditFixesTest {

    private val day = LocalDate.of(2026, 11, 9)
    private val profile = Profile(
        setOf(Source("HDFC", "1234", SourceType.ACCOUNT)), setOf(Source("SBI", "1111", SourceType.CARD), Source("SBI", "2222", SourceType.CARD)), emptySet(), emptySet(),
    )
    private val offer = "Dear Cardholder, convert your SBI Credit Card transactions of the combined value of Rs. 1,234.56 dated 29 OCT'26 into Flexipay EMIs of 24 or 36 months at Zero Processing Fee. Hurry! Offer Valid till 31 Oct'26. Book Now: https://app.sbicard.com/x. T&C"

    @Test fun offersAreNeverRebuiltAsCardSpends() {
        assertNull(TxnCard.rebuild(listOf("AX-SBICRD-P", offer), "AX-SBICRD-P", day))
        // A chat promo with an amount ("Flex credit card ... Rs.59") is not a card either.
        assertNull(TxnCard.rebuild(listOf("Google Pay", "Image", "Maximize everyday savings with Flex credit card. Get it at Rs.59. Apply now"), "Google Pay", day))
        // A real Truecaller card still is.
        assertTrue(TxnCard.rebuild(listOf("₹1,455.14", "•  SBI CARDS  •  Somefoodlimited"), "₹1,455.14", day) != null)
    }

    @Test fun rowsStoredFromAnOfferReadAsPromo() {
        assertEquals(ReadResult.Promo, SmsReader.read("NT-SBICRD-P", offer, profile))
        // What 1.2.3-1.2.7 stored from such an offer is never a spend.
        assertEquals(ReadResult.Promo, SmsReader.read("NT-SBICRD-T", "Rs.1234.56 spent on your SBI Credit Card at AX-SBICRD-P on 09/11/26. Trxn.", profile))
    }

    @Test fun sbiCardEmailIsReadLikeItsSms() {
        val email = "Transaction Alert from SBI Card\nDear Cardholder,\nThis is to inform you that,\nRs.218.24 spent on your SBI Credit Card ending 1111 at SOMEFOODLIMITED on 08/11/26. Trxn. not done by you? Report at https://sbicard.com/Dispute . If you have not authorized this transaction please contact the SBI Card helpline at 39 02 02 02"
        val sender = BankFromText.sender(email, "SBI Card Transaction Ale.")!!
        assertTrue(NotifGate.check(sender, email, profile).pass)
        val p = (SmsReader.read(sender, email, profile) as ReadResult.Parsed).parsed
        assertEquals(Template.SBI_CARD_SPENT, p.template)
        assertEquals(21824L, p.amountPaise)
        assertEquals("1111", p.source.last4)
        assertEquals(LocalDate.of(2026, 11, 8), p.date)
    }

    @Test fun hdfcUpiEmailSharesTheSmsReference() {
        val email = "❗ You have done a UPI txn. Check details!\nDear Customer, Greetings from HDFC Bank! Rs.1786.05 is debited from your account ending 1234 towards VPA **************gpay@okpayaxis (Some Bus Private Limited) on 12-11-26. UPI transaction reference no.: 100000000077. If you did not"
        val p = (SmsReader.read(BankFromText.sender(email, "HDFC Bank InstaAlerts")!!, email, profile) as ReadResult.Parsed).parsed
        assertEquals(Template.HDFC_UPI_SENT, p.template)
        assertEquals(178605L, p.amountPaise)
        assertEquals("100000000077", p.ref)
        val sms = (SmsReader.read("JD-HDFCBK-T", "Sent Rs.1786.05 From HDFC Bank A/C *1234 To Some Bus Private Limi On 12/11/26 Ref 100000000077 Not You? Call 18002586161", profile) as ReadResult.Parsed).parsed
        val at = LocalDate.of(2026, 11, 12).atTime(18, 14)
        // Same layout and reference: counted once.
        assertEquals(1, Dedupe.apply(listOf(Classifier.classify(sms, at, profile), Classifier.classify(p, at, profile))).size)
    }

    @Test fun oldAchWordingIsTheSameDebitAsItsUpdate() {
        val update = "UPDATE: INR 5,000.00 debited from HDFC Bank XX1234 on 27-MAY-24. Info: ACH D- Indian Clearing Corp-ZZZZ. Avl bal:INR 1,00,000.00"
        val towards = "INR 5000.00 debited to HDFC Bank A/C No XXXXXXXXXX1234 towards Indian Clearing Corporation Ltd / 0012345678 with UMRN HDFC0000000000000001"
        val at = LocalDate.of(2024, 5, 27).atTime(9, 0)
        val a = Classifier.classify((SmsReader.read("VM-HDFCBK-S", update, profile) as ReadResult.Parsed).parsed, at, profile)
        val bParsed = (SmsReader.read("VM-HDFCBK-S", towards, profile) as ReadResult.Parsed).parsed
        assertEquals(Template.HDFC_ACH_ALERT, bParsed.template)
        val b = Classifier.classify(bParsed, at.plusHours(2), profile)
        assertEquals(Kind.INVESTMENT_SIP, b.kind)
        assertEquals(1, Dedupe.apply(listOf(a, b)).size)
        // "IndianClearingCorporation" (no spaces) over UPI is an investment, not a spend.
        val upi = (SmsReader.read("VM-HDFCBK-S", "Sent Rs.5000.00 From HDFC Bank A/C *1234 To IndianClearingCorporation On 19/03/24 Ref 100000000002", profile) as ReadResult.Parsed).parsed
        assertEquals(Kind.INVESTMENT_LUMPSUM, Classifier.classify(upi, at, profile).kind)
    }
}
