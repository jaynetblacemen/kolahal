package com.mtwabpin.kolahal.data

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
            put("text", clip(text))
            put("ctx", clip(ctx).toJson())
        })
        changed()
        return id
    }

    /**
     * Like [add], but first removes earlier lines with the same kind and text, so a repeated
     * event (launching the same app again) keeps a single line, moved to the bottom.
     */
    fun addReplacing(kind: LogKind, text: String, ctx: LogContext = LogContext.EMPTY): Long {
        if (capture.get() != null) return add(kind, text, ctx = ctx)
        // The earlier line moves to the bottom: more than an append for home's incremental load.
        if (db.writableDatabase.delete("logs", "kind = ? AND text = ?", arrayOf(kind.key, text)) > 0) structure++
        return add(kind, text, ctx = ctx)
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
                    put("text", clip(p.text))
                    put("ctx", clip(p.ctx).toJson())
                })
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        changed()
    }

    /**
     * Bumped by every change other than new lines at the end (deletes, hides, clears, trims, a
     * line moved down): home then reloads the page instead of fetching only the new lines.
     */
    @Volatile var structure = 0L
        private set

    private fun restructured() {
        structure++
        changed(restructure = true)
    }

    /** The newest [limit] visible lines, oldest first; only those after [afterId] when given. */
    fun recent(limit: Int = 400, afterId: Long = 0L): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        db.readableDatabase.rawQuery(
            // substr: a row over the 2 MB cursor window (written before lines were capped) can't be
            // read at all, and would break loading the log for good.
            "SELECT id, ts, kind, source, substr(text, 1, $MAX_FIELD), substr(ctx, 1, $MAX_FIELD) FROM logs WHERE id > ? ORDER BY id DESC LIMIT ?",
            arrayOf(maxOf(visibleAfter(), afterId).toString(), limit.toString()),
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
        restructured()
    }

    /** Puts a deleted line back with its original id, so it returns to the same place. */
    fun restore(e: LogEntry) {
        db.writableDatabase.insertWithOnConflict("logs", null, ContentValues().apply {
            put("id", e.id)
            put("ts", e.ts)
            put("kind", e.kind.key)
            put("source", e.source)
            put("text", clip(e.text))
            put("ctx", clip(e.ctx).toJson())
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        restructured()
    }

    /** Empties ↑ recall; the log itself (commands and their output) stays as it is. */
    fun clearHistory() {
        val maxId = db.readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM logs", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        prefs.edit().putLong(KEY_HISTORY_FROM, maxId).apply()
        restructured()
    }

    /** Deletes the whole log, history included. */
    fun clearAll() {
        db.writableDatabase.delete("logs", null, null)
        prefs.edit().putLong(KEY_CLEARED_UP_TO, 0L).putLong(KEY_HISTORY_FROM, 0L).putLong(KEY_HIDDEN_UP_TO, 0L).apply()
        restructured()
    }

    /** Lines at or below this id are not shown: the larger of a real clear and a fresh-screen hide. */
    private fun visibleAfter(): Long = maxOf(prefs.getLong(KEY_CLEARED_UP_TO, 0L), prefs.getLong(KEY_HIDDEN_UP_TO, 0L))

    /**
     * Fresh screen (`log.fresh`): hides what is on screen without deleting anything. [hiddenCount]
     * says how much can come back with [unhide].
     */
    fun hideScreen(upTo: Long = maxId()) {
        prefs.edit().putLong(KEY_HIDDEN_UP_TO, upTo).apply()
        restructured()
    }

    fun unhide() {
        prefs.edit().putLong(KEY_HIDDEN_UP_TO, 0L).apply()
        restructured()
    }

    /** Lines a fresh-screen hide is keeping out of view (0 when nothing can be restored). */
    fun hiddenCount(): Int {
        val cleared = prefs.getLong(KEY_CLEARED_UP_TO, 0L)
        val hidden = prefs.getLong(KEY_HIDDEN_UP_TO, 0L)
        if (hidden <= cleared) return 0
        return db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM logs WHERE id > ? AND id <= ?", arrayOf(cleared.toString(), hidden.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** The newest line, to avoid logging the same command twice in a row with nothing in between. */
    fun last(): Pair<LogKind, String>? = db.readableDatabase.rawQuery(
        "SELECT kind, text FROM logs WHERE id > ? ORDER BY id DESC LIMIT 1", arrayOf(visibleAfter().toString()),
    ).use { c -> if (c.moveToFirst()) LogKind.of(c.getString(0)) to c.getString(1) else null }

    /** Hides everything currently on screen; command history is kept for recall. */
    fun clearScreen() {
        val maxId = db.readableDatabase.rawQuery("SELECT COALESCE(MAX(id), 0) FROM logs", null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        prefs.edit().putLong(KEY_CLEARED_UP_TO, maxId).apply()
        db.writableDatabase.delete(
            "logs", "id <= ? AND kind != ?", arrayOf(maxId.toString(), LogKind.COMMAND.key),
        )
        restructured()
    }

    /**
     * `log.keepdays` for a launcher that stays running for weeks: the age trim, at most every 12
     * hours, so notification and call lines don't outlive the setting.
     */
    fun trimIfDue(maxAgeDays: Int) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_TRIMMED_AT, 0L) < 12 * 3_600_000L) return
        prefs.edit().putLong(KEY_TRIMMED_AT, now).apply()
        trim(maxAgeDays = maxAgeDays)
    }

    /** Keeps the newest [keep] lines and drops anything older than [maxAgeDays]. */
    /** [maxAgeDays] only from the UI process (the :script process has a stale settings copy). */
    fun trim(keep: Int = 5000, maxAgeDays: Int? = null) {
        val w = db.writableDatabase
        w.execSQL(
            "DELETE FROM logs WHERE id <= (SELECT id FROM logs ORDER BY id DESC LIMIT 1 OFFSET ?)",
            arrayOf(keep),
        )
        var removed = changes(w)
        if (maxAgeDays != null) {
            val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
            // Commands are kept (↑ history); only their output ages out.
            removed += w.delete("logs", "ts < ? AND kind != ?", arrayOf(cutoff.toString(), LogKind.COMMAND.key))
        }
        if (removed > 0) restructured()
    }

    private fun changes(w: android.database.sqlite.SQLiteDatabase): Int =
        w.rawQuery("SELECT changes()", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

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

    /** Another process changed more than the end of the log (a trim): reload the page. */
    fun onExternalRestructure() = restructured()

    private fun changed(restructure: Boolean = false) {
        _version.value++
        // Other processes (scripts) tell home; home's own writes already bumped the version above.
        if (!inMainProcess) {
            context.sendBroadcast(
                Intent(ACTION_LOGS_CHANGED).setPackage(context.packageName).putExtra(EXTRA_RESTRUCTURE, restructure),
            )
        }
    }

    private val inMainProcess by lazy { android.app.Application.getProcessName() == context.packageName }

    /** Caps a line's text: one huge value (an HTTP body, a script's log(bigString)) must stay readable. */
    private fun clip(s: String) = if (s.length <= MAX_FIELD) s else s.take(MAX_FIELD - 1) + "…"

    private fun clip(ctx: LogContext): LogContext =
        if (ctx.values.values.none { it.length > MAX_FIELD / 4 }) ctx
        else LogContext(ctx.values.mapValues { (_, v) -> if (v.length > MAX_FIELD / 4) v.take(MAX_FIELD / 4 - 1) + "…" else v })

    companion object {
        /** Characters kept per stored field; far below the 2 MB SQLite cursor window. */
        const val MAX_FIELD = 64 * 1024
        private const val KEY_CLEARED_UP_TO = "cleared_up_to"
        private const val KEY_HIDDEN_UP_TO = "hidden_up_to"
        private const val KEY_TRIMMED_AT = "trimmed_at"
        private const val KEY_SEEN_UP_TO = "seen_up_to"
        private const val KEY_HISTORY_FROM = "history_from"
        const val ACTION_LOGS_CHANGED = "com.mtwabpin.kolahal.LOGS_CHANGED"
        const val EXTRA_RESTRUCTURE = "restructure"

        @Volatile
        private var instance: LogRepository? = null

        fun get(context: Context): LogRepository = instance ?: synchronized(this) {
            instance ?: LogRepository(context.applicationContext).also { instance = it }
        }
    }
}
