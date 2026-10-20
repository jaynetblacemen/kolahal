package com.mtwabpin.kolahal.data

import android.content.Context
import android.net.Uri
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.script.Files
import com.mtwabpin.kolahal.script.Json
import com.mtwabpin.kolahal.script.ScriptScheduler

/**
 * One JSON file with everything a user customises: settings, aliases, hidden/renamed apps and
 * all scripts (code + timers). Written and read through the Storage Access Framework, so it can
 * be kept on a computer, edited there, and imported again.
 *
 * ```
 * { "kolahal": 1,
 *   "config":  { "theme": "gruvbox", ... },          // only changed settings
 *   "aliases": { "m": "open maps" },
 *   "apps":    { "hidden": ["<component>#<user>"], "labels": { "<component>#<user>": "Name" } },
 *   "scripts": [ { "name": "hn", "lang": "lua", "code": "...", "every": 30,
 *                  "at": ["08:00"], "notify": true, "enabled": true } ] }
 * ```
 */
class Backup(private val context: Context) {
    private val config = Config.get(context)
    private val prefs = AppPrefs.get(context)
    private val scripts = ScriptRepository.get(context)

    fun export(uri: Uri): Int {
        val all = scripts.all()
        val data = mapOf(
            "kolahal" to VERSION,
            "config" to Keys.ALL.filterNot(config::isDefault).associate { it.key to config.display(it) },
            "aliases" to prefs.aliases(),
            "apps" to mapOf("hidden" to prefs.hiddenKeys(), "labels" to prefs.labels(), "pinned" to prefs.pinned()),
            "scripts" to all.map {
                mapOf(
                    "name" to it.name, "lang" to it.lang.ext, "code" to it.code, "every" to it.intervalMin,
                    "at" to it.atTimes, "notify" to it.notify, "enabled" to it.enabled,
                )
            },
            // Everything else: todos, notes, reminders, feeds, script stores, log, history.
            "data" to FullBackup.export(context),
            // Widget ids can't move between devices; the list lets the Widgets page offer "Add again".
            // An imported terminal font travels with the backup (base64; imports are capped at 10 MB).
            "font" to com.mtwabpin.kolahal.ui.TermFonts.customFile(context)?.let { f ->
                mapOf("name" to f.name, "base64" to android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
            },
            "widgets" to runCatching { com.mtwabpin.kolahal.widgets.WidgetHost.get(context).exportWidgets() }.getOrDefault(emptyList()),
        )
        Files.writeText(context, uri, Json.stringify(data))
        return all.size
    }

    data class Result(
        val settings: Int, val aliases: Int, val scripts: Int, val disabled: Int, val skipped: List<String>,
        /** Rows restored from the raw data section (0 for older, settings-only backups). */
        val restored: Int = 0,
        val full: Boolean = false,
    )

    fun import(uri: Uri): Result {
        val text = Files.readText(context, uri, Files.MAX_BACKUP_BYTES)
        val root = Json.parse(text) as? Map<*, *> ?: throw IllegalStateException("not a Kolahal backup")
        if (root["kolahal"] == null) throw IllegalStateException("not a Kolahal backup (missing \"kolahal\")")
        val skipped = ArrayList<String>()

        // Raw data first, so the readable sections below (which people edit by hand) win.
        val data = root["data"] as? Map<*, *>
        val restored = data?.let { FullBackup.importRaw(context, it) } ?: 0

        var settings = 0
        (root["config"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = Keys.byName(k.toString())
            if (key != null && v != null && config.set(key, v.toString())) settings++ else skipped += "config ${k}"
        }

        var aliases = 0
        (root["aliases"] as? Map<*, *>)?.forEach { (k, v) ->
            val name = k?.toString()
            when {
                name == null || v == null -> Unit
                // Same rule as `alias`: an alias must never shadow a built-in command.
                name in BUILT_INS -> skipped += "alias $name (built-in command)"
                else -> { prefs.setAlias(name, v.toString()); aliases++ }
            }
        }

        (root["apps"] as? Map<*, *>)?.let { apps ->
            (apps["hidden"] as? List<*>)?.forEach { prefs.setHidden(it.toString(), true) }
            (apps["labels"] as? Map<*, *>)?.forEach { (k, v) -> if (k != null) prefs.setLabel(k.toString(), v?.toString()) }
            (apps["pinned"] as? List<*>)?.forEach { prefs.setPinned(it.toString(), true) }
        }

        var count = 0
        var disabled = 0
        (root["scripts"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { m ->
            val name = m["name"]?.toString()?.lowercase()
            val lang = m["lang"]?.toString()?.let(ScriptLang::of)
            val code = m["code"]?.toString()
            if (name == null || lang == null || code == null || !NAME.matches(name) || name in BUILT_INS) {
                skipped += "script ${m["name"]}"
                return@forEach
            }
            val existing = scripts.byName(name)
            var isNew = false
            val script = when {
                existing == null -> scripts.create(name, lang, code).also { isNew = true }
                existing.lang != lang -> { skipped += "script $name (language differs)"; return@forEach }
                else -> existing.also { if (it.code != code) scripts.saveCode(it.id, code) }
            }
            (m["every"] as? Number)?.toLong()?.let { every ->
                val minutes = every.coerceIn(0, 7L * 24 * 60).toInt()
                scripts.setInterval(script.id, if (minutes in 1 until ScriptScheduler.MIN_INTERVAL_MIN) ScriptScheduler.MIN_INTERVAL_MIN else minutes)
            }
            (m["at"] as? List<*>)?.let { at ->
                scripts.setAtTimes(script.id, at.mapNotNull { ScriptScheduler.normalizeTime(it.toString()) }.distinct().sorted())
            }
            (m["notify"] as? Boolean)?.let { scripts.setNotify(script.id, it) }
            // New scripts from a backup arrive switched off: review them before they run on a timer.
            if (isNew) {
                scripts.setEnabled(script.id, false)
                disabled++
            } else {
                (m["enabled"] as? Boolean)?.let { scripts.setEnabled(script.id, it) }
            }
            runCatching { scripts.byId(script.id)?.let { ScriptScheduler.sync(context, it) } }
            count++
        }
        (root["font"] as? Map<*, *>)?.let { font ->
            val name = font["name"]?.toString()?.takeIf { it == "custom.ttf" || it == "custom.otf" }
            val data = (font["base64"] as? String)?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
            if (name != null && data != null && data.size <= 10 * 1024 * 1024) {
                runCatching {
                    val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
                    dir.listFiles()?.filter { it.name.startsWith("custom.") }?.forEach { it.delete() }
                    java.io.File(dir, name).writeBytes(data)
                }
            }
        }
        (root["widgets"] as? List<*>)?.let { list ->
            @Suppress("UNCHECKED_CAST")
            runCatching { com.mtwabpin.kolahal.widgets.WidgetHost.get(context).importWidgets(list.filterIsInstance<Map<String, Any?>>()) }
        }
        if (data != null) {
            FullBackup.importScriptStores(context, data)
            runCatching { com.mtwabpin.kolahal.productivity.ReminderScheduler.rescheduleAll(context) }
            runCatching { com.mtwabpin.kolahal.feeds.FeedScheduler.sync(context) }
            runCatching { ScriptScheduler.syncAll(context) }
        }
        return Result(settings, aliases, count, disabled, skipped, restored, full = data != null)
    }

    companion object {
        const val VERSION = 2
        const val FILE_NAME = "kolahal-backup.json"
        val NAME = Regex("^[a-z0-9_-]{1,40}$")
        private val BUILT_INS get() = com.mtwabpin.kolahal.terminal.CommandProcessor.COMMANDS.map { it.name }.toSet()
    }
}
