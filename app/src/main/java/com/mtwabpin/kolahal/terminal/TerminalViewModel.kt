package com.mtwabpin.kolahal.terminal

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.edit
import com.mtwabpin.kolahal.R
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
import com.mtwabpin.kolahal.script.ScriptAccess
import com.mtwabpin.kolahal.script.ScriptCatalog
import com.mtwabpin.kolahal.script.ScriptMeta
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE = 400
private const val KEY_PENDING_EXPORT = "pending_export"
private const val MAX_PAGE = 5000

/** The whole page is read again at least this often, catching trims by other processes. */
private const val FULL_RELOAD_MS = 60_000L

@OptIn(kotlinx.coroutines.FlowPreview::class)
class TerminalViewModel(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app), TerminalUi {
    private val context: Context get() = getApplication()

    /** Reload requests from anywhere (log changes, paging, resume) funnel into one latest-wins pipeline. */
    private val reloadRequests = MutableStateFlow(0L)
    private fun requestReload() = reloadRequests.update { it + 1 }

    /** Serials of private spaces that are locked. */
    private fun lockedPrivate(profiles: List<Profile>): Set<Long> =
        profiles.filter { it.kind == ProfileKind.PRIVATE && it.quiet }.mapTo(HashSet()) { it.serial }
    private val logs = LogRepository.get(app)
    private val appsRepo = AppRepository.get(app)
    private val scripts = ScriptRepository.get(app)
    private val unlockCounter = UnlockCounter.get(app)
    private val processor = CommandProcessor(app, this)
    private val appPrefs = AppPrefs.get(app)
    private val completer = Completer(app, object : Completer.Data {
        override val history get() = this@TerminalViewModel.history
        override val scripts get() = scriptCache.value
        override val commandNames get() = _commandNames.value
        override val scriptUsages get() = this@TerminalViewModel.scriptUsages
        override val statusNames get() = _statuses.value.keys
        override val feeds get() = feedCache
        override val todos get() = todoCache
        override val notes get() = noteCache
        override val reminders get() = reminderCache
    })

    /** Scripts, refreshed off the main thread whenever the log changes (every script action logs). */
    private val scriptCache = MutableStateFlow<List<Script>>(emptyList())

    private val _commandNames = MutableStateFlow<List<String>>(emptyList())
    /** Names of `@command` scripts; part of the suggestion inputs. */
    val commandNames: StateFlow<List<String>> = _commandNames.asStateFlow()

    /** Usage lines of the command scripts that have a known one (see [ScriptMeta.usage]), set with [commandNames]. */
    @Volatile private var scriptUsages: Map<String, String> = emptyMap()

    private fun setCommandScripts(list: List<Script>) {
        val commands = list.filter { it.enabled && ScriptMeta.isCommand(it.code) }
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
        // Otherwise the screen underneath shows its last message again when the editor closes.
        _notice.value = null
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
            // Locking or unlocking the private space shows or hides its lines.
            launch { appsRepo.profiles.map(::lockedPrivate).distinctUntilChanged().drop(1).collect { requestReload() } }
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
    }

    /** How many log lines are loaded; grows as the user scrolls up (older lines load on demand). */
    @Volatile private var pageLimit = PAGE

    fun loadOlder() {
        if (_entries.value.size < pageLimit || pageLimit >= MAX_PAGE) return
        pageLimit += PAGE
        requestReload()
    }

    /** Every visible line loaded, before the private-space filter; what the next reload builds on. */
    private var loaded: List<LogEntry> = emptyList()
    private var loadedStructure = -1L
    private var loadedLimit = 0
    private var fullAt = 0L
    private var scriptsPrint: String? = null

    /**
     * Brings the log up to date. Usually only the lines after the newest one shown are read; the
     * whole page is read again after a delete, hide or clear, when the page size changes, and once
     * a minute (another process may have trimmed old lines). Scripts, history and the todo and
     * feed caches are read only when something they show could have changed.
     */
    private suspend fun reload() {
        val now = System.currentTimeMillis()
        val structure = logs.structure
        val newest = loaded.lastOrNull()?.id
        val incremental = newest != null && structure == loadedStructure && pageLimit == loadedLimit && now - fullAt < FULL_RELOAD_MS
        val fresh = withContext(Dispatchers.IO) { if (incremental) logs.recent(pageLimit, afterId = newest) else logs.recent(pageLimit) }
        val all = if (incremental) (loaded + fresh).takeLast(pageLimit) else fresh
        if (!incremental) {
            loadedStructure = structure
            loadedLimit = pageLimit
            fullAt = now
        }
        loaded = all
        val print = withContext(Dispatchers.IO) { scripts.fingerprint() }
        if (print != scriptsPrint) {
            val scriptList = withContext(Dispatchers.IO) { scripts.all() }
            scriptCache.value = scriptList
            withContext(Dispatchers.Default) { setCommandScripts(scriptList) }
            scriptsPrint = print
        }
        _statuses.value = withContext(Dispatchers.IO) { KvStore(context, HostApi.STATUS_SCOPE).all() }
        if (!incremental) _hiddenLines.value = withContext(Dispatchers.IO) { logs.hiddenCount() }
        if (!incremental || fresh.any { it.kind == LogKind.COMMAND }) history = withContext(Dispatchers.IO) { logs.commandHistory() }
        if (!incremental || fresh.any(::touchesProductivity)) refreshProductivity()
        // A locked private space hides its apps; its lines (launches, notifications) hide with it.
        val locked = lockedPrivate(appsRepo.profiles.value)
        val list = if (locked.isEmpty()) all else all.filterNot { it.ctx.userSerial in locked }
        _entries.value = list
        _rows.value = withContext(Dispatchers.Default) { buildRows(list, seenUpTo, unreadUntil) }
    }

    /** A line after which the todo, note, reminder or feed caches may be out of date. */
    private fun touchesProductivity(e: LogEntry): Boolean {
        val k = e.ctx.values.keys
        return LogContext.TODO in k || LogContext.NOTE in k || LogContext.REMINDER in k ||
            com.mtwabpin.kolahal.feeds.FeedWorker.CTX_FEED in k || com.mtwabpin.kolahal.feeds.FeedWorker.CTX_ITEM in k ||
            e.source == "remind"
    }

    /** Back at the newest lines after scrolling up: the page shrinks again, so each reload stays small. */
    fun atNewest() {
        if (pageLimit <= PAGE) return
        pageLimit = PAGE
        requestReload()
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
        _peek.value = null
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
        io { logs.trimIfDue(config[Keys.LOG_KEEP_DAYS]) }
        // A crash in the script process doesn't restart home: its report is announced here.
        io { runCatching { com.mtwabpin.kolahal.CrashReports.announce(context) } }
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

    /** launch.instant: launch / run as soon as the input is unambiguous. */
    private fun instantRun(text: String) {
        val target = InstantRun.target(
            text, config[Keys.LAUNCH_INSTANT], config[Keys.LAUNCH_INSTANT_MIN],
            rankedApps = { appsRepo.searchRanked(it) }, appLabel = { it.label },
            commands = scriptUsages + CommandProcessor.COMMAND_USAGES, aliases = appPrefs.aliases(), scriptCommands = _commandNames.value,
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
        (InstantRun.canRun(line, scriptUsages + CommandProcessor.COMMAND_USAGES, appPrefs.aliases(), _commandNames.value) { name ->
            appsRepo.searchRanked(name).firstOrNull()?.second == 0
        } || InstantRun.wholeLine(line, scriptUsages + CommandProcessor.COMMAND_USAGES, appPrefs.aliases()))

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

    fun suggestions(text: String): List<Suggestion> = completer.suggestions(text)

    // ---- log line interaction -------------------------------------------------------------

    private val lineActions = LogLineActions(context, processor, object : LogLineActions.Host {
        override val entries get() = _entries.value
        override val scripts get() = scriptCache.value
        override fun io(block: suspend () -> Unit) = this@TerminalViewModel.io(block)
        override fun setText(text: String) = this@TerminalViewModel.setText(text)
        override fun submit() = this@TerminalViewModel.submit()
        override fun emit(event: UiEvent) { _events.tryEmit(event) }
        override fun collapseHeader() = this@TerminalViewModel.collapseHeader()
        override fun runsOnTap(line: String) = this@TerminalViewModel.runsOnTap(line)
        override fun runCommand(cmd: String) = this@TerminalViewModel.runCommand(cmd)
        override fun openEditorAt(script: Script, line: Int?) = this@TerminalViewModel.openEditorAt(script, line)
        override fun startActivity(intent: Intent) = this@TerminalViewModel.startActivity(intent)
    })

    /** Single tap: the most likely action for this line. */
    fun onTap(entry: LogEntry) = lineActions.onTap(entry)

    /** Long-press: every action that makes sense for the saved context. Destructive ones come last. */
    fun actionsFor(entry: LogEntry): List<LogAction> = lineActions.actionsFor(entry)

    /** Actions for one app (log lines and grid chips). */
    fun appActions(app: AppEntry): List<LogAction> = lineActions.appActions(app)

    /** Apps referenced by a grid line. */
    fun appsOf(entry: LogEntry): List<AppEntry> = lineActions.appsOf(entry)

    fun launchApp(app: AppEntry) = io { processor.launch(app) }

    /** Keys of favourite apps (listed first, with ★, in the app selector). */
    fun pinnedKeys(): Set<String> = appPrefs.pinned().toSet()

    /** One serial worker: commands and actions run in order, and failures are logged, never crash home. */
    private val serialIo = Dispatchers.IO.limitedParallelism(1)
    private val errors = CoroutineExceptionHandler { _, e ->
        runCatching { logs.add(LogKind.ERROR, "Error: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(serialIo + errors) { block() }
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
    fun swipeCopy(entry: LogEntry) = lineActions.swipeCopy(entry)

    private fun startActivity(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { io { logs.add(LogKind.ERROR, "Can't open: ${it.message}") } }
    }

    // ---- script editor --------------------------------------------------------------------

    /** Saves the editor's text; [onDone] gets null once it is written, or what went wrong. */
    fun saveScript(script: Script, code: String, onDone: (Throwable?) -> Unit = {}) {
        _editing.value = script.copy(code = code)
        viewModelScope.launch {
            val failure = withContext(serialIo) {
                runCatching {
                    scripts.saveCode(script.id, code, byUser = true)
                    refreshScriptCaches()
                }.exceptionOrNull()
            }
            onDone(failure)
        }
    }

    fun runScript(script: Script, code: String) {
        io {
            scripts.saveCode(script.id, code, byUser = true)
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
     * Quick actions (tap `$` on the prompt, or the `quick` gesture): everything the header
     * offers, in thumb reach at the bottom of the screen.
     */
    fun openQuick() { _events.tryEmit(UiEvent.ShowQuick) }

    fun quickActions(): List<LogAction> = buildList {
        // The header's icons where it has them (battery and network as it shows them now).
        fun icon(id: Int) = ActionIcon.Drawable(id)
        val s = status.value
        add(LogAction("Settings", icon = icon(R.drawable.ic_settings)) { openScreen(Screen.SETTINGS) })
        add(LogAction("Notifications", icon = icon(R.drawable.ic_notifications)) { expandNotifications() })
        add(LogAction("Alarms & clock", icon = icon(R.drawable.ic_alarm)) { onHeaderTap(HeaderTarget.ALARM) })
        add(LogAction("Battery", icon = s.batteryPct?.let { ActionIcon.Battery(it, s.charging) }) { onHeaderTap(HeaderTarget.BATTERY) })
        val net = when (s.network) {
            "wifi" -> icon(R.drawable.ic_wifi)
            "offline" -> ActionIcon.Drawable(R.drawable.ic_cloud_off, alert = true)
            else -> icon(R.drawable.ic_signal)
        }
        add(LogAction("Network", icon = net) { onHeaderTap(HeaderTarget.NETWORK) })
        add(LogAction("Unlocks this week", icon = icon(R.drawable.ic_lock_open)) { onHeaderTap(HeaderTarget.UNLOCKS) })
        profiles.value.filter { it.kind == ProfileKind.WORK || it.kind == ProfileKind.PRIVATE }.forEach { p ->
            val cmd = if (p.kind == ProfileKind.PRIVATE) "private" else "work"
            // As in the header: the profile's colour while it runs, dim while paused.
            val res = if (p.kind == ProfileKind.WORK) R.drawable.ic_work else R.drawable.ic_lock
            add(
                LogAction(
                    if (p.quiet) "Resume ${p.kind.label} profile" else "Pause ${p.kind.label} profile",
                    icon = ActionIcon.Drawable(res, profile = p.kind.takeUnless { p.quiet }),
                ) {
                    runCommand("$cmd ${if (p.quiet) "on" else "off"}")
                },
            )
        }
        add(LogAction("Apps", icon = icon(R.drawable.ic_apps)) { runCommand("apps") })
        add(LogAction("Widgets", icon = icon(R.drawable.ic_widgets)) { openScreen(Screen.WIDGETS) })
        add(LogAction("Feeds", icon = icon(R.drawable.ic_rss)) { openScreen(Screen.FEEDS) })
        add(LogAction("Scripts", icon = icon(R.drawable.ic_code)) { openScreen(Screen.SCRIPTS) })
        if (com.mtwabpin.kolahal.BuildConfig.LOCK_SERVICE) {
            add(LogAction("Lock screen", icon = icon(R.drawable.ic_power)) { if (!com.mtwabpin.kolahal.gesture.LockService.lock()) runCommand("lock") })
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

    /** Closes the editor; [unsaved] (its text, when it had changes) is saved first and reported where you land. */
    fun closeEditor(unsaved: String? = null) {
        val script = _editing.value
        _editLine.value = null
        _editing.value = null
        if (script != null && unsaved != null) io {
            scripts.saveCode(script.id, unsaved, byUser = true)
            refreshScriptCaches()
            notice("Saved ${script.name}.${script.lang.ext}")
        }
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
        _peek.value = null
        synchronized(backStack) {
            val current = _screen.value
            if (screen == Screen.TERMINAL) backStack.clear()
            else if (current != Screen.TERMINAL && current != screen) backStack.addLast(current)
            _screen.value = screen
        }
        if (screen == Screen.SCRIPTS || screen == Screen.GET_SCRIPTS) refreshScripts()
        if (screen == Screen.GET_SCRIPTS) loadSharedScripts()
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

    /** Switches [s] on with the user's answers for what it asks to use ([choices]: item to allowed). */
    fun enableScript(s: Script, choices: Map<String, Boolean>) = updateScript(s) {
        choices.forEach { (item, on) -> scripts.setAccess(s.id, item, on) }
        scripts.setEnabled(s.id, true)
    }

    /** One access switch; switching the header line off takes the line away now. */
    fun setScriptAccess(s: Script, item: String, on: Boolean) = updateScript(s) {
        scripts.setAccess(s.id, item, on)
        if (item == ScriptAccess.HEADER && !on) {
            KvStore(context, HostApi.STATUS_SCOPE).set(s.name, null)
            logs.onStatusChanged()
        }
    }

    /** Long-press on a script's header line: run it, hide its line, or edit it. */
    fun statusActions(name: String): List<LogAction> {
        val s = scripts.byName(name) ?: return listOf(
            LogAction("Clear this line") { io { KvStore(context, HostApi.STATUS_SCOPE).set(name, null); logs.onStatusChanged() } },
        )
        return listOf(
            LogAction("Run $name now") { io { ScriptScheduler.runNow(context, s) } },
            LogAction("Hide from header") {
                setScriptAccess(s, ScriptAccess.HEADER, false)
                io { logs.add(LogKind.INFO, "$name: header line off — 'script allow $name header' brings it back", ctx = LogContext.of(LogContext.SCRIPT to name)) }
            },
            LogAction("Edit $name.${s.lang.ext}") { openEditor(s) },
        )
    }
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
        if (clean in CommandProcessor.COMMAND_NAMES) {
            _notice.value = "'$clean' is a built-in command, pick another name"
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
                    if (r.created.isNotEmpty() || r.updated.isNotEmpty()) add("switched off until you turn them on (script on <name>)")
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
        fileOp("backup") { notice("Backup saved: settings, apps, ${Backup(context).export(uri)} scripts, todos, notes, reminders, feeds and the log (without notifications and calls)") }
    }

    fun importBackup(uri: Uri?) {
        if (uri == null) return
        fileOp("import") {
            val r = Backup(context).import(uri)
            appsRepo.refresh()
            refreshScriptCaches()
            fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
            notice(
                "Imported ${count(r.settings, "setting")}, ${count(r.aliases, "alias", "aliases")}, ${count(r.scripts, "script")}" +
                    (if (r.full) ", ${r.restored} saved items (todos, notes, reminders, feeds, log…)" else "") +
                    (if (r.disabled == 1) " (1 new script is off — switch it on in Scripts)" else "") +
                    (if (r.disabled > 1) " (${r.disabled} new scripts are off — switch them on in Scripts)" else "") +
                    (if (r.skipped.isNotEmpty()) " · skipped ${r.skipped.joinToString()}" else "") +
                    (if (r.review.isNotEmpty()) " · check these settings from the file: ${r.review.joinToString()}" else "") +
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
            ScriptAccess.askForNew(context, s)
            refreshScriptCaches()
            notice("Installed ${s.name}.${s.lang.ext}" + if (entry.command) " — run it as '${s.name}'" else "")
        }
    }

    // ---- Get more scripts (kolahal-scripts) ------------------------------------------------

    val scriptCatalog by lazy { ScriptCatalog(context) }

    /** The shared scripts, whether they are loading, and why they couldn't be read. */
    data class SharedScripts(val entries: List<ScriptCatalog.Entry> = emptyList(), val loading: Boolean = false, val error: String? = null)

    private val _shared = MutableStateFlow(SharedScripts())
    val sharedScripts: StateFlow<SharedScripts> = _shared.asStateFlow()

    /** Reads the list: the copy kept for an hour, or GitHub when [refresh]. A failure keeps what was there. */
    fun loadSharedScripts(refresh: Boolean = false) {
        _shared.value = _shared.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            _shared.value = runCatching { SharedScripts(scriptCatalog.entries(refresh)) }.getOrElse {
                SharedScripts(_shared.value.entries.ifEmpty { scriptCatalog.cached().orEmpty() }, error = it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** Downloaded code by SHA-256: read once for a script's sheet, then installed from it. */
    private val sharedCode = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun sharedScriptCode(e: ScriptCatalog.Entry): String =
        withContext(Dispatchers.IO) { sharedCode.getOrPut(e.sha256) { scriptCatalog.download(e) } }

    /** Installs or updates [e]; [allow] is the answer for what it declares, [replace] overwrites your edits. */
    fun installSharedScript(e: ScriptCatalog.Entry, allow: Map<String, Boolean> = emptyMap(), replace: Boolean = false) {
        fileOp("install") {
            val code = sharedScriptCode(e)
            val update = scripts.byName(e.name) != null
            val s = scriptCatalog.install(e, code, allow, replace)
            refreshScriptCaches()
            notice(if (update) "Updated ${s.name}.${s.lang.ext} to ${e.version}" else "Installed ${s.name}.${s.lang.ext}" + if (e.command) " — run it as '${s.name}'" else "")
        }
    }

    /** Scripts list + command names, refreshed after edits/imports (not only when the log changes). */
    private suspend fun refreshScriptCaches() {
        val list = withContext(Dispatchers.IO) { scripts.all() }
        scriptCache.value = list
        setCommandScripts(list)
    }

    fun setConfig(key: ConfigKey<*>, raw: String): Boolean = config.set(key, raw).also { ok ->
        if (ok && key == Keys.FEEDS_WIFI) io { com.mtwabpin.kolahal.feeds.FeedScheduler.sync(context) }
    }

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
            val name = TermFonts.importFrom(context, uri)
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

    override fun requestCalendarPermission() {
        _events.tryEmit(UiEvent.RequestCalendarPermission)
    }

    override fun requestCallLogPermission() {
        _events.tryEmit(UiEvent.RequestCallLogPermission)
    }

    override fun requestLocalNetworkPermission() {
        _events.tryEmit(UiEvent.RequestLocalNetworkPermission)
    }

    fun onLocalNetworkPermission(granted: Boolean) {
        refreshSetup()
        io {
            if (granted) {
                logs.add(LogKind.INFO, "Local network allowed — scripts with @uses lan and feeds on your network can reach it")
            } else {
                logs.add(LogKind.ERROR, "Local network permission denied — tap to open app settings", ctx = LogContext.of(LogContext.RUN to "local-network --settings"))
            }
        }
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

    /**
     * Settings showing home through it, to see a look setting while changing it: while a slider is
     * dragged ([key] stays visible) or the page's eye button is held ([key] null). [typing]: show home
     * as if typing, for the key bar, prompt and suggestions.
     */
    data class Peek(val key: String?, val typing: Boolean)

    private val _peek = MutableStateFlow<Peek?>(null)
    val peek: StateFlow<Peek?> = _peek.asStateFlow()

    fun peek(value: Peek?) {
        _peek.value = value
    }

    /** The open Settings category page; kept while a manager screen opened from it is on top. */
    val settingsCategory = mutableStateOf<String?>(null)

    /** The custom theme the theme builder edits; null while it makes a new one. */
    val themeBuilderName = mutableStateOf<String?>(null)

    fun openThemeBuilder(name: String?) {
        themeBuilderName.value = name
        openScreen(Screen.THEME_BUILDER)
    }

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

    // Keep this last in the class. The first-run check runs on another thread straight away and
    // opens the setup screen, which reads fields declared above; started from an earlier init it
    // could find them still unset and crash the first start (seen as a crash soon after install).
    init {
        viewModelScope.launch(Dispatchers.IO) {
            // A persistent flag, so `clear all` doesn't look like a first run.
            val prefs = context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
            val firstRun = !prefs.getBoolean("intro_shown", false) && logs.recent(1).isEmpty()
            prefs.edit { putBoolean("intro_shown", true) }
            if (firstRun) {
                logs.add(LogKind.INFO, "Kolahal — a terminal launcher. type 'help' or start typing an app name.")
                logs.add(LogKind.INFO, "↑/↓ recall commands · tap a line to reuse/open it · long-press for actions")
                openScreen(Screen.SETUP) // first run: show the setup checklist
            }
        }
    }
}
