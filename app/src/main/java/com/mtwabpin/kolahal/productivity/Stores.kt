package com.mtwabpin.kolahal.productivity

import android.content.ContentValues
import android.content.Context
import com.mtwabpin.kolahal.data.Db

data class Todo(val id: Long, val text: String, val done: Boolean, val created: Long, val doneAt: Long?)
data class Note(val id: Long, val text: String, val created: Long)
/** [repeat]: a [Repeat] rule, or null for a reminder that fires once. */
data class Reminder(val id: Long, val at: Long, val text: String, val repeat: String? = null)

/**
 * Picks an item by what the user typed: `#12` is a database id (used by tap actions, stable
 * across edits), a plain `2` is the position in the list as `todo` / `notes` / `reminders` show it.
 */
fun <T> pickRef(ref: String?, list: List<T>, id: (T) -> Long): T? {
    val r = ref?.trim() ?: return null
    if (r.startsWith("#")) return r.drop(1).toLongOrNull()?.let { want -> list.firstOrNull { id(it) == want } }
    return r.toIntOrNull()?.let { list.getOrNull(it - 1) }
}

/** Open items oldest first, then done items (most recently finished first). */
class TodoStore private constructor(context: Context) {
    private val db = Db.get(context)

    fun all(): List<Todo> {
        val out = ArrayList<Todo>()
        db.readableDatabase.rawQuery(
            "SELECT id, text, done, created, done_at FROM todos ORDER BY done, CASE WHEN done = 0 THEN created ELSE -done_at END, id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Todo(c.getLong(0), c.getString(1), c.getInt(2) != 0, c.getLong(3), if (c.isNull(4)) null else c.getLong(4))
            }
        }
        return out
    }

    fun open(): List<Todo> = all().filter { !it.done }

    fun openCount(): Int = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM todos WHERE done = 0", null)
        .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun add(text: String): Long = db.writableDatabase.insert("todos", null, ContentValues().apply {
        put("text", text)
        put("created", System.currentTimeMillis())
    })

    fun setDone(id: Long, done: Boolean) {
        db.writableDatabase.update("todos", ContentValues().apply {
            put("done", if (done) 1 else 0)
            if (done) put("done_at", System.currentTimeMillis()) else putNull("done_at")
        }, "id = ?", arrayOf(id.toString()))
    }

    fun edit(id: Long, text: String) {
        db.writableDatabase.update("todos", ContentValues().apply { put("text", text) }, "id = ?", arrayOf(id.toString()))
    }

    fun remove(id: Long) = db.writableDatabase.delete("todos", "id = ?", arrayOf(id.toString()))

    /** Deletes finished items; returns how many. */
    fun clearDone(): Int = db.writableDatabase.delete("todos", "done = 1", null)

    companion object {
        @Volatile private var instance: TodoStore? = null
        fun get(context: Context): TodoStore = instance ?: synchronized(this) {
            instance ?: TodoStore(context.applicationContext).also { instance = it }
        }
    }
}

/** Quick notes, newest first. */
class NoteStore private constructor(context: Context) {
    private val db = Db.get(context)

    fun all(): List<Note> {
        val out = ArrayList<Note>()
        db.readableDatabase.rawQuery("SELECT id, text, created FROM notes ORDER BY created DESC, id DESC", null).use { c ->
            while (c.moveToNext()) out += Note(c.getLong(0), c.getString(1), c.getLong(2))
        }
        return out
    }

    fun add(text: String): Long = db.writableDatabase.insert("notes", null, ContentValues().apply {
        put("text", text)
        put("created", System.currentTimeMillis())
    })

    fun remove(id: Long) = db.writableDatabase.delete("notes", "id = ?", arrayOf(id.toString()))

    companion object {
        @Volatile private var instance: NoteStore? = null
        fun get(context: Context): NoteStore = instance ?: synchronized(this) {
            instance ?: NoteStore(context.applicationContext).also { instance = it }
        }
    }
}

/** Pending reminders, soonest first. A reminder is deleted once it fires (unless it repeats) or is cancelled. */
class ReminderStore private constructor(context: Context) {
    private val db = Db.get(context)

    fun all(): List<Reminder> {
        val out = ArrayList<Reminder>()
        db.readableDatabase.rawQuery("SELECT id, at, text, repeat_rule FROM reminders ORDER BY at, id", null).use { c ->
            while (c.moveToNext()) out += Reminder(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))
        }
        return out
    }

    fun byId(id: Long): Reminder? = all().firstOrNull { it.id == id }

    fun add(at: Long, text: String, repeat: String? = null): Reminder {
        val id = db.writableDatabase.insertOrThrow("reminders", null, ContentValues().apply {
            put("at", at)
            put("text", text)
            put("created", System.currentTimeMillis())
            put("repeat_rule", repeat)
        })
        return Reminder(id, at, text, repeat)
    }

    /** A repeating reminder's next time. */
    fun setAt(id: Long, at: Long) {
        db.writableDatabase.update("reminders", ContentValues().apply { put("at", at) }, "id = ?", arrayOf(id.toString()))
    }

    fun remove(id: Long) = db.writableDatabase.delete("reminders", "id = ?", arrayOf(id.toString()))

    companion object {
        @Volatile private var instance: ReminderStore? = null
        fun get(context: Context): ReminderStore = instance ?: synchronized(this) {
            instance ?: ReminderStore(context.applicationContext).also { instance = it }
        }
    }
}
