package com.mtwabpin.kolahal.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import com.mtwabpin.kolahal.feeds.FeedDb
import com.mtwabpin.kolahal.notifications.KolahalNotificationListener
import com.mtwabpin.kolahal.script.SecretStore

/**
 * The raw half of a backup: every app preference file and database table, so a restore brings
 * back everything (todos, notes, reminders, feeds and their read state, script stores, the log,
 * launch history, unlock history, notification and trigger settings…).
 *
 * The readable sections of [Backup] (config, aliases, apps, scripts) are applied after this, so
 * hand edits to them win. Things that can't move between devices are left out: the picked script
 * folder (a storage permission) and WorkManager's own schedule state (rebuilt on restore).
 */
object FullBackup {
    /** SharedPreferences files that hold user data. */
    private val PREFS = listOf(
        "config", "aliases", "apps", "app_usage", "notif_prefs", "script_library", "script_catalog", "unlocks", "triggers", "launcher", "logs",
    )

    private class Table(val db: (Context) -> SQLiteDatabase, val name: String)

    private val TABLES = listOf(
        Table({ Db.get(it).writableDatabase }, "logs"),
        Table({ Db.get(it).writableDatabase }, "kv"),
        Table({ Db.get(it).writableDatabase }, "todos"),
        Table({ Db.get(it).writableDatabase }, "notes"),
        Table({ Db.get(it).writableDatabase }, "reminders"),
        Table({ FeedDb.get(it).writableDatabase }, "feeds"),
        Table({ FeedDb.get(it).writableDatabase }, "items"),
    )

    /** kv scopes are "script:<id>"; ids differ between devices, so backups key them by script name. */
    private const val SCRIPT_SCOPE = "script:"
    private val BUILT_INS get() = com.mtwabpin.kolahal.terminal.CommandProcessor.COMMAND_NAMES

    fun export(context: Context): Map<String, Any?> {
        // Rows too big for a cursor would make the whole export fail.
        runCatching { KvStore.repairOversized(context) }
        val scriptNames = ScriptRepository.get(context).all().associate { ScriptRepository.scopeOf(it.id) to it.name }
        val tables = LinkedHashMap<String, Any?>()
        val scriptKv = LinkedHashMap<String, MutableMap<String, String?>>()
        for (t in TABLES) {
            val rows = query(t.db(context), t.name)
            tables[t.name] = if (t.name == "logs") rows.filterNot(::private) else if (t.name != "kv") rows else rows.filter { row ->
                val scope = row["scope"] as? String ?: return@filter false
                // The copy of recent notifications kept for scripts stays on the device.
                if (scope == KolahalNotificationListener.MIRROR_SCOPE) return@filter false
                // Script secrets never leave the phone (and only its Keystore key can read them anyway).
                if (scope.startsWith(SecretStore.SCOPE_PREFIX)) return@filter false
                if (!scope.startsWith(SCRIPT_SCOPE)) return@filter true
                // Stores of deleted scripts are dropped; the rest move under the script's name.
                scriptNames[scope]?.let { name -> scriptKv.getOrPut(name) { LinkedHashMap() }[row["k"].toString()] = row["v"] as? String }
                false
            }
        }
        return mapOf(
            "prefs" to PREFS.associateWith { file -> exportPrefs(context, file) },
            "tables" to tables,
            "scriptStores" to scriptKv,
        )
    }

    /** Replaces preferences and tables with the backup's. Returns how many rows were restored. */
    fun importRaw(context: Context, data: Map<*, *>): Int {
        (data["prefs"] as? Map<*, *>)?.forEach { (file, entries) ->
            if (file !is String || file !in PREFS || entries !is Map<*, *>) return@forEach
            // Same rule as `alias`: an alias never shadows a built-in command.
            val safe = if (file != "aliases") entries else entries.filterKeys { it?.toString()?.lowercase() !in BUILT_INS }
            importPrefs(context, file, safe)
        }
        var rows = 0
        val tables = data["tables"] as? Map<*, *> ?: return 0
        for (t in TABLES) {
            // A backup never brings secrets: only this phone's are kept.
            val list = (tables[t.name] as? List<*>)?.filterIsInstance<Map<*, *>>()
                ?.filterNot { t.name == "kv" && it["scope"]?.toString().orEmpty().startsWith(SecretStore.SCOPE_PREFIX) } ?: continue
            val db = t.db(context)
            db.transaction {
                if (t.name == "kv") {
                    // Keep this device's script stores (re-attached by name afterwards) and script secrets.
                    db.delete("kv", "scope NOT LIKE ? AND scope NOT LIKE ?", arrayOf("$SCRIPT_SCOPE%", "${SecretStore.SCOPE_PREFIX}%"))
                } else {
                    db.delete(t.name, null, null)
                }
                // Only this device's columns: an unknown key would fail the insert halfway through a restore.
                val columns = db.rawQuery("PRAGMA table_info(${t.name})", null).use { c ->
                    buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
                }
                for (row in list) {
                    val clean = if (t.name == "logs") withSafeCtx(row) else row
                    if (db.insertWithOnConflict(t.name, null, values(clean, columns), SQLiteDatabase.CONFLICT_REPLACE) != -1L) rows++
                }
            }
        }
        return rows
    }

    /** After scripts exist (by name), puts each script's store back under its new id. */
    fun importScriptStores(context: Context, data: Map<*, *>) {
        val stores = data["scriptStores"] as? Map<*, *> ?: return
        val repo = ScriptRepository.get(context)
        stores.forEach { (name, entries) ->
            val script = repo.byName(name.toString()) ?: return@forEach
            val kv = KvStore(context, ScriptRepository.scopeOf(script.id))
            // runCatching: an oversized value (e.g. an old cached HTTP body) is skipped, not fatal.
            (entries as? Map<*, *>)?.forEach { (k, v) -> if (k != null) runCatching { kv.set(k.toString(), v?.toString()) } }
        }
    }

    // ---- preferences: values are tagged so ints, longs and string sets survive JSON --------------

    private fun exportPrefs(context: Context, file: String): Map<String, Any?> =
        context.getSharedPreferences(file, Context.MODE_PRIVATE).all.mapValues { (_, v) ->
            when (v) {
                is Boolean -> mapOf("b" to v)
                is Int -> mapOf("i" to v)
                is Long -> mapOf("l" to v)
                is Float -> mapOf("f" to v.toDouble())
                is Set<*> -> mapOf("set" to v.map { it.toString() })
                else -> mapOf("s" to v?.toString())
            }
        }

    private fun importPrefs(context: Context, file: String, entries: Map<*, *>) {
        // commit, not apply: the app restarts right after a restore.
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit(commit = true) {
            clear()
            entries.forEach { (k, tagged) ->
                val key = k?.toString() ?: return@forEach
                val m = tagged as? Map<*, *> ?: return@forEach
                when {
                    "b" in m -> putBoolean(key, m["b"] == true)
                    "i" in m -> putInt(key, (m["i"] as? Number)?.toInt() ?: 0)
                    "l" in m -> putLong(key, (m["l"] as? Number)?.toLong() ?: 0L)
                    "f" in m -> putFloat(key, (m["f"] as? Number)?.toFloat() ?: 0f)
                    "set" in m -> putStringSet(key, (m["set"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet())
                    else -> putString(key, m["s"]?.toString())
                }
            }
        }
    }

    // ---- tables ------------------------------------------------------------------------------

    private fun query(db: SQLiteDatabase, table: String): List<Map<String, Any?>> =
        db.rawQuery("SELECT * FROM $table", null).use { c ->
            val out = ArrayList<Map<String, Any?>>(c.count)
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> mapOf("base64" to Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                        else -> c.getString(i)
                    }
                }
                out += row
            }
            out
        }

    /**
     * Notification and call lines hold other people's messages and numbers: they stay on the
     * device, as do lines from a private space.
     */
    private fun private(row: Map<String, Any?>): Boolean {
        if (row["source"] == com.mtwabpin.kolahal.calls.CallEvents.SOURCE) return true
        val ctx = LogContext.fromJson(row["ctx"] as? String)
        return ctx.values.containsKey(KolahalNotificationListener.CTX_KEY)
    }

    /**
     * A restored line must not do more on a tap than the line it claims to be: no commands that
     * run at once, no app shortcuts, and links only to the web, maps or the store.
     */
    private fun withSafeCtx(row: Map<*, *>): Map<*, *> {
        val ctx = LogContext.fromJson(row["ctx"] as? String)
        if (ctx.isEmpty) return row
        val kept = ctx.values.filter { (k, v) ->
            when (k) {
                LogContext.RUN, LogContext.SHORTCUT -> false
                LogContext.URL -> com.mtwabpin.kolahal.script.Net.webUrl(v) != null || v.startsWith("geo:") || v.startsWith("market:")
                LogContext.IMAGE -> com.mtwabpin.kolahal.script.Net.webUrl(v) != null
                else -> true
            }
        }
        return row.toMutableMap().apply { put("ctx", LogContext(kept).toJson()) }
    }

    private fun values(row: Map<*, *>, columns: Set<String>) = ContentValues().apply {
        row.forEach { (k, v) ->
            val col = k?.toString()?.takeIf { it in columns } ?: return@forEach
            when (v) {
                null -> putNull(col)
                is Boolean -> put(col, if (v) 1 else 0)
                is Int -> put(col, v)
                is Long -> put(col, v)
                is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 9e15) put(col, v.toLong()) else put(col, v)
                is Number -> put(col, v.toLong())
                is Map<*, *> -> (v["base64"] as? String)?.let { put(col, Base64.decode(it, Base64.NO_WRAP)) }
                else -> put(col, v.toString())
            }
        }
    }
}
