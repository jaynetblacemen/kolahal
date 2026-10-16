package io.github.rscipher001.launcher.script

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.data.ScriptRepository

/** Single-file script import/export, for files picked in the system file manager. */
class ScriptFiles(private val context: Context) {
    private val scripts = ScriptRepository.get(context)

    data class ImportResult(val created: List<String>, val updated: List<String>, val skipped: List<String>)

    /** Creates or updates one script per `.lua` / `.js` file; the file name becomes the script name. */
    fun import(uris: List<Uri>): ImportResult {
        val created = ArrayList<String>()
        val updated = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (uri in uris) {
            val fileName = displayName(uri) ?: uri.lastPathSegment ?: "?"
            val lang = ScriptLang.of(fileName.substringAfterLast('.', ""))
            val name = fileName.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(40)
            if (lang == null || name.isEmpty()) {
                skipped += "$fileName (not .lua / .js)"
                continue
            }
            val code = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (code == null) {
                skipped += "$fileName (unreadable)"
                continue
            }
            val existing = scripts.byName(name)
            when {
                existing == null -> { scripts.create(name, lang, code); created += "$name.${lang.ext}" }
                existing.lang != lang -> skipped += "$fileName (a ${existing.lang.ext} script '$name' exists)"
                else -> { scripts.saveCode(existing.id, code); updated += "$name.${lang.ext}" }
            }
        }
        return ImportResult(created, updated, skipped)
    }

    fun export(script: Script, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(script.code.toByteArray()) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
