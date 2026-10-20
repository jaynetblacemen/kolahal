package com.mtwabpin.kolahal.data

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
    val HEADER_DEVICE = ConfigKey.Bool("header.device", false, "Show kolahal@device in the header")
    val HEADER_DATE = ConfigKey.Bool("header.date", true, "Show the date next to the clock")
    val HEADER_UNLOCKS = ConfigKey.Bool("header.unlocks", true, "Show today's unlock count")
    val HEADER_BATTERY = ConfigKey.Bool("header.battery", true, "Show battery level")
    val HEADER_NETWORK = ConfigKey.Bool("header.network", false, "Show network type")
    val HEADER_ALARM = ConfigKey.Bool("header.alarm", true, "Show the next alarm")
    val HEADER_PROFILES = ConfigKey.Bool("header.profiles", true, "Show work/private profile state")
    val HEADER_STATUS = ConfigKey.Bool("header.status", true, "Show status lines pinned by scripts")
    val HEADER_MEDIA = ConfigKey.Bool("header.media", true, "Show what's playing under the clock (tap: play/pause, long-press: next; needs notification access)")
    val HEADER_TODO = ConfigKey.Bool("header.todo", true, "Show the open todo count (tap runs todo)")
    val HEADER_AUTOHIDE = ConfigKey.Bool("header.autohide", true, "Scrolling back hides the header, scrolling forward or a command shows it as one line; Home or a tap restores it")
    val HEADER_ICONS = ConfigKey.Bool("header.icons", true, "Icons for battery, network, unlocks, alarm and profiles in the header (off: text labels)")
    val CLOCK_24H = ConfigKey.Bool("clock.24h", false, "Use 24-hour time in the header and log")
    val KEYBAR = ConfigKey.Text(
        "keybar", DEFAULT_KEYBAR,
        "Touch keys, comma separated: ESC TAB UP DOWN LEFT RIGHT HOME END BKSP ENTER SPACE KBD or literal text",
    )
    val KEYBAR2 = ConfigKey.Text("keybar2", DEFAULT_KEYBAR2, "Second key bar row (same syntax; empty for one row)")
    val KEYBAR_SHOW = ConfigKey.Bool("keybar.show", false, "Show the touch key bar")
    val KEYBAR_AUTO = ConfigKey.Bool("keybar.auto", true, "Show the key bar only while the on-screen keyboard is up for the prompt (off: always)")
    val KEYBAR_HEIGHT = ConfigKey.Num("keybar.height", 40, 20..64, "Key bar row height (dp); smaller leaves more room for the log")
    val PROMPT_HEIGHT_TYPING = ConfigKey.Num("prompt.height", 40, 20..72, "Prompt line height (dp); it never gets shorter than its text")
    val THEME = ConfigKey.Choice(
        "theme", "green",
        listOf("green", "amber", "mono", "dracula", "solarized", "gruvbox", "gruvbox-material"),
        "Colour theme",
    )
    val GUI_COLORS = ConfigKey.Choice("gui.colors", "theme", listOf("theme", "system"), "Settings screens follow the terminal theme or the system (Material You) colours")
    val STATUS_BAR = ConfigKey.Choice("statusbar", "immersive", listOf("show", "hide", "immersive"), "Full screen: hide the status bar, or both system bars (immersive); swipe from the edge to peek")
    val FONT = ConfigKey.Choice(
        "font", "jetbrains-mono",
        listOf("system", "jetbrains-mono", "fira-code", "ibm-plex-mono", "source-code-pro", "custom"),
        "Terminal font (custom: a .ttf/.otf imported with 'font import')",
    )
    val FONT_SIZE = ConfigKey.Num("font.size", 14, 10..24, "Terminal font size (sp)")
    val SUGGESTIONS = ConfigKey.Num("suggestions", 12, 0..30, "Max suggestions shown while typing")
    val AUTO_LAUNCH = ConfigKey.Bool("launch.single", true, "Launch automatically when only one app matches")
    val LAUNCH_INSTANT = ConfigKey.Choice(
        "launch.instant", "all", listOf("off", "apps", "all"),
        "Run without Enter once the input can only mean one thing: apps, or apps and commands (calls, uninstall and other sensitive commands always need Enter)",
    )
    val LAUNCH_INSTANT_MIN = ConfigKey.Num("launch.instant.min", 2, 1..6, "Letters to type before an instant launch")
    val LAUNCH_TOP = ConfigKey.Bool("launch.top", true, "Enter launches the best match when it starts with what you typed")
    val SUGGEST_ICONS = ConfigKey.Bool("suggestions.icons", false, "Show app icons next to app suggestions, in the list and on chips")
    val SUGGEST_IDLE = ConfigKey.Bool("suggestions.idle", false, "Show most-used apps when the prompt is empty")
    val SUGGESTIONS_AUTO = ConfigKey.Bool("suggestions.auto", true, "Show suggestions only while typing (keyboard up or text in the prompt)")
    val SUGGESTION_ROWS = ConfigKey.Num("suggestions.rows", 2, 1..2, "Rows of suggestion chips while typing (suggestions.style chips)")
    val SUGGESTIONS_STYLE = ConfigKey.Choice("suggestions.style", "list", listOf("list", "chips"), "While typing: a list above the prompt (best match at the bottom, nearest the thumb) or a strip of chips")
    val SELECTOR_RAIL = ConfigKey.Bool("selector.rail", true, "Slide along the right edge to glide through all your apps; let go on one to launch")
    val SELECTOR_HANDLE = ConfigKey.Bool("selector.handle", true, "Show the slim A–Z handle on the right edge; off hides it while the selector keeps working")
    val SELECTOR_HEIGHT = ConfigKey.Num("selector.height", 350, 200..900, "Height (dp) of the app selector band, low on the right edge: shorter means less thumb travel")
    val SHOW_TIMESTAMPS = ConfigKey.Choice("log.time", "all", listOf("off", "commands", "all"), "Right-aligned times on log lines")
    val LOG_DIM = ConfigKey.Bool("log.dim", true, "Dim output older than the latest command")
    val LOG_SPACING = ConfigKey.Num("log.spacing", 10, 0..40, "Gap (dp) above each output group: a command and its output, or a background batch (feeds, scripts)")
    val LOG_GUTTER = ConfigKey.Bool("log.gutter", true, "Thin colour bar at the left of each log line: green for commands, script colour for background output, red for errors")
    val LOG_LINE_HEIGHT = ConfigKey.Num("log.lineheight", 25, 10..56, "Minimum height (dp) of a log line and of script status lines in the header; tappable log lines get a little more")
    val CHIP_HEIGHT = ConfigKey.Num("chips.height", 48, 20..64, "Height (dp) of suggestion chips and app-grid chips; list-style suggestion rows shrink with it below 44")
    val LOG_KEEP_DAYS = ConfigKey.Num("log.keepdays", 60, 1..365, "Delete log lines older than this many days")
    val LOG_FRESH = ConfigKey.Num("log.fresh", 10, 0..240, "Start home with a clean screen after this many minutes away (0: never); earlier output is one tap away")
    val FEEDS_LOG = ConfigKey.Bool("feeds.log", true, "Post new feed items in the log")
    val FEEDS_LOG_LINES = ConfigKey.Num("feeds.log.lines", 10, 1..50, "New items per feed and refresh shown in the log; the rest is one '+N more' line")
    val FEEDS_READ_SHOWN = ConfigKey.Bool("feeds.read.shown", false, "Items posted in the log count as read (otherwise only opened or listed ones do)")
    val FEEDS_BACKLOG_READ = ConfigKey.Bool("feeds.backlog.read", false, "When you subscribe, the posts already in the feed count as read; only newer ones are unread")
    val FEEDS_INTERVAL = ConfigKey.Num("feeds.interval", 60, 15..1440, "Refresh interval for newly added feeds (minutes)")
    val FEEDS_NOTIFY = ConfigKey.Bool("feeds.notify", false, "Newly added feeds notify about new items")
    val FEEDS_WIFI = ConfigKey.Bool("feeds.wifi", false, "Scheduled refreshes only on Wi-Fi (unmetered networks); 'feed fetch' always runs")
    val SEARCH_ENGINE = ConfigKey.Choice(
        "search.engine", "duckduckgo",
        listOf("duckduckgo", "google", "bing", "brave", "startpage", "kagi", "custom"),
        "Web search engine for search / g",
    )
    val SEARCH_URL = ConfigKey.Text("search.url", "https://duckduckgo.com/?q=%s", "Custom search URL (%s = query), used when search.engine = custom")
    val FOCUS_ON_HOME = ConfigKey.Bool("keyboard.onhome", false, "Open the soft keyboard whenever home is shown")
    val IME_SUGGESTIONS = ConfigKey.Bool(
        "keyboard.suggestions", false,
        "Prompt keyboard suggestions (off uses a password-type field: no autocorrect, but Latin-only on some keyboards)",
    )

    val WIDGETS_HOME = ConfigKey.Bool("widgets.home", false, "Show widgets placed on home under the header (they hide with it while the log is in use)")
    val WIDGETS_PAGE = ConfigKey.Choice("widgets.page", "left", listOf("left", "right", "off"), "A widgets page beside the terminal: swipe sideways on home to reach it")
    val WIDGETS_HEIGHT = ConfigKey.Num("widgets.height", 180, 60..480, "Max height (dp) of the home widget area; it scrolls when widgets need more")

    val GESTURE_DOUBLE_TAP = ConfigKey.Text("gesture.doubletap", if (com.mtwabpin.kolahal.BuildConfig.LOCK_SERVICE) "lock" else "none", "Double-tap empty log space: a command, lock, shade, quick (the quick sheet) or none")
    val GESTURE_SWIPE_DOWN = ConfigKey.Text("gesture.swipedown", "shade", "Swipe down on the header: a command, lock, shade, quick or none")
    val GESTURE_SWIPE_UP = ConfigKey.Text("gesture.swipeup", "apps", "Swipe up on the prompt: a command, lock, shade, quick or none")
    val GESTURE_SWIPE_UP_ON = ConfigKey.Bool("gesture.swipeup.on", false, "Turn the swipe-up-on-prompt gesture on (off by default: it fires by accident easily)")

    val ALL: List<ConfigKey<*>> = listOf(
        HEADER_DEVICE, HEADER_DATE, HEADER_UNLOCKS, HEADER_BATTERY, HEADER_NETWORK, HEADER_ALARM,
        HEADER_PROFILES, HEADER_STATUS, HEADER_MEDIA, HEADER_AUTOHIDE, HEADER_ICONS, CLOCK_24H, KEYBAR_SHOW, KEYBAR_AUTO, KEYBAR_HEIGHT, PROMPT_HEIGHT_TYPING, KEYBAR, KEYBAR2, THEME, GUI_COLORS, STATUS_BAR, FONT, FONT_SIZE, SUGGESTIONS, SUGGEST_IDLE, SUGGEST_ICONS, SUGGESTIONS_AUTO, SUGGESTION_ROWS, SUGGESTIONS_STYLE, SELECTOR_RAIL, SELECTOR_HANDLE, SELECTOR_HEIGHT, AUTO_LAUNCH, LAUNCH_TOP, LAUNCH_INSTANT, LAUNCH_INSTANT_MIN,
        SHOW_TIMESTAMPS, LOG_DIM, LOG_SPACING, LOG_GUTTER, LOG_LINE_HEIGHT, CHIP_HEIGHT, LOG_KEEP_DAYS, LOG_FRESH, SEARCH_ENGINE, SEARCH_URL, FOCUS_ON_HOME, IME_SUGGESTIONS,
        GESTURE_DOUBLE_TAP, GESTURE_SWIPE_DOWN, GESTURE_SWIPE_UP_ON, GESTURE_SWIPE_UP,
        FEEDS_LOG, FEEDS_LOG_LINES, FEEDS_READ_SHOWN, FEEDS_BACKLOG_READ, FEEDS_INTERVAL, FEEDS_NOTIFY, FEEDS_WIFI,
        HEADER_TODO, WIDGETS_HOME, WIDGETS_PAGE, WIDGETS_HEIGHT,
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
