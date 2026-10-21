package com.mtwabpin.kolahal.script

import android.content.Context
import androidx.core.content.edit
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository

/** Ready-made scripts bundled in assets/library (index.json describes them). */
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

    val entries: List<Entry> by lazy {
        val raw = context.assets.open("library/index.json").use { it.readBytes().toString(Charsets.UTF_8) }
        (Json.parse(raw) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { m ->
            val lang = ScriptLang.of(m["lang"]?.toString().orEmpty()) ?: return@mapNotNull null
            Entry(
                file = m["file"]?.toString() ?: return@mapNotNull null,
                name = m["name"]?.toString() ?: return@mapNotNull null,
                lang = lang,
                title = m["title"]?.toString().orEmpty(),
                description = m["description"]?.toString().orEmpty(),
                command = m["command"] == true,
                suggestedEvery = (m["suggestedEvery"] as? Number)?.toInt() ?: 0,
                suggestedAt = (m["suggestedAt"] as? List<*>)?.map { it.toString() }.orEmpty(),
                notify = m["notify"] == true,
            )
        }
    }

    private val codeCache = HashMap<String, String>()

    fun code(entry: Entry): String = synchronized(codeCache) {
        codeCache.getOrPut(entry.file) {
            context.assets.open("library/${entry.file}").use { it.readBytes().toString(Charsets.UTF_8) }
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
            repo.create(entry.name, entry.lang, code).also { s ->
                if (entry.suggestedEvery > 0) repo.setInterval(s.id, entry.suggestedEvery.coerceAtLeast(ScriptScheduler.MIN_INTERVAL_MIN))
                if (entry.suggestedAt.isNotEmpty()) repo.setAtTimes(s.id, entry.suggestedAt.mapNotNull(ScriptScheduler::normalizeTime))
                repo.setNotify(s.id, entry.notify)
            }
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
}
