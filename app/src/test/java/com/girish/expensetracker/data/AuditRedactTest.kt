package com.girish.expensetracker.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the audit log (and the debug bundle) may hold: bank alerts as text, everything else as lengths. */
class AuditRedactTest {

    private fun sms(sender: String, text: String) = JSONObject().put("kind", "sms").put("sender", sender).put("text", text).put("result", "not-bank")
    private fun notif(title: String?, text: String, bank: String? = null) = JSONObject().put("kind", "notif").put("pkg", "com.google.android.apps.messaging")
        .put("title", title ?: JSONObject.NULL).put("text", text).put("candidate", text).put("bankGuess", bank ?: JSONObject.NULL)

    @Test fun personalSmsKeepsNoTextAndNoNumber() {
        val e = Audit.redact(sms("+919876543210", "see you at 8, bring the keys"))
        assertFalse(e.has("text"))
        assertEquals(28, e.getInt("textLen"))
        assertEquals("(masked)", e.getString("sender"))
        assertTrue(e.getBoolean("masked"))
        assertFalse(e.getBoolean("moneyLike"))
    }

    @Test fun aRegisteredNonBankSenderIsKeptAndMoneyLikeTextIsFlagged() {
        val e = Audit.redact(sms("VM-NEWBNK-S", "Rs.500 debited from A/c XX1234 to SOMEONE"))
        assertEquals("VM-NEWBNK-S", e.getString("sender"))
        assertFalse(e.has("text"))
        assertTrue(e.getBoolean("moneyLike"))
    }

    @Test fun bankAlertsStayAsTheyAreAndOneTimeCodesLoseTheirDigits() {
        val spend = "Rs.723.08 spent on your SBI Credit Card ending 1111 at ZOMATO on 03/08/26."
        assertEquals(spend, Audit.redact(sms("VM-SBICRD-S", spend)).getString("text"))
        val otp = Audit.redact(sms("VM-HDFCBK-S", "123456 is your OTP for Rs.5000 on card 4321"))
        assertEquals("###### is your OTP for Rs.#### on card ####", otp.getString("text"))
    }

    @Test fun notificationsThatNameNoBankAreMasked() {
        val chat = Audit.redact(notif("Arun", "pay me Rs 500 when you can"))
        assertFalse(chat.has("text")); assertFalse(chat.has("title")); assertFalse(chat.has("candidate"))
        assertEquals(4, chat.getInt("titleLen"))
        assertEquals("com.google.android.apps.messaging", chat.getString("pkg"))
        // A Truecaller card names its bank in the text, a stored alert in bankGuess: both kept.
        val card = Audit.redact(notif("₹1,455.14", "•  SBI CARDS  •  Somefoodlimited"))
        assertEquals("•  SBI CARDS  •  Somefoodlimited", card.getString("text"))
        val stored = Audit.redact(notif("VM-SBICRD-S", "Rs.10 spent", bank = "VM-SBICRD-S"))
        assertEquals("Rs.10 spent", stored.getString("text"))
    }

    @Test fun oldLinesAreRedactedOnExportAndBrokenOnesDropped() {
        val old = sms("+919876543210", "your code is 482913").toString()
        val out = JSONObject(Audit.redactLine(old))
        assertFalse(out.has("text"))
        assertEquals(out.toString(), Audit.redactLine(out.toString())) // already masked: unchanged
        assertTrue(JSONObject(Audit.redactLine("not json")).getBoolean("masked"))
        // Other kinds (dedupe collapses) pass through.
        val collapse = JSONObject().put("kind", "l2-collapse").put("droppedText", "Rs.10 spent")
        assertEquals("Rs.10 spent", Audit.redact(collapse).getString("droppedText"))
    }
}
