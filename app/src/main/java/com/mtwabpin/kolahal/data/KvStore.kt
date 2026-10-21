package com.mtwabpin.kolahal.data

import android.content.ContentValues
import android.content.Context

/** Small string key/value store; each script gets its own scope (used by `store` and `rss.fresh`). */
class KvStore(context: Context, private val scope: String) {
    private val db = Db.get(context)

    fun all(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        db.readableDatabase.rawQuery("SELECT k, v FROM kv WHERE scope = ? ORDER BY k", arrayOf(scope)).use { c ->
            while (c.moveToNext()) c.getString(1)?.let { out[c.getString(0)] = it }
        }
        return out
    }

    fun get(key: String): String? = try {
        db.readableDatabase.rawQuery(
            "SELECT v FROM kv WHERE scope = ? AND k = ?", arrayOf(scope, key),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: android.database.sqlite.SQLiteBlobTooBigException) {
        // Written before values were capped and too big to read back: drop it so the key works again.
        set(key, null)
        null
    }

    fun set(key: String, value: String?) {
        require(value == null || value.length <= MAX_VALUE) { "value too large (over ${MAX_VALUE / 1024} KB)" }
        // A script's store is limited as a whole too, so a loop of store.set can't fill the phone.
        if (value != null && scope.startsWith("script:")) {
            db.readableDatabase.rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(length(v)), 0) FROM kv WHERE scope = ? AND k != ?", arrayOf(scope, key),
            ).use { c ->
                c.moveToFirst()
                require(c.getInt(0) < MAX_KEYS) { "store full (over $MAX_KEYS keys)" }
                require(c.getLong(1) + value.length <= MAX_SCOPE) { "store full (over ${MAX_SCOPE / (1024 * 1024)} MB)" }
            }
        }
        if (value == null) {
            db.writableDatabase.delete("kv", "scope = ? AND k = ?", arrayOf(scope, key))
        } else {
            db.writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
                put("scope", scope)
                put("k", key)
                put("v", value)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    companion object {
        /** Largest value a key may hold (characters); keeps every row readable through a cursor. */
        const val MAX_VALUE = 1024 * 1024
        /** Keys and characters one script's store may hold. */
        const val MAX_KEYS = 1000
        const val MAX_SCOPE = 16L * 1024 * 1024

        /**
         * Trims or drops rows too big to read back (the 2 MB cursor window), left over from before
         * values were capped. Otherwise one such row breaks the log, a script key, or backup export.
         */
        fun repairOversized(context: Context) {
            val w = Db.get(context).writableDatabase
            w.execSQL("DELETE FROM kv WHERE length(v) > ?", arrayOf<Any>(MAX_VALUE))
            w.execSQL("UPDATE logs SET text = substr(text, 1, ?) WHERE length(text) > ?", arrayOf<Any>(LogRepository.MAX_FIELD, LogRepository.MAX_FIELD))
            w.execSQL("UPDATE logs SET ctx = NULL WHERE length(ctx) > ?", arrayOf<Any>(LogRepository.MAX_FIELD))
        }
    }
}
