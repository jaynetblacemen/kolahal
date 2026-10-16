package io.github.rscipher001.launcher.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.rscipher001.launcher.apps.AppEntry
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
data class LogAction(val label: String, val run: () -> Unit)

class TerminalViewModel(app: Application) : AndroidViewModel(app), TerminalUi {
    private val context = app.applicationContext
    private val logs = LogRepository.get(app)
    private val appsRepo = AppRepository.get(app)
    private val scripts = ScriptRepository.get(app)
    private val unlockCounter = UnlockCounter.get(app)
    private val processor = CommandProcessor(app, this)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

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
            }
        }
    }

    private suspend fun reload() {
        val (list, hist) = withContext(Dispatchers.IO) { logs.recent() to logs.commandHistory() }
        _entries.value = list
        history = hist
    }

    fun onResume() {
        unlockCounter.refresh()
        appsRepo.refresh()
    }

    // ---- prompt ---------------------------------------------------------------------------

    fun onInputChange(value: TextFieldValue) {
        input = value
        historyIndex = -1
    }

    fun submit() {
        val line = input.text
        input = TextFieldValue("")
        historyIndex = -1
        if (line.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { processor.run(line) }
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
        setText(history[historyIndex])
    }

    fun historyDown() {
        if (historyIndex == -1) return
        historyIndex--
        setText(if (historyIndex == -1) draft else history[historyIndex])
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
                viewModelScope.launch(Dispatchers.IO) { processor.launch(s.app) }
            }
            is Suggestion.Completion -> setText(s.replacement)
        }
    }

    private fun setText(text: String) {
        input = TextFieldValue(text, TextRange(text.length))
    }

    fun suggestions(text: String): List<Suggestion> {
        if (text.isBlank()) return emptyList()
        val parts = text.trimStart().split(' ')
        val head = parts.first().lowercase()
        if (parts.size == 1) {
            val cmds = CommandProcessor.COMMANDS
                .filter { it.name.startsWith(head) && it.name != head }
                .map { Suggestion.Completion(it.name, it.name + " ") }
            val appMatches = appsRepo.search(head).take(12).map { Suggestion.App(it) }
            return (cmds.take(3) + appMatches)
        }
        val arg = parts.drop(1).joinToString(" ")
        return when (head) {
            "open", "launch", "info", "uninstall" ->
                appsRepo.search(arg.replace(Regex("\\s-\\w+"), "").trim()).take(12).map {
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
                        scripts.all().filter { it.name.startsWith(prefix) && it.name != prefix }
                            .map { Suggestion.Completion(it.name, "script $sub ${it.name} ") }
                    } else emptyList()
                }
            }
            "work", "private" -> listOf("on", "off", "status")
                .filter { it.startsWith(arg) && it != arg }
                .map { Suggestion.Completion(it, "$head $it") }
            else -> emptyList()
        }
    }

    private fun flagFor(app: AppEntry) = when (app.profile.kind) {
        ProfileKind.WORK -> " -w"
        ProfileKind.PRIVATE -> " -s"
        ProfileKind.PERSONAL -> if (apps.value.any { it.pkg == app.pkg && it.profile.kind != ProfileKind.PERSONAL }) " -p" else ""
    }

    // ---- log line interaction -------------------------------------------------------------

    /** Single tap: the most likely action for this line. */
    fun onTap(entry: LogEntry) {
        val ctx = entry.ctx
        when {
            entry.kind == LogKind.COMMAND || ctx.command != null -> setText(ctx.command ?: entry.text)
            ctx.values[CommandProcessor.ACTION_KEY] == CommandProcessor.ACTION_USAGE_ACCESS ->
                startActivity(unlockCounter.usageAccessIntent())
            ctx.pkg != null -> appFor(ctx)?.let { app -> io { processor.launch(app) } }
            ctx.url != null -> openUrl(ctx.url!!)
        }
    }

    /** Long-press: every action that makes sense for the saved context. */
    fun actionsFor(entry: LogEntry): List<LogAction> {
        val ctx = entry.ctx
        val actions = ArrayList<LogAction>()
        val command = ctx.command ?: entry.text.takeIf { entry.kind == LogKind.COMMAND }
        if (command != null) {
            actions += LogAction("Run again") { setText(command); submit() }
            actions += LogAction("Edit in prompt") { setText(command) }
        }
        appFor(ctx)?.let { app ->
            val tag = "[${app.profile.kind.tag}]"
            actions += LogAction("Launch ${app.label} $tag") { io { processor.launch(app) } }
            actions += LogAction("App info") { appsRepo.openAppInfo(app) }
            actions += LogAction("Uninstall") { appsRepo.uninstall(app) }
            actions += LogAction("Copy package name") { copy(app.pkg) }
            val twin = apps.value.firstOrNull { it.pkg == app.pkg && it.profile.serial != app.profile.serial }
            if (twin != null) {
                actions += LogAction("Launch in ${twin.profile.kind.label} profile") { io { processor.launch(twin) } }
            }
        }
        ctx.url?.let { url ->
            actions += LogAction("Open link") { openUrl(url) }
            actions += LogAction("Share link") { share(entry.text, url) }
            actions += LogAction("Copy link") { copy(url) }
        }
        ctx.script?.let { name ->
            scripts.byName(name)?.let { s ->
                actions += LogAction("Run $name now") { ScriptScheduler.runNow(context, s) }
                actions += LogAction("Edit $name.${s.lang.ext}") { openEditor(s) }
                actions += LogAction(if (s.enabled) "Disable $name" else "Enable $name") {
                    scripts.setEnabled(s.id, !s.enabled)
                    scripts.byId(s.id)?.let { ScriptScheduler.sync(context, it) }
                }
                actions += LogAction(if (s.notify) "Mute notifications" else "Notify on new items") {
                    scripts.setNotify(s.id, !s.notify)
                    if (!s.notify) _events.tryEmit(UiEvent.RequestNotificationPermission)
                }
            }
        }
        actions += LogAction("Copy text") { copy(ctx.copy ?: entry.text) }
        actions += LogAction("Delete line") { io { logs.delete(entry.id) } }
        return actions
    }

    private fun appFor(ctx: LogContext): AppEntry? =
        ctx.pkg?.let { appsRepo.find(it, ctx.cls, ctx.userSerial) ?: appsRepo.find(it, null, ctx.userSerial) }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
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
        io { scripts.saveCode(script.id, code) }
    }

    fun runScript(script: Script, code: String) {
        io {
            scripts.saveCode(script.id, code)
            scripts.byId(script.id)?.let { ScriptScheduler.runNow(context, it) }
            logs.add(LogKind.INFO, "running ${script.name}…", ctx = LogContext.of(LogContext.SCRIPT to script.name))
        }
    }

    fun closeEditor() {
        _editing.value = null
    }

    // ---- TerminalUi -----------------------------------------------------------------------

    override fun openEditor(script: Script) {
        _editing.value = script
    }

    override fun requestNotificationPermission() {
        _events.tryEmit(UiEvent.RequestNotificationPermission)
    }

    override fun clearScreen() {
        logs.clearScreen()
    }

    /** Pressing Home while already home: reset the prompt. */
    fun onHomePressed() {
        _editing.value?.let { return }
        escape()
        _events.tryEmit(UiEvent.ScrollToBottom)
        if (config[Keys.FOCUS_ON_HOME]) _events.tryEmit(UiEvent.FocusPrompt)
    }
}
