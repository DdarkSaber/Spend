package com.girish.expensetracker.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * One name per shop: brand variants merge (ZOMATO / ZOMATO LTD / ETERNAL LIMITED -> Zomato), other names are cleaned
 * (case, PVT/LTD/PRIVATE/LIMITED) so the same shop under two bank spellings is one shop.
 */
object Brands {

    // Display name -> words seen in bank SMS. Words of 5+ letters match inside a name, shorter ones must be whole words
    // (the same rule as PayeeGuess). Brands whose company name differs from the app name are listed together.
    private val brands: List<Pair<String, List<String>>> = listOf(
        "Zomato" to listOf("ZOMATO", "ETERNAL"),
        "Swiggy" to listOf("SWIGGY", "INSTAMART", "BUNDL TECHNOLOGIES"),
        "Blinkit" to listOf("BLINKIT", "GROFERS"),
        "Zepto" to listOf("ZEPTO", "KIRANAKART"),
        "BigBasket" to listOf("BIGBASKET", "BB DAILY", "SUPERMARKET GROCERY SUPPLIES"),
        "Amazon" to listOf("AMAZON", "AMZN"),
        "Flipkart" to listOf("FLIPKART"),
        "Myntra" to listOf("MYNTRA"),
        "Uber" to listOf("UBER"),
        "Ola" to listOf("OLA", "ANI TECHNOLOGIES"),
        "Rapido" to listOf("RAPIDO", "ROPPEN"),
        "Namma Metro" to listOf("BMRCL", "NAMMA METRO"),
        "IRCTC" to listOf("IRCTC"),
        "Domino's" to listOf("DOMINO", "DOMINOS", "JUBILANT"),
        "McDonald's" to listOf("MCDONALD"),
        "KFC" to listOf("KFC"),
        "Starbucks" to listOf("STARBUCKS"),
        "BookMyShow" to listOf("BOOKMYSHOW", "BOOK MY SHOW", "BIGTREE", "BIG TREE"),
        "District" to listOf("DISTRICT"),
        "Netflix" to listOf("NETFLIX"),
        "Spotify" to listOf("SPOTIFY"),
        "YouTube" to listOf("YOUTUBE"),
        "Google Play" to listOf("GOOGLE PLAY", "GOOGLEPLAY"),
        "Apple" to listOf("APPLE.COM", "ICLOUD", "APPLE MEDIA"),
        "Airtel" to listOf("AIRTEL"),
        "Jio" to listOf("JIO", "RELIANCE JIO"),
        "BESCOM" to listOf("BESCOM"),
        "Lenskart" to listOf("LENSKART"),
        "Apollo" to listOf("APOLLO"),
        "Decathlon" to listOf("DECATHLON"),
        "IKEA" to listOf("IKEA"),
        "Urban Company" to listOf("URBANCLAP", "URBAN COMPANY"),
        "DMart" to listOf("DMART", "AVENUE SUPERMARTS"),
        "Croma" to listOf("CROMA"),
        "Groww" to listOf("GROWW"),
        "Zerodha" to listOf("ZERODHA"),
    )

    private val companyWords = setOf("PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "INDIA", "IN", "THE", "M/S", "MS")

    private fun words(s: String) = s.uppercase().split(Regex("[^A-Z0-9&.']+")).filter { it.isNotEmpty() }

    /** The brand a payee name belongs to, if any. */
    fun brandOf(name: String): String? {
        val up = name.uppercase()
        val squashed = up.filter { it in 'A'..'Z' || it in '0'..'9' }
        val tokens = words(up).toSet()
        return brands.firstOrNull { (_, ws) ->
            ws.any { w ->
                if (w.length >= 5) w.filter { it in 'A'..'Z' || it in '0'..'9' } in squashed
                else if (' ' in w) up.contains(w) else w in tokens
            }
        }?.first
    }

    /** The shop's display name: its brand, else the name without company words, in title case. */
    fun canonical(name: String): String {
        brandOf(name)?.let { return it }
        val n = name.trim()
        // A UPI ID without a brand: keep it as it is (lower case reads as an ID).
        if ('@' in n && ' ' !in n) return n.lowercase()
        val kept = words(n).filter { it !in companyWords }.ifEmpty { words(n) }
        return kept.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }

    /** Grouping key for [canonical]: letters and digits only. */
    fun key(name: String): String = canonical(name).uppercase().filter { it.isLetterOrDigit() }
}

/** All payments at one shop (brand variants and bank-cut spellings merged). */
data class Shop(val key: String, val name: String, val txns: List<Txn>) {
    private fun orders(r: ClosedRange<LocalDate>) = txns.filter { it.day in r && (it.kind == Kind.SPEND || it.kind == Kind.SUBSCRIPTION) }
    fun spentIn(r: ClosedRange<LocalDate>): Long = txns.filter { it.day in r }.sumOf(Ledger::monthAmount)
    fun ordersIn(r: ClosedRange<LocalDate>): Int = orders(r).size
    fun averageIn(r: ClosedRange<LocalDate>): Long = orders(r).let { o -> if (o.isEmpty()) 0 else o.sumOf { it.ownPaise } / o.size }
    fun biggestIn(r: ClosedRange<LocalDate>): Txn? = orders(r).maxByOrNull { it.ownPaise }
    val first: LocalDate get() = txns.minOf { it.day }
    /** Spend per calendar month, oldest first, from the first payment to [today]. */
    fun monthly(today: LocalDate): List<Pair<YearMonth, Long>> {
        val by = txns.groupBy { YearMonth.from(it.day) }.mapValues { (_, l) -> l.sumOf(Ledger::monthAmount) }
        val out = mutableListOf<Pair<YearMonth, Long>>()
        var m = YearMonth.from(first)
        while (m <= YearMonth.from(today)) { out += m to (by[m] ?: 0L); m = m.plusMonths(1) }
        return out
    }
}

object Shops {
    /** Payments that belong to a shop: money out to a merchant, and refunds from one. */
    fun isShopPayment(t: Txn): Boolean = !t.parsed.counterparty.isNullOrBlank() && t.parsed.template != Template.MANUAL && when (t.kind) {
        Kind.SPEND -> t.payeeType == PayeeType.MERCHANT
        Kind.SUBSCRIPTION, Kind.REFUND -> true
        else -> false
    }

    /**
     * Every shop, biggest all-time spend first. A bank-cut name (about 15 letters: "ZEESHAN RESTAURA") joins the
     * full spelling it is the start of ("Zeeshan Restaurant") when exactly one such spelling exists.
     */
    fun all(txns: List<Txn>): List<Shop> {
        val groups = txns.filter(::isShopPayment).groupBy { Brands.key(it.parsed.counterparty!!) }.toMutableMap()
        val keys = groups.keys.sortedByDescending { it.length }
        for (k in keys) {
            if (k.length < 10 || k !in groups) continue
            val longer = groups.keys.filter { it != k && it.length > k.length && it.startsWith(k) }
            if (longer.size == 1) groups[longer.single()] = groups.getValue(longer.single()) + groups.remove(k)!!
        }
        return groups.map { (k, l) ->
            val names = l.map { Brands.canonical(it.parsed.counterparty!!) }
            Shop(k, names.groupingBy { it }.eachCount().maxWith(compareBy({ it.value }, { it.key.length })).key, l.sortedByDescending { it.receivedAt })
        }.sortedByDescending { it.txns.sumOf(Ledger::monthAmount) }
    }

    fun find(txns: List<Txn>, key: String): Shop? = all(txns).firstOrNull { it.key == key }

    /** The shop key a payee name ends up under (after bank-cut merging). */
    fun keyOf(txns: List<Txn>, name: String): String {
        val k = Brands.key(name)
        return all(txns).firstOrNull { it.key == k || (k.length >= 10 && it.key.startsWith(k)) }?.key ?: k
    }
}
