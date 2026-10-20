package com.mtwabpin.kolahal.terminal

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.mtwabpin.kolahal.notifications.NotifPrefs
import com.mtwabpin.kolahal.notifications.NotificationActions
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.mtwabpin.kolahal.ui.HeaderTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.data.ConfigKey
import com.mtwabpin.kolahal.script.ScriptMeta
import com.mtwabpin.kolahal.script.Json
import com.mtwabpin.kolahal.script.ScriptFolder
import com.mtwabpin.kolahal.script.ScriptFiles
import com.mtwabpin.kolahal.script.ScriptLibrary
import com.mtwabpin.kolahal.script.ScriptTemplates
import com.mtwabpin.kolahal.data.Backup
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.script.HostApi
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.Profile
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.script.ScriptScheduler
import com.mtwabpin.kolahal.unlock.UnlockCounter
import com.mtwabpin.kolahal.status.DeviceStatus
import com.mtwabpin.kolahal.status.Status
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.ui.KeyBarKey
import com.mtwabpin.kolahal.ui.TermFonts
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiEvent {
    data object RequestNotificationPermission : UiEvent
    data object ScrollToBottom : UiEvent
    data object FocusPrompt : UiEvent
    data object HideKeyboard : UiEvent
    data object PickScriptFolder : UiEvent
    data object RequestContactsPermission : UiEvent
    data object RequestCalendarPermission : UiEvent
    data object RequestCallLogPermission : UiEvent
    data object PickFont : UiEvent
    data object ShowQuick : UiEvent
}

/** Something the user can tap in the suggestion strip. */
sealed interface Suggestion {
    val label: String

    /** Identity in the suggestion lists; [TerminalViewModel.suggestions] never returns two with the same key. */
    val key: String

    data class App(val app: AppEntry) : Suggestion {
        override val label get() = app.label
        override val key get() = "a:" + app.key
    }

    /** [app]: the app this completion is about (`uninstall maps`, `open maps:…`), for its icon. */
    data class Completion(override val label: String, val replacement: String, val app: AppEntry? = null) : Suggestion {
        override val key get() = "c:" + replacement.trimEnd()
    }
}

/** One entry of a log line's long-press menu. */
private const val NOTIF = com.mtwabpin.kolahal.notifications.KolahalNotificationListener.CTX_KEY

/** [icon]: a short symbol drawn in a fixed-width column, so labels line up whatever its width. */
data class LogAction(val label: String, val destructive: Boolean = false, val icon: String? = null, val run: () -> Unit)

/** Usage line per command name, for option completion. */
private val USAGES: Map<String, String> by lazy { CommandProcessor.COMMANDS.associate { it.name to it.usage } }

private const val PAGE = 400
private const val KEY_PENDING_EXPORT = "pending_export"
private const val MAX_PAGE = 5000

@OptIn(kotlinx.coroutines.FlowPreview::class)
class TerminalViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app), TerminalUi {
    private val context = app.applicationContext

    /** Reload requests from anywhere (log changes, paging, resume) funnel into one latest-wins pipeline. */
    private val reloadRequests = MutableStateFlow(0L)
    private fun requestReload() = reloadRequests.update { it + 1 }
    private val logs = LogRepository.get(app)
    private val appsRepo = AppRepository.get(app)
    private val scripts = ScriptRepository.get(app)
    private val unlockCounter = UnlockCounter.get(app)
    private val processor = CommandProcessor(app, this)
    private val appPrefs = AppPrefs.get(app)

    /** Scripts, refreshed off the main thread whenever the log changes (every script action logs). */
    private val scriptCache = MutableStateFlow<List<Script>>(emptyList())

    private val _commandNames = MutableStateFlow<List<String>>(emptyList())
    /** Names of `@command` scripts; part of the suggestion inputs. */
    val commandNames: StateFlow<List<String>> = _commandNames.asStateFlow()

    /** Usage lines of the command scripts that have a known one (see [ScriptMeta.usage]), set with [commandNames]. */
    @Volatile private var scriptUsages: Map<String, String> = emptyMap()

    private fun setCommandScripts(list: List<Script>) {
        val commands = list.filter { ScriptMeta.isCommand(it.code) }
        scriptUsages = commands.mapNotNull { s -> ScriptMeta.usage(s.code, s.name)?.let { s.name to it } }.toMap()
        _commandNames.value = commands.map { it.name }
    }

    /** Bumps when aliases, hidden apps or labels change. */
    val appPrefsVersion: StateFlow<Int> get() = appPrefs.version

    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Header lines pinned by scripts with `status(text)`, keyed by script name. */
    val statuses: StateFlow<Map<String, String>> = _statuses.asStateFlow()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _headerCollapsed = MutableStateFlow(false)
    /** header.autohide: the header shrinks to one line while the log is in use. */
    val headerCollapsed: StateFlow<Boolean> = _headerCollapsed.asStateFlow()

    private val _headerHidden = MutableStateFlow(false)
    /** Scrolling back through older output hides the header entirely (header.autohide). */
    val headerHidden: StateFlow<Boolean> = _headerHidden.asStateFlow()

    fun collapseHeader() {
        if (!config[Keys.HEADER_AUTOHIDE]) return
        _headerCollapsed.value = true
        _headerHidden.value = false
    }

    /** Scrolling toward older lines: no header at all, the log gets the whole screen. */
    fun hideHeader() {
        if (!config[Keys.HEADER_AUTOHIDE]) return
        _headerCollapsed.value = true
        _headerHidden.value = true
    }

    fun expandHeader() {
        _headerCollapsed.value = false
        _headerHidden.value = false
    }

    private val _screen = MutableStateFlow(Screen.TERMINAL)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _rows = MutableStateFlow<List<LogRow>>(emptyList())
    /** What the log list shows: entries (with dimming/gutter) and day separators. */
    val rows: StateFlow<List<LogRow>> = _rows.asStateFlow()

    val apps: StateFlow<List<AppEntry>> = appsRepo.apps
    val profiles: StateFlow<List<Profile>> = appsRepo.profiles
    val unlocksToday: StateFlow<Int> = unlockCounter.today
    val status: StateFlow<Status> = DeviceStatus.get(app).status
    val config: Config = Config.get(app)
    private val mediaRepo = com.mtwabpin.kolahal.control.MediaRepository.get(app)
    /** Active media session for the header (null without notification access or when idle). */
    val nowPlaying: StateFlow<com.mtwabpin.kolahal.control.NowPlaying?> = mediaRepo.nowPlaying

    /** Header now-playing line: tap toggles play/pause, long-press (or ⏭) skips. */
    fun mediaToggle() = io { mediaRepo.send(com.mtwabpin.kolahal.control.MediaRepository.Action.TOGGLE) }
    fun mediaNext() = io { mediaRepo.send(com.mtwabpin.kolahal.control.MediaRepository.Action.NEXT) }

    var input by mutableStateOf(TextFieldValue(""))
        private set

    private val _editing = MutableStateFlow<Script?>(null)
    val editing: StateFlow<Script?> = _editing.asStateFlow()

    private val _editLine = MutableStateFlow<Int?>(null)
    /** Line to jump to (and flash) when the editor opens from an error. */
    val editLine: StateFlow<Int?> = _editLine.asStateFlow()

    fun openEditorAt(script: Script, line: Int?) {
        _editLine.value = line
        _editing.value = script
    }

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private var history: List<String> = emptyList()
    private var historyIndex = -1
    private var draft = ""

    /** launcher.run(cmd) from scripts the user started (see HostApi.runCommand). */
    private val runCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, intent: Intent) {
            intent.getStringExtra(HostApi.EXTRA_COMMAND)?.let(::runCommand)
        }
    }

    override fun onCleared() {
        runCatching { context.unregisterReceiver(runCommandReceiver) }
    }

    init {
        mediaRepo.start()
        androidx.core.content.ContextCompat.registerReceiver(
            context, runCommandReceiver, android.content.IntentFilter(HostApi.ACTION_RUN_COMMAND),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModelScope.launch {
            // Bursts of changes (a script logging many lines, another process) collapse into one reload.
            combine(logs.version, reloadRequests) { a, b -> a to b }.debounce(40).collectLatest {
                // A failed reload keeps the previous rows instead of crashing home. Not logged:
                // writing to the log would trigger the next reload.
                try {
                    reload()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.w("Kolahal", "log reload failed", e)
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // A persistent flag, so `clear all` doesn't look like a first run.
            val prefs = context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
            val firstRun = !prefs.getBoolean("intro_shown", false) && logs.recent(1).isEmpty()
            prefs.edit().putBoolean("intro_shown", true).apply()
            if (firstRun) {
                logs.add(LogKind.INFO, "Kolahal — a terminal launcher. type 'help' or start typing an app name.")
                logs.add(LogKind.INFO, "↑/↓ recall commands · tap a line to reuse/open it · long-press for actions")
                openScreen(Screen.SETUP) // first run: show the setup checklist
            }
        }
    }

    /** How many log lines are loaded; grows as the user scrolls up (older lines load on demand). */
    @Volatile private var pageLimit = PAGE

    fun loadOlder() {
        if (_entries.value.size < pageLimit || pageLimit >= MAX_PAGE) return
        pageLimit += PAGE
        requestReload()
    }

    private suspend fun reload() {
        val (list, hist, scriptList) = withContext(Dispatchers.IO) {
            Triple(logs.recent(pageLimit), logs.commandHistory(), scripts.all())
        }
        scriptCache.value = scriptList
        _statuses.value = withContext(Dispatchers.IO) { KvStore(context, HostApi.STATUS_SCOPE).all() }
        withContext(Dispatchers.Default) { setCommandScripts(scriptList) }
        _hiddenLines.value = withContext(Dispatchers.IO) { logs.hiddenCount() }
        _entries.value = list
        refreshProductivity()
        _rows.value = withContext(Dispatchers.Default) { buildRows(list, seenUpTo, unreadUntil) }
        history = hist
    }

    /** Script lines in (seenUpTo, unreadUntil] arrived while the user was away; later ones were seen live. */
    @Volatile private var seenUpTo = Long.MAX_VALUE
    @Volatile private var unreadUntil = Long.MAX_VALUE
    @Volatile private var pausedAt = 0L

    private val _hiddenLines = MutableStateFlow(0)
    /** Lines a fresh-screen hide keeps out of view; the log shows a tap-to-restore line for them. */
    val hiddenLines: StateFlow<Int> = _hiddenLines.asStateFlow()

    fun showHidden() = io { logs.unhide() }

    fun onPause() {
        _entries.value.lastOrNull()?.let { logs.seenUpTo = it.id }
        pausedAt = System.currentTimeMillis()
    }

    /** Exact-alarm permission at the last resume; null before the first. */
    @Volatile private var exactAlarms: Boolean? = null

    fun onResume() {
        // Allowing exact alarms (`remind exact`) doesn't restart us: re-arm the pending reminders
        // so they stop firing late in Doze.
        val exact = com.mtwabpin.kolahal.productivity.ReminderScheduler.canBeExact(context)
        if (exact && exactAlarms == false) io { com.mtwabpin.kolahal.productivity.ReminderScheduler.rescheduleAll(context) }
        exactAlarms = exact
        // Coming back from an app is a fresh look at home: show the full header again.
        expandHeader()
        seenUpTo = logs.seenUpTo
        // log.fresh: after a while away, home starts clean. Only what you had already seen is
        // hidden (script lines that arrived meanwhile stay), and nothing is deleted.
        val freshMin = config[Keys.LOG_FRESH]
        val seen = seenUpTo
        if (freshMin > 0 && pausedAt > 0 && System.currentTimeMillis() - pausedAt >= freshMin * 60_000L && seen != Long.MAX_VALUE) {
            io { logs.hideScreen(seen) }
        }
        viewModelScope.launch {
            unreadUntil = withContext(Dispatchers.IO) { logs.maxId() }
            requestReload()
        }
        if (_screen.value == Screen.SETUP) refreshSetup()
        unlockCounter.refresh()
        appsRepo.refresh()
        mediaRepo.start() // picks up notification access granted while we were away
    }

    // ---- prompt ---------------------------------------------------------------------------

    fun onInputChange(value: TextFieldValue) {
        val old = input.text
        // Cursor moves and IME composition updates must not abandon history navigation.
        if (value.text != old) historyIndex = -1
        input = value
        // Only while typing forward at the end, so backspacing or editing mid-line never fires it.
        // One character at a time (or IME composition): a paste of a whole word is not "typing".
        // A line with arguments may also end in a word the keyboard commits whole, space and all
        // (`torch on `); only a line with nothing left to choose runs then.
        val typed = value.text.length - old.length == 1 || value.composition != null
        if (value.text.length > old.length && value.selection.collapsed && value.selection.end == value.text.length &&
            (typed || value.text.trim().contains(' '))
        ) {
            instantRun(value.text)
        }
    }

    private val commandUsages by lazy { CommandProcessor.COMMANDS.associate { it.name to it.usage } }

    /** launch.instant: launch / run as soon as the input is unambiguous. */
    private fun instantRun(text: String) {
        val target = InstantRun.target(
            text, config[Keys.LAUNCH_INSTANT], config[Keys.LAUNCH_INSTANT_MIN],
            rankedApps = { appsRepo.searchRanked(it) }, appLabel = { it.label },
            commands = scriptUsages + commandUsages, aliases = appPrefs.aliases(), scriptCommands = _commandNames.value,
            isOn = { !it.profile.quiet },
        ) ?: return
        setText("")
        collapseHeader()
        _events.tryEmit(UiEvent.ScrollToBottom)
        when (target) {
            is InstantRun.Target.App -> io { processor.launch(target.app) }
            is InstantRun.Target.Command -> io { processor.run(target.line) }
        }
    }

    /** In `launch.instant all` mode a tapped history line runs at once (unless it is sensitive). */
    private fun runsOnTap(line: String) = config[Keys.LAUNCH_INSTANT] == InstantRun.ALL &&
        // A trailing space marks a prefill ("apps " from help): it is waiting for an argument.
        !line.endsWith(' ') &&
        (InstantRun.canRun(line, scriptUsages + commandUsages, appPrefs.aliases(), _commandNames.value) { name ->
            appsRepo.searchRanked(name).firstOrNull()?.second == 0
        } || InstantRun.wholeLine(line, scriptUsages + commandUsages, appPrefs.aliases()))

    fun submit() {
        val line = input.text
        input = TextFieldValue("")
        historyIndex = -1
        if (line.isBlank()) return
        collapseHeader()
        io { processor.run(line) }
        _events.tryEmit(UiEvent.ScrollToBottom)
    }

    /** Insert [text] at the cursor (for touch keys). */
    fun insert(text: String) {
        val v = input
        val newText = v.text.replaceRange(v.selection.min, v.selection.max, text)
        input = TextFieldValue(newText, TextRange(v.selection.min + text.length))
    }

    fun historyUp() {
        if (history.isEmpty()) return
        if (historyIndex == -1) draft = input.text
        historyIndex = (historyIndex + 1).coerceAtMost(history.lastIndex)
        setText(history.getOrNull(historyIndex) ?: return)
    }

    fun historyDown() {
        if (historyIndex == -1) return
        historyIndex--
        setText(if (historyIndex == -1) draft else history.getOrNull(historyIndex) ?: draft)
    }

    /** Backspace for the touch key bar (deletes the selection or the char before the cursor). */
    fun deleteBackward() {
        val v = input
        val sel = v.selection
        val (start, end) = if (sel.collapsed) (sel.start - 1).coerceAtLeast(0) to sel.start else sel.min to sel.max
        if (start == end) return
        historyIndex = -1
        input = TextFieldValue(v.text.removeRange(start, end), TextRange(start))
    }

    fun moveCursor(delta: Int) {
        val pos = (input.selection.end + delta).coerceIn(0, input.text.length)
        input = input.copy(selection = TextRange(pos))
    }

    fun onKeyBar(key: KeyBarKey) {
        when (key) {
            KeyBarKey.ESC -> escape()
            KeyBarKey.TAB -> complete()
            KeyBarKey.UP -> historyUp()
            KeyBarKey.DOWN -> historyDown()
            KeyBarKey.LEFT -> moveCursor(-1)
            KeyBarKey.RIGHT -> moveCursor(1)
            KeyBarKey.HOME -> input = input.copy(selection = TextRange(0))
            KeyBarKey.END -> input = input.copy(selection = TextRange(input.text.length))
            KeyBarKey.ENTER -> submit()
            KeyBarKey.KBD -> _events.tryEmit(UiEvent.FocusPrompt)
            KeyBarKey.SPACE -> insert(" ")
            KeyBarKey.BKSP -> deleteBackward()
        }
    }

    fun escape() {
        historyIndex = -1
        setText("")
    }

    /** TAB: accept the first suggestion. */
    fun complete() {
        val first = suggestions(input.text).firstOrNull() ?: return
        when (first) {
            is Suggestion.Completion -> pickCompletion(first.replacement)
            is Suggestion.App -> setText(first.app.label)
        }
    }

    /** A completion fills the prompt; in `launch.instant all` a line it leaves with nothing to choose runs (`torch on`). */
    private fun pickCompletion(replacement: String) {
        setText(replacement)
        if (replacement.trim().contains(' ')) instantRun(replacement)
    }

    fun onSuggestion(s: Suggestion) {
        when (s) {
            is Suggestion.App -> {
                setText("")
                io { processor.launch(s.app) }
            }
            is Suggestion.Completion -> pickCompletion(s.replacement)
        }
    }

    private fun setText(text: String) {
        input = TextFieldValue(text, TextRange(text.length))
    }

    fun suggestions(text: String): List<Suggestion> {
        if (text.isBlank()) {
            return if (config[Keys.SUGGEST_IDLE]) appsRepo.top(config[Keys.SUGGESTIONS]).map { Suggestion.App(it) } else emptyList()
        }
        val parts = text.trimStart().split(' ')
        val head = parts.first().lowercase()
        val scriptList = scriptCache.value
        if (parts.size == 1) {
            val names = CommandProcessor.COMMANDS.map { it.name } +
                _commandNames.value +
                appPrefs.aliases().keys
            val cmds = names.distinct()
                .filter { it.startsWith(head) && it != head }
                .map { Suggestion.Completion(it, "$it ") }
            val ranked = appsRepo.searchRanked(head).take(12)
            // When Enter would launch an app (launch.top + a prefix match), that app is the first,
            // highlighted chip, so Enter, TAB and the highlight agree.
            val enterLaunches = config[Keys.LAUNCH_TOP] && ranked.firstOrNull()?.second?.let { it <= 2 } == true &&
                names.none { it == head }
            val apps = ranked.map { Suggestion.App(it.first) }
            // A whole command name already: its first options too, so `xkcd` offers `xkcd random`.
            val options = if (head in names) usageOptions(head, listOf(head, "")).map { it.copy(label = "$head ${it.label}") } else emptyList()
            return (if (enterLaunches) listOf(apps.first()) + cmds.take(3) + apps.drop(1) else cmds.take(3) + options + apps).distinctBy { it.key }
        }
        val specific = argSuggestions(head, parts, scriptList)
        // Commands without their own completer still get the options their usage line lists.
        val options = if (specific.isEmpty()) usageOptions(head, parts) else emptyList()
        val typed = text.trim()
        return (specific + options + historySuggestions(text))
            .filter { it !is Suggestion.Completion || it.replacement.trim() != typed }
            .distinctBy { it.key }
    }

    /** Earlier commands that continue what is typed: the completer for free text (search, note, …). */
    private fun historySuggestions(text: String): List<Suggestion> {
        val typed = text.trimStart()
        return history.asSequence()
            .filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
            .take(4)
            .map { Suggestion.Completion(it, it) }
            .toList()
    }

    /** Literal options at this argument position in the command's usage line (`apps [work|personal|…]`). */
    private fun usageOptions(head: String, parts: List<String>): List<Suggestion.Completion> {
        val usage = USAGES[head] ?: scriptUsages[head] ?: return emptyList()
        val token = UsageLine.tokens(usage).drop(1).getOrNull(parts.size - 2) ?: return emptyList()
        if (token.startsWith("<")) return emptyList() // a placeholder: <app>, <query>, …
        val inner = token.removeSurrounding("[", "]")
        val last = parts.last()
        val before = parts.dropLast(1).joinToString(" ")
        return UsageLine.split(inner, '|').mapNotNull { alt ->
            val word = alt.trim().substringBefore(' ')
            val rest = alt.trim().substringAfter(' ', "")
            // `add <url>` and `mute <app>` take a value after them; `name = command` is not an option.
            word.takeIf { it.matches(UsageLine.OPTION) && (rest.isEmpty() || rest.startsWith("<") || rest.startsWith("[")) }
        }.filter { it.startsWith(last) && it != last }
            .map { Suggestion.Completion(it, "$before $it ") }
    }

    private fun argSuggestions(head: String, parts: List<String>, scriptList: List<Script>): List<Suggestion> {
        val arg = parts.drop(1).joinToString(" ")
        val last = parts.last()
        return when (head) {
            "open", "launch" -> if (arg.contains(':')) shortcutSuggestions(head, arg)
                else appsRepo.search(arg.replace(Regex("\\s-\\w+"), "").trim()).take(12).map { Suggestion.App(it) }
            "info", "uninstall", "hide", "unhide", "rename", "shortcuts", "pin", "fav", "unpin", "unfav" ->
                if (head == "rename" && arg.contains('=')) emptyList() else appCompletions(head, arg, includeHidden = head == "unhide")
            "notifs" -> when {
                parts.size >= 3 && parts[1] in listOf("mute", "unmute") -> appCompletions("notifs ${parts[1]}", parts.drop(2).joinToString(" "))
                else -> emptyList()
            }
            "alias", "unalias" -> if (parts.size == 2) appPrefs.aliases().filterKeys { it.startsWith(last) }.map { (name, cmd) ->
                if (head == "unalias") Suggestion.Completion("$name = $cmd", "unalias $name")
                else Suggestion.Completion("$name = $cmd", "alias $name = $cmd")
            } else emptyList()
            "status" -> if (parts.size == 3 && parts[1] == "clear") (_statuses.value.keys + "all")
                .filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(it, "status clear $it") } else emptyList()
            "feed" -> if (parts.size == 3 && parts[1] in listOf("rm", "read", "open", "notify", "every", "fetch")) feedCache
                .filter { last.isEmpty() || it.id.toString().startsWith(last) || (it.title ?: it.url).contains(last, ignoreCase = true) }
                .take(12).map { Suggestion.Completion("#${it.id} ${it.title ?: it.url}", "feed ${parts[1]} ${it.id}") } else emptyList()
            "usage", "unlocks" -> if (parts.size == 2) listOf("1", "7", "14", "30")
                .filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(if (it == "1") "today" else "$it days", "$head $it") } else emptyList()
            "installs", "updates", "calls" -> if (parts.size == 2) listOf("20", "50", "200")
                .filter { it.startsWith(last) && it != last }.map { Suggestion.Completion("last $it", "$head $it") } else emptyList()
            "script" -> {
                if (parts.size == 2) {
                    CommandProcessor.SCRIPT_SUBCOMMANDS.filter { it.startsWith(parts[1]) && it != parts[1] }
                        .map { Suggestion.Completion(it, "script $it ") }
                } else {
                    val sub = parts[1]
                    val prefix = parts.getOrNull(2).orEmpty()
                    if (parts.size == 3 && sub != "new") {
                        scriptList.filter { it.name.startsWith(prefix) && it.name != prefix }
                            .map { Suggestion.Completion(it.name, "script $sub ${it.name} ") }
                    } else emptyList()
                }
            }
            "config", "set" -> {
                val keyIndex = if (parts.getOrNull(1) in listOf("set", "get", "reset")) 2 else 1
                when {
                    parts.size == 2 && keyIndex == 2 -> emptyList()
                    parts.size == keyIndex + 1 -> {
                        val prefix = parts.take(keyIndex).joinToString(" ")
                        val subs = if (keyIndex == 1) listOf("ls", "set", "get", "reset").filter { it.startsWith(last) } else emptyList()
                        subs.map { Suggestion.Completion(it, "$prefix $it ") } +
                            Keys.ALL.filter { it.key.startsWith(last) && it.key != last }
                                .map { Suggestion.Completion(it.key, "$prefix ${it.key} ") }
                    }
                    parts.size == keyIndex + 2 -> {
                        val key = Keys.byName(parts[keyIndex]) ?: return emptyList()
                        val prefix = parts.take(keyIndex + 1).joinToString(" ")
                        val values = when (key) {
                            is ConfigKey.Bool -> listOf("on", "off")
                            is ConfigKey.Choice -> key.choices
                            else -> emptyList()
                        }
                        values.filter { it.startsWith(last) && it != last }.map { Suggestion.Completion(it, "$prefix $it") }
                    }
                    else -> emptyList()
                }
            }
            "theme" -> Keys.THEME.choices.filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "theme $it") }
            "font" -> (TermFonts.names + "import").filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "font $it") }
            "call", "dial", "sms", "text", "contacts", "contact" -> contactSuggestions(head, parts)
            "work", "private" -> listOf("on", "off", "status")
                .filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "$head $it") }
            in CommandProcessor.PRODUCTIVITY -> productivitySuggestions(head, parts)
            else -> com.mtwabpin.kolahal.control.ControlCommands.suggest(head, arg)
                .map { (label, line) -> Suggestion.Completion(label, line) }
        }
    }

    /** `<head> <app>` completions; the profile flag is added when the app exists in several profiles. */
    private fun appCompletions(head: String, arg: String, includeHidden: Boolean = false): List<Suggestion> =
        appsRepo.search(arg.replace(Regex("\\s-\\w+"), "").trim(), includeHidden = includeHidden)
            .take(12).map { Suggestion.Completion("${it.label} [${it.profile.kind.tag}]", "$head ${it.label}" + flagFor(it), app = it) }

    /** Last app's shortcut labels, so typing after `open maps:` doesn't ask the system on every key. */
    private var shortcutCache: Pair<String, List<String>>? = null

    private var contactsLoadRequested = false

    /** `open maps:` → that app's launcher shortcuts. */
    private fun shortcutSuggestions(head: String, arg: String): List<Suggestion> {
        val app = appsRepo.search(arg.substringBefore(':').trim()).firstOrNull() ?: return emptyList()
        val labels = shortcutCache?.takeIf { it.first == app.key }?.second
            ?: appsRepo.shortcuts(app).mapNotNull { (it.shortLabel ?: it.longLabel)?.toString() }.distinct()
                .also { shortcutCache = app.key to it }
        val wanted = arg.substringAfter(':').trim()
        return labels.filter { it.contains(wanted, ignoreCase = true) }.take(12)
            .map { Suggestion.Completion("${app.label} › $it", "$head ${app.label}:$it" + flagFor(app), app = app) }
    }

    /**
     * `call ` lists favourites first, then everyone; typing narrows by name or number. A name with
     * several numbers gets one row per number and completes to the number, so each row is distinct
     * and dials what it shows. `sms` always completes to the number: its first word is the recipient.
     */
    private fun contactSuggestions(head: String, parts: List<String>): List<Suggestion> {
        val repo = com.mtwabpin.kolahal.contacts.ContactsRepository.get(context)
        if (!repo.hasPermission()) return listOf(Suggestion.Completion("allow contacts access", "contacts --allow"))
        val sms = head == "sms" || head == "text"
        // `sms <number> hello…`: the recipient is done, the rest is the message.
        if (sms && parts.size > 2 && com.mtwabpin.kolahal.contacts.ContactsRepository.looksLikeNumber(parts[1])) return emptyList()
        val query = parts.drop(1).joinToString(" ")
        val all = repo.contacts.value
        // Allowed after start (e.g. from system settings): load now; [contacts] recomposes us when done.
        if (all.isEmpty() && !contactsLoadRequested) { contactsLoadRequested = true; repo.refresh() }
        val list =if (query.isBlank()) all.take(12) else repo.search(query, 12)
        val numbersPerName = all.groupingBy { it.name.lowercase() }.eachCount()
        return list.map { c ->
            val shared = (numbersPerName[c.name.lowercase()] ?: 1) > 1
            val label = (if (c.starred) "★ " else "") + c.name + if (shared) " · ${c.label} ${c.number}" else ""
            val target = when {
                head == "contacts" || head == "contact" -> c.name
                sms || shared -> c.number
                else -> c.name
            }
            Suggestion.Completion(label, "$head $target" + if (sms) " " else "")
        }
    }

    private fun flagFor(app: AppEntry) = when (app.profile.kind) {
        ProfileKind.WORK -> " -w"
        ProfileKind.PRIVATE -> " -s"
        ProfileKind.CLONE -> " -c"
        ProfileKind.PERSONAL -> if (apps.value.any { it.pkg == app.pkg && it.profile.kind != ProfileKind.PERSONAL }) " -p" else ""
    }

    // ---- log line interaction -------------------------------------------------------------

    /** Single tap: the most likely action for this line. */
    fun onTap(entry: LogEntry) {
        val ctx = entry.ctx
        val errorLine = ctx.values[LogContext.LINE]?.toIntOrNull()
        when {
            errorLine != null && ctx.script != null -> {
                val name = ctx.script!!
                io { scripts.byName(name)?.let { openEditorAt(it, errorLine) } }
            }
            ctx.values[LogContext.NOTE] != null -> copy(ctx.copy ?: entry.text)
            ctx.values[LogContext.EVENT] != null -> ProductivityCommands.eventIntent(ctx)?.let(::startActivity)
            // Cancelling takes Enter (or the long-press "Cancel reminder").
            ctx.values[LogContext.REMINDER] != null -> {
                setText("remind rm #${ctx.values.getValue(LogContext.REMINDER)}")
                _events.tryEmit(UiEvent.FocusPrompt)
            }
            ctx.values[LogContext.RUN] != null -> {
                val cmd = ctx.values.getValue(LogContext.RUN)
                _events.tryEmit(UiEvent.ScrollToBottom)
                io { processor.run(cmd, expand = false) }
            }
            entry.kind == LogKind.COMMAND || ctx.command != null -> {
                val line = ctx.command ?: entry.text
                if (runsOnTap(line)) {
                    collapseHeader()
                    _events.tryEmit(UiEvent.ScrollToBottom)
                    io { processor.run(line) }
                } else {
                    setText(line)
                    _events.tryEmit(UiEvent.FocusPrompt)
                }
            }
            ctx.values[NOTIF] != null -> {
                // The notification's own tap action if it's still live, otherwise just the app.
                if (!NotificationActions.open(getApplication(), ctx.values.getValue(NOTIF))) appFor(ctx)?.let { app -> io { processor.launch(app) } }
            }
            ctx.values[LogContext.SHORTCUT] != null && appFor(ctx) != null -> {
                val app = appFor(ctx)!!
                val id = ctx.values.getValue(LogContext.SHORTCUT)
                io { runCatching { appsRepo.startShortcut(app, id) }.onFailure { logs.add(LogKind.ERROR, "Shortcut unavailable: ${it.message}") } }
            }
            ctx.values[LogContext.PHONE] != null -> startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", ctx.values.getValue(LogContext.PHONE), null)))
            ctx.pkg != null -> appFor(ctx)?.let { app -> io { processor.launch(app) } }
            ctx.url != null -> openLink(ctx)
        }
    }

    /** Long-press: every action that makes sense for the saved context. Destructive ones come last. */
    fun actionsFor(entry: LogEntry): List<LogAction> {
        val ctx = entry.ctx
        val actions = ArrayList<LogAction>()
        val danger = ArrayList<LogAction>()
        val command = ctx.command ?: entry.text.takeIf { entry.kind == LogKind.COMMAND }
        if (command != null) {
            actions += LogAction("Run again") { setText(command); submit() }
            actions += LogAction("Edit in prompt") { setText(command); _events.tryEmit(UiEvent.FocusPrompt) }
        } else if (entry.kind != LogKind.SCRIPT) {
            // Plain output: offer the command that printed it.
            producingCommand(entry)?.let { cmd ->
                actions += LogAction("Run '${cmd.text}' again") { setText(cmd.text); submit() }
                actions += LogAction("Edit '${cmd.text}' in prompt") { setText(cmd.text); _events.tryEmit(UiEvent.FocusPrompt) }
            }
        }
        appFor(ctx)?.let { app ->
            val (safe, destructive) = appActions(app).partition { !it.destructive }
            actions += safe
            danger += destructive
        }
        ctx.values[NOTIF]?.let { key ->
            actions += LogAction("Open notification") { if (!NotificationActions.open(getApplication(), key)) appFor(ctx)?.let { app -> io { processor.launch(app) } } }
            ctx.pkg?.let { pkg ->
                actions += LogAction("Mute this app's notifications") { NotifPrefs.get(getApplication()).mute(pkg); logs.add(LogKind.INFO, "Muted notifications from ${appFor(ctx)?.label ?: pkg}") }
            }
            danger += LogAction("Dismiss notification", destructive = true) { NotificationActions.dismiss(key) }
        }
        ctx.values[LogContext.PHONE]?.let { number ->
            actions += LogAction("Call $number") { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
            actions += LogAction("Send a message") { startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))) }
            ctx.values[LogContext.CONTACT]?.let { uri ->
                actions += LogAction("Open contact") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            }
            actions += LogAction("Copy number") { copy(number) }
        }
        ctx.url?.let { url ->
            actions += LogAction("Open link") { openLink(ctx) }
            actions += LogAction("Share link") { share(entry.text, url) }
            actions += LogAction("Copy link") { copy(url) }
        }
        ctx.image?.takeIf { it != ctx.url }?.let { image -> actions += LogAction("Open image") { openUrl(image) } }
        ctx.script?.let { name ->
            scriptCache.value.firstOrNull { it.name == name }?.let { s ->
                actions += LogAction("Run $name now") { io { ScriptScheduler.runNow(context, s) } }
                ctx.values[LogContext.LINE]?.toIntOrNull()?.let { line ->
                    actions += LogAction("Edit $name at line $line") { io { scripts.byId(s.id)?.let { openEditorAt(it, line) } } }
                }
                actions += LogAction("Edit $name.${s.lang.ext}") { io { scripts.byId(s.id)?.let(::openEditor) } }
                actions += LogAction(if (s.enabled) "Disable $name" else "Enable $name") {
                    io {
                        scripts.setEnabled(s.id, !s.enabled)
                        scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                        logs.add(LogKind.INFO, "$name: ${if (s.enabled) "disabled" else "enabled"}", ctx = LogContext.of(LogContext.SCRIPT to name))
                    }
                }
                actions += LogAction(if (s.notify) "Mute notifications" else "Notify on new items") {
                    io {
                        scripts.setNotify(s.id, !s.notify)
                        logs.add(LogKind.INFO, "$name: notifications ${if (s.notify) "off" else "on"}", ctx = LogContext.of(LogContext.SCRIPT to name))
                    }
                    if (!s.notify) _events.tryEmit(UiEvent.RequestNotificationPermission)
                }
            }
        }
        productivityActions(ctx, actions, danger)
        if (entry.id < 0) return actions + danger // synthetic entry (e.g. an app chip): nothing to copy or delete
        val block = if (entry.kind == LogKind.COMMAND) blockOf(entry) else emptyList()
        if (block.size > 1) {
            actions += LogAction("Copy output") { copy(block.drop(1).joinToString("\n") { it.text }) }
            danger += LogAction("Delete command and output", destructive = true) {
                val ids = block.map { it.id }
                io { ids.forEach(logs::delete) }
            }
        }
        actions += LogAction("Copy text") { copy(ctx.copy ?: entry.text) }
        danger += LogAction("Delete line", destructive = true) { io { logs.delete(entry.id) } }
        return actions + danger
    }

    /** Actions for one app (log lines and grid chips). */
    fun appActions(app: AppEntry): List<LogAction> = buildList {
        add(LogAction("Launch ${app.label} [${app.profile.kind.tag}]") { io { processor.launch(app) } })
        // The app's own shortcuts (e.g. "New message"), when Kolahal is the default launcher.
        appsRepo.shortcuts(app).take(5).forEach { s ->
            add(LogAction("${s.shortLabel}", icon = "›") { io { runCatching { appsRepo.startShortcut(app, s.id) } } })
        }
        apps.value.firstOrNull { it.pkg == app.pkg && it.profile.serial != app.profile.serial }?.let { twin ->
            add(LogAction("Launch in ${twin.profile.kind.label} profile") { io { processor.launch(twin) } })
        }
        add(LogAction("App info") { io { appsRepo.openAppInfo(app) } })
        add(LogAction("Copy package name") { copy(app.pkg) })
        val pinned = appPrefs.isPinned(app.key)
        add(LogAction(if (pinned) "Unpin from favourites" else "Pin to favourites") { appPrefs.setPinned(app.key, !pinned) })
        add(LogAction(if (app.hidden) "Unhide" else "Hide from lists") {
            io {
                appPrefs.setHidden(app.key, !app.hidden)
                appsRepo.refresh()
            }
        })
        add(LogAction("Uninstall", destructive = true) { io { appsRepo.uninstall(app) } })
    }

    fun launchApp(app: AppEntry) = io { processor.launch(app) }

    /** Keys of favourite apps (listed first, with ★, in the app selector). */
    fun pinnedKeys(): Set<String> = appPrefs.pinned().toSet()

    /** A command line plus the output that followed it (up to the next command; timer output excluded). */
    /** The command line above [entry] in its block (null for script output or orphan lines). */
    private fun producingCommand(entry: LogEntry): LogEntry? {
        val all = _entries.value
        val index = all.indexOfFirst { it.id == entry.id }
        if (index < 0) return null
        for (i in index - 1 downTo 0) {
            val e = all[i]
            // Timer script lines can land inside a block; they are not part of it.
            if (e.kind == LogKind.COMMAND) return e
        }
        return null
    }

    private fun blockOf(command: LogEntry): List<LogEntry> {
        val all = _entries.value
        val start = all.indexOfFirst { it.id == command.id }
        if (start < 0) return listOf(command)
        val rest = all.drop(start + 1).takeWhile { it.kind != LogKind.COMMAND }.filter { it.kind != LogKind.SCRIPT }
        return listOf(command) + rest
    }

    /** Apps referenced by a grid line (`ctx.apps` = JSON list of {pkg, cls, user}). */
    fun appsOf(entry: LogEntry): List<AppEntry> {
        val raw = entry.ctx.values[LogContext.APPS] ?: return emptyList()
        val list = runCatching { Json.parse(raw) as? List<*> }.getOrNull().orEmpty()
        return list.filterIsInstance<Map<*, *>>().mapNotNull { m ->
            val pkg = m["pkg"]?.toString() ?: return@mapNotNull null
            appsRepo.find(pkg, m["cls"]?.toString(), m["user"]?.toString()?.toLongOrNull())
        }
    }

    private fun appFor(ctx: LogContext): AppEntry? =
        ctx.pkg?.let { appsRepo.find(it, ctx.cls, ctx.userSerial) ?: appsRepo.find(it, null, ctx.userSerial) }

    /** One serial worker: commands and actions run in order, and failures are logged, never crash home. */
    private val serialIo = Dispatchers.IO.limitedParallelism(1)
    private val errors = CoroutineExceptionHandler { _, e ->
        runCatching { logs.add(LogKind.ERROR, "Error: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(serialIo + errors) { block() }
    }

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** Opens a line's link; a feed item counts as read once you have opened it. */
    private fun openLink(ctx: LogContext) {
        openUrl(ctx.url ?: return)
        ctx.values[com.mtwabpin.kolahal.feeds.FeedWorker.CTX_ITEM]?.toLongOrNull()?.let { id ->
            io { com.mtwabpin.kolahal.feeds.FeedRepository.get(context).markRead(id) }
        }
    }

    private fun share(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private val _undoDelete = MutableStateFlow<LogEntry?>(null)
    /** The line a swipe just deleted; the terminal offers Undo for a few seconds. */
    val undoDelete: StateFlow<LogEntry?> = _undoDelete.asStateFlow()
    private var undoJob: kotlinx.coroutines.Job? = null

    /** Swipe left on a log line. */
    fun swipeDelete(entry: LogEntry) {
        io { logs.delete(entry.id) }
        _undoDelete.value = entry
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            kotlinx.coroutines.delay(8_000)
            _undoDelete.value = null
        }
    }

    fun undoSwipeDelete() {
        val entry = _undoDelete.value ?: return
        _undoDelete.value = null
        undoJob?.cancel()
        io { logs.restore(entry) }
    }

    /** Swipe right on a log line. */
    fun swipeCopy(entry: LogEntry) = copy(entry.text)

    private fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("kolahal", text))
    }

    private fun startActivity(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { io { logs.add(LogKind.ERROR, "Can't open: ${it.message}") } }
    }

    // ---- script editor --------------------------------------------------------------------

    fun saveScript(script: Script, code: String) {
        _editing.value = script.copy(code = code)
        io {
            scripts.saveCode(script.id, code)
            refreshScriptCaches()
        }
    }

    fun runScript(script: Script, code: String) {
        io {
            scripts.saveCode(script.id, code)
            scripts.byId(script.id)?.let { ScriptScheduler.runNow(context, it) }
            logs.add(LogKind.INFO, "Running ${script.name}…", ctx = LogContext.of(LogContext.SCRIPT to script.name))
        }
    }

    fun onHeaderTap(target: HeaderTarget) {
        when (target) {
            HeaderTarget.CLOCK, HeaderTarget.ALARM -> startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            HeaderTarget.UNLOCKS -> runCommand("unlocks 7")
            HeaderTarget.BATTERY -> startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            HeaderTarget.NETWORK -> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            HeaderTarget.SETTINGS -> openScreen(Screen.SETTINGS)
            HeaderTarget.TODO -> runCommand("todo")
        }
    }

    /** Resuming is harmless, pausing asks for a second tap (the logged line). */
    /**
     * The quick sheet (tap `$` on the prompt, or the `quick` gesture): everything the header
     * offers, in thumb reach at the bottom of the screen.
     */
    fun openQuick() { _events.tryEmit(UiEvent.ShowQuick) }

    fun quickActions(): List<LogAction> = buildList {
        add(LogAction("Settings", icon = "⚙") { openScreen(Screen.SETTINGS) })
        add(LogAction("Notifications", icon = "▾") { expandNotifications() })
        add(LogAction("Alarms & clock", icon = "◷") { onHeaderTap(HeaderTarget.ALARM) })
        add(LogAction("Battery", icon = "▮") { onHeaderTap(HeaderTarget.BATTERY) })
        add(LogAction("Network", icon = "⇅") { onHeaderTap(HeaderTarget.NETWORK) })
        add(LogAction("Unlocks this week", icon = "#") { onHeaderTap(HeaderTarget.UNLOCKS) })
        profiles.value.filter { it.kind == ProfileKind.WORK || it.kind == ProfileKind.PRIVATE }.forEach { p ->
            val cmd = if (p.kind == ProfileKind.PRIVATE) "private" else "work"
            add(
                LogAction(if (p.quiet) "Resume ${p.kind.label} profile" else "Pause ${p.kind.label} profile", icon = if (p.quiet) "▸" else "‖") {
                    runCommand("$cmd ${if (p.quiet) "on" else "off"}")
                },
            )
        }
        add(LogAction("Apps", icon = "☰") { runCommand("apps") })
        add(LogAction("Widgets", icon = "◫") { openScreen(Screen.WIDGETS) })
        add(LogAction("Feeds", icon = "≋") { openScreen(Screen.FEEDS) })
        add(LogAction("Scripts", icon = "{}") { openScreen(Screen.SCRIPTS) })
        if (com.mtwabpin.kolahal.BuildConfig.LOCK_SERVICE) {
            add(LogAction("Lock screen", icon = "⏻") { if (!com.mtwabpin.kolahal.gesture.LockService.lock()) runCommand("lock") })
        }
    }

    fun onProfileTap(profile: Profile) {
        val cmd = if (profile.kind == ProfileKind.PRIVATE) "private" else "work"
        if (profile.quiet) {
            runCommand("$cmd on")
        } else {
            io {
                logs.add(
                    LogKind.INFO,
                    "Tap to pause the ${profile.kind.label} profile",
                    ctx = LogContext.of(LogContext.RUN to "$cmd off"),
                )
            }
        }
    }

    /** Runs a command as if typed (without alias expansion). */
    fun runCommand(cmd: String) {
        collapseHeader()
        _events.tryEmit(UiEvent.ScrollToBottom)
        io { processor.run(cmd, expand = false) }
    }

    fun saveFontSize(size: Int) {
        config.set(Keys.FONT_SIZE, size.toString())
    }

    fun runScriptNamed(name: String) {
        io {
            scripts.byName(name)?.let {
                ScriptScheduler.runNow(context, it)
                logs.add(LogKind.INFO, "Running $name…", ctx = LogContext.of(LogContext.SCRIPT to name))
            }
        }
    }

    fun expandNotifications() = com.mtwabpin.kolahal.gesture.Gestures.expandShade(context)

    private var lockHintShown = false

    /** Runs the action configured for a gesture: lock, shade, none or a terminal command. */
    fun onGesture(key: com.mtwabpin.kolahal.data.ConfigKey.Text) {
        val g = com.mtwabpin.kolahal.gesture.Gestures
        when (val action = config[key].trim()) {
            "", g.NONE -> Unit
            g.SHADE -> expandNotifications()
            "quick" -> _events.tryEmit(UiEvent.ShowQuick)
            // Locking needs no log line; only report when the accessibility service is off.
            // Without the service, explain once per session instead of on every stray double-tap.
            g.LOCK -> if (!com.mtwabpin.kolahal.gesture.LockService.lock() && !lockHintShown) {
                lockHintShown = true
                runCommand("lock")
            }
            // Gesture actions may be aliases, so they expand like typed input.
            else -> {
                collapseHeader()
                _events.tryEmit(UiEvent.ScrollToBottom)
                io { processor.run(action) }
            }
        }
    }

    fun closeEditor() {
        _editLine.value = null
        _editing.value = null
    }

    // ---- GUI screens (settings, script manager) ------------------------------------------


    /** Scripts for the manager screen. */
    val scriptList: StateFlow<List<Script>> get() = scriptCache

    private val _notice = MutableStateFlow<String?>(null)
    /** Short result message shown on the GUI screens (also logged to the terminal). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Pages opened from other pages (Settings → Script manager); Back returns along it. */
    private val backStack = ArrayDeque<Screen>()

    override fun openScreen(screen: Screen) {
        _notice.value = null
        synchronized(backStack) {
            val current = _screen.value
            if (screen == Screen.TERMINAL) backStack.clear()
            else if (current != Screen.TERMINAL && current != screen) backStack.addLast(current)
            _screen.value = screen
        }
        if (screen == Screen.SCRIPTS) refreshScripts()
        if (screen == Screen.SETUP) refreshSetup()
    }

    private val _setup = MutableStateFlow<List<SetupCheck>>(emptyList())
    val setupChecks: StateFlow<List<SetupCheck>> = _setup.asStateFlow()

    fun refreshSetup() = io { _setup.value = SetupChecks.run(context) }

    /** Opens the system page for a check; the list refreshes when the user comes back. */
    fun fixSetup(check: SetupCheck) {
        check.fix?.let { cmd -> io { processor.run(cmd, expand = false) } }
    }

    /** Back from a page: the page it was opened from, or the terminal. */
    fun closeScreen() {
        val previous = synchronized(backStack) { backStack.removeLastOrNull() } ?: Screen.TERMINAL
        _screen.value = previous
        if (previous == Screen.SCRIPTS) refreshScripts()
        if (previous == Screen.SETUP) refreshSetup()
    }

    private fun refreshScripts() = io { scriptCache.value = scripts.all() }

    private fun notice(text: String, error: Boolean = false) {
        _notice.value = text
        logs.add(if (error) LogKind.ERROR else LogKind.INFO, text)
    }

    /** Applies a script change, reschedules it, and refreshes the manager list. */
    private fun updateScript(s: Script, change: () -> Unit) = io {
        change()
        scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
        scriptCache.value = scripts.all()
    }

    fun setScriptEnabled(s: Script, on: Boolean) = updateScript(s) { scripts.setEnabled(s.id, on) }
    fun setScriptNotify(s: Script, on: Boolean) {
        updateScript(s) { scripts.setNotify(s.id, on) }
        if (on) _events.tryEmit(UiEvent.RequestNotificationPermission)
    }

    fun setScriptInterval(s: Script, minutes: Int) = updateScript(s) {
        scripts.setInterval(s.id, if (minutes > 0) minutes.coerceAtLeast(ScriptScheduler.MIN_INTERVAL_MIN) else 0)
        if (minutes > 0 && !s.enabled) scripts.setEnabled(s.id, true)
    }

    /** "08:00, 18:30" -> daily times; returns false if any time is invalid. */
    fun setScriptTimes(s: Script, raw: String): Boolean {
        val parts = raw.split(',', ' ').filter { it.isNotBlank() }
        val times = parts.mapNotNull(ScriptScheduler::normalizeTime)
        if (times.size != parts.size) return false
        updateScript(s) { scripts.setAtTimes(s.id, times.distinct().sorted()) }
        return true
    }

    fun runScriptNow(s: Script) = io {
        ScriptScheduler.runNow(context, s)
        notice("Running ${s.name}…")
    }

    fun deleteScript(s: Script) = io {
        ScriptScheduler.cancel(context, s.id)
        scripts.delete(s.id)
        KvStore(context, HostApi.STATUS_SCOPE).set(s.name, null)
        scriptCache.value = scripts.all()
        notice("Deleted ${s.name}")
    }

    fun newScript(name: String, lang: ScriptLang, asCommand: Boolean) {
        val clean = name.trim().lowercase()
        if (!Regex("^[a-z0-9_-]{1,40}$").matches(clean)) {
            _notice.value = "names: lowercase letters, digits, - and _"
            return
        }
        io {
            if (scripts.byName(clean) != null) return@io notice("Script '$clean' already exists", error = true)
            val code = if (asCommand) ScriptTemplates.command(lang, clean) else ScriptTemplates.forLang(lang, clean)
            val s = scripts.create(clean, lang, code)
            scriptCache.value = scripts.all()
            openEditor(s)
        }
    }

    /**
     * File operations (SAF providers can be slow, e.g. cloud storage) run off the serial command
     * queue, and failures show as a notice on the GUI page instead of only in the terminal log.
     */
    private fun fileOp(label: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { notice("$label failed: ${it.message ?: it.javaClass.simpleName}", error = true) }
        }
    }

    fun importScriptFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        fileOp("import") {
            val r = ScriptFiles(context).import(uris)
            refreshScriptCaches()
            notice(
                buildList {
                    if (r.created.isNotEmpty()) add("added ${r.created.joinToString()}")
                    if (r.updated.isNotEmpty()) add("updated ${r.updated.joinToString()}")
                    if (r.skipped.isNotEmpty()) add("skipped ${r.skipped.joinToString()}")
                }.joinToString(" · ").ifEmpty { "nothing imported" }.replaceFirstChar { it.uppercase() },
                error = r.created.isEmpty() && r.updated.isEmpty(),
            )
        }
    }

    /** Script waiting for the "save as" picker; kept in SavedStateHandle so process death can't lose it. */
    fun beginExportScript(s: Script) {
        state[KEY_PENDING_EXPORT] = s.id
    }

    fun finishExportScript(uri: Uri?) {
        val id = state.get<Long>(KEY_PENDING_EXPORT) ?: return
        state.remove<Long>(KEY_PENDING_EXPORT)
        if (uri == null) return
        fileOp("export") {
            val s = scripts.byId(id) ?: throw IllegalStateException("script was deleted")
            ScriptFiles(context).export(s, uri)
            notice("Exported ${s.name}.${s.lang.ext}")
        }
    }

    fun exportBackup(uri: Uri?) {
        if (uri == null) return
        fileOp("backup") { notice("Backup saved: everything — settings, apps, ${Backup(context).export(uri)} scripts, todos, notes, reminders, feeds and the log") }
    }

    fun importBackup(uri: Uri?) {
        if (uri == null) return
        fileOp("import") {
            val r = Backup(context).import(uri)
            appsRepo.refresh()
            refreshScriptCaches()
            notice(
                "imported ${r.settings} settings, ${r.aliases} aliases, ${r.scripts} scripts" +
                    (if (r.full) ", ${r.restored} saved items (todos, notes, reminders, feeds, log…)" else "") +
                    (if (r.disabled > 0) " (${r.disabled} new scripts are off — enable them in Scripts)" else "") +
                    (if (r.skipped.isNotEmpty()) " · skipped ${r.skipped.joinToString()}" else "") +
                    if (r.full) " — restarting to load it all" else ""
            )
            // A full restore replaced preferences and tables under every in-memory cache: start fresh.
            if (r.full) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    context.startActivity(
                        android.content.Intent(context, com.mtwabpin.kolahal.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 2_000)
            }
        }
    }

    val library by lazy { ScriptLibrary(context) }

    fun installFromLibrary(entry: ScriptLibrary.Entry) {
        fileOp("install") {
            val s = library.install(entry)
            refreshScriptCaches()
            notice("Installed ${s.name}.${s.lang.ext}" + if (entry.command) " — run it as '${s.name}'" else "")
        }
    }

    /** Scripts list + command names, refreshed after edits/imports (not only when the log changes). */
    private suspend fun refreshScriptCaches() {
        val list = withContext(Dispatchers.IO) { scripts.all() }
        scriptCache.value = list
        setCommandScripts(list)
    }

    fun setConfig(key: ConfigKey<*>, raw: String): Boolean = config.set(key, raw)

    fun resetConfig(key: ConfigKey<*>) = config.reset(key)

    // ---- TerminalUi -----------------------------------------------------------------------

    override fun openEditor(script: Script) = openEditorAt(script, null)

    override fun requestContactsPermission() {
        _events.tryEmit(UiEvent.RequestContactsPermission)
    }

    fun onContactsPermission(granted: Boolean) {
        refreshSetup()
        if (granted) com.mtwabpin.kolahal.contacts.ContactsRepository.get(context).refresh()
        io { logs.add(if (granted) LogKind.INFO else LogKind.ERROR, if (granted) "Contacts allowed" else "Contacts permission denied") }
    }

    override fun pickScriptFolder() {
        _events.tryEmit(UiEvent.PickScriptFolder)
    }

    override fun requestFontImport() {
        _events.tryEmit(UiEvent.PickFont)
    }

    /**
     * Copies a picked .ttf/.otf to filesDir/fonts/custom.<ext> (replacing any earlier import),
     * checks that Android can load it, and switches `font` to custom.
     */
    fun importFont(uri: Uri?) {
        if (uri == null) return
        fileOp("font import") {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val named = name?.substringAfterLast('.', "")?.lowercase()
            // Some providers give no display name; then the file's own header decides.
            if (named != null && named != "ttf" && named != "otf") throw IllegalArgumentException("pick a .ttf or .otf file (got $name)")
            if (size > TermFonts.MAX_BYTES) throw IllegalArgumentException("font is larger than 10 MB")
            val dir = TermFonts.dir(context).apply { mkdirs() }
            val tmp = java.io.File(dir, "import.tmp")
            try {
                val input = resolver.openInputStream(uri) ?: throw java.io.IOException("cannot open the file")
                input.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > TermFonts.MAX_BYTES) throw IllegalArgumentException("font is larger than 10 MB")
                            out.write(buf, 0, n)
                        }
                    }
                }
                val magic = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                val ext = when {
                    magic.contentEquals(byteArrayOf(0x4F, 0x54, 0x54, 0x4F)) -> "otf" // "OTTO": CFF outlines
                    magic.contentEquals(byteArrayOf(0, 1, 0, 0)) || magic.contentEquals("true".toByteArray()) -> "ttf"
                    else -> throw IllegalArgumentException("${name ?: "the file"} is not a TrueType/OpenType font")
                }
                if (TermFonts.loadTypeface(tmp) == null) throw IllegalArgumentException("${name ?: "the file"} is not a font Android can load")
                listOf("custom.ttf", "custom.otf").forEach { java.io.File(dir, it).delete() }
                if (!tmp.renameTo(java.io.File(dir, "custom.$ext"))) throw java.io.IOException("could not save the font")
            } finally {
                tmp.delete()
            }
            fontErrorShown = false
            withContext(Dispatchers.Main) {
                TermFonts.revision++
                config.set(Keys.FONT, TermFonts.CUSTOM)
            }
            notice("Font = custom (${name ?: "imported font"})")
        }
    }

    @Volatile private var fontErrorShown = false

    /** The configured font could not be loaded (e.g. custom set but the file is gone); said once per run. */
    fun onFontUnavailable(font: String) {
        if (fontErrorShown) return
        fontErrorShown = true
        io {
            logs.add(
                LogKind.ERROR,
                "Font '$font' could not be loaded — using the system monospace · tap to import one",
                ctx = LogContext.of(LogContext.RUN to "font import"),
            )
        }
    }

    fun onScriptFolderPicked(uri: Uri?) {
        if (uri == null) return
        io {
            ScriptFolder(context).setFolder(uri)
            logs.add(LogKind.INFO, "Script folder set — 'script export' / 'script import' to sync")
        }
    }

    fun onNotificationPermission(granted: Boolean) {
        refreshSetup()
        io {
            logs.add(
                if (granted) LogKind.INFO else LogKind.ERROR,
                if (granted) "Notifications allowed" else "Notifications denied — tap to open notification settings",
                ctx = if (granted) LogContext.EMPTY else LogContext.of(LogContext.RUN to "notifications settings"),
            )
        }
    }

    override fun requestNotificationPermission() {
        _events.tryEmit(UiEvent.RequestNotificationPermission)
    }

    override fun clearScreen() {
        expandHeader()
        io { logs.clearScreen() }
    }

    override fun freshScreen() {
        expandHeader()
        io { logs.hideScreen() }
    }

    // ---- productivity (todo, notes, reminders, agenda) --------------------------------------

    private val _todoCount = MutableStateFlow(0)
    /** Open todos, for the header (header.todo). */
    val todoCount: StateFlow<Int> = _todoCount.asStateFlow()

    /** Suggestion data, refreshed with the log (every todo / note / remind command logs). */
    @Volatile private var todoCache: List<com.mtwabpin.kolahal.productivity.Todo> = emptyList()
    @Volatile private var noteCache: List<com.mtwabpin.kolahal.productivity.Note> = emptyList()
    @Volatile private var reminderCache: List<com.mtwabpin.kolahal.productivity.Reminder> = emptyList()
    @Volatile private var feedCache: List<com.mtwabpin.kolahal.feeds.FeedInfo> = emptyList()

    /** Contacts with numbers (empty without the permission); suggestions recompute when they load. */
    val contacts get() = com.mtwabpin.kolahal.contacts.ContactsRepository.get(context).contacts

    private fun refreshProductivity() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                todoCache = com.mtwabpin.kolahal.productivity.TodoStore.get(context).all()
                noteCache = com.mtwabpin.kolahal.productivity.NoteStore.get(context).all()
                reminderCache = com.mtwabpin.kolahal.productivity.ReminderStore.get(context).all()
                _todoCount.value = todoCache.count { !it.done }
            }
            runCatching { feedCache = com.mtwabpin.kolahal.feeds.FeedRepository.get(context).all() }
        }
    }

    /** Long-press entries for todo, note and reminder lines (destructive ones go to [danger]). */
    private fun productivityActions(ctx: LogContext, actions: MutableList<LogAction>, danger: MutableList<LogAction>) {
        ctx.values[LogContext.TODO]?.let { id ->
            val done = ctx.values[LogContext.TODO_DONE] == "1"
            actions += LogAction(if (done) "Mark not done" else "Mark done") { runCommand("todo ${if (done) "undo" else "done"} #$id") }
            actions += LogAction("Edit in prompt") {
                setText("todo edit #$id ${ctx.copy.orEmpty()}")
                _events.tryEmit(UiEvent.FocusPrompt)
            }
            danger += LogAction("Delete todo", destructive = true) { runCommand("todo rm #$id") }
        }
        ctx.values[LogContext.NOTE]?.let { id ->
            danger += LogAction("Delete note", destructive = true) { runCommand("notes rm #$id") }
        }
        ctx.values[LogContext.REMINDER]?.let { id ->
            danger += LogAction("Cancel reminder", destructive = true) { runCommand("remind rm #$id") }
        }
        if (ctx.values[LogContext.EVENT] != null) {
            actions += LogAction("Open event") { ProductivityCommands.eventIntent(ctx)?.let(::startActivity) }
        }
    }

    private fun productivitySuggestions(head: String, parts: List<String>): List<Suggestion> {
        val sub = parts.getOrNull(1).orEmpty()
        fun subs(list: List<String>) = if (parts.size == 2) {
            list.filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion(it, "$head $it ") }
        } else emptyList()
        // `todo done ` → the open items, by their list number (what `todo` shows).
        fun items(labels: List<Pair<Int, String>>) = if (parts.size == 3) {
            labels.filter { (n, text) -> parts[2].isEmpty() || n.toString().startsWith(parts[2]) || text.contains(parts[2], ignoreCase = true) }
                .take(12).map { (n, text) -> Suggestion.Completion("$n. $text", "$head $sub $n") }
        } else emptyList()
        val numberedTodos = todoCache.mapIndexed { i, t -> i + 1 to t }
        return when (head) {
            "todo" -> when (sub) {
                "done", "toggle" -> items(numberedTodos.filter { !it.second.done }.map { it.first to it.second.text })
                "undo", "undone" -> items(numberedTodos.filter { it.second.done }.map { it.first to it.second.text })
                "rm", "edit" -> items(numberedTodos.map { it.first to it.second.text })
                else -> subs(ProductivityCommands.TODO_SUBCOMMANDS)
            }
            "notes" -> if (sub == "rm") items(noteCache.mapIndexed { i, n -> i + 1 to n.text }) else subs(ProductivityCommands.NOTES_SUBCOMMANDS)
            "remind" -> when {
                sub == "rm" -> items(reminderCache.mapIndexed { i, r -> i + 1 to r.text })
                parts.size == 2 -> (listOf("rm") + listOf("10m", "30m", "1h", "tomorrow 9:00"))
                    .filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion(it, "remind $it ") }
                else -> emptyList()
            }
            "timer" -> if (parts.size == 2) listOf("5m", "10m", "15m", "25m", "1h")
                .filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion(it, "timer $it ") } else emptyList()
            "agenda" -> if (parts.size == 2) listOf("1", "7", "14")
                .filter { it.startsWith(sub) && it != sub }.map { Suggestion.Completion("$it days", "agenda $it") } else emptyList()
            else -> emptyList()
        }
    }

    override fun requestCalendarPermission() {
        _events.tryEmit(UiEvent.RequestCalendarPermission)
    }

    override fun requestCallLogPermission() {
        _events.tryEmit(UiEvent.RequestCallLogPermission)
    }

    fun onCallLogPermission(granted: Boolean) {
        refreshSetup()
        if (granted) com.mtwabpin.kolahal.calls.CallEvents.get(context).start()
        io {
            if (granted) {
                logs.add(LogKind.INFO, "Call log allowed — new calls appear here; tap to list recent ones", ctx = LogContext.of(LogContext.RUN to "calls"))
            } else {
                logs.add(LogKind.ERROR, "Call log permission denied — tap to open app settings", ctx = LogContext.of(LogContext.RUN to "calls --settings"))
            }
        }
    }

    fun onCalendarPermission(granted: Boolean) {
        refreshSetup()
        io {
            if (granted) {
                logs.add(LogKind.INFO, "Calendar allowed — tap to see your agenda", ctx = LogContext.of(LogContext.RUN to "agenda"))
            } else {
                logs.add(LogKind.ERROR, "Calendar permission denied — tap to open app settings", ctx = LogContext.of(LogContext.RUN to "agenda --settings"))
            }
        }
    }

    /** Pressing Home while already home: reset the prompt. */
    private val _homePresses = MutableStateFlow(0)
    /** Bumps on every Home press while already home; the widgets pager returns to the terminal. */
    val homePresses: StateFlow<Int> = _homePresses.asStateFlow()

    /** The open Settings category page; kept while a manager screen opened from it is on top. */
    val settingsCategory = mutableStateOf<String?>(null)

    fun onHomePressed() {
        settingsCategory.value = null
        _homePresses.value++
        _editing.value?.let { return }
        expandHeader()
        val fromOtherScreen = _screen.value != Screen.TERMINAL
        openScreen(Screen.TERMINAL) // Home always lands on the terminal and forgets the page stack
        escape()
        if (fromOtherScreen) {
            // The terminal isn't composed yet, so an event now would have no collector: it picks
            // these up when it appears (consumeArrival).
            arrival = Arrival(focus = config[Keys.FOCUS_ON_HOME])
        } else {
            _events.tryEmit(UiEvent.ScrollToBottom)
            if (config[Keys.FOCUS_ON_HOME]) _events.tryEmit(UiEvent.FocusPrompt)
        }
    }

    /** What the terminal should do once it is on screen again after Home from another page. */
    data class Arrival(val focus: Boolean)

    @Volatile private var arrival: Arrival? = null

    fun consumeArrival(): Arrival? = arrival.also { arrival = null }
}
