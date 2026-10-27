package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.LocalDateTime

enum class SourceType { ACCOUNT, CARD }

/** A bank account or card, identified by bank + last 4 digits. last4 = "*" means "any card of this bank" (SBI bill SMS has no digits). */
data class Source(val bank: String, val last4: String, val type: SourceType)

/** Every SMS layout we know how to read. One real transaction can produce several of these (see Dedupe). */
enum class Template {
    HDFC_UPI_SENT,
    HDFC_UPI_MANDATE,
    HDFC_ACH_UPDATE,
    HDFC_ACH_ALERT,
    HDFC_CARD_SPENT,
    HDFC_CARD_AMOUNT_SPENT,
    HDFC_AUTOPAY_SUCCESS,
    HDFC_CARD_PAYMENT_RECEIVED,
    HDFC_CARD_PAYMENT_CREDITED,
    HDFC_UPI_CREDIT,
    HDFC_DEPOSIT,
    HDFC_IMPS_RECEIVED,
    SBI_UPI_DEBIT,
    SBI_CREDIT,
    SBI_CARD_SPENT,
    SBI_CARD_PAYMENT,
    DCB_CREDIT,
    DCB_UPI_CREDIT,
    DCB_UPI_DEBIT,
    DCB_CARD_SPENT,
    ICICI_CARD_SPENT,
    ICICI_CARD_PAYMENT,
    HDFC_CARD_WITHDRAWAL,
    HDFC_SMARTPAY_BILL,
    // Any other bank, read by the generic phrasing rules (see Generic in SmsReader).
    GENERIC_DEBIT,
    GENERIC_CREDIT,
    GENERIC_CARD_SPENT,
    GENERIC_CARD_PAYMENT,
    GENERIC_CARD_REFUND,
    // A row from an imported bank statement (CSV).
    STATEMENT_DEBIT,
    STATEMENT_CREDIT,
    // Entered by hand in the app (cash).
    MANUAL,
}

enum class Direction { DEBIT, CREDIT }

data class ParsedSms(
    val template: Template,
    val direction: Direction,
    val amountPaise: Long,
    val source: Source,
    /** Payee for debits, payer (name or VPA) for credits, merchant for card spends. */
    val counterparty: String?,
    /** Date printed in the SMS; null when the SMS has none (then the received date is used). */
    val date: LocalDate?,
    val ref: String?,
    /** Free text such as the ACH/NEFT narration. */
    val narration: String?,
)

enum class Kind {
    SPEND,
    SUBSCRIPTION,
    INVESTMENT_SIP,
    INVESTMENT_LUMPSUM,
    TRANSFER,
    INCOME,
    PERSON_CREDIT,
    REFUND,
    /** Money lent to a person (marked by the owner): not spend, tracked until they pay it back. */
    LENT,
    /** A person paying back money lent to them: settles the loan, does not reduce spend. */
    REPAID,
}

enum class PayeeType { MERCHANT, PERSON }

data class Txn(
    val parsed: ParsedSms,
    val kind: Kind,
    val payeeType: PayeeType?,
    val category: String,
    val receivedAt: LocalDateTime,
    val isSalary: Boolean = false,
) {
    val day: LocalDate get() = parsed.date ?: receivedAt.toLocalDate()
    val amountPaise: Long get() = parsed.amountPaise
}

/** Personal setup: which accounts/cards are real, and which names/VPAs are "me" (self transfers). */
data class Profile(
    val accounts: Set<Source>,
    val cards: Set<Source>,
    val selfNames: Set<String>,
    val selfVpas: Set<String>,
    /** Detection mode: read every bank account/card before the owner has confirmed which are theirs. */
    val acceptAll: Boolean = false,
) {
    fun isKnown(s: Source): Boolean = acceptAll || when (s.type) {
        SourceType.ACCOUNT -> s in accounts
        SourceType.CARD -> if (s.last4 == "*") cards.any { it.bank == s.bank } else s in cards
    }

    private val selfNameKeys by lazy { selfNames.map(::normalizeName).toSet() }
    private val selfVpaKeys by lazy { selfVpas.map { it.lowercase() }.toSet() }
    private val selfVpaBases by lazy { selfVpas.map { vpaBase(it.lowercase()) }.toSet() }

    fun isSelfName(name: String): Boolean = normalizeName(name) in selfNameKeys

    /** "name2007-1@okhdfcbank" counts as self when "name2007@oksbi" (or just "name2007") is listed. */
    fun isSelfVpa(vpa: String): Boolean {
        val v = vpa.lowercase()
        if ("@" !in v) return false
        return v in selfVpaKeys || vpaBase(v) in selfVpaBases
    }
}

/** "name-2@okaxis" -> "name": the UPI handle without its numbered suffix or bank. */
private fun vpaBase(vpa: String): String = vpa.substringBefore('@').replace(Regex("-[0-9]+$"), "")

/** "RAVI KUMARM P" and "Ravi Kumar M P" both become "RAVIKUMARMP". */
fun normalizeName(name: String): String = name.uppercase().filter { it in 'A'..'Z' }

/** Key for remembered retags: a UPI ID keeps its digits ("9876543210@ybl" must not match every @ybl), names use [normalizeName]. */
fun ruleKey(label: String): String = if ('@' in label) label.trim().lowercase() else normalizeName(label)
