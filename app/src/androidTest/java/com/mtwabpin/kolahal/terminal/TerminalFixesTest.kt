package com.mtwabpin.kolahal.terminal

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.notifications.KolahalNotificationListener
import com.mtwabpin.kolahal.notifications.NotifPrefs
import com.mtwabpin.kolahal.notifications.NotificationActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixes to typing, the log and its lines, as home runs them. */
@RunWith(AndroidJUnit4::class)
class TerminalFixesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val logs = LogRepository.get(app)
    private val config = Config.get(app)
    private val scripts = ScriptRepository.get(app)
    private val muted = NotifPrefs.get(app)
    private val gone = "com.example.kolahal.gone"

    private val ui = object : TerminalUi {
        override fun pickScriptFolder() = Unit
        override fun requestFontImport() = Unit
        override fun openScreen(screen: Screen) = Unit
        override fun requestContactsPermission() = Unit
        override fun requestCalendarPermission() = Unit
        override fun openEditor(script: Script) = Unit
        override fun askSecret(script: Script, name: String) = Unit
        override fun requestNotificationPermission() = Unit
        override fun clearScreen() = logs.clearScreen()
    }
    private val processor = CommandProcessor(app, ui)

    @After fun tearDown() {
        Keys.ALL.forEach(config::reset)
        AppPrefs.get(app).setAlias("text", null)
        scripts.byName("tfcat")?.let { scripts.delete(it.id) }
        muted.unmute(gone)
    }

    /** Runs [line] and returns the lines it printed, the command line left out. */
    private fun run(line: String): List<LogEntry> {
        val before = logs.maxId()
        processor.run(line)
        return logs.recent(200).filter { it.id > before && it.kind != LogKind.COMMAND }
    }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    private fun viewModel(): TerminalViewModel {
        lateinit var vm: TerminalViewModel
        instrumentation.runOnMainSync { vm = TerminalViewModel(app, SavedStateHandle()) }
        return vm
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(what, check())
    }

    @Test fun didYouMeanFillsThePromptInsteadOfRunning() {
        // Even where a tap runs whole command lines.
        config.set(Keys.LAUNCH_INSTANT, InstantRun.ALL)
        val hint = run("echoo hi").single()
        assertTrue(hint.text, hint.text.contains("did you mean 'echo hi'"))
        val vm = viewModel()
        val before = logs.maxId()
        instrumentation.runOnMainSync { vm.onTap(hint) }
        assertEquals("echo hi ", vm.input.text)
        Thread.sleep(500)
        assertTrue(logs.recent(50).none { it.id > before })
    }

    /** `feed fetch` for every feed says which ones failed, as fetching one feed does. */
    @Test fun fetchingAllFeedsReportsTheOnesThatFail() {
        val feeds = com.mtwabpin.kolahal.feeds.FeedRepository.get(app)
        val feed = feeds.add("https://kolahal-test.invalid/feed.xml", title = "tf-broken")
        try {
            val before = logs.maxId()
            processor.run("feed fetch")
            val until = System.currentTimeMillis() + 20_000
            fun failed() = logs.recent(100).any { it.id > before && it.kind == LogKind.ERROR && it.text.startsWith("tf-broken: ") }
            while (!failed() && System.currentTimeMillis() < until) Thread.sleep(100)
            assertTrue(failed())
        } finally {
            feeds.remove(feed.id)
        }
    }

    /** The same message twice shows twice: each is a notice of its own. */
    @Test fun theSameMessageTwiceIsTwoNotices() {
        val vm = viewModel()
        instrumentation.runOnMainSync { vm.newScript("Not A Name", ScriptLang.LUA, asCommand = false) }
        val first = vm.notice.value!!
        instrumentation.runOnMainSync { vm.newScript("Not A Name", ScriptLang.LUA, asCommand = false) }
        val second = vm.notice.value!!
        assertEquals(first.text, second.text)
        assertTrue(first != second)
    }

    @Test fun aCommandsOtherNamesCantBeAliases() {
        assertTrue(run("alias text = echo x").single().text.contains("built-in command"))
        assertNull(AppPrefs.get(app).aliases()["text"])
    }

    @Test fun unmuteTakesThePackageThatMutedLists() {
        val listener = "${app.packageName}/${KolahalNotificationListener::class.java.name}"
        val had = NotificationActions.isEnabled(app)
        shell("cmd notification allow_listener $listener")
        waitUntil("notification access") { NotificationActions.isEnabled(app) }
        try {
            muted.mute(gone)
            // `notifs muted` lists an app no longer installed by its package, to unmute by tapping.
            assertEquals("notifs unmute $gone", run("notifs muted").single { it.text == gone }.ctx.command)
            run("notifs unmute $gone")
            assertFalse(gone in muted.muted.value)
        } finally {
            if (!had) shell("cmd notification disallow_listener $listener")
        }
    }

    @Test fun scriptCatKeepsOneCopyOfTheCode() {
        val code = "-- a\nlocal x = 1\nreturn x"
        scripts.create("tfcat", ScriptLang.LUA, code)
        val lines = run("script cat tfcat")
        assertEquals(listOf("  1  -- a", "  2  local x = 1", "  3  return x"), lines.map { it.text })
        assertEquals(listOf(code, null, null), lines.map { it.ctx.copy })
    }

    @Test fun aFreshScreenHideNeverBringsBackWhatFreshHid() {
        val first = logs.add(LogKind.INFO, "tf one")
        val second = logs.add(LogKind.INFO, "tf two")
        try {
            logs.hideScreen(upTo = second)
            // Leaving home later hides only what was seen then: less than `fresh` hid.
            logs.hideScreen(upTo = first)
            assertTrue(logs.isHidden(second))
        } finally {
            logs.unhide()
            logs.delete(first)
            logs.delete(second)
        }
    }

    @Test fun deleteCommandAndOutputStopsWhereTheGroupDoes() {
        val now = System.currentTimeMillis()
        logs.addAll(
            listOf(
                LogRepository.Pending(LogKind.COMMAND, "tf echo", null, LogContext.EMPTY, now),
                LogRepository.Pending(LogKind.OUTPUT, "tf output", null, LogContext.EMPTY, now + 100),
                // A timer script's line inside the block is its own.
                LogRepository.Pending(LogKind.SCRIPT, "tf script", "tfs", LogContext.EMPTY, now + 200),
                LogRepository.Pending(LogKind.OUTPUT, "tf more output", null, LogContext.EMPTY, now + 300),
                // Much later, with no command in between: a call or an install, not output.
                LogRepository.Pending(LogKind.INFO, "tf installed", null, LogContext.EMPTY, now + 60_000),
            ),
        )
        val vm = viewModel()
        waitUntil("the lines load") { vm.entries.value.any { it.text == "tf installed" } }
        val command = vm.entries.value.single { it.text == "tf echo" }
        val delete = vm.actionsFor(command).single { it.label == "Delete command and output" }
        instrumentation.runOnMainSync { delete.run() }
        waitUntil("the block goes") { logs.recent(50).none { it.text == "tf echo" } }
        val left = logs.recent(50).map { it.text }.filter { it.startsWith("tf ") }
        assertEquals(listOf("tf script", "tf installed"), left)
        logs.recent(50).filter { it.text in left }.forEach { logs.delete(it.id) }
    }
}
