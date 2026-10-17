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
        capture.get()?.let { buffer ->
            buffer += Pending(kind, text, source, ctx, System.currentTimeMillis())
            return -1
        }
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

    data class Pending(val kind: LogKind, val text: String, val source: String?, val ctx: LogContext, val ts: Long)

    /** Per-thread output buffer for pipes; lines added on other threads are written as usual. */
    private val capture = ThreadLocal<MutableList<Pending>?>()

    /** Runs [block] with [add] on this thread buffered instead of written, and returns the buffer. */
    fun <T> capture(block: () -> T): Pair<T, List<Pending>> {
        val buffer = ArrayList<Pending>()
        val previous = capture.get()
        capture.set(buffer)
        try {
            return block() to buffer
        } finally {
            capture.set(previous)
        }
    }

    /** Inserts several lines in one transaction with a single change notification. */
    fun addAll(lines: List<Pending>) {
        if (lines.isEmpty()) return
        capture.get()?.let { buffer ->
            buffer += lines
            return
        }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            for (p in lines) {
                w.insert("logs", null, ContentValues().apply {
                    put("ts", p.ts)
                    put("kind", p.kind.key)
                    put("source", p.source)
                    put("text", p.text)
                    put("ctx", p.ctx.toJson())
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        changed()
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
            "SELECT text FROM logs WHERE kind = ? AND id > ? GROUP BY text ORDER BY MAX(id) DESC LIMIT ?",
            arrayOf(LogKind.COMMAND.key, prefs.getLong(KEY_HISTORY_FROM, 0L).toString(), limit.toString()),
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("logs", "id = ?", arrayOf(id.toString()))
        changed()
    }

    /** Empties ↑ recall; the log itself (commands and their output) stays as it is. */
    fun clearHistory() {
        val maxId = db.readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM logs", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        prefs.edit().putLong(KEY_HISTORY_FROM, maxId).apply()
        changed()
    }

    /** Deletes the whole log, history included. */
    fun clearAll() {
        db.writableDatabase.delete("logs", null, null)
        prefs.edit().putLong(KEY_CLEARED_UP_TO, 0L).putLong(KEY_HISTORY_FROM, 0L).apply()
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

    /** Keeps the newest [keep] lines and drops anything older than [maxAgeDays]. */
    /** [maxAgeDays] only from the UI process (the :script process has a stale settings copy). */
    fun trim(keep: Int = 5000, maxAgeDays: Int? = null) {
        db.writableDatabase.execSQL(
            "DELETE FROM logs WHERE id <= (SELECT id FROM logs ORDER BY id DESC LIMIT 1 OFFSET ?)",
            arrayOf(keep),
        )
        if (maxAgeDays == null) return
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        // Commands are kept (↑ history); only their output ages out.
        db.writableDatabase.delete("logs", "ts < ? AND kind != ?", arrayOf(cutoff.toString(), LogKind.COMMAND.key))
    }

    fun maxId(): Long = db.readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM logs", null)
        .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** Newest log id the user has seen (set when home is left), for unread markers. */
    var seenUpTo: Long
        get() = prefs.getLong(KEY_SEEN_UP_TO, Long.MAX_VALUE)
        set(value) = prefs.edit().putLong(KEY_SEEN_UP_TO, value).apply()

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
        private const val KEY_SEEN_UP_TO = "seen_up_to"
        private const val KEY_HISTORY_FROM = "history_from"
        const val ACTION_LOGS_CHANGED = "io.github.rscipher001.launcher.LOGS_CHANGED"

        @Volatile
        private var instance: LogRepository? = null

        fun get(context: Context): LogRepository = instance ?: synchronized(this) {
            instance ?: LogRepository(context.applicationContext).also { instance = it }
        }
    }
}
