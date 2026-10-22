package com.mtwabpin.kolahal.terminal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.mtwabpin.kolahal.calls.CallEvents
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository

/** `call`, `sms`, `contacts` and `calls`. Kept out of [CommandProcessor] like the other command groups. */
class PeopleCommands(private val context: Context, private val ui: TerminalUi) {
    private val logs = LogRepository.get(context)
    private val conf = Config.get(context)
    private val contacts = com.mtwabpin.kolahal.contacts.ContactsRepository.get(context)

    private fun out(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.OUTPUT, text, ctx = ctx) }
    private fun info(text: String, ctx: LogContext = LogContext.EMPTY) { logs.add(LogKind.INFO, text, ctx = ctx) }
    private fun error(text: String) { logs.add(LogKind.ERROR, text) }

    private fun startActivity(intent: Intent) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** `calls [n]`: the newest calls from Android's call log, in the same form as the live lines. */
    fun calls(args: List<String>) {
        if (!com.mtwabpin.kolahal.BuildConfig.CALL_LOG) return error("This build can't read the call log, so calls isn't available")
        when (args.firstOrNull()) {
            "--allow" -> return if (CallEvents.hasPermission(context)) info("Call log is allowed") else ui.requestCallLogPermission()
            "--settings" -> return startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
        if (!CallEvents.hasPermission(context)) {
            logs.add(LogKind.ERROR, "Calls need call log access — tap to allow", ctx = LogContext.of(LogContext.RUN to "calls --allow"))
            return
        }
        val n = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 200) ?: 20
        val list = CallEvents.recent(context, n)
        if (list.isEmpty()) return info("No calls in the call log")
        list.reversed().forEach { out(CallEvents.line(it, conf, withDay = true), CallEvents.ctx(it)) }
    }

    /** Resolves a name to one contact; null (with a message) when permission is missing or ambiguous. */
    private fun resolveContact(query: String): com.mtwabpin.kolahal.contacts.Contact? {
        if (!contacts.hasPermission()) {
            logs.add(LogKind.ERROR, "Contacts permission needed — tap to allow", ctx = LogContext.of(LogContext.RUN to "contacts --allow"))
            ui.requestContactsPermission()
            return null
        }
        val matches = contacts.search(query)
        if (matches.isEmpty()) { error("No contact matches '$query'"); return null }
        if (matches.size > 1 && !matches[0].name.equals(query, ignoreCase = true)) {
            matches.take(8).forEach(::contactLine)
            info("${matches.size} contacts match — tap one to call, long-press for more")
            return null
        }
        return matches[0]
    }

    private fun contactLine(c: com.mtwabpin.kolahal.contacts.Contact) = out(
        c.name + if (c.starred) " ★" else "",
        LogContext.of(LogContext.PHONE to c.number, LogContext.CONTACT to c.lookupUri.toString(), LogContext.DESC to "${c.number} · ${c.label}"),
    )

    fun call(rest: String) {
        if (rest.isBlank()) return error("Usage: call <name|number>")
        val number = if (com.mtwabpin.kolahal.contacts.ContactsRepository.looksLikeNumber(rest)) rest else resolveContact(rest)?.number ?: return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
        info("Dialing $number", LogContext.of(LogContext.PHONE to number))
    }

    fun sms(args: List<String>) {
        val who = args.firstOrNull() ?: return error("Usage: sms <name|number> [message]")
        val number = if (com.mtwabpin.kolahal.contacts.ContactsRepository.looksLikeNumber(who)) who else resolveContact(who)?.number ?: return
        val body = args.drop(1).joinToString(" ")
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", body))
        info("Message to $number", LogContext.of(LogContext.PHONE to number))
    }

    fun listContacts(query: String) {
        if (query == "--allow" || !contacts.hasPermission()) {
            if (contacts.hasPermission()) return info("Contacts are allowed")
            ui.requestContactsPermission()
            return
        }
        val list = if (query.isBlank()) contacts.contacts.value.filter { it.starred }.ifEmpty { contacts.contacts.value.take(20) } else contacts.search(query, 20)
        if (list.isEmpty()) return info(if (query.isBlank()) "No contacts" else "No contact matches '$query'")
        list.forEach(::contactLine)
    }
}
