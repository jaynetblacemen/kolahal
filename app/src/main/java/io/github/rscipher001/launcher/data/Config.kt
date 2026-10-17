package io.github.rscipher001.launcher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A user-tunable setting, edited with `config set <key> <value>`. */
sealed class ConfigKey<T>(val key: String, val default: T, val help: String) {
    abstract fun parse(raw: String): T?
    open fun format(value: T): String = value.toString()

    class Bool(key: String, default: Boolean, help: String) : ConfigKey<Boolean>(key, default, help) {
        override fun parse(raw: String) = when (raw.lowercase()) {
            "on", "true", "yes", "1" -> true
            "off", "false", "no", "0" -> false
            else -> null
        }
        override fun format(value: Boolean) = if (value) "on" else "off"
    }

    class Num(key: String, default: Int, val range: IntRange, help: String) : ConfigKey<Int>(key, default, help) {
        override fun parse(raw: String) = raw.toIntOrNull()?.takeIf { it in range }
    }

    class Choice(key: String, default: String, val choices: List<String>, help: String) :
        ConfigKey<String>(key, default, help) {
        override fun parse(raw: String) = raw.lowercase().takeIf { it in choices }
    }

    class Text(key: String, default: String, help: String) : ConfigKey<String>(key, default, help) {
        override fun parse(raw: String) = raw
    }
}

object Keys {
    val HEADER_DEVICE = ConfigKey.Bool("header.device", false, "show kolahal@device in the header")
    val HEADER_DATE = ConfigKey.Bool("header.date", true, "show the date next to the clock")
    val HEADER_UNLOCKS = ConfigKey.Bool("header.unlocks", true, "show today's unlock count")
    val HEADER_BATTERY = ConfigKey.Bool("header.battery", true, "show battery level")
    val HEADER_NETWORK = ConfigKey.Bool("header.network", true, "show network type")
    val HEADER_ALARM = ConfigKey.Bool("header.alarm", true, "show the next alarm")
    val HEADER_PROFILES = ConfigKey.Bool("header.profiles", true, "show work/private profile state")
    val HEADER_STATUS = ConfigKey.Bool("header.status", true, "show status lines pinned by scripts")
    val HEADER_MEDIA = ConfigKey.Bool("header.media", true, "show what's playing under the clock (tap: play/pause, long-press: next; needs notification access)")
    val HEADER_TODO = ConfigKey.Bool("header.todo", true, "show the open todo count (tap runs todo)")
    val HEADER_AUTOHIDE = ConfigKey.Bool("header.autohide", true, "shrink the header to one line while scrolling or after a command; Home or a tap brings it back")
    val CLOCK_24H = ConfigKey.Bool("clock.24h", true, "use 24-hour time in the header and log")
    val KEYBAR = ConfigKey.Text(
        "keybar", DEFAULT_KEYBAR,
        "touch keys, comma separated: ESC TAB UP DOWN LEFT RIGHT HOME END BKSP ENTER SPACE KBD or literal text",
    )
    val KEYBAR2 = ConfigKey.Text("keybar2", DEFAULT_KEYBAR2, "second key bar row (same syntax; empty for one row)")
    val KEYBAR_SHOW = ConfigKey.Bool("keybar.show", true, "show the touch key bar")
    val KEYBAR_AUTO = ConfigKey.Bool("keybar.auto", true, "show the key bar only while the on-screen keyboard is up for the prompt (off: always)")
    val KEYBAR_HEIGHT = ConfigKey.Num("keybar.height", 48, 28..64, "key bar row height (dp); smaller leaves more room for the log")
    val PROMPT_HEIGHT_TYPING = ConfigKey.Num("prompt.height", 52, 32..72, "prompt line height (dp) while the keyboard is up")
    val THEME = ConfigKey.Choice(
        "theme", "green",
        listOf("green", "amber", "mono", "dracula", "solarized", "gruvbox", "gruvbox-material"),
        "colour theme",
    )
    val GUI_COLORS = ConfigKey.Choice("gui.colors", "theme", listOf("theme", "system"), "settings screens follow the terminal theme or the system (Material You) colours")
    val FONT_SIZE = ConfigKey.Num("font.size", 14, 10..24, "terminal font size (sp)")
    val SUGGESTIONS = ConfigKey.Num("suggestions", 12, 0..30, "max suggestions shown while typing")
    val AUTO_LAUNCH = ConfigKey.Bool("launch.single", true, "launch automatically when only one app matches")
    val LAUNCH_INSTANT = ConfigKey.Choice(
        "launch.instant", "apps", listOf("off", "apps", "all"),
        "run without Enter once the input can only mean one thing: apps, or apps and commands (calls, uninstall and other sensitive commands always need Enter)",
    )
    val LAUNCH_INSTANT_MIN = ConfigKey.Num("launch.instant.min", 2, 1..6, "letters to type before an instant launch")
    val LAUNCH_TOP = ConfigKey.Bool("launch.top", true, "Enter launches the best match when it starts with what you typed")
    val SUGGEST_IDLE = ConfigKey.Bool("suggestions.idle", true, "show most-used apps when the prompt is empty")
    val SHOW_TIMESTAMPS = ConfigKey.Choice("log.time", "commands", listOf("off", "commands", "all"), "right-aligned times on log lines")
    val LOG_DIM = ConfigKey.Bool("log.dim", true, "dim output older than the latest command")
    val LOG_SPACING = ConfigKey.Num("log.spacing", 12, 0..40, "gap (dp) above each command and its output")
    val LOG_KEEP_DAYS = ConfigKey.Num("log.keepdays", 60, 1..365, "delete log lines older than this many days")
    val SEARCH_ENGINE = ConfigKey.Choice(
        "search.engine", "duckduckgo",
        listOf("duckduckgo", "google", "bing", "brave", "startpage", "kagi", "custom"),
        "web search engine for search / g",
    )
    val SEARCH_URL = ConfigKey.Text("search.url", "https://duckduckgo.com/?q=%s", "custom search URL (%s = query), used when search.engine = custom")
    val FOCUS_ON_HOME = ConfigKey.Bool("keyboard.onhome", false, "open the soft keyboard whenever home is shown")
    val IME_SUGGESTIONS = ConfigKey.Bool(
        "keyboard.suggestions", false,
        "prompt keyboard suggestions (off uses a password-type field: no autocorrect, but Latin-only on some keyboards)",
    )

    val WIDGETS_HOME = ConfigKey.Bool("widgets.home", true, "show widgets placed on home under the header (they hide with it while the log is in use)")
    val WIDGETS_HEIGHT = ConfigKey.Num("widgets.height", 180, 60..480, "max height (dp) of the home widget area; it scrolls when widgets need more")

    val GESTURE_DOUBLE_TAP = ConfigKey.Text("gesture.doubletap", "lock", "double-tap empty log space: a command, lock, shade or none")
    val GESTURE_SWIPE_DOWN = ConfigKey.Text("gesture.swipedown", "shade", "swipe down on the header: a command, lock, shade or none")
    val GESTURE_SWIPE_UP = ConfigKey.Text("gesture.swipeup", "apps", "swipe up on the prompt: a command, lock, shade or none")

    val ALL: List<ConfigKey<*>> = listOf(
        HEADER_DEVICE, HEADER_DATE, HEADER_UNLOCKS, HEADER_BATTERY, HEADER_NETWORK, HEADER_ALARM,
        HEADER_PROFILES, HEADER_STATUS, HEADER_MEDIA, HEADER_AUTOHIDE, CLOCK_24H, KEYBAR_SHOW, KEYBAR_AUTO, KEYBAR_HEIGHT, PROMPT_HEIGHT_TYPING, KEYBAR, KEYBAR2, THEME, GUI_COLORS, FONT_SIZE, SUGGESTIONS, SUGGEST_IDLE, AUTO_LAUNCH, LAUNCH_TOP, LAUNCH_INSTANT, LAUNCH_INSTANT_MIN,
        SHOW_TIMESTAMPS, LOG_DIM, LOG_SPACING, LOG_KEEP_DAYS, SEARCH_ENGINE, SEARCH_URL, FOCUS_ON_HOME, IME_SUGGESTIONS,
        GESTURE_DOUBLE_TAP, GESTURE_SWIPE_DOWN, GESTURE_SWIPE_UP,
        HEADER_TODO, WIDGETS_HOME, WIDGETS_HEIGHT,
    )

    fun byName(name: String) = ALL.firstOrNull { it.key == name.lowercase() }
}

private const val DEFAULT_KEYBAR = "ESC,/,-,HOME,UP,END,BKSP"
private const val DEFAULT_KEYBAR2 = "TAB,|,.,LEFT,DOWN,RIGHT,ENTER"

/** Snapshot of all settings; recomposes the UI when anything changes. */
class Config private constructor(private val prefs: SharedPreferences) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    // Every change goes through set()/reset(), which bump the version directly. (A SharedPreferences
    // change listener is only weakly referenced by Android; R8 dropped our strong reference in release
    // builds, so the listener was garbage-collected and settings changes stopped applying.)

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: ConfigKey<T>): T {
        val raw = prefs.getString(key.key, null) ?: return key.default
        return key.parse(raw) ?: key.default
    }

    /** Returns false if [raw] is not a valid value for [key]. */
    fun <T> set(key: ConfigKey<T>, raw: String): Boolean {
        val value = key.parse(raw) ?: return false
        prefs.edit().putString(key.key, key.format(value)).apply()
        _version.update { it + 1 }
        return true
    }

    fun reset(key: ConfigKey<*>) {
        prefs.edit().remove(key.key).apply()
        _version.update { it + 1 }
    }

    fun isDefault(key: ConfigKey<*>) = !prefs.contains(key.key)

    fun <T> display(key: ConfigKey<T>): String = key.format(get(key))

    companion object {
        @Volatile
        private var instance: Config? = null

        fun get(context: Context): Config = instance ?: synchronized(this) {
            instance ?: Config(
                context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}

/** "22:40" or "10:40 PM" per [Keys.CLOCK_24H], for log lines written outside the UI (workers). */
fun Config.shortTime(millis: Long = System.currentTimeMillis()): String =
    java.text.SimpleDateFormat(if (this[Keys.CLOCK_24H]) "HH:mm" else "h:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))
