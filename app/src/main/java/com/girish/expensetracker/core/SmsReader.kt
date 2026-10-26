package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** What happened to one incoming SMS. */
sealed interface ReadResult {
    /** Not from a bank transaction sender (OTP apps, shops, personal numbers...). */
    data object NotBank : ReadResult
    /** Bank sender, but promotional (-P) or offer/scam wording. */
    data object Promo : ReadResult
    /** Known layout, but no money moved (pre-debit notices, reward points...). */
    data object Informational : ReadResult
    /** Known layout for an account/card that is not on the tracked list. */
    data class UnknownSource(val parsed: ParsedSms) : ReadResult
    /** Looks like a transaction but no layout matched: goes to the Unparsed list, never counted. */
    data class Unparsed(val body: String) : ReadResult
    data class Parsed(val parsed: ParsedSms) : ReadResult
}

object SmsReader {

    // DLT headers look like "VM-HDFCBK-S" (prefix, 6-char entity, optional S/T/G/P type).
    private val dltHeader = Regex("^[A-Z]{2}-([A-Z0-9]{6})(?:-([STGP]))?$", RegexOption.IGNORE_CASE)

    private val bankCodes = setOf(
        "HDFCBK", "HDFCBN", "SBIUPI", "SBICRD", "MYSBIC", "SBIINB", "SBIPSG",
        "ICICIT", "ICICIB", "ICICIS", "DCBANK", "DCBBNK",
    )

    private val promoPhrases = listOf(
        "pre-approved", "preapproved", "pre approved", "eligible", "eligibility", "personal loan",
        "loan offer", "offer", "claim", "click here", "step away", "kyc", "reward worth",
        "you have won", "you've won", "lucky", "limited period", "apply now", "upgrade your",
    )

    private val informationalPhrases = listOf(
        "will be debited", "reward points credited", "mandate to", "has been cancelled",
        "txn locked", "login pin", "fingerprint login", "logged in", "reset password",
        "upi registration", "adding dcb bank account",
        // Statements, reminders and duplicate confirmations: no new money moved.
        "statement", "will be deducted", "will auto-debit", "has been mailed",
        "we have successfully debited your hdfc bank credit card ending",
    )

    private val looksTransactional = Regex(
        "(?:rs\\.?|inr)\\s?[\\d,]+(?:\\.\\d+)?.*?(?:debited|credited|spent|sent|deducted|received|paid|withdrawn|refund|reversed)" +
            "|(?:debited|credited|spent|sent|deducted|received|paid|withdrawn|refund|reversed).*?(?:rs\\.?|inr)\\s?[\\d,]+",
        RegexOption.IGNORE_CASE,
    )

    fun read(sender: String, body: String, profile: Profile): ReadResult {
        val header = dltHeader.matchEntire(sender.trim()) ?: return ReadResult.NotBank
        val entity = header.groupValues[1].uppercase()
        val type = header.groupValues[2].uppercase()
        if (entity !in bankCodes) return ReadResult.NotBank
        if (type == "P") return ReadResult.Promo

        val text = body.replace(Regex("\\s+"), " ").trim()
        val lower = text.lowercase()
        if (informationalPhrases.any { it in lower }) return ReadResult.Informational

        val parsed = Layouts.parse(text)
        if (parsed == null) {
            if (promoPhrases.any { it in lower }) return ReadResult.Promo
            return if (looksTransactional.containsMatchIn(text)) ReadResult.Unparsed(text) else ReadResult.Informational
        }
        if (!profile.isKnown(parsed.source)) {
            // A transfer between your own accounts can mention an account that bank doesn't hold; it is never spend.
            val cp = parsed.counterparty.orEmpty()
            if (profile.isSelfName(cp) || profile.isSelfVpa(cp)) return ReadResult.Informational
            return ReadResult.UnknownSource(parsed)
        }
        return ReadResult.Parsed(parsed)
    }
}

/** The bank SMS layouts seen in real messages (Jul–Oct 2026). Each returns null if the text does not match. */
internal object Layouts {

    private const val AMT = "([\\d,]+(?:\\.\\d{1,2})?)"
    private val I = RegexOption.IGNORE_CASE

    private class Layout(val regex: Regex, val build: (MatchResult) -> ParsedSms?)

    private val layouts: List<Layout> = listOf(
        // Sent Rs.135.34 From HDFC Bank A/C *1234 To RAVI SHANKAR On 25/10/26 Ref 100000000001
        Layout(Regex("^Sent Rs\\.?$AMT From HDFC Bank A/C \\*?(\\d{4}) To (.+?) On (\\d{2}/\\d{2}/\\d{2}) Ref (\\d+)", I)) { m ->
            ParsedSms(Template.HDFC_UPI_SENT, Direction.DEBIT, paise(m[1]), acct("HDFC", m[2]), m[3], Dates.parse(m[4]), m[5], null)
        },
        // UPI Mandate: Sent Rs.170.00 from HDFC Bank A/c 1234 To Google Play 15/08/26 Ref 100000000001
        Layout(Regex("^UPI Mandate: Sent Rs\\.?$AMT from HDFC Bank A/c \\*?(\\d{4}) To (.+?) (\\d{2}/\\d{2}/\\d{2}) Ref (\\d+)", I)) { m ->
            ParsedSms(Template.HDFC_UPI_MANDATE, Direction.DEBIT, paise(m[1]), acct("HDFC", m[2]), m[3], Dates.parse(m[4]), m[5], null)
        },
        // UPDATE: INR 5,000.00 debited from HDFC Bank XX1234 on 02-OCT-26. Info: ACH D- GROWW INVEST TECH PR-XXXX. Avl bal:INR ...
        Layout(Regex("^UPDATE: INR $AMT debited from HDFC Bank XX(\\d{4}) on (\\d{2}-[A-Z]{3}-\\d{2})\\. Info: (.+?)\\. Avl bal", I)) { m ->
            ParsedSms(Template.HDFC_ACH_UPDATE, Direction.DEBIT, paise(m[1]), acct("HDFC", m[2]), achPayee(m[4]), Dates.parse(m[3]), null, m[4])
        },
        // PAYMENT ALERT! INR 9000.00 deducted from HDFC Bank A/C No 1234 towards Indian Clearing Corporation Lt UMRN: ...
        Layout(Regex("^PAYMENT ALERT! INR $AMT deducted from HDFC Bank A/C No (\\d{4}) towards (.+?) UMRN", I)) { m ->
            ParsedSms(Template.HDFC_ACH_ALERT, Direction.DEBIT, paise(m[1]), acct("HDFC", m[2]), m[3].trim(), null, null, m[3].trim())
        },
        // Spent Rs.585 On HDFC Bank Card 4321 At ..PVR LIMITED_ On 2026-08-19:00:16:16.
        Layout(Regex("^Spent Rs\\.?$AMT On HDFC Bank Card (\\d{4}) At (.+?) On (\\d{4}-\\d{2}-\\d{2})", I)) { m ->
            ParsedSms(Template.HDFC_CARD_SPENT, Direction.DEBIT, paise(m[1]), card("HDFC", m[2]), cleanMerchant(m[3]), Dates.parse(m[4]), null, null)
        },
        // Rs.649 without OTP/PIN HDFC Bank Card x4321 At NETFLIX On 2026-08-25:21:56:18.  (recurring card charge)
        Layout(Regex("^Rs\\.?$AMT without OTP/PIN HDFC Bank Card x(\\d{4}) At (.+?) On (\\d{4}-\\d{2}-\\d{2})", I)) { m ->
            ParsedSms(Template.HDFC_CARD_SPENT, Direction.DEBIT, paise(m[1]), card("HDFC", m[2]), cleanMerchant(m[3]), Dates.parse(m[4]), null, null)
        },
        // Amount Spent: Rs.649 with HDFC Bank Credit Card 4321 at NETFLIX on 2026-10-25:23:17:51
        Layout(Regex("^Amount Spent: Rs\\.?$AMT with HDFC Bank Credit Card (\\d{4}) at (.+?) on (\\d{4}-\\d{2}-\\d{2})", I)) { m ->
            ParsedSms(Template.HDFC_CARD_AMOUNT_SPENT, Direction.DEBIT, paise(m[1]), card("HDFC", m[2]), cleanMerchant(m[3]), Dates.parse(m[4]), null, null)
        },
        // AutoPay (E-mandate) Success! For NETFLIX Txn Amt:INR649.00 Dt:25/10/2026 Via:HDFC Bank CC 4321
        Layout(Regex("^AutoPay \\(E-mandate\\) Success! For (.+?) Txn Amt:INR ?$AMT Dt:(\\d{2}/\\d{2}/\\d{4}) Via:HDFC Bank CC (\\d{4})", I)) { m ->
            ParsedSms(Template.HDFC_AUTOPAY_SUCCESS, Direction.DEBIT, paise(m[2]), card("HDFC", m[4]), cleanMerchant(m[1]), Dates.parse(m[3]), null, null)
        },
        // DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 6000.00 RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH 4321 ON 23-9-2026.
        Layout(Regex("PAYMENT OF Rs\\.? ?$AMT RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH (\\d{4}) ON (\\d{1,2}-\\d{1,2}-\\d{4})", I)) { m ->
            ParsedSms(Template.HDFC_CARD_PAYMENT_RECEIVED, Direction.CREDIT, paise(m[1]), card("HDFC", m[2]), null, Dates.parse(m[3]), null, null)
        },
        // HDFC Bank Cardmember, Payment of Rs 3000.00 was credited to your card ending 4321 on 30/JUL/2026.
        Layout(Regex("Payment of Rs\\.? ?$AMT was credited to your card ending (\\d{4}) on (\\d{2}/[A-Z]{3}/\\d{4})", I)) { m ->
            ParsedSms(Template.HDFC_CARD_PAYMENT_CREDITED, Direction.CREDIT, paise(m[1]), card("HDFC", m[2]), null, Dates.parse(m[3]), null, null)
        },
        // Credit Alert! Rs.5000.00 credited to HDFC Bank A/c XX1234 on 10-09-26 from VPA someone@oksbi (UPI 100000000001)
        Layout(Regex("^Credit Alert! Rs\\.?$AMT credited to HDFC Bank A/c XX(\\d{4}) on (\\d{2}-\\d{2}-\\d{2}) from VPA (\\S+) \\(UPI (\\d+)\\)", I)) { m ->
            ParsedSms(Template.HDFC_UPI_CREDIT, Direction.CREDIT, paise(m[1]), acct("HDFC", m[2]), m[4], Dates.parse(m[3]), m[5], null)
        },
        // Update! INR 1,00,000.00 deposited in HDFC Bank A/c XX1234 on 07-AUG-26 for NEFT Cr-...-SALARY PAYMENT-....Avl bal INR ...
        Layout(Regex("^Update! INR $AMT deposited in HDFC Bank A/c XX(\\d{4}) on (\\d{2}-[A-Z]{3}-\\d{2}) for (.+?)\\.?Avl bal", I)) { m ->
            ParsedSms(Template.HDFC_DEPOSIT, Direction.CREDIT, paise(m[1]), acct("HDFC", m[2]), null, Dates.parse(m[3]), null, m[4].trim())
        },
        // Received! INR 50,000.00 in HDFC Bank A/c xx1234 On 15-09-26 For IMPS -GROWW INVEST TECH PR- 100000000001 Avl bal INR ...
        Layout(Regex("^Received! INR $AMT in HDFC Bank A/c xx(\\d{4}) On (\\d{2}-\\d{2}-\\d{2}) For (.+?) Avl bal", I)) { m ->
            ParsedSms(Template.HDFC_IMPS_RECEIVED, Direction.CREDIT, paise(m[1]), acct("HDFC", m[2]), null, Dates.parse(m[3]), null, m[4].trim())
        },
        // Dear UPI user A/C X5678 debited by 70.00 on date 24Oct26 trf to KRISHNA STORES Refno 100000000001
        Layout(Regex("^Dear UPI user A/C X(\\d{4}) debited by $AMT on date (\\d{1,2}[A-Z]{3}\\d{2}) trf to (.+?) Refno (\\d+)", I)) { m ->
            ParsedSms(Template.SBI_UPI_DEBIT, Direction.DEBIT, paise(m[2]), acct("SBI", m[1]), m[4].trim(), Dates.parse(m[3]), m[5], null)
        },
        // Dear SBI User, your A/c X5678-credited by Rs.1286 on 18Aug26 transfer from FRIENDNAMEKS Ref No 100000000001 -SBI
        Layout(Regex("^Dear SBI User, your A/c X(\\d{4})-credited by Rs\\.?$AMT on (\\d{1,2}[A-Z]{3}\\d{2}) transfer from (.+?) Ref No (\\d+)", I)) { m ->
            ParsedSms(Template.SBI_CREDIT, Direction.CREDIT, paise(m[2]), acct("SBI", m[1]), m[4].trim(), Dates.parse(m[3]), m[5], null)
        },
        // Rs.723.08 spent on your SBI Credit Card ending 1111 at ZOMATO on 03/08/26.
        Layout(Regex("^Rs\\.?$AMT spent on your SBI Credit Card ending (\\d{4}) at (.+?) on (\\d{2}/\\d{2}/\\d{2})", I)) { m ->
            ParsedSms(Template.SBI_CARD_SPENT, Direction.DEBIT, paise(m[1]), card("SBI", m[2]), cleanMerchant(m[3]), Dates.parse(m[4]), null, null)
        },
        // Dear SBI Cardholder, payment of Rs. 27951.76 for your SBI Credit Card has been successfully processed. ref no : EKT53GV1Z4WTSX.
        Layout(Regex("payment of Rs\\.? ?$AMT for your SBI Credit Card has been successfully processed\\.?(?: ref no ?: ?(\\w+))?", I)) { m ->
            ParsedSms(Template.SBI_CARD_PAYMENT, Direction.CREDIT, paise(m[1]), Source("SBI", "*", SourceType.CARD), null, null, m[2].ifEmpty { null }, null)
        },
        // DCB Bank Alert - A/c no. *7777 is credited by INR 30. Avail Bal is INR 10000.00 on 17-oct-2026 02:15 PM.
        Layout(Regex("^DCB Bank Alert - A/c no\\. \\*(\\d{4}) is credited by INR $AMT\\. .*? on (\\d{2}-(?:[A-Z]{3}|\\d{2})-\\d{4})", I)) { m ->
            ParsedSms(Template.DCB_CREDIT, Direction.CREDIT, paise(m[2]), acct("DCB", m[1]), null, Dates.parse(m[3]), null, null)
        },
        // INR 1000.00 credited to your DCB Bank a/c 7777 from UPI ID someone@okaxis. UPI ref. 100000000001. Available balance ...
        Layout(Regex("^INR $AMT credited to your DCB Bank a/c (\\d{4}) from UPI ID (\\S+?)\\.? UPI ref\\. (\\d+)", I)) { m ->
            ParsedSms(Template.DCB_UPI_CREDIT, Direction.CREDIT, paise(m[1]), acct("DCB", m[2]), m[3], null, m[4], null)
        },
        // INR 2000.00 debited DCB a/c 7777; to SOME NAME; UPI 100000000001. Not you? ...
        Layout(Regex("^INR $AMT debited DCB a/c (\\d{4}); to (.+?); UPI (\\d+)", I)) { m ->
            ParsedSms(Template.DCB_UPI_DEBIT, Direction.DEBIT, paise(m[1]), acct("DCB", m[2]), m[3].trim(), null, m[4], null)
        },
        // INR 1014.1 debited DCB Bank a/c*7777 POS/Ecom txn to some shop on 31-08-2026 03:34 AM.
        Layout(Regex("^INR $AMT debited DCB Bank a/c\\*(\\d{4}) POS/Ecom txn to (.+?) on (\\d{2}-\\d{2}-\\d{4})", I)) { m ->
            ParsedSms(Template.DCB_CARD_SPENT, Direction.DEBIT, paise(m[1]), acct("DCB", m[2]), cleanMerchant(m[3]), Dates.parse(m[4]), null, null)
        },
        // INR 1,199.00 spent using ICICI Bank Card XX9999 on 16-Oct-26 on HOYOVERSE. Avl Limit: ...
        Layout(Regex("^INR $AMT spent using ICICI Bank Card XX(\\d{4}) on (\\d{2}-[A-Z]{3}-\\d{2}) on (.+?)\\. Avl Limit", I)) { m ->
            ParsedSms(Template.ICICI_CARD_SPENT, Direction.DEBIT, paise(m[1]), card("ICICI", m[2]), cleanMerchant(m[4]), Dates.parse(m[3]), null, null)
        },
        // Dear Customer, payment of INR 2,000.00 towards your ICICI Bank Credit Card XX9998 has been received through Click to Pay on 03-SEP-26.
        Layout(Regex("payment of INR $AMT towards your ICICI Bank Credit Card XX(\\d{4}) has been received.*? on (\\d{2}-[A-Z]{3}-\\d{2})", I)) { m ->
            ParsedSms(Template.ICICI_CARD_PAYMENT, Direction.CREDIT, paise(m[1]), card("ICICI", m[2]), null, Dates.parse(m[3]), null, null)
        },
        // Rs.5000 withdrawn from HDFC Bank Card x4321 at +P H ROAD BRANCH on 2026-08-14:17:32:20.
        Layout(Regex("^Rs\\.?$AMT withdrawn from HDFC Bank Card x(\\d{4}) at (.+?) on (\\d{4}-\\d{2}-\\d{2})", I)) { m ->
            ParsedSms(Template.HDFC_CARD_WITHDRAWAL, Direction.DEBIT, paise(m[1]), card("HDFC", m[2]), cleanMerchant(m[3]), Dates.parse(m[4]), null, null)
        },
        // Bill Paid: VILPOSTPAID Bill 99XXXXXXXX of Rs. 532.19 paid on 07-Aug-2026 from HDFC Bank Credit Card xx4321 via SmartPay. Ref:...
        Layout(Regex("^Bill Paid: (\\S+) Bill \\S+ of Rs\\.? ?$AMT paid on (\\d{2}-[A-Z]{3}-\\d{4}) from HDFC Bank Credit Card xx(\\d{4})", I)) { m ->
            ParsedSms(Template.HDFC_SMARTPAY_BILL, Direction.DEBIT, paise(m[2]), card("HDFC", m[4]), m[1], Dates.parse(m[3]), null, "Bill")
        },
    )

    fun parse(text: String): ParsedSms? {
        for (layout in layouts) {
            val m = layout.regex.find(text) ?: continue
            return layout.build(m) ?: continue
        }
        return null
    }

    private operator fun MatchResult.get(i: Int): String = groupValues[i]

    private fun paise(raw: String): Long {
        val clean = raw.replace(",", "")
        val parts = clean.split('.')
        val rupees = parts[0].toLong()
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0L
        return rupees * 100 + frac
    }

    private fun acct(bank: String, last4: String) = Source(bank, last4, SourceType.ACCOUNT)
    private fun card(bank: String, last4: String) = Source(bank, last4, SourceType.CARD)

    /** "..PVR LIMITED_" -> "PVR LIMITED" */
    private fun cleanMerchant(raw: String): String = raw.trim().trim('.', '_', ' ')

    /** "ACH D- GROWW INVEST TECH PR-AIETEPGNRN9T" -> "GROWW INVEST TECH PR" */
    private fun achPayee(info: String): String =
        info.removePrefix("ACH D-").trim().substringBeforeLast('-').trim()
}

internal object Dates {
    private fun f(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH)

    private val formats = listOf(
        Regex("^\\d{2}/\\d{2}/\\d{2}$") to f("dd/MM/yy"),
        Regex("^\\d{2}/\\d{2}/\\d{4}$") to f("dd/MM/yyyy"),
        Regex("^\\d{2}-\\d{2}-\\d{2}$") to f("dd-MM-yy"),
        Regex("^\\d{1,2}-\\d{1,2}-\\d{4}$") to f("d-M-yyyy"),
        Regex("^\\d{4}-\\d{2}-\\d{2}$") to f("yyyy-MM-dd"),
        Regex("^\\d{2}-[A-Za-z]{3}-\\d{2}$") to f("dd-MMM-yy"),
        Regex("^\\d{2}-[A-Za-z]{3}-\\d{4}$") to f("dd-MMM-yyyy"),
        Regex("^\\d{2}/[A-Za-z]{3}/\\d{4}$") to f("dd/MMM/yyyy"),
        Regex("^\\d{1,2}[A-Za-z]{3}\\d{2}$") to f("dMMMyy"),
    )

    fun parse(raw: String): LocalDate? {
        val s = raw.trim()
        for ((shape, fmt) in formats) {
            if (shape.matches(s)) return runCatching { LocalDate.parse(s, fmt) }.getOrNull()
        }
        return null
    }
}
