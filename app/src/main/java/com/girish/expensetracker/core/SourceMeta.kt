package com.girish.expensetracker.core

import java.time.LocalDate

/** auto = decided from activity; the others are the owner's choice and always win. */
enum class SourceStatus { AUTO, ACTIVE, PAST, IGNORED }

/** Whose card it is: the owner's, or an add-on whose bills someone else pays (spends count, bills don't). */
enum class SourceOwner { MINE, ADDON_PAID_BY_OTHER }

/**
 * What the owner said about one account or card. [linkedTo] is the account a debit card draws from (its spends are
 * shown under that account and counted once). [cardGroup] names the group of cards on one statement.
 */
data class SourceMeta(
    val source: Source,
    val status: SourceStatus = SourceStatus.AUTO,
    val owner: SourceOwner = SourceOwner.MINE,
    val linkedTo: Source? = null,
    val cardGroup: String? = null,
    val label: String? = null,
) {
    val isAddOn: Boolean get() = owner == SourceOwner.ADDON_PAID_BY_OTHER

    companion object {
        /** A source with a transaction in the last this-many days is active; otherwise it is past. */
        const val ACTIVE_DAYS = 120L

        /** ACTIVE, PAST or IGNORED: the owner's choice when they made one, else from the last transaction. */
        fun effective(meta: SourceMeta?, lastTxn: LocalDate?, today: LocalDate): SourceStatus = when (val s = meta?.status ?: SourceStatus.AUTO) {
            SourceStatus.AUTO -> if (lastTxn != null && !lastTxn.isBefore(today.minusDays(ACTIVE_DAYS))) SourceStatus.ACTIVE else SourceStatus.PAST
            else -> s
        }

        fun keyOf(s: Source) = "${s.bank}|${s.last4}|${s.type.name}"

        fun parseKey(key: String): Source? = key.split('|').takeIf { it.size == 3 }?.let { p ->
            runCatching { Source(p[0], p[1], SourceType.valueOf(p[2])) }.getOrNull()
        }

        /** "add-on (paid by father)" for an add-on, else the owner's own label, else nothing. */
        fun describe(meta: SourceMeta?): String? = when {
            meta == null -> null
            meta.isAddOn -> "add-on (paid by ${meta.label?.ifBlank { null } ?: "someone else"})"
            else -> meta.label?.ifBlank { null }
        }
    }
}
