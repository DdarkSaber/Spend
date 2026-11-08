package com.girish.expensetracker.core

/**
 * Dedupe level 0: the same alert text stored twice. O(n) with hash maps.
 *  - Same channel: same sender and body within 10 minutes (the live receiver and the inbox import stamp one SMS
 *    a little differently).
 *  - Different channels: the same normalized body within 24 hours (an SMS and the Truecaller or Gmail copy of it).
 *    The copy from the better channel is kept: sms, then notification, then archive.
 */
object SameText {

    const val SAME_CHANNEL_MS = 10 * 60_000L
    const val CROSS_CHANNEL_MS = 24 * 60 * 60_000L

    private val spaces = Regex("\\s+")

    fun normalize(body: String): String = body.replace(spaces, " ").trim().lowercase()

    /** Lower is better: sms, then notification, then archive, then anything else. */
    fun rank(channel: String): Int = when {
        channel == "sms" -> 0
        channel.startsWith("notif:") -> 1
        channel.startsWith("archive:") -> 2
        else -> 3
    }

    /** [items] in time order; returns the ones to read, still in time order. */
    fun <T> keep(items: List<T>, sender: (T) -> String, body: (T) -> String, at: (T) -> Long, channel: (T) -> String): List<T> {
        val dropped = BooleanArray(items.size)
        val lastSeen = HashMap<String, Long>()
        // Normalized body -> indices of kept items with that body (almost always one).
        val byText = HashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, item ->
            val ch = channel(item)
            val sameKey = ch + "\u0000" + sender(item) + "\u0000" + body(item)
            val prev = lastSeen.put(sameKey, at(item))
            if (prev != null && kotlin.math.abs(at(item) - prev) < SAME_CHANNEL_MS) { dropped[i] = true; return@forEachIndexed }

            val text = normalize(body(item))
            val list = byText.getOrPut(text) { mutableListOf() }
            // Items are in time order, so anything a day older can never match again.
            list.removeAll { j -> at(item) - at(items[j]) >= CROSS_CHANNEL_MS }
            val other = list.firstOrNull { j -> channel(items[j]) != ch && kotlin.math.abs(at(items[j]) - at(item)) < CROSS_CHANNEL_MS }
            when {
                other == null -> list += i
                rank(ch) < rank(channel(items[other])) -> { dropped[other] = true; list.remove(other); list += i }
                else -> dropped[i] = true
            }
        }
        return items.filterIndexed { i, _ -> !dropped[i] }
    }
}
