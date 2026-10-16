package io.github.rscipher001.launcher.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification feed settings. Every app is logged to the terminal unless muted;
 * [logToTerminal] turns logging off entirely (the in-memory feed keeps working).
 */
class NotifPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE)

    private val _muted = MutableStateFlow(prefs.getStringSet(KEY_MUTED, emptySet())!!.toSet())
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    var logToTerminal: Boolean
        get() = prefs.getBoolean(KEY_LOG, true)
        set(value) = prefs.edit().putBoolean(KEY_LOG, value).apply()

    fun isMuted(pkg: String): Boolean = pkg in _muted.value

    fun mute(pkg: String) = setMuted(_muted.value + pkg)

    fun unmute(pkg: String) = setMuted(_muted.value - pkg)

    /** Whether a notification from [pkg] should become a terminal line. */
    fun shouldLog(pkg: String): Boolean = logToTerminal && !isMuted(pkg)

    private fun setMuted(set: Set<String>) {
        _muted.value = set
        prefs.edit().putStringSet(KEY_MUTED, set).apply()
    }

    companion object {
        private const val KEY_LOG = "log_to_terminal"
        private const val KEY_MUTED = "muted"

        @Volatile
        private var instance: NotifPrefs? = null

        fun get(context: Context): NotifPrefs = instance ?: synchronized(this) {
            instance ?: NotifPrefs(context.applicationContext).also { instance = it }
        }
    }
}
