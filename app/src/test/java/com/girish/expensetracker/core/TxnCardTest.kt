package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Truecaller transaction cards, laid out as on the phone (made-up merchant, card and amount). */
class TxnCardTest {

    private val day = LocalDate.of(2026, 11, 8)
    private val profile = Profile(emptySet(), setOf(Source("SBI", "4415", SourceType.CARD)), emptySet(), emptySet())

    @Test fun sbiCardSpendCardIsReadAsTheSbiSms() {
        val body = TxnCard.rebuild(listOf("Somefoodlimited", "• Credit Card xx4415", "₹1,455.14"), "Business chat from SBI Card", day)
        assertEquals("Rs.1455.14 spent on your SBI Credit Card ending 4415 at SOMEFOODLIMITED on 08/11/26. Trxn.", body)
        val sender = BankFromText.sender(body!!, "Business chat from SBI Card")!!
        assertTrue(NotifGate.check(sender, body, profile).pass)
        val p = (SmsReader.read(sender, body, profile) as ReadResult.Parsed).parsed
        assertEquals(145514L, p.amountPaise)
        assertEquals(Direction.DEBIT, p.direction)
        assertEquals("4415", p.source.last4)
        assertEquals(day, p.date)
    }

    @Test fun oneLineTextAndTitleAsMerchant() {
        val body = TxnCard.rebuild(listOf("Somefoodlimited", "Credit Card xx4415 · ₹ 99"), "SBI Card", day)
        assertEquals("Rs.99 spent on your SBI Credit Card ending 4415 at SOMEFOODLIMITED on 08/11/26. Trxn.", body)
    }

    @Test fun otherBanksUseTheGenericSentence() {
        val body = TxnCard.rebuild(listOf("Some Shop", "Credit Card XX9999", "₹250.00"), "Business chat from ICICI Bank", day)!!
        val p = SmsReader.read(BankFromText.sender(body, "ICICI Bank")!!, body, Profile(emptySet(), setOf(Source("ICICI", "9999", SourceType.CARD)), emptySet(), emptySet()))
        assertTrue("$body -> $p", p is ReadResult.Parsed && p.parsed.amountPaise == 25000L && p.parsed.direction == Direction.DEBIT)
    }

    @Test fun moneyInAndUnclearCardsAreLeftAlone() {
        assertNull(TxnCard.rebuild(listOf("Payment received", "Credit Card xx4415", "₹5,000"), "SBI Card", day))
        assertNull(TxnCard.rebuild(listOf("Somefood", "Credit Card xx4415", "+₹120"), "SBI Card", day))
        assertNull(TxnCard.rebuild(listOf("Refund", "Credit Card xx4415", "₹120"), "SBI Card", day))
        assertNull(TxnCard.rebuild(listOf("Somefood", "₹120"), "ICICI Bank", day)) // no card digits (non-SBI)
        assertNull(TxnCard.rebuild(listOf("Somefood", "Credit Card xx4415", "₹120"), "Business chat", day)) // no bank
        assertNull(TxnCard.rebuild(listOf("Somefood", "Credit Card xx4415", "₹120", "₹99"), "SBI Card", day)) // two amounts
    }

    /** Exactly what Truecaller posted on the phone (made-up merchant): amount as title, bank and merchant as text. */
    @Test fun truecallerExtrasWithoutDigitsGoToTheUnassignedSbiCard() {
        val title = "₹1,455.14"
        val text = "•  SBI CARDS  •  Somefoodlimited"
        val body = TxnCard.rebuild(listOf(title, text), title, day)
        assertEquals("Rs.1455.14 spent on your SBI Credit Card at SOMEFOODLIMITED on 08/11/26. Trxn.", body)
        val sender = BankFromText.sender(body!!, title)!!
        assertEquals("NT-SBICRD-T", sender)
        assertTrue(NotifGate.check(sender, body, profile).pass)
        // Two SBI cards: not assigned until the owner says which ("Which card was it?").
        val two = Profile(emptySet(), setOf(Source("SBI", "4415", SourceType.CARD), Source("SBI", "3652", SourceType.CARD)), emptySet(), emptySet())
        val p = (SmsReader.read(sender, body, two) as ReadResult.Parsed).parsed
        assertEquals(Source("SBI", UNASSIGNED, SourceType.CARD), p.source)
        assertEquals(145514L, p.amountPaise)
        // With the digits drawn in the card's layout, it lands on the card itself.
        val withDigits = TxnCard.rebuild(listOf(title, text, "Somefoodlimited", "• Credit Card", "xx4415", "₹1,455.14"), title, day)
        assertEquals("Rs.1455.14 spent on your SBI Credit Card ending 4415 at SOMEFOODLIMITED on 08/11/26. Trxn.", withDigits)
        // Other banks need the digits.
        assertNull(TxnCard.rebuild(listOf("₹99", "•  HDFC BANK  •  Shop"), "₹99", day))
    }

    /** The offer that was counted as a spend on 1.2.3 to 1.2.7: an SMS notification titled with its sender. */
    @Test fun anOfferIsNeverACardSpend() {
        val offer = "Dear Cardholder, convert your SBI Credit Card transactions of the combined value of Rs. 12,345 into Flexipay EMIs. Offer Valid till 15 Nov. Book Now T&C"
        assertNull(TxnCard.rebuild(listOf("AX-SBICRD-P", offer), "AX-SBICRD-P", day))
        // A sender header as the title is an SMS, not a card, whatever the text says.
        assertNull(TxnCard.rebuild(listOf("AX-SBICRD-S", "Rs. 500 at Some Shop"), "AX-SBICRD-S", day))
        // The row those versions already stored now reads as an offer, so it drops out of the totals.
        val stored = "Rs.12345 spent on your SBI Credit Card at AX-SBICRD-P on 09/11/26. Trxn."
        assertEquals(ReadResult.Promo, SmsReader.read("NT-SBICRD-T", stored, profile))
        // A real card rebuilt the same way is untouched.
        assertTrue(SmsReader.read("NT-SBICRD-T", "Rs.1455.14 spent on your SBI Credit Card at SOMEFOODLIMITED on 08/11/26. Trxn.", profile) is ReadResult.Parsed)
    }

    /** Truecaller shows direction only by the amount's colour. */
    @Test fun amountColourDecidesDirection() {
        assertEquals(Direction.DEBIT, TxnCard.directionOf(0xFFF26B3A.toInt()))  // orange, as on the phone
        assertEquals(Direction.DEBIT, TxnCard.directionOf(0xFFE53935.toInt()))  // red
        assertEquals(Direction.CREDIT, TxnCard.directionOf(0xFF2E7D32.toInt())) // green
        assertEquals(Direction.CREDIT, TxnCard.directionOf(0xFF4CAF50.toInt()))
        assertNull(TxnCard.directionOf(0xFFFFFFFF.toInt()))                      // white text: unknown
        assertNull(TxnCard.directionOf(0xFF9E9E9E.toInt()))                      // grey
        val parts = listOf("₹1,455.14", "•  SBI CARDS  •  Somefoodlimited")
        // Green (money in): never counted as a spend.
        assertNull(TxnCard.rebuild(parts, "₹1,455.14", day, amountColor = 0xFF2E7D32.toInt()))
        // Red/orange or unknown: a spend, as before.
        assertTrue(TxnCard.rebuild(parts, "₹1,455.14", day, amountColor = 0xFFF26B3A.toInt())!!.startsWith("Rs.1455.14 spent"))
        assertTrue(TxnCard.rebuild(parts, "₹1,455.14", day, amountColor = null) != null)
    }
}
