package io.github.rscipher001.launcher.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rscipher001.launcher.apps.AppPrefs
import io.github.rscipher001.launcher.apps.AppRepository
import io.github.rscipher001.launcher.data.Config
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.Script
import io.github.rscipher001.launcher.data.ScriptRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logs = LogRepository.get(context)
    private val config = Config.get(context)
    private val prefs = AppPrefs.get(context)
    private val apps = AppRepository.get(context)
    private val opened = mutableListOf<Script>()

    private val ui = object : TerminalUi {
        override fun pickScriptFolder() = Unit
        override fun openEditor(script: Script) { opened += script }
        override fun requestNotificationPermission() = Unit
        override fun clearScreen() = logs.clearScreen()
    }
    private val processor = CommandProcessor(context, ui)

    @Before fun setUp() {
        apps.refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (apps.apps.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @After fun tearDown() {
        prefs.setAlias("tt", null)
        Keys.ALL.forEach(config::reset)
        ScriptRepository.get(context).byName("cptest")?.let { ScriptRepository.get(context).delete(it.id) }
    }

    /** Runs [line] and returns the non-command lines it printed. */
    private fun run(line: String): List<String> {
        val before = logs.recent(1).lastOrNull()?.id ?: 0
        processor.run(line)
        return logs.recent(200).filter { it.id > before && it.kind != LogKind.COMMAND }.map { it.text }
    }

    @Test fun echoAndUnknown() {
        assertEquals(listOf("hello world"), run("echo hello world"))
        assertTrue(run("definitely-not-an-app-zzz").single().startsWith("command not found"))
    }

    @Test fun aliasExpands() {
        run("alias tt = echo aliased")
        assertEquals(listOf("aliased extra"), run("tt extra"))
        run("unalias tt")
        assertFalse(run("tt").any { it.contains("aliased") })
    }

    @Test fun configSetAndValidate() {
        run("config set font.size 18")
        assertEquals(18, config[Keys.FONT_SIZE])
        assertTrue(run("config set font.size 99").single().startsWith("invalid value"))
        assertEquals(18, config[Keys.FONT_SIZE])
        run("theme amber")
        assertEquals("amber", config[Keys.THEME])
        run("config set keybar ESC,TAB,\",|")
        assertEquals("ESC,TAB,\",|", config[Keys.KEYBAR])
        run("config reset all")
        assertEquals(14, config[Keys.FONT_SIZE])
    }

    @Test fun scriptLifecycle() {
        run("script new cptest --cmd")
        val script = ScriptRepository.get(context).byName("cptest")
        assertNotNull(script)
        assertEquals(script!!.id, opened.single().id)
        run("script every cptest 5m")
        assertEquals(15, ScriptRepository.get(context).byName("cptest")!!.intervalMin)
        run("script at cptest 7:05,19:00")
        assertEquals(listOf("07:05", "19:00"), ScriptRepository.get(context).byName("cptest")!!.atTimes)
        assertTrue(run("script at cptest 25:00").single().startsWith("bad time"))
        run("script rm cptest")
        assertEquals(null, ScriptRepository.get(context).byName("cptest"))
        assertTrue(run("script new help").single().contains("built-in"))
    }

    @Test fun hideAndRenameApp() {
        val app = apps.apps.value.first { it.profile.kind == io.github.rscipher001.launcher.apps.ProfileKind.PERSONAL }
        try {
            prefs.setHidden(app.key, true)
            apps.refresh()
            Thread.sleep(500)
            assertFalse(apps.search(app.originalLabel).any { it.key == app.key })
            assertTrue(apps.search(app.originalLabel, includeHidden = true).any { it.key == app.key })
            prefs.setLabel(app.key, "Zz Renamed")
            apps.refresh()
            Thread.sleep(500)
            assertEquals("Zz Renamed", apps.apps.value.first { it.key == app.key }.label)
        } finally {
            prefs.setHidden(app.key, false)
            prefs.setLabel(app.key, null)
            apps.refresh()
        }
    }
}
