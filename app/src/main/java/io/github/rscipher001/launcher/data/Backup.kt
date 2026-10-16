package io.github.rscipher001.launcher.data

import android.content.Context
import android.net.Uri
import io.github.rscipher001.launcher.apps.AppPrefs
import io.github.rscipher001.launcher.script.Json
import io.github.rscipher001.launcher.script.ScriptScheduler

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
            "apps" to mapOf("hidden" to prefs.hiddenKeys(), "labels" to prefs.labels()),
            "scripts" to all.map {
                mapOf(
                    "name" to it.name, "lang" to it.lang.ext, "code" to it.code, "every" to it.intervalMin,
                    "at" to it.atTimes, "notify" to it.notify, "enabled" to it.enabled,
                )
            },
        )
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Json.stringify(data).toByteArray()) }
        return all.size
    }

    data class Result(val settings: Int, val aliases: Int, val scripts: Int, val skipped: List<String>)

    fun import(uri: Uri): Result {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("can't read the file")
        val root = Json.parse(text) as? Map<*, *> ?: throw IllegalStateException("not a Kolahal backup")
        if (root["kolahal"] == null) throw IllegalStateException("not a Kolahal backup (missing \"kolahal\")")
        val skipped = ArrayList<String>()

        var settings = 0
        (root["config"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = Keys.byName(k.toString())
            if (key != null && v != null && config.set(key, v.toString())) settings++ else skipped += "config ${k}"
        }

        var aliases = 0
        (root["aliases"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k != null && v != null) { prefs.setAlias(k.toString(), v.toString()); aliases++ }
        }

        (root["apps"] as? Map<*, *>)?.let { apps ->
            (apps["hidden"] as? List<*>)?.forEach { prefs.setHidden(it.toString(), true) }
            (apps["labels"] as? Map<*, *>)?.forEach { (k, v) -> if (k != null) prefs.setLabel(k.toString(), v?.toString()) }
        }

        var count = 0
        (root["scripts"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { m ->
            val name = m["name"]?.toString()?.lowercase()
            val lang = m["lang"]?.toString()?.let(ScriptLang::of)
            val code = m["code"]?.toString()
            if (name == null || lang == null || code == null || !NAME.matches(name)) {
                skipped += "script ${m["name"]}"
                return@forEach
            }
            val existing = scripts.byName(name)
            val script = when {
                existing == null -> scripts.create(name, lang, code)
                existing.lang != lang -> { skipped += "script $name (language differs)"; return@forEach }
                else -> existing.also { if (it.code != code) scripts.saveCode(it.id, code) }
            }
            (m["every"] as? Number)?.let { scripts.setInterval(script.id, it.toInt()) }
            (m["at"] as? List<*>)?.let { at ->
                scripts.setAtTimes(script.id, at.mapNotNull { ScriptScheduler.normalizeTime(it.toString()) }.distinct().sorted())
            }
            (m["notify"] as? Boolean)?.let { scripts.setNotify(script.id, it) }
            (m["enabled"] as? Boolean)?.let { scripts.setEnabled(script.id, it) }
            runCatching { scripts.byId(script.id)?.let { ScriptScheduler.sync(context, it) } }
            count++
        }
        return Result(settings, aliases, count, skipped)
    }

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "kolahal-backup.json"
        val NAME = Regex("^[a-z0-9_-]{1,40}$")
    }
}
