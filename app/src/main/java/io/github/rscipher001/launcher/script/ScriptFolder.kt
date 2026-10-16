package io.github.rscipher001.launcher.script

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.data.ScriptRepository

/**
 * Syncs scripts with a user-picked folder (Storage Access Framework), so they can be edited on a
 * computer, kept in git, or moved to another phone. Code goes to `<name>.lua` / `<name>.js`;
 * timers and flags go to `kolahal.json` next to them.
 */
class ScriptFolder(private val context: Context) {
    private val prefs = context.getSharedPreferences("script_folder", Context.MODE_PRIVATE)
    private val scripts = ScriptRepository.get(context)

    val uri: Uri? get() = prefs.getString(KEY_URI, null)?.let(Uri::parse)

    fun setFolder(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_URI, tree.toString()).apply()
    }

    private fun folder(): DocumentFile {
        val tree = uri ?: throw IllegalStateException("no script folder — run 'script dir' first")
        val dir = DocumentFile.fromTreeUri(context, tree)
        if (dir == null || !dir.canWrite()) throw IllegalStateException("script folder is not accessible — run 'script dir'")
        return dir
    }

    /** Writes every script plus the manifest. Returns the number of scripts written. */
    fun export(): Int {
        val dir = folder()
        val all = scripts.all()
        for (s in all) {
            val fileName = "${s.name}.${s.lang.ext}"
            val file = dir.findFile(fileName) ?: dir.createFile(MIME_TEXT, fileName)
                ?: throw IllegalStateException("can't create $fileName")
            context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(s.code.toByteArray()) }
        }
        val manifest = mapOf(
            "version" to 1,
            "scripts" to all.map {
                mapOf(
                    "name" to it.name,
                    "lang" to it.lang.ext,
                    "every" to it.intervalMin,
                    "at" to it.atTimes,
                    "notify" to it.notify,
                    "enabled" to it.enabled,
                )
            },
        )
        val manifestFile = dir.findFile(MANIFEST) ?: dir.createFile("application/json", MANIFEST)
            ?: throw IllegalStateException("can't create $MANIFEST")
        context.contentResolver.openOutputStream(manifestFile.uri, "wt")!!.use {
            it.write(Json.stringify(manifest).toByteArray())
        }
        return all.size
    }

    data class ImportResult(val created: Int, val updated: Int, val skipped: List<String>)

    /** Reads `*.lua` / `*.js` from the folder, creating or updating scripts; applies the manifest. */
    fun import(): ImportResult {
        val dir = folder()
        val manifest = dir.findFile(MANIFEST)?.let { readText(it.uri) }
            ?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }
            ?.get("scripts") as? List<*>
        val settings = manifest.orEmpty().filterIsInstance<Map<*, *>>().associateBy { it["name"]?.toString() }

        var created = 0
        var updated = 0
        val skipped = ArrayList<String>()
        for (file in dir.listFiles()) {
            val fileName = file.name ?: continue
            if (!file.isFile) continue
            val lang = ScriptLang.of(fileName.substringAfterLast('.', "")) ?: continue
            val name = fileName.substringBeforeLast('.')
            if (!NAME.matches(name)) {
                skipped += fileName
                continue
            }
            val code = readText(file.uri) ?: continue
            val existing = scripts.byName(name)
            val script = if (existing == null) {
                created++
                scripts.create(name, lang, code)
            } else {
                if (existing.lang != lang) {
                    skipped += fileName
                    continue
                }
                if (existing.code != code) scripts.saveCode(existing.id, code)
                updated++
                existing
            }
            settings[name]?.let { m ->
                (m["every"] as? Number)?.let { scripts.setInterval(script.id, it.toInt()) }
                (m["at"] as? List<*>)?.let { at -> scripts.setAtTimes(script.id, at.map { it.toString() }) }
                (m["notify"] as? Boolean)?.let { scripts.setNotify(script.id, it) }
                (m["enabled"] as? Boolean)?.let { scripts.setEnabled(script.id, it) }
            }
            scripts.byId(script.id)?.let { ScriptScheduler.sync(context, it) }
        }
        return ImportResult(created, updated, skipped)
    }

    private fun readText(uri: Uri): String? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val KEY_URI = "uri"
        const val MANIFEST = "kolahal.json"
        const val MIME_TEXT = "application/octet-stream"
        val NAME = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
