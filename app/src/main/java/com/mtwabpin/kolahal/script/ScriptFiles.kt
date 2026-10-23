package com.mtwabpin.kolahal.script

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository

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
            // A new name can't be a built-in's or an app's; a script that exists is updated whatever its name.
            if (scripts.byName(name) == null) ScriptNames.clash(context, name)?.let { why ->
                skipped += "$fileName ($why)"
                continue
            }
            val code = runCatching { Files.readText(context, uri) }.getOrElse {
                skipped += "$fileName (${it.message})"
                continue
            }
            // Written for a newer Kolahal: it may call what this one doesn't have.
            if (ScriptMeta.needsNewer(code, com.mtwabpin.kolahal.BuildConfig.VERSION_NAME)) {
                skipped += "$fileName (needs Kolahal ${ScriptMeta.requires(code)})"
                continue
            }
            val existing = scripts.byName(name)
            // A script from a file arrives switched off, and so does one whose code a file replaces:
            // turning it on is the user's go-ahead for its timers, triggers and `@uses`.
            when {
                existing == null -> {
                    val s = scripts.create(name, lang, code, enabled = false)
                    scripts.applySuggested(s.id, code)
                    created += "$name.${lang.ext}"
                }
                existing.lang != lang -> skipped += "$fileName (a ${existing.lang.ext} script '$name' exists)"
                existing.code == code -> Unit
                else -> {
                    scripts.replaceCode(existing.id, code)
                    scripts.byId(existing.id)?.let { runCatching { ScriptScheduler.sync(context, it) } }
                    updated += "$name.${lang.ext}"
                }
            }
        }
        return ImportResult(created, updated, skipped)
    }

    fun export(script: Script, uri: Uri) {
        Files.writeText(context, uri, script.code)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
