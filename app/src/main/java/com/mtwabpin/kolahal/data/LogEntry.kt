package com.mtwabpin.kolahal.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class LogKind {
    /** A command the user typed. Tapping it recalls it into the prompt. */
    COMMAND,
    OUTPUT,
    INFO,
    ERROR,
    /** Output produced by a timer script. */
    SCRIPT;

    val key: String get() = name.lowercase()

    companion object {
        fun of(key: String): LogKind = entries.firstOrNull { it.key == key } ?: OUTPUT
    }
}

/**
 * Structured context saved alongside a log line. The terminal uses it to decide what a
 * tap does and which actions to offer on long-press (open link, launch app, rerun script, ...).
 *
 * Well-known keys are exposed as properties; scripts may attach any extra string keys.
 */
@Immutable
data class LogContext(val values: Map<String, String> = emptyMap()) {
    val url: String? get() = values[URL]
    val pkg: String? get() = values[PKG]
    val cls: String? get() = values[CLS]
    val userSerial: Long? get() = values[USER]?.toLongOrNull()
    val script: String? get() = values[SCRIPT]
    val copy: String? get() = values[COPY]
    val command: String? get() = values[COMMAND]
    val image: String? get() = values[IMAGE]

    val isEmpty: Boolean get() = values.isEmpty()

    operator fun plus(other: Map<String, String>) = LogContext(values + other)

    fun toJson(): String? = if (values.isEmpty()) null else JSONObject(values).toString()

    companion object {
        const val URL = "url"
        const val PKG = "pkg"
        const val CLS = "cls"
        const val USER = "user"
        const val SCRIPT = "script"
        const val COPY = "copy"
        const val COMMAND = "command"
        /** An image URL a script attached: it shows under the line, as wide as the log. */
        const val IMAGE = "image"
        /** A command that runs immediately when the line is tapped (setup checklist, hints). */
        const val RUN = "run"
        /** Phone number (tap dials) and contact lookup URI (long-press opens the contact). */
        const val PHONE = "phone"
        /** An app shortcut id (with PKG/USER): tap starts the shortcut. */
        const val SHORTCUT = "shortcut"
        const val CONTACT = "contact"
        /** Source line of a script error (tap opens the editor there). */
        const val LINE = "line"
        /** Marks the header line of a script run. */
        const val RUN_HEADER = "runheader"
        /** Second column text (help, config, script listings render as two columns). */
        const val DESC = "desc"
        /** JSON list of {pkg, cls, user}: the line renders as a grid of app chips. */
        const val APPS = "apps"
        /** Productivity items by database id: a todo ("done" = 1 when finished), note, reminder. */
        const val TODO = "todo"
        const val TODO_DONE = "done"
        const val NOTE = "note"
        const val REMINDER = "reminder"
        /** Calendar event id (with BEGIN/END of the instance): tap opens it. */
        const val EVENT = "event"
        const val BEGIN = "begin"
        const val END = "end"

        val EMPTY = LogContext()

        fun of(vararg pairs: Pair<String, String?>) =
            LogContext(pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap())

        fun fromJson(json: String?): LogContext {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val obj = JSONObject(json)
                LogContext(obj.keys().asSequence().associateWith { obj.optString(it) })
            }.getOrDefault(EMPTY)
        }
    }
}

@Immutable
data class LogEntry(
    val id: Long,
    val ts: Long,
    val kind: LogKind,
    val source: String?,
    val text: String,
    val ctx: LogContext,
)
