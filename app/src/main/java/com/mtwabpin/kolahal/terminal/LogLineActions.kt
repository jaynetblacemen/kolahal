package com.mtwabpin.kolahal.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.notifications.NotifPrefs
import com.mtwabpin.kolahal.notifications.NotificationActions
import com.mtwabpin.kolahal.script.Json
import com.mtwabpin.kolahal.script.ScriptScheduler
import com.mtwabpin.kolahal.script.ScriptAccess
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What a tap or a long-press on a log line does; the line's saved [LogContext] decides. The view
 * model owns the prompt and the command queue and lends them through [Host].
 */
class LogLineActions(private val context: Context, private val processor: CommandProcessor, private val host: Host) {
    /** What the actions need from the view model. */
    interface Host {
        /** The log as loaded, oldest first (a command's block is the lines after it). */
        val entries: List<LogEntry>
        val scripts: List<Script>
        /** Runs [block] on the serial command queue. */
        fun io(block: suspend () -> Unit)
        fun setText(text: String)
        fun submit()
        fun emit(event: UiEvent)
        fun collapseHeader()
        fun runsOnTap(line: String): Boolean
        fun runCommand(cmd: String)
        fun openEditorAt(script: Script, line: Int?)
        fun startActivity(intent: Intent)
    }

    private val logs = LogRepository.get(context)
    private val appsRepo = AppRepository.get(context)
    private val appPrefs = AppPrefs.get(context)
    private val scripts = ScriptRepository.get(context)

    /** Single tap: the most likely action for this line. */
    fun onTap(entry: LogEntry) {
        val ctx = entry.ctx
        val errorLine = ctx.values[LogContext.LINE]?.toIntOrNull()
        when {
            errorLine != null && ctx.script != null -> {
                val name = ctx.script!!
                host.io { scripts.byName(name)?.let { host.openEditorAt(it, errorLine) } }
            }
            ctx.values[LogContext.NOTE] != null -> copy(ctx.copy ?: entry.text)
            ctx.values[LogContext.EVENT] != null -> ProductivityCommands.eventIntent(ctx)?.let(host::startActivity)
            // Cancelling takes Enter (or the long-press "Cancel reminder").
            ctx.values[LogContext.REMINDER] != null -> {
                host.setText("remind rm #${ctx.values.getValue(LogContext.REMINDER)}")
                host.emit(UiEvent.FocusPrompt)
            }
            ctx.values[LogContext.RUN] != null -> {
                val cmd = ctx.values.getValue(LogContext.RUN)
                host.emit(UiEvent.ScrollToBottom)
                host.io { processor.run(cmd, expand = false) }
            }
            entry.kind == LogKind.COMMAND || ctx.command != null -> {
                val line = ctx.command ?: entry.text
                if (host.runsOnTap(line)) {
                    host.collapseHeader()
                    host.emit(UiEvent.ScrollToBottom)
                    host.io { processor.run(line) }
                } else {
                    host.setText(line)
                    host.emit(UiEvent.FocusPrompt)
                }
            }
            ctx.values[NOTIF] != null -> {
                // The notification's own tap action if it's still live, otherwise just the app.
                if (!NotificationActions.open(context, ctx.values.getValue(NOTIF))) appFor(ctx)?.let { app -> host.io { processor.launch(app) } }
            }
            ctx.values[LogContext.SHORTCUT] != null && appFor(ctx) != null -> {
                val app = appFor(ctx)!!
                val id = ctx.values.getValue(LogContext.SHORTCUT)
                host.io { runCatching { appsRepo.startShortcut(app, id) }.onFailure { logs.add(LogKind.ERROR, "Shortcut unavailable: ${it.message}") } }
            }
            ctx.values[LogContext.PHONE] != null -> host.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", ctx.values.getValue(LogContext.PHONE), null)))
            ctx.pkg != null -> appFor(ctx)?.let { app -> host.io { processor.launch(app) } }
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
            actions += LogAction("Run again") { host.setText(command); host.submit() }
            actions += LogAction("Edit in prompt") { host.setText(command); host.emit(UiEvent.FocusPrompt) }
        } else if (entry.kind != LogKind.SCRIPT) {
            // Plain output: offer the command that printed it.
            producingCommand(entry)?.let { cmd ->
                actions += LogAction("Run '${cmd.text}' again") { host.setText(cmd.text); host.submit() }
                actions += LogAction("Edit '${cmd.text}' in prompt") { host.setText(cmd.text); host.emit(UiEvent.FocusPrompt) }
            }
        }
        appFor(ctx)?.let { app ->
            val (safe, destructive) = appActions(app).partition { !it.destructive }
            actions += safe
            danger += destructive
        }
        ctx.values[NOTIF]?.let { key ->
            actions += LogAction("Open notification") { if (!NotificationActions.open(context, key)) appFor(ctx)?.let { app -> host.io { processor.launch(app) } } }
            ctx.pkg?.let { pkg ->
                actions += LogAction("Mute this app's notifications") { host.io { NotifPrefs.get(context).mute(pkg); logs.add(LogKind.INFO, "Muted notifications from ${appFor(ctx)?.label ?: pkg}") } }
            }
            danger += LogAction("Dismiss notification", destructive = true) { NotificationActions.dismiss(key) }
        }
        ctx.values[LogContext.PHONE]?.let { number ->
            actions += LogAction("Call $number") { host.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
            actions += LogAction("Send a message") { host.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))) }
            ctx.values[LogContext.CONTACT]?.let { uri ->
                actions += LogAction("Open contact") { host.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) }
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
            host.scripts.firstOrNull { it.name == name }?.let { s ->
                actions += LogAction("Run $name now") { host.io { ScriptScheduler.runNow(context, s) } }
                ctx.values[LogContext.LINE]?.toIntOrNull()?.let { line ->
                    actions += LogAction("Edit $name at line $line") { host.io { scripts.byId(s.id)?.let { host.openEditorAt(it, line) } } }
                }
                actions += LogAction("Edit $name.${s.lang.ext}") { host.io { scripts.byId(s.id)?.let { host.openEditorAt(it, null) } } }
                actions += LogAction(if (s.enabled) "Disable $name" else "Enable $name") {
                    host.io {
                        scripts.setEnabled(s.id, !s.enabled)
                        scripts.byId(s.id)?.let {
                            ScriptScheduler.sync(context, it)
                            if (it.enabled) ScriptAccess.askForNew(context, it)
                        }
                        logs.add(LogKind.INFO, "$name: ${if (s.enabled) "disabled" else "enabled"}", ctx = LogContext.of(LogContext.SCRIPT to name))
                    }
                }
                actions += LogAction(if (s.notify) "Mute notifications" else "Notify on new items") {
                    host.io {
                        scripts.setNotify(s.id, !s.notify)
                        logs.add(LogKind.INFO, "$name: notifications ${if (s.notify) "off" else "on"}", ctx = LogContext.of(LogContext.SCRIPT to name))
                    }
                    if (!s.notify) host.emit(UiEvent.RequestNotificationPermission)
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
                host.io { ids.forEach(logs::delete) }
            }
        }
        actions += LogAction("Copy text") { copy(ctx.copy ?: entry.text, sensitive(entry)) }
        danger += LogAction("Delete line", destructive = true) { host.io { logs.delete(entry.id) } }
        return actions + danger
    }

    /** Actions for one app (log lines and grid chips). */
    fun appActions(app: AppEntry): List<LogAction> = buildList {
        add(LogAction("Launch ${app.label} [${app.profile.kind.tag}]") { host.io { processor.launch(app) } })
        // The app's own shortcuts (e.g. "New message"), when Kolahal is the default launcher.
        appsRepo.shortcuts(app).take(5).forEach { s ->
            add(LogAction("${s.shortLabel}", icon = ActionIcon.Drawable(R.drawable.ic_chevron_right)) { host.io { runCatching { appsRepo.startShortcut(app, s.id) } } })
        }
        appsRepo.apps.value.firstOrNull { it.pkg == app.pkg && it.profile.serial != app.profile.serial }?.let { twin ->
            add(LogAction("Launch in ${twin.profile.kind.label} profile") { host.io { processor.launch(twin) } })
        }
        add(LogAction("App info") { host.io { appsRepo.openAppInfo(app) } })
        add(LogAction("Copy package name") { copy(app.pkg) })
        val pinned = appPrefs.isPinned(app.key)
        add(LogAction(if (pinned) "Unpin from favourites" else "Pin to favourites") { appPrefs.setPinned(app.key, !pinned) })
        add(LogAction(if (app.hidden) "Unhide" else "Hide from lists") {
            host.io {
                appPrefs.setHidden(app.key, !app.hidden)
                appsRepo.refresh()
            }
        })
        add(LogAction("Uninstall", destructive = true) { host.io { appsRepo.uninstall(app) } })
    }

    /** The command line above [entry] in its block (null for script output or orphan lines). */
    private fun producingCommand(entry: LogEntry): LogEntry? {
        val all = host.entries
        val index = all.indexOfFirst { it.id == entry.id }
        if (index < 0) return null
        for (i in index - 1 downTo 0) {
            val e = all[i]
            // Timer script lines can land inside a block; they are not part of it.
            if (e.kind == LogKind.COMMAND) return e
        }
        return null
    }

    /**
     * A command line plus the output that followed it: up to the next command or a pause longer
     * than [GROUP_GAP_MS], as the log groups it (a call or install line later on isn't output).
     * Script lines are left out.
     */
    private fun blockOf(command: LogEntry): List<LogEntry> {
        val all = host.entries
        val start = all.indexOfFirst { it.id == command.id }
        if (start < 0) return listOf(command)
        val block = arrayListOf(command)
        var previous = command
        for (e in all.subList(start + 1, all.size)) {
            if (e.kind == LogKind.COMMAND || e.ts - previous.ts > GROUP_GAP_MS) break
            if (e.kind != LogKind.SCRIPT && LogContext.RUN_HEADER !in e.ctx.values) block += e
            previous = e
        }
        return block
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

    private fun openUrl(url: String) = host.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))

    /** Opens a line's link; a feed item counts as read once you have opened it. */
    private fun openLink(ctx: LogContext) {
        openUrl(ctx.url ?: return)
        ctx.values[com.mtwabpin.kolahal.feeds.FeedWorker.CTX_ITEM]?.toLongOrNull()?.let { id ->
            host.io { com.mtwabpin.kolahal.feeds.FeedRepository.get(context).markRead(id) }
        }
    }

    private fun share(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, url)
        host.startActivity(Intent.createChooser(send, null))
    }

    /** Swipe right on a log line. */
    fun swipeCopy(entry: LogEntry) = copy(entry.text, sensitive(entry))

    /** Notification and call lines: other people's messages, codes and numbers. */
    private fun sensitive(entry: LogEntry) =
        entry.ctx.values.containsKey(NOTIF) || entry.source == com.mtwabpin.kolahal.calls.CallEvents.SOURCE

    /** [sensitive] text is marked so the keyboard's clipboard preview and history hide it. */
    private fun copy(text: String, sensitive: Boolean = false) {
        val clip = ClipData.newPlainText("kolahal", text)
        if (sensitive) clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** Long-press entries for todo, note and reminder lines (destructive ones go to [danger]). */
    private fun productivityActions(ctx: LogContext, actions: MutableList<LogAction>, danger: MutableList<LogAction>) {
        ctx.values[LogContext.TODO]?.let { id ->
            val done = ctx.values[LogContext.TODO_DONE] == "1"
            actions += LogAction(if (done) "Mark not done" else "Mark done") { host.runCommand("todo ${if (done) "undo" else "done"} #$id") }
            actions += LogAction("Edit in prompt") {
                host.setText("todo edit #$id ${ctx.copy.orEmpty()}")
                host.emit(UiEvent.FocusPrompt)
            }
            danger += LogAction("Delete todo", destructive = true) { host.runCommand("todo rm #$id") }
        }
        ctx.values[LogContext.NOTE]?.let { id ->
            danger += LogAction("Delete note", destructive = true) { host.runCommand("notes rm #$id") }
        }
        ctx.values[LogContext.REMINDER]?.let { id ->
            danger += LogAction("Cancel reminder", destructive = true) { host.runCommand("remind rm #$id") }
        }
        if (ctx.values[LogContext.EVENT] != null) {
            actions += LogAction("Open event") { ProductivityCommands.eventIntent(ctx)?.let(host::startActivity) }
        }
    }

    companion object {
        private const val NOTIF = com.mtwabpin.kolahal.notifications.KolahalNotificationListener.CTX_KEY
    }
}
