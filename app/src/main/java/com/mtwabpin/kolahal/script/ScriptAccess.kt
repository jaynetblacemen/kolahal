package com.mtwabpin.kolahal.script

import android.content.Context
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script

/**
 * What a script may do, as the user set it: the script's Access switches in the Scripts screen, or
 * `script allow|deny <name> <what>`. Three kinds of items:
 * - [ScriptMeta.USES], given by declaring them with `@uses`: off until allowed. Switching a script on
 *   allows the ones not decided yet (the Scripts screen asks first, with everything on); code from
 *   outside that declares more leaves the new ones off.
 * - [INTERNET] and [HEADER]: every script has them unless they are switched off.
 * - Each `@on` event: on unless switched off.
 * Switched off, a script gets an empty answer where that is honest (no apps, no notifications, no
 * clipboard, its header line ignored) and stops where it isn't (internet, the local network).
 * Posting notifications keeps its own switch ([com.mtwabpin.kolahal.data.Script.notify]).
 */
object ScriptAccess {
    const val INTERNET = "internet"
    const val HEADER = "header"

    /** Everything a script with [code] can be switched for, in display order. */
    fun items(code: String): List<String> = listOf(INTERNET, HEADER) + ScriptMeta.uses(code) + ScriptMeta.events(code)

    fun label(item: String): String = when (item) {
        INTERNET -> "Internet"
        HEADER -> "Header line"
        "notifications" -> "Your notifications"
        "apps" -> "Your app list"
        "clipboard" -> "Clipboard"
        "lan" -> "Local network"
        "commands" -> "Run commands"
        else -> "Runs on $item"
    }

    fun help(item: String): String = when (item) {
        INTERNET -> "Web requests and feeds, and the links and pictures its lines carry; switched off, a run that needs a request stops"
        HEADER -> "Its line at the top of home; switched off, the script still runs"
        "notifications" -> "Reads your recent notifications; switched off, it sees none"
        "apps" -> "Lists your installed apps; switched off, it sees none"
        "clipboard" -> "Reads and sets the clipboard; switched off, it reads and sets nothing"
        "lan" -> "Reaches this phone and your local network; switched off, those requests stop the run"
        "commands" -> "Runs terminal commands and other scripts when you start it from the prompt; switched off, it can't"
        else -> "Runs when this happens (@on $item)"
    }

    /** A script's access: what its code declares with `@uses`, and what the user decided. */
    class Grants(val declared: Set<String>, private val values: Map<String, Boolean>) {
        fun allows(item: String): Boolean =
            if (item in ScriptMeta.USES) item in declared && values[item] == true else values[item] != false

        /** Declared, but not allowed or switched off yet: what switching the script on asks for. */
        val undecided: List<String> get() = declared.filter { it !in values }
    }

    fun grants(code: String, values: Map<String, Boolean>) = Grants(ScriptMeta.uses(code), values)

    /** [values] with everything [code] declares and nobody decided yet allowed. */
    fun allowUndecided(code: String, values: Map<String, Boolean>): Map<String, Boolean> =
        values + ScriptMeta.uses(code).filter { it !in values }.associateWith { true }

    fun parse(raw: String?): Map<String, Boolean> =
        (raw?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }).orEmpty()
            .mapNotNull { (k, v) -> (v as? Boolean)?.let { k.toString() to it } }.toMap()

    fun encode(values: Map<String, Boolean>): String? = values.takeIf { it.isNotEmpty() }?.let(Json::stringify)

    /**
     * A switched-on script with an `@secret` that has no value it may use: a line to set it, one per
     * name. A value set for other sites than its line names now waits to be set again.
     */
    fun askForSecrets(context: Context, s: Script) {
        if (!s.enabled) return
        val store = SecretStore(context)
        for (d in ScriptMeta.secrets(s.code)) {
            if (store.isSet(s.name, d)) continue
            val text = if (store.hostsChanged(s.name, d)) "${s.name} now sends ${d.name} to ${d.hosts.joinToString()} — tap to set it again"
            else "${s.name} needs ${d.name}" + (if (d.desc.isBlank()) "" else " (${d.desc})") + " — tap to set it"
            LogRepository.get(context).add(LogKind.INFO, text, s.name, LogContext.of(LogContext.RUN to "script secret ${s.name} ${d.name}"))
        }
    }

    /**
     * A script switched on without the user's go-ahead for what it declares (a timer set in its sheet,
     * an import): what nobody decided yet stays off, with a line to allow it.
     */
    fun askForNew(context: Context, s: Script) {
        askForSecrets(context, s)
        if (s.enabled) askToAllow(context, s.name, s.grants.undecided)
    }

    /** "[name] asks for [items] — tap to allow": one tap allows them all ([what] words the line). */
    fun askToAllow(context: Context, name: String, items: List<String>, what: String = "asks for") {
        if (items.isEmpty()) return
        LogRepository.get(context).add(
            LogKind.INFO, "$name $what ${items.joinToString(", ")} — tap to allow", name,
            LogContext.of(LogContext.RUN to "script allow $name ${items.joinToString(" ")}"),
        )
    }
}
