package com.mtwabpin.kolahal.script

import android.content.Context
import androidx.core.content.edit
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository

/** Ready-made scripts bundled in assets/library; each describes itself with its directives ([ScriptMeta]). */
class ScriptLibrary(private val context: Context) {
    data class Entry(
        val file: String,
        val name: String,
        val lang: ScriptLang,
        val title: String,
        val description: String,
        val command: Boolean,
        val suggestedEvery: Int,
        val suggestedAt: List<String>,
        val notify: Boolean,
    )

    enum class State { NOT_INSTALLED, INSTALLED, MODIFIED, UPDATE_AVAILABLE }

    /** Every `.lua` and `.js` file in assets/library, by name. */
    val entries: List<Entry> by lazy {
        context.assets.list("library").orEmpty().sorted().mapNotNull { file ->
            val lang = ScriptLang.of(file.substringAfterLast('.', "")) ?: return@mapNotNull null
            val code = read(file)
            val name = file.substringBeforeLast('.')
            Entry(
                file = file,
                name = name,
                lang = lang,
                title = ScriptMeta.title(code) ?: name,
                description = ScriptMeta.description(code).orEmpty(),
                command = ScriptMeta.isCommand(code),
                suggestedEvery = ScriptMeta.every(code) ?: 0,
                suggestedAt = ScriptMeta.at(code),
                notify = ScriptMeta.notifies(code),
            )
        }
    }

    private val codeCache = HashMap<String, String>()

    fun code(entry: Entry): String = read(entry.file)

    private fun read(file: String): String = synchronized(codeCache) {
        codeCache.getOrPut(file) {
            context.assets.open("library/$file").use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    /** Library state of [entry] relative to the user's scripts. */
    fun state(entry: Entry, installed: List<Script>): State {
        val script = installed.firstOrNull { it.name == entry.name } ?: return State.NOT_INSTALLED
        val original = installedOriginal(entry.name)
        val bundled = code(entry)
        return when {
            script.code == bundled -> State.INSTALLED
            original != null && script.code == original -> State.UPDATE_AVAILABLE
            else -> State.MODIFIED
        }
    }

    /**
     * Installs (or updates) [entry] with its suggested timers. Remembers the installed text so a
     * later app update can tell "update available" from "you edited it".
     */
    fun install(entry: Entry): Script {
        val repo = ScriptRepository.get(context)
        val code = code(entry)
        val existing = repo.byName(entry.name)
        val script = if (existing == null) {
            repo.create(entry.name, entry.lang, code).also { s -> repo.applySuggested(s.id, code) }
        } else {
            if (existing.lang != entry.lang) throw IllegalStateException("a ${existing.lang.ext} script '${entry.name}' already exists")
            repo.saveCode(existing.id, code)
            existing
        }
        prefs.edit { putString("installed:${entry.name}", code) }
        return repo.byId(script.id)!!.also { ScriptScheduler.sync(context, it) }
    }

    private val prefs by lazy { context.getSharedPreferences("script_library", Context.MODE_PRIVATE) }

    private fun installedOriginal(name: String): String? = prefs.getString("installed:$name", null)

    /** The script named [name] is no longer the library's copy (renamed or deleted). */
    fun forget(name: String) = prefs.edit { remove("installed:$name") }
}
