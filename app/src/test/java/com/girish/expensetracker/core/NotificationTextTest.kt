package com.girish.expensetracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notification texts as Truecaller, Gmail and Google Messages post them (made-up names, digits and references). */
class NotificationTextTest {

    private val profile = Profile(
        accounts = setOf(Source("HDFC", "1234", SourceType.ACCOUNT), Source("SBI", "5678", SourceType.ACCOUNT)),
        cards = setOf(Source("SBI", "1111", SourceType.CARD), Source("ICICI", "9999", SourceType.CARD), Source("HDFC", "4321", SourceType.CARD)),
        selfNames = emptySet(), selfVpas = emptySet(),
    )

    private val sbiCard = "Rs.1000.00 spent on your SBI Credit Card ending 1111 at ZOMATO on 01/10/26. Trxn."
    private val hdfcUpi = "Sent Rs.135.34 From HDFC Bank A/C *1234 To RAVI KUMAR On 25/10/26 Ref 100000000001 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808"
    private val icici = "INR 1,250.00 spent using ICICI Bank Card XX9999 on 03-Sep-26 on SOMEAPP. Avl Limit: INR 1,95,899.53."
    private val sbiUpi = "Dear UPI user A/C X5678 debited by 75.00 on date 20Sep26 trf to Mr AARIF Refno 100000000010 If not u? call 1800111109. -SBI"

    @Test fun bankComesFromTheText() {
        assertEquals("NT-SBICRD-T", BankFromText.sender(sbiCard, "SBI Card"))
        assertEquals("NT-HDFCBK-T", BankFromText.sender(hdfcUpi, null))
        assertEquals("NT-ICICIT-T", BankFromText.sender(icici, "ICICI Bank"))
        assertEquals("NT-SBIINB-T", BankFromText.sender(sbiUpi, "State Bank of India"))
        assertEquals("NT-DCBANK-T", BankFromText.sender("DCB Bank Alert - A/c no. *7777 is credited by INR 30.", null))
        assertEquals("NT-AXISBK-T", BankFromText.sender("INR 500 debited from AXIS Bank A/c XX0001 on 01-11-26", null))
        // The title is only a hint, used when the text names no bank.
        assertEquals("NT-HDFCBK-T", BankFromText.sender("Rs.649 debited from card 4321", "HDFC Bank"))
        assertNull(BankFromText.sender("Your Swiggy order is on the way", "Swiggy"))
    }

    @Test fun knownLayoutsReadExactlyLikeTheirSms() {
        for ((sms, text) in listOf("VM-SBICRD-S" to sbiCard, "VM-HDFCBK-T" to hdfcUpi, "VA-ICICIT-S" to icici, "JD-SBIUPI-S" to sbiUpi)) {
            val sender = BankFromText.sender(text, null)!!
            val viaSms = SmsReader.read(sms, text, profile)
            val viaNotif = SmsReader.read(sender, text, profile)
            assertTrue("$text -> $viaNotif", viaNotif is ReadResult.Parsed)
            assertEquals((viaSms as ReadResult.Parsed).parsed, (viaNotif as ReadResult.Parsed).parsed)
            assertTrue(NotifGate.check(sender, text, profile).pass)
        }
    }

    @Test fun gateRejectsOtpsOffersAndChatter() {
        assertEquals("otp", NotifGate.check("NT-HDFCBK-T", "123456 is your OTP for txn of Rs.500 at SHOP on HDFC Bank card 4321", profile).reason)
        assertEquals("offer", NotifGate.check("NT-HDFCBK-T", "Congrats! Rs.60,000 credited to your pre-approved limit. Click here to claim", profile).reason)
        assertFalse(NotifGate.check("NT-HDFCBK-T", "HDFC Bank: thank you for choosing us", profile).pass)
        // An email-style alert in no known layout still passes on amount + verb + digits (it lands in Unparsed).
        val email = NotifGate.check("NT-HDFCBK-T", "Dear Customer, Rs.2,500.00 has been debited from your HDFC Bank account ending 1234 towards UPI/SHOP", profile)
        assertTrue(email.pass)
        assertFalse(NotifGate.check("NT-ICICIT-T", "ICICI Bank: update your app for new features", profile).pass)
    }
}
