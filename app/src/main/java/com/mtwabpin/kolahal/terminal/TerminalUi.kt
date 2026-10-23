package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.data.Script

/** Full-screen GUI pages besides the terminal (the script editor is separate). */
enum class Screen { TERMINAL, SETTINGS, SCRIPTS, SCRIPT_LIBRARY, GET_SCRIPTS, SETUP, FEEDS, WIDGETS, WIDGET_PICKER, THEME_BUILDER }

/** UI-only operations the processor cannot do from an application context. */
interface TerminalUi {
    fun requestContactsPermission()
    fun openScreen(screen: Screen)
    fun pickScriptFolder()
    /** File picker for `font import` (a .ttf/.otf). */
    fun requestFontImport()
    fun openEditor(script: Script)
    /** `script secret <name> <NAME>`: a masked box for that secret's value. */
    fun askSecret(script: Script, name: String)
    fun requestNotificationPermission()
    fun clearScreen()
    /** `fresh`: hide what is on screen now, as `log.fresh` does after a while away. */
    fun freshScreen() {}
    /** READ_CALENDAR for `agenda`. */
    fun requestCalendarPermission() {}
    /** READ_CALL_LOG for calls in the log and `calls`. */
    fun requestCallLogPermission() {}
    /** ACCESS_LOCAL_NETWORK (Android 17+) for `local-network`. */
    fun requestLocalNetworkPermission() {}
    /** `log only …` ([filter]) or `log all` (null): the lines the log shows. */
    fun filterLog(filter: LogFilter?) {}
}

data class CommandSpec(val name: String, val usage: String, val help: String)
