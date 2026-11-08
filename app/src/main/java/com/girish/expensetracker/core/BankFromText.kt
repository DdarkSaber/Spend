package com.girish.expensetracker.core

/**
 * Which bank a notification's text is from, as a synthetic DLT sender ("NT-SBICRD-T"), so SmsReader.bankOf() and
 * every SMS layout work unchanged on Truecaller, Gmail and RCS alerts. Ordered: the first match wins. The
 * notification title (the chat or sender name) is only a hint, used when the text names no bank.
 */
object BankFromText {

    private val I = RegexOption.IGNORE_CASE

    private val ordered: List<Pair<Regex, String>> = listOf(
        Regex("SBI\\s*Credit\\s*Card|SBI\\s*Card", I) to "SBICRD",
        Regex("HDFC\\s*Bank", I) to "HDFCBK",
        Regex("ICICI\\s*Bank", I) to "ICICIT",
        Regex("DCB\\s*Bank|\\bNiyo\\b", I) to "DCBANK",
        Regex("Bank\\s+of\\s+Baroda", I) to "BOBTXN",
        Regex("Punjab\\s+National\\s+Bank", I) to "PNBSMS",
        Regex("Bank\\s+of\\s+India", I) to "BOIIND",
        Regex("Indian\\s+Overseas\\s+Bank", I) to "IOBCHN",
        Regex("Central\\s+Bank\\s+of\\s+India", I) to "CENTBK",
        Regex("South\\s+Indian\\s+Bank", I) to "SIBSMS",
        Regex("City\\s+Union\\s+Bank", I) to "CUBANK",
        Regex("Union\\s+Bank", I) to "UNIONB",
        Regex("\\bIndian\\s+Bank\\b", I) to "INDBNK",
        Regex("AU\\s+Small\\s+Finance|\\bAU\\s+Bank", I) to "AUBANK",
        Regex("\\bKotak\\b", I) to "KOTAKB",
        Regex("\\bIDFC\\b", I) to "IDFCBK",
        Regex("\\bYES\\s+Bank", I) to "YESBNK",
        Regex("Karnataka\\s+Bank", I) to "KBLBNK",
    )

    // State Bank account alerts: the bank's name plus an account, so SBI Card text (matched above) never lands here.
    private val sbi = Regex("\\bSBI\\b|State\\s+Bank", I)
    private val account = Regex("A/c|\\bAc\\b|account", I)

    /** "AXIS" -> "Axis Bank" pattern for every other bank the reader knows. */
    private val byName: List<Pair<Regex, String>> by lazy {
        val covered = ordered.map { it.second }.mapNotNull(SmsReader::bankOf).toSet() + setOf("SBI", "HDFC", "ICICI", "DCB")
        SmsReader.banks.filter { it !in covered }.sorted().mapNotNull { bank ->
            SmsReader.codeFor(bank)?.let { Regex("\\b${Regex.escape(bank)}\\s+Bank\\b", I) to it }
        }
    }

    fun code(text: String): String? {
        ordered.firstOrNull { it.first.containsMatchIn(text) }?.let { return it.second }
        if (sbi.containsMatchIn(text) && account.containsMatchIn(text)) return "SBIINB"
        return byName.firstOrNull { it.first.containsMatchIn(text) }?.second
    }

    /** "NT-SBICRD-T" from the text, else from the title; null when neither names a bank. */
    fun sender(text: String, title: String?): String? =
        (code(text) ?: title?.let { t -> code(t) ?: if (sbi.containsMatchIn(t)) "SBIINB" else null })?.let { "NT-$it-T" }
}

/**
 * Which notification texts are worth storing: a known layout, or an amount + a money verb + account or card digits.
 * OTPs and offers never pass. Returns the reason, for the audit log.
 */
object NotifGate {

    private val I = RegexOption.IGNORE_CASE
    private val otp = Regex("\\bOTP\\b|one[- ]time password|verification code|\\bcode is\\b", I)
    private val amount = Regex("(?:Rs\\.?|INR|₹)\\s*[\\d,]+(?:\\.\\d{1,2})?", I)
    private val verb = Regex("debited|credited|spent|paid|sent|received|withdrawn|charged|deducted|transferred|refund|purchase|txn|transaction", I)
    private val digits = Regex("(?:A/c|account|card|ending|no\\.?)\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with\\s*)?)?[xX*]*\\s*\\d{2,6}\\b", I)

    data class Result(val pass: Boolean, val reason: String)

    fun check(sender: String, body: String, profile: Profile, learned: List<LearnedLayout> = emptyList()): Result {
        if (otp.containsMatchIn(body)) return Result(false, "otp")
        return when (SmsReader.read(sender, body, profile.copy(acceptAll = true), learned)) {
            ReadResult.Promo -> Result(false, "offer")
            is ReadResult.Parsed, is ReadResult.UnknownSource -> Result(true, "layout")
            else -> when {
                // A card statement or available-limit notice moves no money but tells how a card stands.
                CardFacts.statement(sender, body, java.time.LocalDateTime.now()) != null ||
                    CardFacts.limit(sender, body, java.time.LocalDateTime.now()) != null -> Result(true, "card-facts")
                amount.containsMatchIn(body) && verb.containsMatchIn(body) && digits.containsMatchIn(body) -> Result(true, "money")
                else -> Result(false, "no-money")
            }
        }
    }
}
