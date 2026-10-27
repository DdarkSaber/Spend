package com.girish.expensetracker.core

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class DetectedSource(val source: Source, val count: Int)

/** What the inbox says about its owner: no hardcoding, everything comes from their own bank SMS. */
data class DetectedProfile(
    val sources: List<DetectedSource>,
    val selfNames: Set<String>,
    val selfVpas: Set<String>,
    val salaryDay: Int?,
    val displayName: String?,
) {
    fun toProfile(keep: Set<Source> = sources.map { it.source }.toSet()) = Profile(
        accounts = keep.filter { it.type == SourceType.ACCOUNT }.toSet(),
        cards = keep.filter { it.type == SourceType.CARD }.toSet(),
        selfNames = selfNames,
        selfVpas = selfVpas,
    )
}

data class InboxSms(val sender: String, val body: String, val at: LocalDateTime)

object ProfileDetector {

    /** Reads every bank SMS without knowing the owner yet. */
    private val open = Profile(emptySet(), emptySet(), emptySet(), emptySet(), acceptAll = true)

    private val salaryName = Regex("SALARY PAYMENT-([A-Za-z .]+?)-", RegexOption.IGNORE_CASE)

    private val upiDebits = setOf(Template.HDFC_UPI_SENT, Template.SBI_UPI_DEBIT, Template.DCB_UPI_DEBIT, Template.GENERIC_DEBIT)
    private val upiCredits = setOf(Template.HDFC_UPI_CREDIT, Template.SBI_CREDIT, Template.DCB_UPI_CREDIT, Template.GENERIC_CREDIT)

    /** Transfers between your own accounts are usually round and large; small amounts are too easy to match by chance. */
    private const val MIN_SELF_TRANSFER_PAISE = 500_00L

    fun detect(inbox: List<InboxSms>): DetectedProfile {
        val parsed = inbox.mapNotNull { m ->
            (SmsReader.read(m.sender, m.body, open) as? ReadResult.Parsed)?.let { it.parsed to m.at }
        }

        val sources = parsed.map { it.first.source }
            .filter { it.last4 != "*" }
            .groupingBy { it }.eachCount()
            .map { (s, n) -> DetectedSource(s, n) }
            .sortedWith(compareBy({ it.source.type }, { -it.count }))

        val salaries = parsed.filter { (p, _) -> p.template == Template.HDFC_DEPOSIT && "SALARY" in p.narration.orEmpty().uppercase() }
        val salaryNames = salaries.mapNotNull { (p, _) -> salaryName.find(p.narration.orEmpty())?.groupValues?.get(1)?.trim() }
        val salaryDay = salaries.map { (p, at) -> (p.date ?: at.toLocalDate()).dayOfMonth }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        // Self transfers: money leaves one of your accounts and the same amount lands in a different one within a day.
        val names = salaryNames.toMutableSet()
        val vpas = mutableSetOf<String>()
        val debits = parsed.filter { it.first.template in upiDebits && it.first.amountPaise >= MIN_SELF_TRANSFER_PAISE }
        val credits = parsed.filter { it.first.template in upiCredits && it.first.amountPaise >= MIN_SELF_TRANSFER_PAISE }
        for ((d, dAt) in debits) {
            val match = credits.firstOrNull { (c, cAt) ->
                c.amountPaise == d.amountPaise && c.source != d.source &&
                    abs(ChronoUnit.DAYS.between((d.date ?: dAt.toLocalDate()), (c.date ?: cAt.toLocalDate()))) <= 1
            } ?: continue
            d.counterparty?.let { if ('@' in it) vpas += it.lowercase() else names += it }
            match.first.counterparty?.let { if ('@' in it) vpas += it.lowercase() else names += it }
        }

        return DetectedProfile(
            sources = sources,
            selfNames = names,
            selfVpas = vpas,
            salaryDay = salaryDay,
            displayName = salaryNames.firstOrNull() ?: names.firstOrNull(),
        )
    }
}
