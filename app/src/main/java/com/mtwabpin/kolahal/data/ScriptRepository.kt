package com.mtwabpin.kolahal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

enum class ScriptLang(val ext: String) {
    LUA("lua"),
    JS("js");

    companion object {
        fun of(value: String): ScriptLang? = when (value.lowercase().removePrefix(".")) {
            "lua" -> LUA
            "js", "javascript" -> JS
            else -> null
        }
    }
}

data class Script(
    val id: Long,
    val name: String,
    val lang: ScriptLang,
    val code: String,
    /** 0 = manual only. WorkManager enforces a 15 minute minimum for periodic runs. */
    val intervalMin: Int,
    val enabled: Boolean,
    val notify: Boolean,
    val lastRun: Long?,
    val lastStatus: String?,
    /** Daily run times as "HH:mm", in addition to (or instead of) the interval. */
    val atTimes: List<String> = emptyList(),
)

class ScriptRepository private constructor(private val context: Context) {
    private val db get() = Db.get(context)

    fun all(): List<Script> = query(null, null)

    fun byName(name: String): Script? = query("name = ?", arrayOf(name)).firstOrNull()

    fun byId(id: Long): Script? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    fun create(name: String, lang: ScriptLang, code: String): Script {
        db.writableDatabase.insertOrThrow("scripts", null, ContentValues().apply {
            put("name", name)
            put("lang", lang.ext)
            put("code", code)
        })
        version.incrementAndGet()
        return byName(name)!!
    }

    fun saveCode(id: Long, code: String) = update(id) { put("code", code) }

    fun setInterval(id: Long, minutes: Int) = update(id) { put("interval_min", minutes) }

    fun setEnabled(id: Long, enabled: Boolean) = update(id) { put("enabled", if (enabled) 1 else 0) }

    fun setAtTimes(id: Long, times: List<String>) = update(id) {
        if (times.isEmpty()) putNull("at_times") else put("at_times", times.joinToString(","))
    }

    fun setNotify(id: Long, notify: Boolean) = update(id) { put("notify", if (notify) 1 else 0) }

    fun recordRun(id: Long, status: String) = update(id) {
        put("last_run", System.currentTimeMillis())
        put("last_status", status)
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("scripts", "id = ?", arrayOf(id.toString()))
        db.writableDatabase.delete("kv", "scope = ?", arrayOf(scopeOf(id)))
        version.incrementAndGet()
    }

    private fun update(id: Long, block: ContentValues.() -> Unit) {
        db.writableDatabase.update("scripts", ContentValues().apply(block), "id = ?", arrayOf(id.toString()))
        version.incrementAndGet()
    }

    private fun query(where: String?, args: Array<String>?): List<Script> {
        val out = ArrayList<Script>()
        db.readableDatabase.query("scripts", null, where, args, null, null, "name").use { c ->
            while (c.moveToNext()) c.toScript()?.let(out::add)
        }
        return out
    }

    private fun Cursor.toScript(): Script? {
        val lang = ScriptLang.of(getString(getColumnIndexOrThrow("lang"))) ?: return null
        val lastRunIdx = getColumnIndexOrThrow("last_run")
        return Script(
            id = getLong(getColumnIndexOrThrow("id")),
            name = getString(getColumnIndexOrThrow("name")),
            lang = lang,
            code = getString(getColumnIndexOrThrow("code")),
            intervalMin = getInt(getColumnIndexOrThrow("interval_min")),
            enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
            notify = getInt(getColumnIndexOrThrow("notify")) != 0,
            lastRun = if (isNull(lastRunIdx)) null else getLong(lastRunIdx),
            lastStatus = getString(getColumnIndexOrThrow("last_status")),
            atTimes = getString(getColumnIndexOrThrow("at_times"))?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    companion object {
        fun scopeOf(scriptId: Long) = "script:$scriptId"

        /** Bumped on every write made in this process; caches compare it to know when to reload. */
        val version = java.util.concurrent.atomic.AtomicLong()

        @Volatile
        private var instance: ScriptRepository? = null

        fun get(context: Context): ScriptRepository = instance ?: synchronized(this) {
            instance ?: ScriptRepository(context.applicationContext).also { instance = it }
        }
    }
}

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
