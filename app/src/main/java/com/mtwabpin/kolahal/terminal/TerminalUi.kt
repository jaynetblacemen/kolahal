package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.data.Script

/** Full-screen GUI pages besides the terminal (the script editor is separate). */
enum class Screen { TERMINAL, SETTINGS, SCRIPTS, SETUP, FEEDS, WIDGETS, WIDGET_PICKER }

/** UI-only operations the processor cannot do from an application context. */
interface TerminalUi {
    fun requestContactsPermission()
    fun openScreen(screen: Screen)
    fun pickScriptFolder()
    /** File picker for `font import` (a .ttf/.otf). */
    fun requestFontImport()
    fun openEditor(script: Script)
    fun requestNotificationPermission()
    fun clearScreen()
    /** `fresh`: hide what is on screen now, as `log.fresh` does after a while away. */
    fun freshScreen() {}
    /** READ_CALENDAR for `agenda`. */
    fun requestCalendarPermission() {}
    /** READ_CALL_LOG for calls in the log and `calls`. */
    fun requestCallLogPermission() {}
}

data class CommandSpec(val name: String, val usage: String, val help: String)
