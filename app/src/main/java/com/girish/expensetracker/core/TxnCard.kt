package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Truecaller Business Chat "transaction cards": no sentence, only the merchant, the amount and "Credit Card xx4415",
 * with the bank in the header ("Business chat from SBI Card"). The card is rebuilt as the bank's own SMS sentence so
 * the normal layouts read it. Only card spends: anything that reads like money in (refund, payment, credited) is left
 * alone, because the card shows no direction to be sure of.
 */
object TxnCard {

    private val I = RegexOption.IGNORE_CASE
    private val amount = Regex("(?:₹|Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d{1,2})?)", I)
    private val card = Regex("(?:Credit\\s*Card|Card)\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with\\s*)?)?[xX*•.\\s]*(\\d{4})\\b", I)
    private val moneyIn = Regex("credited|received|refund|revers|cashback|payment|paid\\s+to\\s+(?:your\\s+)?card|declined|failed|\\+\\s*(?:₹|Rs|INR)", I)
    private val sentence = Regex("\\b(spent|debited|charged|purchase|txn|transactions?)\\b", I)
    // An offer quotes an amount too ("convert transactions of Rs. X into Flexipay EMIs. Book Now"): never a spend.
    private val offer = Regex("\\boffers?\\b|\\bEMIs?\\b|flexipay|\\bconvert|book now|valid (?:till|until|upto)|T&C|\\bloan\\b|pre-?approved|https?://", I)
    // A sender header ("AX-SBICRD-P") is the title of an SMS notification, never a merchant.
    private val senderHeader = Regex("^[A-Z]{2}-[A-Z0-9]{6}(?:-[STGP])?$", I)
    private val ddMMyy = DateTimeFormatter.ofPattern("dd/MM/yy")

    /** Red or orange -> money out, green -> money in, anything else (grey, white, unknown) -> null. */
    fun directionOf(argb: Int): Direction? {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        if (max < 0.2f || d / max < 0.35f) return null // too dark or too grey to tell
        val hue = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return when {
            hue < 45f || hue >= 330f -> Direction.DEBIT
            hue in 80f..170f -> Direction.CREDIT
            else -> null
        }
    }

    private val bankNames = mapOf("SBICRD" to "SBI", "HDFCBK" to "HDFC Bank", "ICICIT" to "ICICI Bank", "DCBANK" to "DCB Bank")

    /**
     * [parts] are the notification's texts (title, text, lines, texts drawn in its layout); [hint] the header/sub text.
     * Returns the rebuilt SMS sentence, or null when this is not a card spend.
     */
    fun rebuild(parts: List<String>, hint: String?, day: LocalDate, amountColor: Int? = null): String? {
        // Truecaller shows direction only by colour: red/orange = money out, green = money in.
        if (amountColor != null && directionOf(amountColor) == Direction.CREDIT) return null
        val all = parts.joinToString(" \n ")
        if (moneyIn.containsMatchIn(all) || sentence.containsMatchIn(all) || offer.containsMatchIn(all)) return null
        if (parts.any { senderHeader.matches(it.trim()) }) return null
        val amt = amount.findAll(all).map { it.groupValues[1] }.distinct().toList().singleOrNull() ?: return null
        val digits = card.findAll(all).map { it.groupValues[1] }.distinct().toList()
        if (digits.size > 1) return null
        val last4 = digits.singleOrNull()
        val code = BankFromText.code(all) ?: hint?.let(BankFromText::code) ?: return null
        // Without the card's digits only SBI Card is read ("•  SBI CARDS  •  Merchant", amount as the title): it is
        // filed on the bank's not-assigned card, and "Which card was it?" puts it (and the shop) on the right one.
        if (last4 == null && code != "SBICRD") return null
        val merchant = parts.asSequence()
            .flatMap { it.split('\n', '•', '·', '|').asSequence() }
            .map { it.trim() }
            .filter { it.length >= 2 && !amount.containsMatchIn(it) && !card.containsMatchIn(it) && BankFromText.code(it) == null }
            .filter { it.any(Char::isLetter) && !it.equals("Credit Card", true) && !it.contains("business chat", true) && !it.contains("truecaller", true) }
            .firstOrNull() ?: return null
        val rs = amt.replace(",", "")
        val date = day.format(ddMMyy)
        if (last4 == null) return "Rs.$rs spent on your SBI Credit Card at ${merchant.uppercase()} on $date. Trxn."
        return if (code == "SBICRD") "Rs.$rs spent on your SBI Credit Card ending $last4 at ${merchant.uppercase()} on $date. Trxn."
        else "Rs.$rs spent on your ${bankNames[code] ?: (SmsReader.bankOf(code) ?: code) + " Bank"} Credit Card ending $last4 at ${merchant.uppercase()} on $date."
    }
}
