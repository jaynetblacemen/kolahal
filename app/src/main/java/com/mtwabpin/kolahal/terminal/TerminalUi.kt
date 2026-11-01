package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.data.Script

/** Full-screen GUI pages besides the terminal (the script editor is separate). */
enum class Screen { TERMINAL, SETTINGS, SCRIPTS, SCRIPT_LIBRARY, GET_SCRIPTS, SETUP, ONBOARDING, FEEDS, WIDGETS, WIDGET_PICKER, THEME_BUILDER }

/** UI-only operations the processor cannot do from an application context. */
interface TerminalUi {
    fun requestContactsPermission()
    /** Android's contact picker (the Play build, without READ_CONTACTS); [then] gets the contact picked, if one is. */
    fun pickContact(then: (com.mtwabpin.kolahal.contacts.Contact) -> Unit) {}
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
    /**
     * `notifs enable` with access off: what notification access reads and what for, with a choice,
     * before Android's Notification access screen opens.
     */
    fun askNotificationAccess() {}
    /** `log only …` ([filter]) or `log all` (null): the lines the log shows. */
    fun filterLog(filter: LogFilter?) {}
    /** The log's width on screen (px), for output laid out to fit it; null before home has shown. */
    fun logWidthPx(): Int? = null
    /** `backup dir`: a folder for backups; [thenBackUp] writes one once it is picked (`backup` with none yet). */
    fun askBackupFolder(thenBackUp: Boolean) {}
}

data class CommandSpec(val name: String, val usage: String, val help: String)
data class UpdateProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = (downloadedBytes.toFloat() / totalBytes.coerceAtLeast(1))
            .coerceIn(0f, 1f)
}
