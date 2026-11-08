package com.girish.expensetracker.core

/** A phone contact as read on the device: the saved name and its numbers (digits only). Never stored. */
data class Contact(val name: String, val numbers: List<String>)

/**
 * Payee names and UPI IDs matched to phone contacts, on the device only. One clear contact makes a payee a person,
 * shown under the contact's saved name; several possible contacts leave it alone. Only the matches are stored.
 */
object ContactMatch {

    private val honorifics = setOf("MR", "MRS", "MS", "DR", "MASTER", "SMT", "SHRI", "SRI", "KUM", "MISS")
    private val companyWords = setOf("PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "CORP", "CORPORATION", "TECHNOLOGIES", "SERVICES",
        "ENTERPRISES", "CO", "COMPANY", "PAYMENTS", "UTILITY", "AGENCY", "AGENCIES", "TRADERS", "TRADING", "INDUSTRIES", "STORE", "STORES", "SHOP", "BANK", "INDIA")

    /** Bank SMS cut names at about this many characters, so a last word that ends there may be cut short. */
    private const val BANK_CUT = 14

    /** "Mr. RAVI K SURESH2" -> [RAVI, SURESH]: upper case, no honorifics, no one-letter initials, no digits. */
    fun tokens(name: String): List<String> =
        name.uppercase().split(Regex("[^A-Z0-9]+")).map { it.filter { c -> c in 'A'..'Z' } }
            .filter { it.length >= 2 && it !in honorifics }

    /** The word a payee is matched on: its longest word of 3+ letters. */
    fun mainToken(label: String): String? = tokens(label.substringBefore('@')).filter { it.length >= 3 }.maxByOrNull { it.length }

    /** The 10-digit mobile number in a UPI ID ("9876543210@ybl", "919876543210@paytm"), if any. */
    fun phoneOf(label: String): String? {
        if ('@' !in label) return null
        val d = label.substringBefore('@').filter(Char::isDigit)
        return if (d.length in 10..12 && label.substringBefore('@').all { it.isDigit() || it == '-' || it == '+' }) d.takeLast(10) else null
    }

    /** Payees worth matching: no brand, no shop word, no company word. */
    fun isCandidate(label: String): Boolean {
        if (phoneOf(label) != null) return true
        if (Brands.brandOf(label) != null || PayeeGuess.category(label) != null) return false
        return tokens(label).none { it in companyWords }
    }

    private fun digits(n: String) = n.filter(Char::isDigit).takeLast(10)

    /** The one contact [label] belongs to, or null (no contact, or more than one possible). */
    fun match(label: String, contacts: List<Contact>, index: Index = Index(contacts)): Contact? {
        phoneOf(label)?.let { phone -> return index.byPhone[phone]?.distinctBy { it.name.trim().lowercase() }?.singleOrNull() }
        if (!isCandidate(label)) return null
        val main = mainToken(label) ?: return null
        val words = tokens(label.substringBefore('@'))
        // A cut-off last word ("SURESHKUMA") may be the start of the contact's word.
        val cut = '@' !in label && label.trim().length >= BANK_CUT && words.lastOrNull() == main
        val hits = if (cut) index.byPrefix(main) else index.byToken[main].orEmpty()
        return hits.distinctBy { it.name.trim().lowercase() }.singleOrNull()
    }

    /** Contacts looked up by word and by number, built once per run. */
    class Index(contacts: List<Contact>) {
        val byToken: Map<String, List<Contact>> = contacts.flatMap { c -> tokens(c.name).filter { it.length >= 3 }.distinct().map { it to c } }
            .groupBy({ it.first }, { it.second })
        val byPhone: Map<String, List<Contact>> = contacts.flatMap { c -> c.numbers.map(::digits).filter { it.length == 10 }.distinct().map { it to c } }
            .groupBy({ it.first }, { it.second })
        private val sorted = byToken.keys.sorted()
        fun byPrefix(p: String): List<Contact> {
            var i = java.util.Collections.binarySearch(sorted, p).let { if (it < 0) -it - 1 else it }
            val out = mutableListOf<Contact>()
            while (i < sorted.size && sorted[i].startsWith(p)) { out += byToken.getValue(sorted[i]); i++ }
            return out
        }
    }

    /** label -> contact name, for every payee label that matches exactly one contact. */
    fun matchAll(labels: Collection<String>, contacts: List<Contact>): Map<String, String> {
        val index = Index(contacts)
        return labels.distinct().mapNotNull { l -> match(l, contacts, index)?.let { l to it.name.trim() } }.toMap()
    }

    /** Stored as "label|contact name" lines in settings. */
    fun encode(m: Map<String, String>): String = m.entries.joinToString("\n") { "${it.key.replace('|', ' ').replace('\n', ' ')}|${it.value.replace('\n', ' ')}" }
    fun decode(s: String?): Map<String, String> = s.orEmpty().lines().mapNotNull { l ->
        val i = l.indexOf('|'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
    }.toMap()
}

/**
 * Payees that are a person only by default (no honorific, no contact, no shop word, no rule): asked once, "Shop or
 * person?". Likely shops (3+ payments, amounts not round, never sent money back) are pre-ticked as shops.
 */
data class PayeeReview(val label: String, val payments: Int, val likelyShop: Boolean)

object PayeeReviews {
    private val honorific = Regex("^(MR|MRS|MS|DR|SMT|SHRI|MASTER)\\.?\\s", RegexOption.IGNORE_CASE)

    fun of(txns: List<Txn>, ruled: (String) -> Boolean, contactMatched: Set<String>): List<PayeeReview> {
        val byLabel = txns.filter { it.payeeType == PayeeType.PERSON && !it.parsed.counterparty.isNullOrBlank() && it.parsed.template != Template.MANUAL }
            .groupBy { it.parsed.counterparty!! }
        return byLabel.filter { (label, _) ->
            label !in contactMatched && !ruled(label) && !honorific.containsMatchIn(label.trim()) && ContactMatch.phoneOf(label) == null
        }.map { (label, list) ->
            val out = list.filter { it.parsed.direction == Direction.DEBIT }
            val gotBack = list.any { it.parsed.direction == Direction.CREDIT }
            val round = out.count { it.amountPaise % 1000 == 0L }
            PayeeReview(label, list.size, likelyShop = out.size >= 3 && round * 2 < out.size && !gotBack)
        }.sortedWith(compareByDescending<PayeeReview> { it.payments }.thenBy { it.label })
    }
}
