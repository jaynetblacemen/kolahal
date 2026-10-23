package com.mtwabpin.kolahal.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.data.StyledText
import com.mtwabpin.kolahal.data.shownText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The bundled scripts that need no network, run as Kolahal runs them: what each one logs. */
@RunWith(AndroidJUnit4::class)
class LibraryScriptsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logs = LogRepository.get(context)
    private val library = ScriptLibrary(context)
    private val name = "lib${System.nanoTime()}"
    // A script id of its own, so the script's store starts empty.
    private val id = 900_000_000L + System.nanoTime() % 1_000_000
    private val store = KvStore(context, ScriptRepository.scopeOf(id))
    private val status = KvStore(context, HostApi.STATUS_SCOPE)

    private fun run(file: String, argline: String = "", event: Map<String, Any?>? = null) {
        lines().forEach { logs.delete(it.id) }
        val entry = library.entries.single { it.file == file }
        val host = HostApi(context, name, scriptId = id, notifyEnabled = false)
        val args = argline.split(' ').filter(String::isNotEmpty)
        runBlocking { ScriptEngine.forLang(entry.lang).run(library.code(entry), name, host, 10_000, false, args, event) }
        host.flush()
    }

    private fun lines(): List<LogEntry> = logs.recent(limit = 200).filter { it.source == name }

    /** The one line logged: its title as shown, its body, and the colour of all of its title (null: none, or mixed). */
    private fun only(): Triple<String, String?, StyledText.Colour?> = lines().single().let { Triple(it.text.let(StyledText::plain), it.ctx.body, colourOf(it.text)) }

    private fun colourOf(text: String) = StyledText.lines(text).single().spans.map { it.colour }.distinct().singleOrNull()

    @After fun tearDown() {
        lines().forEach { logs.delete(it.id) }
        store.all().keys.forEach { store.set(it, null) }
        status.set(name, null)
    }

    @Test fun countdownSavesListsAndRemoves() {
        val today = LocalDate.now()
        run("countdown.lua")
        assertEquals(Triple("No countdowns yet: countdown add 2026-12-25 Christmas", null, StyledText.Colour.MUTED), only())

        // Colours by how near: this week's in the warning colour, today's in the good one, past ones muted.
        run("countdown.lua", "add ${today.plusDays(3)} Soon")
        assertEquals(Triple("Soon: in 3 days", "saved · ${today.plusDays(3)}", StyledText.Colour.WARN), only())
        run("countdown.lua", "add $today Now")
        assertEquals(StyledText.Colour.GOOD, only().third)
        run("countdown.lua", "add ${today.minusDays(10)} Past")
        assertEquals(Triple("Past: 10 days ago", "saved · ${today.minusDays(10)}", StyledText.Colour.MUTED), only())
        run("countdown.lua", "add ${today.plusDays(30)} Trip")
        assertEquals(Triple("Trip: in 30 days", "saved · ${today.plusDays(30)}", null), only())

        run("countdown.lua")
        assertEquals(listOf("1. Past: 10 days ago", "2. Now: today", "3. Soon: in 3 days", "4. Trip: in 30 days"), lines().map { StyledText.plain(it.text) })
        assertEquals(listOf(today.minusDays(10), today, today.plusDays(3), today.plusDays(30)).map(LocalDate::toString), lines().map { it.ctx.body })
        // The nearest one not past is pinned in the header.
        assertEquals("Now: today", status.get(name))

        run("countdown.lua", "rm 1")
        val removed = lines().single()
        assertEquals("Removed Past", removed.shownText.lines().first())
        assertEquals(listOf(null, setOf(StyledText.Style.BOLD)), StyledText.lines(removed.text).single().spans.map { it.styles.ifEmpty { null } })
        run("countdown.lua", "rm 9")
        assertEquals(Triple("no countdown #9: countdown lists them", null, StyledText.Colour.MUTED), only())
        run("countdown.lua", "add someday Trip")
        assertEquals(Triple("usage: countdown YYYY-MM-DD | add YYYY-MM-DD label | rm N", null, StyledText.Colour.MUTED), only())

        // A date on its own is counted, not saved.
        run("countdown.lua", "${today.plusDays(5)}")
        assertEquals("${today.plusDays(5)}: in 5 days", only().first)
        run("countdown.lua")
        assertEquals(3, lines().size)
    }

    @Test fun releasesWatchesAndListsRepos() {
        run("releases.lua", "list")
        val defaults = listOf("mtwabpin/kolahal", "neovim/neovim", "termux/termux-app")
        assertEquals(defaults, lines().map { it.text })
        assertEquals(defaults.map { "https://github.com/$it/releases" }, lines().map { it.ctx.url })
        // Each repo a line of its own: no body under one.
        assertEquals(listOf(null, null, null), lines().map { it.ctx.body })

        run("releases.lua", "add sharkdp/fd")
        assertEquals(Triple("Watching sharkdp/fd", "4 repos", null), only())
        assertEquals("https://github.com/sharkdp/fd/releases", lines().single().ctx.url)
        run("releases.lua", "add SharkDP/fd")
        assertEquals(Triple("SharkDP/fd is watched already", null, StyledText.Colour.MUTED), only())
        run("releases.lua", "add fd")
        assertEquals(Triple("usage: releases add owner/repo", null, StyledText.Colour.MUTED), only())
        run("releases.lua", "rm neovim/neovim")
        assertEquals(Triple("Stopped watching neovim/neovim", "3 repos left", null), only())
        run("releases.lua", "rm neovim/neovim")
        assertEquals(Triple("neovim/neovim isn't watched", null, StyledText.Colour.MUTED), only())
        run("releases.lua", "list")
        assertEquals(listOf("mtwabpin/kolahal", "termux/termux-app", "sharkdp/fd"), lines().map { it.text })
        run("releases.lua", "check")
        assertEquals(Triple("usage: releases [add|rm owner/repo | list]", null, StyledText.Colour.MUTED), only())
    }

    @Test fun chargeReminderSumsUpASession() {
        run("charge-reminder.lua")
        assertEquals(Triple("No charging session yet: plug in the charger", null, StyledText.Colour.MUTED), only())

        run("charge-reminder.lua", event = mapOf("name" to "charger", "level" to 15, "plug" to "usb"))
        assertEquals(emptyList<LogEntry>(), lines())
        assertEquals(true, status.get(name)?.startsWith("charging since "))
        // Unplugged still below 30%: the warning colour, and how much it added under it.
        run("charge-reminder.lua", event = mapOf("name" to "unplug", "level" to 20, "plug" to "usb"))
        assertEquals(Triple("unplugged at 20%", "+5% in 0 min", StyledText.Colour.WARN), only())
        assertNull(status.get(name))
        run("charge-reminder.lua", event = mapOf("name" to "charger", "level" to 20, "plug" to "ac"))
        run("charge-reminder.lua", event = mapOf("name" to "unplug", "level" to 90, "plug" to "ac"))
        assertEquals(Triple("unplugged at 90%", "+70% in 0 min", StyledText.Colour.GOOD), only())

        run("charge-reminder.lua")
        val (title, body) = only()
        assertEquals("Last charge", title)
        assertEquals(true, body?.endsWith(" unplugged at 90% (+70% in 0 min)"))
    }
}
