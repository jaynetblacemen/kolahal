package com.mtwabpin.kolahal.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.mtwabpin.kolahal.script.ScriptAccess
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.script.ScriptScheduler

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
    /** The user's access switches, item to on or off ([ScriptAccess]); a missing item is undecided. */
    val access: Map<String, Boolean> = emptyMap(),
) {
    val grants: ScriptAccess.Grants get() = ScriptAccess.grants(code, access)
}

class ScriptRepository private constructor(private val context: Context) {
    private val db get() = Db.get(context)

    fun all(): List<Script> = query(null, null)

    fun byName(name: String): Script? = query("name = ?", arrayOf(name)).firstOrNull()

    fun byId(id: Long): Script? = query("id = ?", arrayOf(id.toString())).firstOrNull()

    /**
     * A new script. Created switched on (one of yours, a library install) it may use what it
     * declares; code from a file, a folder or a backup comes [enabled] false and asks when switched on.
     */
    fun create(name: String, lang: ScriptLang, code: String, enabled: Boolean = true): Script {
        db.writableDatabase.insertOrThrow("scripts", null, ContentValues().apply {
            put("name", name)
            put("lang", lang.ext)
            put("code", code)
            put("enabled", if (enabled) 1 else 0)
            if (enabled) put("access", ScriptAccess.encode(ScriptAccess.allowUndecided(code, emptyMap())))
        })
        version.incrementAndGet()
        return byName(name)!!
    }

    /**
     * New code for a script. [byUser] (typed in the editor) and the script switched on: what the new
     * code declares is allowed, since you wrote it. Code from elsewhere leaves new declarations off.
     */
    fun saveCode(id: Long, code: String, byUser: Boolean = false) {
        val s = if (byUser) byId(id) else null
        update(id) {
            put("code", code)
            if (s != null && s.enabled) put("access", ScriptAccess.encode(ScriptAccess.allowUndecided(code, s.access)))
        }
    }

    /**
     * Changes whenever the script list would: this process's writes ([version]) and runs the
     * script process records. Cheap, it reads no code; lets home skip re-reading every script.
     */
    fun fingerprint(): String = db.readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(MAX(last_run), 0), COALESCE(SUM(enabled), 0), COALESCE(MAX(id), 0) FROM scripts", null,
    ).use { c ->
        c.moveToFirst()
        "${version.get()}:${c.getLong(0)}:${c.getLong(1)}:${c.getLong(2)}:${c.getLong(3)}"
    }

    fun setInterval(id: Long, minutes: Int) = update(id) { put("interval_min", minutes) }

    /** Switching a script on allows what it declares and nobody decided yet: the Scripts screen asks first. */
    fun setEnabled(id: Long, enabled: Boolean) {
        val s = byId(id) ?: return
        update(id) {
            put("enabled", if (enabled) 1 else 0)
            if (enabled) put("access", ScriptAccess.encode(ScriptAccess.allowUndecided(s.code, s.access)))
        }
        // Switched off, its header line goes too, rather than coming back stale when it is switched on.
        if (!enabled) KvStore(context, com.mtwabpin.kolahal.script.HostApi.STATUS_SCOPE).set(s.name, null)
    }

    /** The timers and notifications [code] suggests (`@every`, `@at`, `@notify`), for a script just added. */
    fun applySuggested(id: Long, code: String) {
        ScriptMeta.every(code)?.let { setInterval(id, it.coerceAtLeast(ScriptScheduler.MIN_INTERVAL_MIN)) }
        ScriptMeta.at(code).takeIf { it.isNotEmpty() }?.let { setAtTimes(id, it) }
        if (ScriptMeta.notifies(code)) setNotify(id, true)
    }

    /** Switches one access item; see [ScriptAccess]. */
    fun setAccess(id: Long, item: String, allowed: Boolean) {
        val s = byId(id) ?: return
        update(id) { put("access", ScriptAccess.encode(s.access + (item to allowed))) }
    }

    fun setAtTimes(id: Long, times: List<String>) = update(id) {
        if (times.isEmpty()) putNull("at_times") else put("at_times", times.joinToString(","))
    }

    fun setNotify(id: Long, notify: Boolean) = update(id) { put("notify", if (notify) 1 else 0) }

    fun recordRun(id: Long, status: String) = update(id) {
        put("last_run", System.currentTimeMillis())
        put("last_status", status)
    }

    fun delete(id: Long) {
        // Its secrets go with it.
        byId(id)?.let { com.mtwabpin.kolahal.script.SecretStore(context).clear(it.name) }
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
            access = ScriptAccess.parse(getString(getColumnIndexOrThrow("access"))),
        )
    }

    companion object {
        fun scopeOf(scriptId: Long) = "script:$scriptId"

        /** Bumped on every write made in this process; caches compare it to know when to reload. */
        val version = java.util.concurrent.atomic.AtomicLong()

        @SuppressLint("StaticFieldLeak") // application context only

        @Volatile
        private var instance: ScriptRepository? = null

        fun get(context: Context): ScriptRepository = instance ?: synchronized(this) {
            instance ?: ScriptRepository(context.applicationContext).also { instance = it }
        }
    }
}
