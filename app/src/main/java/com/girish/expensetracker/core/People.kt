package com.girish.expensetracker.core

import kotlin.math.min

/**
 * One person often shows up under several labels: a bank name ("SURESH KUMAR RAMAN ANAND"), a UPI handle
 * ("ksuresh2001@okaxis") and a truncated handle ("ksuresh20"). These are grouped into one person.
 */
object People {

    private val honorific = Regex("^(MR|MRS|MS|DR|SMT|SHRI|KUM)\\.?\\s+", RegexOption.IGNORE_CASE)

    /** Letters only, no honorific, no bank part of a UPI handle: "Mr ANIL V S" -> "ANILVS", "vsanil2001@okhdfcbank" -> "VSANIL". */
    fun key(label: String): String {
        var s = label.trim()
        if ('@' in s) s = s.substringBefore('@')
        s = s.replace(honorific, "")
        return s.uppercase().filter { it in 'A'..'Z' }
    }

    /** Same person when the labels share a long run of letters covering most of the shorter one. */
    fun same(a: String, b: String): Boolean {
        val x = key(a)
        val y = key(b)
        if (x.length < 4 || y.length < 4) return x.isNotEmpty() && x == y
        if (x == y) return true
        val run = longestCommonRun(x, y)
        if (run >= 6 && run >= 0.6 * min(x.length, y.length)) return true
        // Handles often reorder the name's parts: "Mr ANIL V S" -> "vsanil2001".
        return reordered(a, y) || reordered(b, x)
    }

    private fun words(label: String): List<String> =
        label.replace(honorific, "").uppercase().split(Regex("[^A-Z]+")).filter { it.isNotEmpty() }

    /** True when [handleKey] spells the words of [label] in some order (names of 2 to 4 parts). */
    private fun reordered(label: String, handleKey: String): Boolean {
        if ('@' in label) return false
        val w = words(label)
        if (w.size !in 2..4 || w.sumOf { it.length } != handleKey.length) return false
        fun perms(list: List<String>): List<List<String>> =
            if (list.size <= 1) listOf(list) else list.indices.flatMap { i -> perms(list.filterIndexed { j, _ -> j != i }).map { listOf(list[i]) + it } }
        return perms(w).any { it.joinToString("") == handleKey }
    }

    /** Groups labels into people (union-find over [same]). Order inside a group follows the input. */
    fun group(labels: List<String>): List<List<String>> {
        val parent = IntArray(labels.size) { it }
        fun find(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; parent[i] = r; return r }
        for (i in labels.indices) for (j in i + 1 until labels.size) {
            if (find(i) != find(j) && same(labels[i], labels[j])) parent[find(j)] = find(i)
        }
        return labels.indices.groupBy { find(it) }.values.map { idx -> idx.map { labels[it] } }
    }

    /** The friendliest label: a real name over a UPI handle, the fullest name, in title case. */
    fun displayName(labels: List<String>): String {
        val names = labels.filter { '@' !in it }
        val best = names.maxByOrNull { it.trim().split(Regex("\\s+")).size * 100 + it.length } ?: labels.first().substringBefore('@')
        return best.replace(honorific, "").trim().lowercase().split(Regex("\\s+"))
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun longestCommonRun(a: String, b: String): Int {
        var best = 0
        val prev = IntArray(b.length + 1)
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            cur.copyInto(prev)
        }
        return best
    }
}
