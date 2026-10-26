package com.girish.expensetracker.core

import java.time.LocalDateTime

/** Turns a parsed SMS into a ledger entry: what kind of money movement it is, who the other side is, and a category guess. */
object Classifier {

    private val cardBillPayees = listOf("SBI CARDS", "SBICARD", "CRED", "HDFC CREDIT CARD", "ICICI CREDIT CARD", "CREDIT CARD")
    private val investmentPayees = listOf("GROWW", "GR0WW", "INDIAN CLEARING", "ZERODHA", "UPSTOX", "BSE", "NSE CLEARING", "MUTUAL FUND", "ICCL")
    private val refundWords = listOf("REFUND", "CASHBACK", "PLAYSTORE", "GOOGLE", "AMAZON", "FLIPKART", "PAYTM", "RAZORPAY", "BILLDESK")

    fun classify(p: ParsedSms, receivedAt: LocalDateTime, profile: Profile, payeeRules: PayeeRules = PayeeRules.EMPTY): Txn {
        val name = p.counterparty.orEmpty()
        val upper = name.uppercase()
        val narration = p.narration.orEmpty().uppercase()
        // For a UPI ID only the handle counts: "ravi@paytm" is a person, the "@paytm" is just their bank app.
        val payer = upper.substringBefore('@')

        fun txn(kind: Kind, type: PayeeType? = null, category: String = kind.defaultCategory(), salary: Boolean = false) =
            Txn(p, kind, type, category, receivedAt, salary)

        return when (p.template) {
            Template.HDFC_UPI_SENT, Template.SBI_UPI_DEBIT, Template.DCB_UPI_DEBIT -> when {
                profile.isSelfName(name) || profile.isSelfVpa(name) -> txn(Kind.TRANSFER)
                cardBillPayees.any { upper.startsWith(it) } -> txn(Kind.TRANSFER, category = "Card bill")
                investmentPayees.any { it in upper } -> txn(Kind.INVESTMENT_LUMPSUM, PayeeType.MERCHANT)
                else -> spend(p, receivedAt, payeeRules)
            }

            Template.HDFC_UPI_MANDATE, Template.HDFC_AUTOPAY_SUCCESS ->
                txn(Kind.SUBSCRIPTION, PayeeType.MERCHANT)

            Template.HDFC_ACH_UPDATE, Template.HDFC_ACH_ALERT ->
                if (investmentPayees.any { it in narration || it in upper }) txn(Kind.INVESTMENT_SIP, PayeeType.MERCHANT)
                else txn(Kind.SPEND, PayeeType.MERCHANT, "Bills & EMI")

            Template.HDFC_CARD_SPENT, Template.HDFC_CARD_AMOUNT_SPENT, Template.SBI_CARD_SPENT,
            Template.ICICI_CARD_SPENT, Template.DCB_CARD_SPENT ->
                spend(p, receivedAt, payeeRules)

            Template.HDFC_CARD_WITHDRAWAL -> txn(Kind.SPEND, PayeeType.MERCHANT, "Cash")

            Template.HDFC_SMARTPAY_BILL -> txn(Kind.SPEND, PayeeType.MERCHANT, "Bills")

            Template.HDFC_CARD_PAYMENT_RECEIVED, Template.HDFC_CARD_PAYMENT_CREDITED, Template.SBI_CARD_PAYMENT,
            Template.ICICI_CARD_PAYMENT ->
                txn(Kind.TRANSFER, category = "Card bill")

            Template.HDFC_UPI_CREDIT, Template.DCB_UPI_CREDIT, Template.SBI_CREDIT -> when {
                profile.isSelfVpa(name) || profile.isSelfName(name) -> txn(Kind.TRANSFER)
                payeeRules.lookup(name)?.type == PayeeType.MERCHANT -> txn(Kind.REFUND, PayeeType.MERCHANT, "Refund")
                // Money back from an investment platform is a payout, not a friend repaying you.
                investmentPayees.any { it in payer } -> txn(Kind.INCOME, category = "Money in")
                // Refunds and merchant credits (GPay refund, Play Store, a shop) reduce spend but are not people.
                refundWords.any { it in payer } || PayeeGuess.isMerchantLike(payer) -> txn(Kind.REFUND, PayeeType.MERCHANT, "Refund")
                else -> txn(Kind.PERSON_CREDIT, PayeeType.PERSON)
            }

            Template.HDFC_DEPOSIT ->
                txn(Kind.INCOME, category = if ("SALARY" in narration) "Salary" else "Money in", salary = "SALARY" in narration)

            Template.HDFC_IMPS_RECEIVED, Template.DCB_CREDIT ->
                txn(Kind.INCOME, category = "Money in")

            // Hand-entered cash: the category is stored in the narration.
            Template.MANUAL ->
                txn(Kind.SPEND, PayeeType.MERCHANT, p.narration ?: "Uncategorised")
        }
    }

    private fun spend(p: ParsedSms, receivedAt: LocalDateTime, rules: PayeeRules): Txn {
        val name = p.counterparty.orEmpty()
        // A retag by the user always wins over the keyword guess.
        rules.lookup(name)?.let { return Txn(p, Kind.SPEND, it.type, it.category, receivedAt) }
        // Card swipes (credit, or a debit card at a shop) always pay a merchant; UPI can pay either.
        val type = if (p.source.type == SourceType.CARD || p.template == Template.DCB_CARD_SPENT) PayeeType.MERCHANT else PayeeGuess.type(name)
        val category = PayeeGuess.category(name) ?: if (type == PayeeType.PERSON) "People" else "Uncategorised"
        return Txn(p, Kind.SPEND, type, category, receivedAt)
    }

    private fun Kind.defaultCategory(): String = when (this) {
        Kind.SPEND -> "Uncategorised"
        Kind.SUBSCRIPTION -> "Subscriptions"
        Kind.INVESTMENT_SIP, Kind.INVESTMENT_LUMPSUM -> "Investment"
        Kind.TRANSFER -> "Transfer"
        Kind.INCOME -> "Money in"
        Kind.PERSON_CREDIT -> "Paid back"
        Kind.REFUND -> "Refund"
    }
}

/** Remembered retags: payee name -> (type, category). */
class PayeeRules(private val rules: Map<String, Rule>) {
    data class Rule(val type: PayeeType, val category: String)

    fun lookup(name: String): Rule? = rules[ruleKey(name)]

    companion object {
        val EMPTY = PayeeRules(emptyMap())
        fun of(vararg pairs: Pair<String, Rule>) = PayeeRules(pairs.associate { ruleKey(it.first) to it.second })
    }
}

/** Merchant-or-person guess from the payee name alone, plus a category guess. */
object PayeeGuess {

    private val companyWords = listOf("PVT", "PRIVATE", "LTD", "LIMITED", "TECHNOLOGIES", "TECHNO", "CORP", "CORPORATION", "LLP", "INDIA", "SERVICES", "ENTERPRISES")

    // keyword -> category; first match wins, so brands come before generic words.
    // Words of 5+ letters match inside a name ("ETERNALLIMITED"); shorter ones must be whole words ("TEA" not "TEAM").
    private fun group(category: String, vararg words: String) = words.map { it to category }

    private val categoryWords: List<Pair<String, String>> =
        // --- food delivery & chains (Zomato's company is now Eternal)
        group("Food", "SWIGGY", "ZOMATO", "ETERNAL", "DOMINO", "DOMINOS", "PIZZA HUT", "PIZZA", "MCDONALD", "KFC", "BURGER KING",
            "SUBWAY", "STARBUCKS", "CHAAYOS", "THIRD WAVE", "BLUE TOKAI", "HALDIRAM", "BEHROUZ", "FAASOS", "BOX8", "EATSURE",
            "REBEL FOODS", "WOW MOMO", "THEOBROMA", "CAFE COFFEE DAY", "CCD", "BASKIN", "KRISPY", "NATURALS ICE", "THICKSHAKE",
            "EATFIT", "LICIOUS", "FRESHMENU", "MAGICPIN", "EAZYDINER", "DINEOUT") +
        // --- quick commerce & grocery
        group("Groceries", "BLINKIT", "GROFERS", "ZEPTO", "KIRANAKART", "INSTAMART", "BIGBASKET", "BB DAILY", "DMART", "AVENUE SUPERMARTS",
            "MORE RETAIL", "RELIANCE FRESH", "RELIANCE SMART", "SMART BAZAAR", "JIOMART", "NATURES BASKET", "RATNADEEP", "SPENCERS",
            "STAR BAZAAR", "COUNTRY DELIGHT", "AKSHAYAKALPA", "NANDINI MILK", "NANDINI PARLOUR", "HERITAGE FOODS", "MILKBASKET", "MUNCHMART", "SWISH",
            "SUPERMARKET", "SUPER MARKET", "SUPER MARKE", "SUPERMART", "PROVISION", "KIRANA", "DAIRY", "VEGETABLE") +
        // --- getting around
        group("Transport", "UBER", "OLA", "RAPIDO", "NAMMA YATRI", "BLUSMART", "BMRCL", "METRO", "BMTC", "IRCTC", "REDBUS", "ABHIBUS",
            "YULU", "BOUNCE", "FASTAG", "PARKING", "TOLL", "BIAL") +
        group("Travel", "INDIGO", "INTERGLOBE", "AIR INDIA", "VISTARA", "AKASA", "SPICEJET", "MAKEMYTRIP", "GOIBIBO", "CLEARTRIP",
            "YATRA", "IXIGO", "AGODA", "BOOKING.COM", "OYO", "AIRBNB", "TREEBO", "FABHOTEL", "ZOSTEL") +
        group("Fuel", "HPCL", "HINDUSTAN PETROLEUM", "IOCL", "INDIAN OIL", "BPCL", "BHARAT PETROLEUM", "SHELL", "NAYARA", "JIO-BP", "JIO BP",
            "FUELS", "FUEL", "PETROL", "PETROLEUM", "FILLING STATION", "SERVICE STATION") +
        // --- health & care
        group("Medical", "LENSKART", "TITAN EYE", "JOHN JACOBS", "VISION EXPRESS", "SPECSMAKERS", "OPTICAL", "OPTICIAN", "EYE CARE",
            "EYE HOSPITAL", "NETRADHAMA", "APOLLO", "MEDPLUS", "NETMEDS", "TATA 1MG", "1MG", "PHARMEASY", "TRUEMEDS", "PHARMACY", "PHARMA",
            "CHEMIST", "DRUG", "HOSPITAL", "NARAYANA HEALTH", "NARAYANA HRUDAYALAYA", "MANIPAL HOSP", "FORTIS HEALTH", "ASTER CMI", "ASTER DM",
            "CLOUDNINE", "MOTHERHOOD HOSP", "SAKRA WORLD", "PRACTO",
            "THYROCARE", "DR LAL", "LAL PATH", "METROPOLIS", "HEALTHIANS", "PATHOLOGY", "LABS", "CLINIC", "DIAGNOSTIC", "DENTAL",
            "MEDICAL", "FERTILITY", "HEALTHCARE", "PHYSIO", "CULTFIT", "CULT.FIT") +
        group("Personal care", "SALON", "SPA", "NATURALS", "TONI&GUY", "BARBER", "GROOMING", "UNISEX") +
        // --- fun & subscriptions
        group("Entertainment", "BOOKMYSHOW", "BOOK MY SHOW", "PVR", "INOX", "CINEPOLIS", "DISTRICT", "PAYTM INSIDER", "HOYOVERSE",
            "STEAM", "PLAYSTATION", "XBOX", "NINTENDO", "EPIC GAMES", "RIOT", "VALVE", "GAMES") +
        group("Subscriptions", "NETFLIX", "SPOTIFY", "YOUTUBE", "GOOGLE PLAY", "GOOGLEPLAY", "GOOGLE", "APPLE.COM", "ICLOUD", "PRIME VIDEO",
            "JIOHOTSTAR", "HOTSTAR", "DISNEY", "SONYLIV", "ZEE5", "AHA", "OPENAI", "CHATGPT", "ANTHROPIC", "CLAUDE", "NOTION",
            "MICROSOFT", "ADOBE", "PATREON", "LINKEDIN") +
        // --- bills & home
        group("Bills", "AIRTEL", "JIO", "VODAFONE", "VILPOSTPAID", "BSNL", "ACT FIBERNET", "HATHWAY", "TATA PLAY", "BESCOM", "TANGEDCO",
            "ELECTRICITY", "BWSSB", "WATER", "INDANE", "BHARATGAS", "HP GAS", "GAS", "BROADBAND", "POSTPAID", "RECHARGE", "BBPS") +
        group("Bills & EMI", "INSURANCE", "POLICYBAZAAR", "LIC", "ACKO", "GO DIGIT", "HDFC ERGO", "ICICI LOMBARD", "NIVA BUPA", "EMI", "LOAN") +
        group("Household", "URBANCLAP", "URBAN COMPANY", "HOUSEJOY", "LAUNDRY", "DRY CLEAN", "MAINTENANCE", "SOCIETY", "MYGATE", "NOBROKERHOOD") +
        group("Rent", "NOBROKER", "RENT") +
        group("Education", "UDEMY", "COURSERA", "BYJU", "UNACADEMY", "SCALER", "LEETCODE", "SCHOOL", "COLLEGE", "UNIVERSITY") +
        group("Vehicle", "ROYAL ENFIELD", "AUTO SERVICE", "SERVICE CENTER", "MOTORS", "TYRE", "GARAGE") +
        // --- shopping brands
        group("Shopping", "AMAZON", "FLIPKART", "MYNTRA", "AJIO", "NYKAA", "MEESHO", "TATA CLIQ", "CROMA", "RELIANCE DIGITAL", "VIJAY SALES",
            "DECATHLON", "IKEA", "UNIQLO", "H&M", "ZARA", "WESTSIDE", "PANTALOONS", "LIFESTYLE", "MAX FASHION", "TRENDS", "RARE RABBIT",
            "HOUSE OF RARE", "SNITCH", "BEWAKOOF", "TITAN", "TANISHQ", "APPLE INDIA", "SAMSUNG", "ONEPLUS", "BOAT", "PUMA",
            "NIKE", "ADIDAS", "MIDAS", "DIGITAL") +
        // --- generic food words last (after brands like OYO / hotels in Travel)
        group("Food", "RESTAURANT", "HOTEL", "DHABA", "BAKERY", "BAKE", "BREAD", "CAFE", "KITCHEN", "FOODS", "FOOD", "SWEETS", "JUICE",
            "FRUIT", "TEA", "COFFEE", "CHAI", "MESS", "CANTEEN", "EATERY", "GRILL", "BISTRO", "BREW", "BAR", "PUB", "MOMO", "MOMOS",
            "DOSA", "TIFFIN", "BHAVAN", "DARSHINI", "BIRYANI", "BBQ", "BARBEQUE", "PALACE", "SZECHUAN", "DRAGON", "CHINESE",
            "PIZZERIA", "BURGER", "SHAKE", "ICE CREAM", "CREAMERY") +
        group("Groceries", "STORES", "STORE", "MART", "SUPER", "FRESH")

    private val honorific = Regex("^(MR|MRS|MS|DR|SMT|SHRI)\\.?\\s", RegexOption.IGNORE_CASE)

    // Short words must be whole words ("TEA" must not match "TEAM"); long words may sit inside a name
    // ("FUELS" in "VRIDDHIIFUELS"); "MART" also matches as a suffix ("DMART", "MUNCHMART").
    private val categoryMatchers: List<Pair<Regex, String>> = categoryWords.map { (word, cat) ->
        val w = Regex.escape(word)
        val pattern = when {
            word == "MART" -> "MART\\b"
            word.length >= 5 -> w
            else -> "\\b$w\\b"
        }
        Regex(pattern) to cat
    }

    fun category(name: String): String? {
        val upper = name.uppercase()
        return categoryMatchers.firstOrNull { (re, _) -> re.containsMatchIn(upper) }?.second
    }

    fun type(name: String): PayeeType {
        val upper = name.uppercase().trim()
        if (honorific.containsMatchIn(upper)) return PayeeType.PERSON
        // Default (user decision): anything not recognisably a business is treated as a person until retagged.
        return if (isMerchantLike(upper)) PayeeType.MERCHANT else PayeeType.PERSON
    }

    // Truncated company suffixes as banks print them: "Pte.L", "PVT L", "Limi", "PRI".
    private val companyPrefixes = listOf("PTE", "PVT", "LTD", "LIMI", "PRIV", "INC", "LLC", "LLP", "CORP", "TECHNOLOG")

    /** Business, brand or merchant UPI handle (as opposed to a person's name). */
    fun isMerchantLike(name: String): Boolean {
        val upper = name.uppercase().trim()
        if (upper.isEmpty() || honorific.containsMatchIn(upper)) return false
        if (category(upper) != null) return true
        if (upper.startsWith("THE ") || " & " in upper) return true
        val words = upper.split(Regex("[\\s.]+")).filter { it.isNotBlank() }
        if (words.any { w -> w in companyWords || companyPrefixes.any { w.startsWith(it) } }) return true
        return false
    }
}
