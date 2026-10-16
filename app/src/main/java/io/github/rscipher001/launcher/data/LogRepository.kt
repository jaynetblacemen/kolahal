package io.github.rscipher001.launcher.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent terminal log. Writes from the `:script` process are announced with a
 * package-scoped broadcast so the UI process can refresh.
 */
class LogRepository private constructor(private val context: Context) {
    private val db get() = Db.get(context)
    private val prefs by lazy { context.getSharedPreferences("logs", Context.MODE_PRIVATE) }

    private val _version = MutableStateFlow(0L)

    /** Bumped on every change seen by this process. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun add(
        kind: LogKind,
        text: String,
        source: String? = null,
        ctx: LogContext = LogContext.EMPTY,
    ): Long {
        val id = db.writableDatabase.insert("logs", null, ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("kind", kind.key)
            put("source", source)
            put("text", text)
            put("ctx", ctx.toJson())
        })
        changed()
        return id
    }

    fun recent(limit: Int = 400): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        db.readableDatabase.rawQuery(
            "SELECT id, ts, kind, source, text, ctx FROM logs WHERE id > ? ORDER BY id DESC LIMIT ?",
            arrayOf(prefs.getLong(KEY_CLEARED_UP_TO, 0L).toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += LogEntry(
                    id = c.getLong(0),
                    ts = c.getLong(1),
                    kind = LogKind.of(c.getString(2)),
                    source = c.getString(3),
                    text = c.getString(4),
                    ctx = LogContext.fromJson(c.getString(5)),
                )
            }
        }
        out.reverse()
        return out
    }

    /** Distinct commands, most recent first, for history navigation. */
    fun commandHistory(limit: Int = 200): List<String> {
        val out = ArrayList<String>()
        db.readableDatabase.rawQuery(
            "SELECT text FROM logs WHERE kind = ? GROUP BY text ORDER BY MAX(id) DESC LIMIT ?",
            arrayOf(LogKind.COMMAND.key, limit.toString()),
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("logs", "id = ?", arrayOf(id.toString()))
        changed()
    }

    /** Hides everything currently on screen; command history is kept for recall. */
    fun clearScreen() {
        val maxId = db.readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM logs", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        prefs.edit().putLong(KEY_CLEARED_UP_TO, maxId).apply()
        db.writableDatabase.delete(
            "logs", "id <= ? AND kind != ?", arrayOf(maxId.toString(), LogKind.COMMAND.key),
        )
        changed()
    }

    fun trim(keep: Int = 3000) {
        db.writableDatabase.execSQL(
            "DELETE FROM logs WHERE id <= (SELECT id FROM logs ORDER BY id DESC LIMIT 1 OFFSET ?)",
            arrayOf(keep),
        )
    }

    /** Script status lines live in the kv table; reuse the log change signal to refresh the header. */
    fun onStatusChanged() = changed()

    /** Called when another process tells us the log changed. */
    fun onExternalChange() {
        _version.value++
    }

    private fun changed() {
        _version.value++
        context.sendBroadcast(Intent(ACTION_LOGS_CHANGED).setPackage(context.packageName))
    }

    companion object {
        private const val KEY_CLEARED_UP_TO = "cleared_up_to"
        const val ACTION_LOGS_CHANGED = "io.github.rscipher001.launcher.LOGS_CHANGED"

        @Volatile
        private var instance: LogRepository? = null

        fun get(context: Context): LogRepository = instance ?: synchronized(this) {
            instance ?: LogRepository(context.applicationContext).also { instance = it }
        }
    }
}
