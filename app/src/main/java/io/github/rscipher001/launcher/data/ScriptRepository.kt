package io.github.rscipher001.launcher.data

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
    }

    private fun update(id: Long, block: ContentValues.() -> Unit) {
        db.writableDatabase.update("scripts", ContentValues().apply(block), "id = ?", arrayOf(id.toString()))
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

    fun get(key: String): String? = db.readableDatabase.rawQuery(
        "SELECT v FROM kv WHERE scope = ? AND k = ?", arrayOf(scope, key),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun set(key: String, value: String?) {
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
}
