package com.girish.expensetracker.ui

import com.girish.expensetracker.core.Classifier
import com.girish.expensetracker.core.Dedupe
import com.girish.expensetracker.core.PayeeRules
import com.girish.expensetracker.core.ReadResult
import com.girish.expensetracker.core.SmsReader
import com.girish.expensetracker.data.NewSource
import com.girish.expensetracker.data.Row
import com.girish.expensetracker.data.Snapshot
import com.girish.expensetracker.data.UnparsedSms
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * Synthetic demo data for previews: made-up merchants, names and amounts written in the real bank SMS layouts,
 * then run through the real reader, classifier and ledger, so the screens show what the app would compute.
 */
object DemoData {

    /** Synthetic owner for previews and the debug demo: made-up account digits and name. */
    val DEMO_PROFILE = com.girish.expensetracker.core.Profile(
        accounts = setOf(
            com.girish.expensetracker.core.Source("HDFC", "1234", com.girish.expensetracker.core.SourceType.ACCOUNT),
            com.girish.expensetracker.core.Source("SBI", "5678", com.girish.expensetracker.core.SourceType.ACCOUNT),
        ),
        cards = setOf(
            com.girish.expensetracker.core.Source("HDFC", "4321", com.girish.expensetracker.core.SourceType.CARD),
            com.girish.expensetracker.core.Source("SBI", "1111", com.girish.expensetracker.core.SourceType.CARD),
            com.girish.expensetracker.core.Source("SBI", "2222", com.girish.expensetracker.core.SourceType.CARD),
            com.girish.expensetracker.core.Source("ICICI", "9999", com.girish.expensetracker.core.SourceType.CARD),
        ),
        selfNames = setOf("DEMO USER"),
        selfVpas = setOf("demouser@okaxis"),
    )

    private val en = Locale.ENGLISH
    private fun f(p: String) = DateTimeFormatter.ofPattern(p, en)

    class Sms(val sender: String, val body: String, val at: LocalDateTime)

    /** The synthetic SMS themselves (also loaded into the real database by the debug-only demo button). */
    fun messages(today: LocalDate = LocalDate.now()): List<Sms> {
        val rnd = Random(42)
        val list = mutableListOf<Sms>()
        var ref = 600000000000L
        fun nextRef() = (++ref).toString()
        fun amt(v: Double) = "%.2f".format(v)

        fun hdfcUpi(at: LocalDateTime, rs: Double, to: String) = list.add(Sms("VM-HDFCBK-S",
            "Sent Rs.${amt(rs)} From HDFC Bank A/C *1234 To $to On ${at.format(f("dd/MM/yy"))} Ref ${nextRef()} Not You? Call 18002586161", at))
        fun sbiUpi(at: LocalDateTime, rs: Double, to: String) = list.add(Sms("JD-SBIUPI-S",
            "Dear UPI user A/C X5678 debited by ${amt(rs)} on date ${at.format(f("ddMMMyy"))} trf to $to Refno ${nextRef()} If not u? call-1800111109", at))
        fun hdfcCard(at: LocalDateTime, rs: Int, at2: String) = list.add(Sms("JD-HDFCBK-S",
            "Spent Rs.$rs On HDFC Bank Card 4321 At $at2 On ${at.format(f("yyyy-MM-dd:HH:mm:ss"))}.Not You?", at))
        fun sbiCard(at: LocalDateTime, rs: Double, card: String, where: String) = list.add(Sms("VM-SBICRD-S",
            "Rs.${amt(rs)} spent on your SBI Credit Card ending $card at $where on ${at.format(f("dd/MM/yy"))}. Trxn. not done by you? Report at", at))
        fun paidBack(at: LocalDateTime, rs: Int, from: String) = list.add(Sms("JK-SBIUPI-S",
            "Dear SBI User, your A/c X5678-credited by Rs.$rs on ${at.format(f("ddMMMyy"))} transfer from $from Ref No ${nextRef()} -SBI", at))

        val shops = listOf(
            "Blinkit" to (180.0..900.0), "SWIGGY" to (220.0..640.0), "Zepto Marketplace Pvt" to (150.0..700.0),
            "Bangalore Metro Rail Corp" to (40.0..90.0), "SRI GAJANANA FRUIT JUICE" to (60.0..140.0),
            "Vriddhii Fuels" to (500.0..1200.0), "THE THICKSHAKE FACTORY" to (180.0..360.0), "M2 ZION SALON" to (150.0..400.0),
        )
        val people = listOf("Mr RAHUL S", "KAVYA N", "ARJUN MENON")

        val start = today.minusDays(125)
        var d = start
        while (!d.isAfter(today)) {
            val n = if (d == today) 3 else rnd.nextInt(1, 5)
            repeat(n) { i ->
                // Demo times may sit later than the real clock on "today"; previews only need a believable day.
                val at = d.atTime(8 + i * 4 + rnd.nextInt(0, 3), rnd.nextInt(0, 59))
                val (name, range) = shops[rnd.nextInt(shops.size)]
                val rs = rnd.nextDouble(range.start, range.endInclusive)
                when (rnd.nextInt(10)) {
                    in 0..4 -> hdfcUpi(at, rs, name)
                    in 5..6 -> sbiUpi(at, rs, name)
                    7 -> hdfcCard(at, rs.toInt(), "..${name.uppercase().take(22)}_")
                    8 -> sbiCard(at, rs, if (rnd.nextBoolean()) "1111" else "2222", name.uppercase().take(22))
                    else -> hdfcUpi(at, rnd.nextDouble(120.0, 900.0), people[rnd.nextInt(people.size)])
                }
            }
            if (d.dayOfWeek.value == 6) paidBack(d.atTime(21, 10), 300 + rnd.nextInt(0, 6) * 50, people[rnd.nextInt(people.size)])
            if (d.dayOfMonth == 2) list.add(Sms("BP-HDFCBK-S",
                "UPDATE: INR 5,000.00 debited from HDFC Bank XX1234 on ${d.format(f("dd-MMM-yy")).uppercase()}. Info: ACH D- GROWW INVEST TECH PR-DEMO${d.monthValue}. Avl bal:INR 1.00", d.atTime(9, 5)))
            if (d.dayOfMonth == 25) {
                list.add(Sms("AD-HDFCBK-S", "AutoPay (E-mandate) Success! For NETFLIX Txn Amt:INR649.00 Dt:${d.format(f("dd/MM/yyyy"))} Via:HDFC Bank CC 4321 Mandate ID: DEMO", d.atTime(23, 17)))
                hdfcCard(d.atTime(23, 17), 649, "NETFLIX")
            }
            if (d.dayOfMonth == 28) list.add(Sms("VD-HDFCBK-S",
                "Update! INR 1,78,283.00 deposited in HDFC Bank A/c XX1234 on ${d.format(f("dd-MMM-yy")).uppercase()} for NEFT Cr-DEMO0000001-EMPLOYER-SALARY PAYMENT-Demo User-REF.Avl bal INR 1.00", d.atTime(10, 2)))
            if (d.dayOfMonth == 12) sbiUpi(d.atTime(19, 40), 18_450.30, "SBI Cards and Pa")
            d = d.plusDays(1)
        }
        // Credit card facts: ICICI spends carry the available limit, ICICI and SBI statements carry the bill.
        var avail = 66_000.0
        var cardDay = today.minusDays(27)
        val cardShops = listOf("AMAZON", "BOOKMYSHOW", "MYNTRA", "DECATHLON", "CROMA")
        while (!cardDay.isAfter(today)) {
            val spend = 900.0 + rnd.nextInt(0, 8) * 260.0
            avail -= spend
            list.add(Sms("AD-ICICIT-S", "INR ${amt(spend)} spent using ICICI Bank Card XX9999 on ${cardDay.format(f("dd-MMM-yy"))} on ${cardShops[rnd.nextInt(cardShops.size)]}. Avl Limit: INR ${amt(avail)}. If not you, call 1800 2662.", cardDay.atTime(16, 20)))
            cardDay = cardDay.plusDays(4)
        }
        list.add(Sms("JM-ICICIT-S", "ICICI Bank Credit Card XX9999 Statement is sent to de***@mail.com. Total of Rs 18,240.00 or minimum of Rs 910.00 is due by ${today.plusDays(2).format(f("dd-MMM-yy")).uppercase()}.", today.minusDays(16).atTime(9, 0)))
        list.add(Sms("AX-MYSBIC-S", "E-statement of SBI Credit Card ending XX11 dated ${today.minusDays(20).format(f("dd/MM/yyyy"))} has been mailed. Total Amt Due Rs 18450; Min Amt Due Rs 930; Payable by ${today.minusDays(1).format(f("dd/MM/yyyy"))}.", today.minusDays(19).atTime(8, 0)))
        list.add(Sms("AD-HDFCBK-S", "DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 12000.00 RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH 4321 ON ${today.minusDays(9).format(f("d-M-yyyy"))}.YOUR AVAILABLE LIMIT IS RS. 236500.00", today.minusDays(9).atTime(11, 0)))
        // Worth a look: the same order charged twice minutes apart, and a foreign-currency charge.
        val dup = today.minusDays(1).atTime(20, 1)
        hdfcCard(dup, 489, "ZOMATO")
        hdfcCard(dup.plusMinutes(3), 489, "ZOMATO")
        list.add(Sms("BP-IDFCFB-S", "Transaction Successful! USD 9.99 spent on your IDFC FIRST Bank Credit Card ending XX7788 at STEAMGAMES on ${today.minusDays(2).format(f("dd-MMM-yyyy")).uppercase()} Avbl Limit: INR 41,250.00", today.minusDays(2).atTime(23, 5)))
        // A monthly loan EMI on the 5th.
        var emi = today.withDayOfMonth(5).let { if (it.isAfter(today)) it.minusMonths(1) else it }.minusMonths(3)
        while (!emi.isAfter(today)) {
            list.add(Sms("AD-HDFCBK-S", "PAYMENT ALERT! INR 4250.00 deducted from HDFC Bank A/C No 1234 towards BAJAJ FINANCE LTD UMRN: DEMO${emi.monthValue}", emi.atTime(7, 30)))
            emi = emi.plusMonths(1)
        }
        // One layout the reader does not know yet, and one card that is not tracked.
        list.add(Sms("VA-ICICIT-S", "Refund of INR 499.00 credited to your ICICI Bank Card for DEMO STORE on ${today.minusDays(3).format(f("dd-MMM-yy"))}.", today.minusDays(3).atTime(18, 30)))
        list.add(Sms("JD-HDFCBK-S", "Spent Rs.250 On HDFC Bank Card 9911 At ..DEMO CAFE_ On ${today.minusDays(5).format(f("yyyy-MM-dd"))}:12:10:00.", today.minusDays(5).atTime(12, 10)))
        return list.sortedBy { it.at }
    }

    fun snapshot(today: LocalDate = LocalDate.now()): Snapshot {
        val list = messages(today)

        val profile = DEMO_PROFILE
        val rows = mutableListOf<Row>()
        val unparsed = mutableListOf<UnparsedSms>()
        val newSources = mutableListOf<NewSource>()
        list.forEachIndexed { i, s ->
            when (val r = SmsReader.read(s.sender, s.body, profile)) {
                is ReadResult.Parsed -> rows += Row(Classifier.classify(r.parsed, s.at, profile, PayeeRules.EMPTY), i.toLong(), null, s.body)
                is ReadResult.Unparsed -> unparsed += UnparsedSms(i.toLong(), s.sender, s.body, s.at)
                is ReadResult.UnknownSource -> newSources += NewSource(r.parsed.source, s.body)
                else -> Unit
            }
        }
        val byParsed = java.util.IdentityHashMap<Any, Row>().apply { rows.forEach { put(it.txn.parsed, it) } }
        val deduped = Dedupe.apply(rows.map { it.txn }).map { kept -> byParsed.getValue(kept.parsed).copy(txn = kept) }
        return Snapshot(deduped, unparsed, newSources, today)
    }
}
