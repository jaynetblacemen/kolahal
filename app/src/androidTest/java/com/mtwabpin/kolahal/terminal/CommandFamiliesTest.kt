package com.mtwabpin.kolahal.terminal

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One test per command family: what each prints and what it changes. */
@RunWith(AndroidJUnit4::class)
class CommandFamiliesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logs = LogRepository.get(context)
    private val config = Config.get(context)
    private val prefs = AppPrefs.get(context)
    private val apps = AppRepository.get(context)
    private var cleared = 0

    private val ui = object : TerminalUi {
        override fun pickScriptFolder() = Unit
        override fun requestFontImport() = Unit
        override fun openScreen(screen: Screen) = Unit
        override fun requestContactsPermission() = Unit
        override fun openEditor(script: Script) = Unit
        override fun askSecret(script: Script, name: String) = Unit
        override fun requestNotificationPermission() = Unit
        override fun clearScreen() { cleared++ }
    }
    private val processor = CommandProcessor(context, ui)
    private val crashDir = File(context.filesDir, "crash")

    @Before fun setUp() {
        apps.refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (apps.apps.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @After fun tearDown() {
        prefs.setAlias("tt", null)
        Keys.ALL.forEach(config::reset)
        crashDir.deleteRecursively()
    }

    /** Runs [line] and returns the non-command lines it printed. */
    private fun run(line: String): List<String> {
        val before = logs.recent(1).lastOrNull()?.id ?: 0
        processor.run(line)
        return logs.recent(400).filter { it.id > before && it.kind != LogKind.COMMAND }.map { it.text }
    }

    @Test fun helpListsEveryCommandAndPipesFilterIt() {
        val help = run("help")
        for (spec in CommandProcessor.COMMANDS) assertTrue("${spec.usage} missing from help", spec.usage in help)
        val grepped = run("help | grep crashes")
        assertTrue(grepped.toString(), grepped.isNotEmpty() && grepped.all { it.contains("crashes", ignoreCase = true) })
        assertEquals(2, run("help | grep -v zzz | head 2").size)
    }

    @Test fun unitConversion() {
        val out = run("= 5 km in mi")
        assertTrue(out.toString(), out.single().contains("3.1"))
    }

    @Test fun todoAddDoneClear() {
        assertTrue(run("todo cptest milk").any { it.endsWith("[ ] cptest milk") })
        assertTrue(run("todo").any { it.endsWith("[ ] cptest milk") })
        run("todo done 1")
        assertTrue(run("todo").single().startsWith("Nothing open"))
        assertEquals(listOf("Cleared 1 finished todo"), run("todo clear"))
        assertTrue(run("todo").single().startsWith("No todos"))
    }

    @Test fun notesAddFindRemove() {
        assertTrue(run("note cptest idea").single().startsWith("Noted"))
        assertTrue(run("notes").any { it.contains("cptest idea") })
        assertTrue(run("notes grep cptest").any { it.contains("cptest idea") })
        assertTrue(run("notes rm 1").single().contains("cptest idea"))
        assertTrue(run("notes").single().startsWith("No notes"))
    }

    @Test fun remindListCancel() {
        assertTrue(run("remind 10m cptest tea").first().contains("cptest tea"))
        assertTrue(run("reminders").any { it.contains("cptest tea") })
        assertEquals("Cancelled reminder: cptest tea", run("remind rm 1").single())
        assertTrue(run("reminders").single().startsWith("No reminders"))
        assertTrue(run("remind someday x").single().startsWith("Usage: remind"))
    }

    @Test fun crashesListAndClear() {
        assertTrue(run("crashes").single().startsWith("No crash reports"))
        crashDir.mkdirs()
        File(crashDir, "20260101-120000-crash.txt").writeText(
            "Kolahal crash report\nKolahal 0.0 (1, debug)\nAndroid\nWhen: x\nWhat: crash in test, thread main\n\njava.lang.IllegalStateException: boom\n\tat a.b(c:1)\n",
        )
        val listed = run("crashes")
        assertTrue(listed.toString(), listed.first().startsWith("#1 crash") && listed.first().endsWith("IllegalStateException: boom"))
        assertTrue(run("crashes share 5").single().startsWith("No crash report 5"))
        assertTrue(run("crashes bogus").single().startsWith("Usage: crashes"))
        assertEquals(listOf("Deleted 1 crash report"), run("crashes clear"))
        assertFalse(crashDir.listFiles().orEmpty().any())
    }

    @Test fun configGetSetReset() {
        assertEquals(listOf("font.size = 14"), run("config get font.size"))
        run("config set clock.24h on")
        assertTrue(config[Keys.CLOCK_24H])
        run("config reset clock.24h")
        assertFalse(config[Keys.CLOCK_24H])
        assertTrue(run("config set no.such.key 1").single().startsWith("Unknown setting"))
    }

    @Test fun fontSansSetsTheMode() {
        assertEquals(listOf("font.sans = prompt"), run("font sans prompt"))
        assertEquals("prompt", config[Keys.FONT_SANS])
        assertTrue(run("font sans bogus").single().startsWith("Invalid value"))
        assertEquals("prompt", config[Keys.FONT_SANS])
        assertTrue(run("font sans").single().startsWith("font.sans = prompt"))
        assertTrue(run("font").any { it.startsWith("The phone's font: prompt") })
    }

    @Test fun historyAndClear() {
        run("echo cptest-one")
        assertTrue(run("history").contains("echo cptest-one"))
        assertEquals(listOf("Command history cleared"), run("clear history"))
        assertFalse(run("history").contains("echo cptest-one"))
        val before = cleared
        run("clear")
        assertEquals(before + 1, cleared)
    }

    @Test fun aliasesAreListed() {
        run("alias tt = echo cptest")
        assertTrue(run("alias").any { it.contains("tt") && it.contains("echo cptest") })
    }

    @Test fun pinAndUnpinApp() {
        val app = apps.apps.value.first { it.profile.kind == ProfileKind.PERSONAL }
        try {
            run("pin ${app.label}")
            assertTrue(prefs.isPinned(app.key))
            run("unpin ${app.label}")
            assertFalse(prefs.isPinned(app.key))
        } finally {
            prefs.setPinned(app.key, false)
        }
    }

    @Test fun setupTextAndDeviceCommands() {
        val setup = run("setup text")
        assertTrue(setup.toString(), setup.first().startsWith("Setup") && setup.size > 1 && setup.drop(1).all { it.startsWith("[") })
        if (Build.VERSION.SDK_INT < 37) assertTrue(run("local-network").single().contains("no permission"))
        assertTrue(run("date").single().isNotBlank())
        val cal = run("cal")
        assertTrue(cal.toString(), cal.size in 7..8 && cal[1].trim().split(" ").size == 7)
        assertTrue(run("cal nope").single().startsWith("No month"))
    }
}
