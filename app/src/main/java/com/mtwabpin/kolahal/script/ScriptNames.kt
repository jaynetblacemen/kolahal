package com.mtwabpin.kolahal.script

import android.content.Context
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.terminal.CommandCatalog

/** Names a script from a file or the shared catalog may not take. */
object ScriptNames {
    private val PUNCTUATION = Regex("[^a-z0-9]")

    /**
     * Why [name] is taken, or null: a built-in command, or an app's name. A command script runs
     * before an app with the same name would open, so typing `gpay` could run a script instead.
     */
    fun clash(context: Context, name: String): String? {
        if (name in CommandCatalog.RESERVED_NAMES) return "'$name' is a built-in command"
        val bare = name.replace(PUNCTUATION, "")
        val app = AppRepository.get(context).apps.value.firstOrNull { it.label.lowercase().replace(PUNCTUATION, "") == bare }
        return app?.let { "'$name' is the name of the app ${it.label}" }
    }

    /** What a script can be called. */
    val NAME = Regex("^[a-z0-9_-]{1,40}$")

    /** Why [name] can't name a script of yours, or null; [taken]: whether a script already has it. */
    fun invalid(name: String, taken: (String) -> Boolean): String? = when {
        !NAME.matches(name) -> "Script names: lowercase letters, digits, - and _, up to 40"
        name in CommandCatalog.RESERVED_NAMES -> "'$name' is a built-in command"
        taken(name) -> "There is already a script named $name"
        else -> null
    }
}
