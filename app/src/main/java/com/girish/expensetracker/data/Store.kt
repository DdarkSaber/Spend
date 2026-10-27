package com.girish.expensetracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.girish.expensetracker.core.PayeeRules
import com.girish.expensetracker.core.PayeeType
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType
import com.girish.expensetracker.core.LearnedLayout
import com.girish.expensetracker.core.SplitShare
import com.girish.expensetracker.core.Trip
import com.girish.expensetracker.core.ruleKey
import java.time.LocalDate

data class RawSms(val id: Long, val sender: String, val body: String, val receivedAt: Long)
data class ManualEntry(val id: Long, val day: LocalDate, val amountPaise: Long, val note: String, val category: String, val createdAt: Long)
data class StatementRow(val day: LocalDate, val amountPaise: Long, val debit: Boolean, val description: String)

/**
 * The database keeps only raw inputs: bank SMS exactly as received, the user's retags, cash entries and extra tracked cards.
 * Everything else (kinds, categories, totals) is recomputed from these, so a parser fix also fixes old history.
 */
class Store(context: Context) : SQLiteOpenHelper(context, "spend.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sms (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, body TEXT NOT NULL, " +
                "received_at INTEGER NOT NULL, UNIQUE(sender, body, received_at))"
        )
        db.execSQL("CREATE INDEX sms_time ON sms(received_at)")
        db.execSQL("CREATE TABLE rule (name TEXT PRIMARY KEY, type TEXT NOT NULL, category TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE manual (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, amount INTEGER NOT NULL, " +
                "note TEXT NOT NULL, category TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE tracked (bank TEXT NOT NULL, last4 TEXT NOT NULL, type TEXT NOT NULL, PRIMARY KEY(bank, last4, type))")
        createSettings(db)
        createOverrides(db)
        createStatementRows(db)
        createV5(db)
    }

    // v2 adds owner settings; nothing existing is touched, so an upgrade keeps every stored SMS, retag and cash entry.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSettings(db)
        if (oldVersion < 3) createOverrides(db)
        if (oldVersion < 4) createStatementRows(db)
        if (oldVersion < 5) createV5(db)
    }

    /** v4: rows imported from a bank statement file, and split payments. */
    private fun createStatementRows(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS split (row_key TEXT PRIMARY KEY, shares TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stmt (id INTEGER PRIMARY KEY AUTOINCREMENT, bank TEXT NOT NULL, last4 TEXT NOT NULL, " +
                "type TEXT NOT NULL, day TEXT NOT NULL, amount INTEGER NOT NULL, debit INTEGER NOT NULL, description TEXT NOT NULL, " +
                "UNIQUE(bank, last4, type, day, amount, debit, description))"
        )
    }

    /** Returns how many rows were new (re-importing the same file adds nothing). */
    fun addStatementRows(source: Source, rows: List<StatementRow>): Int {
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val v = ContentValues().apply {
                    put("bank", source.bank)
                    put("last4", source.last4)
                    put("type", source.type.name)
                    put("day", r.day.toString())
                    put("amount", r.amountPaise)
                    put("debit", if (r.debit) 1 else 0)
                    put("description", r.description)
                }
                if (db.insertWithOnConflict("stmt", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun statementRowsSince(since: LocalDate): List<Pair<Source, StatementRow>> =
        readableDatabase.rawQuery(
            "SELECT bank, last4, type, day, amount, debit, description FROM stmt WHERE day >= ? ORDER BY day, id",
            arrayOf(since.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Source(c.getString(0), c.getString(1), SourceType.valueOf(c.getString(2))) to
                        StatementRow(LocalDate.parse(c.getString(3)), c.getLong(4), c.getInt(5) == 1, c.getString(6)))
                }
            }
        }

    /** row key ("sms:12", "cash:3") -> other people's shares, stored as JSON. */
    fun splits(): Map<String, List<SplitShare>> =
        readableDatabase.rawQuery("SELECT row_key, shares FROM split", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val arr = org.json.JSONArray(c.getString(1))
                    put(c.getString(0), (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        SplitShare(o.getString("name"), o.getLong("paise"), o.optString("label").ifEmpty { null }, o.optBoolean("settled"))
                    })
                }
            }
        }

    /** An empty list removes the split. */
    fun setSplit(rowKey: String, shares: List<SplitShare>) {
        if (shares.isEmpty()) {
            writableDatabase.delete("split", "row_key = ?", arrayOf(rowKey))
            return
        }
        val arr = org.json.JSONArray()
        shares.forEach { s ->
            arr.put(org.json.JSONObject().put("name", s.name).put("paise", s.sharePaise).put("label", s.label ?: "").put("settled", s.settled))
        }
        writableDatabase.insertWithOnConflict("split", null, ContentValues().apply { put("row_key", rowKey); put("shares", arr.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** v5: trips (named date ranges), a free-text tag per payment, and SMS layouts the owner taught. */
    private fun createV5(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trip (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, start TEXT NOT NULL, finish TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tag (row_key TEXT PRIMARY KEY, tag TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS learned (id INTEGER PRIMARY KEY AUTOINCREMENT, bank TEXT NOT NULL, pattern TEXT NOT NULL, " +
                "roles TEXT NOT NULL, debit INTEGER NOT NULL, card INTEGER NOT NULL)"
        )
    }

    fun trips(): List<Trip> = readableDatabase.rawQuery("SELECT id, name, start, finish FROM trip ORDER BY start DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Trip(c.getLong(0), c.getString(1), LocalDate.parse(c.getString(2)), LocalDate.parse(c.getString(3)))) }
    }

    fun addTrip(name: String, start: LocalDate, end: LocalDate) {
        writableDatabase.insert("trip", null, ContentValues().apply { put("name", name); put("start", start.toString()); put("finish", end.toString()) })
    }

    fun deleteTrip(id: Long) { writableDatabase.delete("trip", "id = ?", arrayOf(id.toString())) }

    fun tags(): Map<String, String> = readableDatabase.rawQuery("SELECT row_key, tag FROM tag", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    /** A blank tag removes it. */
    fun setTag(rowKey: String, tag: String) {
        if (tag.isBlank()) writableDatabase.delete("tag", "row_key = ?", arrayOf(rowKey))
        else writableDatabase.insertWithOnConflict("tag", null, ContentValues().apply { put("row_key", rowKey); put("tag", tag.trim()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun learned(): List<LearnedLayout> = readableDatabase.rawQuery("SELECT bank, pattern, roles, debit, card FROM learned ORDER BY id DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(LearnedLayout(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4) == 1)) }
    }

    fun addLearned(l: LearnedLayout) {
        writableDatabase.insert("learned", null, ContentValues().apply {
            put("bank", l.bank); put("pattern", l.pattern); put("roles", l.roles); put("debit", if (l.debit) 1 else 0); put("card", if (l.card) 1 else 0)
        })
    }

    private val tables = listOf("sms", "rule", "manual", "tracked", "settings", "override", "stmt", "split", "trip", "tag", "learned")

    /** Every table as JSON rows, for an encrypted backup. */
    fun dump(): org.json.JSONObject = org.json.JSONObject().apply {
        put("version", 4)
        for (table in tables) {
            val rows = org.json.JSONArray()
            readableDatabase.rawQuery("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    val row = org.json.JSONObject()
                    for (i in 0 until c.columnCount) {
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_NULL -> Unit
                            else -> row.put(c.getColumnName(i), c.getString(i))
                        }
                    }
                    rows.put(row)
                }
            }
            put(table, rows)
        }
    }

    /** Replaces everything with a backup's contents, all or nothing. */
    fun restore(backup: org.json.JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in tables) {
                val rows = backup.optJSONArray(table) ?: continue
                db.delete(table, null, null)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val v = ContentValues()
                    row.keys().forEach { k ->
                        when (val x = row.get(k)) {
                            is Int -> v.put(k, x.toLong())
                            is Long -> v.put(k, x)
                            else -> v.put(k, x.toString())
                        }
                    }
                    db.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createSettings(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    /** "File this one payment as X", without changing how the payee is treated elsewhere. */
    private fun createOverrides(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS override (sms_id INTEGER PRIMARY KEY, category TEXT NOT NULL)")
    }

    fun setOverride(smsId: Long, category: String, type: PayeeType?) {
        val v = ContentValues().apply {
            put("sms_id", smsId)
            put("category", "${type?.name.orEmpty()}|$category")
        }
        writableDatabase.insertWithOnConflict("override", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** sms id -> (payee type or null, category). Older rows without a type read as category only. */
    fun overrides(): Map<Long, Pair<PayeeType?, String>> =
        readableDatabase.rawQuery("SELECT sms_id, category FROM override", null).use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val v = c.getString(1)
                    val type = v.substringBefore('|', "").takeIf { it.isNotEmpty() }?.let { runCatching { PayeeType.valueOf(it) }.getOrNull() }
                    put(c.getLong(0), type to v.substringAfter('|'))
                }
            }
        }

    fun setting(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setSetting(key: String, value: String) {
        val v = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun untrack(source: Source) {
        writableDatabase.delete("tracked", "bank = ? AND last4 = ? AND type = ?", arrayOf(source.bank, source.last4, source.type.name))
    }

    /** Returns false if this exact SMS was already stored (import and live receiver can both see it). */
    fun insertSms(sender: String, body: String, receivedAt: Long): Boolean {
        // The live receiver and the inbox import stamp the same SMS a little differently; within 10 minutes it is one message.
        val seen = readableDatabase.rawQuery(
            "SELECT 1 FROM sms WHERE sender = ? AND body = ? AND ABS(received_at - ?) < 600000 LIMIT 1",
            arrayOf(sender, body, receivedAt.toString()),
        ).use { it.moveToFirst() }
        if (seen) return false
        val v = ContentValues().apply {
            put("sender", sender)
            put("body", body)
            put("received_at", receivedAt)
        }
        return writableDatabase.insertWithOnConflict("sms", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun smsSince(sinceMillis: Long): List<RawSms> =
        readableDatabase.rawQuery(
            "SELECT id, sender, body, received_at FROM sms WHERE received_at >= ? ORDER BY received_at, id",
            arrayOf(sinceMillis.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(RawSms(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }

    fun smsCount(): Long = readableDatabase.compileStatement("SELECT COUNT(*) FROM sms").simpleQueryForLong()

    fun rules(): PayeeRules =
        readableDatabase.rawQuery("SELECT name, type, category FROM rule", null).use { c ->
            val map = buildMap {
                while (c.moveToNext()) put(c.getString(0), PayeeRules.Rule(PayeeType.valueOf(c.getString(1)), c.getString(2)))
            }
            PayeeRules(map)
        }

    fun setRule(payee: String, type: PayeeType, category: String) {
        val v = ContentValues().apply {
            put("name", ruleKey(payee))
            put("type", type.name)
            put("category", category)
        }
        writableDatabase.insertWithOnConflict("rule", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addManual(day: LocalDate, amountPaise: Long, note: String, category: String) {
        val v = ContentValues().apply {
            put("day", day.toString())
            put("amount", amountPaise)
            put("note", note)
            put("category", category)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("manual", null, v)
    }

    fun deleteManual(id: Long) {
        writableDatabase.delete("manual", "id = ?", arrayOf(id.toString()))
    }

    fun manualSince(since: LocalDate): List<ManualEntry> =
        readableDatabase.rawQuery(
            "SELECT id, day, amount, note, category, created_at FROM manual WHERE day >= ? ORDER BY day, id",
            arrayOf(since.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ManualEntry(c.getLong(0), LocalDate.parse(c.getString(1)), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5)))
                }
            }
        }

    fun tracked(): Set<Source> =
        readableDatabase.rawQuery("SELECT bank, last4, type FROM tracked", null).use { c ->
            buildSet { while (c.moveToNext()) add(Source(c.getString(0), c.getString(1), SourceType.valueOf(c.getString(2)))) }
        }

    fun track(source: Source) {
        val v = ContentValues().apply {
            put("bank", source.bank)
            put("last4", source.last4)
            put("type", source.type.name)
        }
        writableDatabase.insertWithOnConflict("tracked", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }
}
