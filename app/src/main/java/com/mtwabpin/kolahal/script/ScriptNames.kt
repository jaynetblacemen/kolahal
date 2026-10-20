package com.mtwabpin.kolahal.script

import android.content.Context
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.terminal.CommandProcessor

/** Names a script from outside (a file, a folder, a backup) may not take. */
object ScriptNames {
    private val PUNCTUATION = Regex("[^a-z0-9]")

    /**
     * Why [name] is taken, or null: a built-in command, or an app's name. A command script runs
     * before an app with the same name would open, so typing `gpay` could run a script instead.
     */
    fun clash(context: Context, name: String): String? {
        if (name in CommandProcessor.COMMANDS.map { it.name }) return "'$name' is a built-in command"
        val bare = name.replace(PUNCTUATION, "")
        val app = AppRepository.get(context).apps.value.firstOrNull { it.label.lowercase().replace(PUNCTUATION, "") == bare }
        return app?.let { "'$name' is the name of the app ${it.label}" }
    }
}
