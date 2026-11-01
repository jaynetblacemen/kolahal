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
     * Installs [entry] with its suggested timers, or updates it keeping its own. Remembers the installed text so a
     * later app update can tell "update available" from "you edited it".
     */
    fun install(entry: Entry): Script {
        // A command script by a built-in's name could never run by it.
        require(entry.name !in com.mtwabpin.kolahal.terminal.CommandProcessor.RESERVED_NAMES) { "'${entry.name}' is a built-in command" }
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

    /**
     * The library's copies you never edited follow the app: when a new Kolahal brings new copies
     * (or a different library), each one still as it was installed becomes the new one, timers and
     * switches kept. Edited copies are left alone ([State.MODIFIED]; Reset brings the library's).
     * Returns the names updated; once per library, so it costs nothing on a usual start.
     */
    fun updateUnedited(): List<String> {
        val fingerprint = entries.joinToString("\n") { it.file + ":" + code(it).hashCode() }.hashCode()
        if (prefs.getInt(KEY_LIBRARY, 0) == fingerprint) return emptyList()
        val installed = ScriptRepository.get(context).all()
        val results = entries.filter { state(it, installed) == State.UPDATE_AVAILABLE }.map { e -> e.name to runCatching { install(e) }.isSuccess }
        val updated = results.filter { it.second }.map { it.first }
        // One that failed (a locked database, a changed language) is tried again next start.
        if (results.all { it.second }) prefs.edit { putInt(KEY_LIBRARY, fingerprint) }
        if (updated.isNotEmpty()) {
            com.mtwabpin.kolahal.data.LogRepository.get(context).add(
                com.mtwabpin.kolahal.data.LogKind.INFO,
                "Library scripts updated with Kolahal: " + updated.joinToString(", "),
            )
        }
        return updated
    }

    private val prefs by lazy { context.getSharedPreferences("script_library", Context.MODE_PRIVATE) }

    private fun installedOriginal(name: String): String? = prefs.getString("installed:$name", null)

    /** The script named [name] is no longer the library's copy (renamed or deleted). */
    fun forget(name: String) = prefs.edit { remove("installed:$name") }

    private companion object {
        /** The library [updateUnedited] last brought copies up to (a fingerprint of its files). */
        const val KEY_LIBRARY = "library"
    }
}
