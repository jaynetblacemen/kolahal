package io.github.rscipher001.launcher.terminal

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import io.github.rscipher001.launcher.ui.HeaderTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rscipher001.launcher.apps.AppEntry
import io.github.rscipher001.launcher.apps.AppPrefs
import io.github.rscipher001.launcher.data.ConfigKey
import io.github.rscipher001.launcher.script.ScriptMeta
import io.github.rscipher001.launcher.script.Json
import io.github.rscipher001.launcher.script.ScriptFolder
import io.github.rscipher001.launcher.script.HostApi
import io.github.rscipher001.launcher.data.KvStore
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.apps.Profile
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogEntry
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptRepository
import io.github.rscipher001.launcher.script.ScriptScheduler
import io.github.rscipher001.launcher.unlock.UnlockCounter
import io.github.rscipher001.launcher.status.DeviceStatus
import io.github.rscipher001.launcher.status.Status
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.ui.KeyBarKey
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
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
}

/** Something the user can tap in the suggestion strip. */
sealed interface Suggestion {
    val label: String

    data class App(val app: AppEntry) : Suggestion {
        override val label get() = app.label
    }

    data class Completion(override val label: String, val replacement: String) : Suggestion
}

/** One entry of a log line's long-press menu. */
data class LogAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

class TerminalViewModel(app: Application) : AndroidViewModel(app), TerminalUi {
    private val context = app.applicationContext
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

    /** Bumps when aliases, hidden apps or labels change. */
    val appPrefsVersion: StateFlow<Int> get() = appPrefs.version

    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Header lines pinned by scripts with `status(text)`, keyed by script name. */
    val statuses: StateFlow<Map<String, String>> = _statuses.asStateFlow()

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _rows = MutableStateFlow<List<LogRow>>(emptyList())
    /** What the log list shows: entries (with dimming/gutter) and day separators. */
    val rows: StateFlow<List<LogRow>> = _rows.asStateFlow()

    val apps: StateFlow<List<AppEntry>> = appsRepo.apps
    val profiles: StateFlow<List<Profile>> = appsRepo.profiles
    val unlocksToday: StateFlow<Int> = unlockCounter.today
    val status: StateFlow<Status> = DeviceStatus.get(app).status
    val config: Config = Config.get(app)

    var input by mutableStateOf(TextFieldValue(""))
        private set

    private val _editing = MutableStateFlow<Script?>(null)
    val editing: StateFlow<Script?> = _editing.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private var history: List<String> = emptyList()
    private var historyIndex = -1
    private var draft = ""

    init {
        viewModelScope.launch {
            logs.version.collect { reload() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (logs.recent(1).isEmpty() && logs.commandHistory(1).isEmpty()) {
                logs.add(LogKind.INFO, "kolahal — a terminal launcher. type 'help' or start typing an app name.")
                logs.add(LogKind.INFO, "↑/↓ recall commands · tap a line to reuse/open it · long-press for actions")
                processor.setup()
            }
        }
    }

    private suspend fun reload() {
        val (list, hist, scriptList) = withContext(Dispatchers.IO) {
            Triple(logs.recent(), logs.commandHistory(), scripts.all())
        }
        scriptCache.value = scriptList
        _statuses.value = withContext(Dispatchers.IO) { KvStore(context, HostApi.STATUS_SCOPE).all() }
        _commandNames.value = withContext(Dispatchers.Default) {
            scriptList.filter { ScriptMeta.isCommand(it.code) }.map { it.name }
        }
        _entries.value = list
        _rows.value = withContext(Dispatchers.Default) { buildRows(list, seenUpTo) }
        history = hist
    }

    /** Snapshot of what was already seen when home was shown; newer script lines are unread. */
    private var seenUpTo = Long.MAX_VALUE

    fun onPause() {
        _entries.value.lastOrNull()?.let { logs.seenUpTo = it.id }
    }

    fun onResume() {
        seenUpTo = logs.seenUpTo
        viewModelScope.launch { reload() }
        unlockCounter.refresh()
        appsRepo.refresh()
    }

    // ---- prompt ---------------------------------------------------------------------------

    fun onInputChange(value: TextFieldValue) {
        // Cursor moves and IME composition updates must not abandon history navigation.
        if (value.text != input.text) historyIndex = -1
        input = value
    }

    fun submit() {
        val line = input.text
        input = TextFieldValue("")
        historyIndex = -1
        if (line.isBlank()) return
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
            is Suggestion.Completion -> setText(first.replacement)
            is Suggestion.App -> setText(first.app.label)
        }
    }

    fun onSuggestion(s: Suggestion) {
        when (s) {
            is Suggestion.App -> {
                setText("")
                io { processor.launch(s.app) }
            }
            is Suggestion.Completion -> setText(s.replacement)
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
            val appMatches = appsRepo.search(head).take(12).map { Suggestion.App(it) }
            return (cmds.take(3) + appMatches)
        }
        val arg = parts.drop(1).joinToString(" ")
        val last = parts.last()
        return when (head) {
            "open", "launch", "info", "uninstall", "hide", "unhide", "rename" ->
                appsRepo.search(arg.replace(Regex("\\s-\\w+"), "").trim(), includeHidden = head == "unhide")
                    .take(12).map {
                        if (head == "open" || head == "launch") Suggestion.App(it)
                        else Suggestion.Completion("${it.label} [${it.profile.kind.tag}]", "$head ${it.label}" + flagFor(it))
                    }
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
                        val subs = if (keyIndex == 1) listOf("set", "get", "reset").filter { it.startsWith(last) } else emptyList()
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
            "work", "private" -> listOf("on", "off", "status")
                .filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "$head $it") }
            else -> emptyList()
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
        when {
            ctx.values[LogContext.RUN] != null -> {
                val cmd = ctx.values.getValue(LogContext.RUN)
                _events.tryEmit(UiEvent.ScrollToBottom)
                io { processor.run(cmd, expand = false) }
            }
            entry.kind == LogKind.COMMAND || ctx.command != null -> {
                setText(ctx.command ?: entry.text)
                _events.tryEmit(UiEvent.FocusPrompt)
            }
            ctx.pkg != null -> appFor(ctx)?.let { app -> io { processor.launch(app) } }
            ctx.url != null -> openUrl(ctx.url!!)
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
        }
        appFor(ctx)?.let { app ->
            val (safe, destructive) = appActions(app).partition { !it.destructive }
            actions += safe
            danger += destructive
        }
        ctx.url?.let { url ->
            actions += LogAction("Open link") { openUrl(url) }
            actions += LogAction("Share link") { share(entry.text, url) }
            actions += LogAction("Copy link") { copy(url) }
        }
        ctx.script?.let { name ->
            scriptCache.value.firstOrNull { it.name == name }?.let { s ->
                actions += LogAction("Run $name now") { io { ScriptScheduler.runNow(context, s) } }
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
        apps.value.firstOrNull { it.pkg == app.pkg && it.profile.serial != app.profile.serial }?.let { twin ->
            add(LogAction("Launch in ${twin.profile.kind.label} profile") { io { processor.launch(twin) } })
        }
        add(LogAction("App info") { io { appsRepo.openAppInfo(app) } })
        add(LogAction("Copy package name") { copy(app.pkg) })
        add(LogAction(if (app.hidden) "Unhide" else "Hide from lists") {
            io {
                appPrefs.setHidden(app.key, !app.hidden)
                appsRepo.refresh()
            }
        })
        add(LogAction("Uninstall", destructive = true) { io { appsRepo.uninstall(app) } })
    }

    fun launchApp(app: AppEntry) = io { processor.launch(app) }

    /** A command line plus the output that followed it (up to the next command; timer output excluded). */
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
        runCatching { logs.add(LogKind.ERROR, "error: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(serialIo + errors) { block() }
    }

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun share(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("kolahal", text))
    }

    private fun startActivity(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { io { logs.add(LogKind.ERROR, "can't open: ${it.message}") } }
    }

    // ---- script editor --------------------------------------------------------------------

    fun saveScript(script: Script, code: String) {
        _editing.value = script.copy(code = code)
        io { scripts.saveCode(script.id, code) }
    }

    fun runScript(script: Script, code: String) {
        io {
            scripts.saveCode(script.id, code)
            scripts.byId(script.id)?.let { ScriptScheduler.runNow(context, it) }
            logs.add(LogKind.INFO, "running ${script.name}…", ctx = LogContext.of(LogContext.SCRIPT to script.name))
        }
    }

    fun onHeaderTap(target: HeaderTarget) {
        when (target) {
            HeaderTarget.CLOCK, HeaderTarget.ALARM -> startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            HeaderTarget.UNLOCKS -> runCommand("unlocks 7")
            HeaderTarget.BATTERY -> startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            HeaderTarget.NETWORK -> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    /** Resuming is harmless, pausing asks for a second tap (the logged line). */
    fun onProfileTap(profile: Profile) {
        val cmd = if (profile.kind == ProfileKind.PRIVATE) "private" else "work"
        if (profile.quiet) {
            runCommand("$cmd on")
        } else {
            io {
                logs.add(
                    LogKind.INFO,
                    "tap to pause the ${profile.kind.label} profile",
                    ctx = LogContext.of(LogContext.RUN to "$cmd off"),
                )
            }
        }
    }

    /** Runs a command as if typed (without alias expansion). */
    fun runCommand(cmd: String) {
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
                logs.add(LogKind.INFO, "running $name…", ctx = LogContext.of(LogContext.SCRIPT to name))
            }
        }
    }

    /** Pulls down the notification shade (hidden API used by most launchers; no-op if blocked). */
    @SuppressLint("WrongConstant")
    fun expandNotifications() {
        runCatching {
            val sbm = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }

    fun closeEditor() {
        _editing.value = null
    }

    // ---- TerminalUi -----------------------------------------------------------------------

    override fun openEditor(script: Script) {
        _editing.value = script
    }

    override fun pickScriptFolder() {
        _events.tryEmit(UiEvent.PickScriptFolder)
    }

    fun onScriptFolderPicked(uri: Uri?) {
        if (uri == null) return
        io {
            ScriptFolder(context).setFolder(uri)
            logs.add(LogKind.INFO, "script folder set — 'script export' / 'script import' to sync")
        }
    }

    fun onNotificationPermission(granted: Boolean) {
        io {
            logs.add(
                if (granted) LogKind.INFO else LogKind.ERROR,
                if (granted) "notifications allowed" else "notifications denied — tap to open notification settings",
                ctx = if (granted) LogContext.EMPTY else LogContext.of(LogContext.RUN to "notifications settings"),
            )
        }
    }

    override fun requestNotificationPermission() {
        _events.tryEmit(UiEvent.RequestNotificationPermission)
    }

    override fun clearScreen() {
        io { logs.clearScreen() }
    }

    /** Pressing Home while already home: reset the prompt. */
    fun onHomePressed() {
        _editing.value?.let { return }
        escape()
        _events.tryEmit(UiEvent.ScrollToBottom)
        if (config[Keys.FOCUS_ON_HOME]) _events.tryEmit(UiEvent.FocusPrompt)
    }
}
