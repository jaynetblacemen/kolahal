package com.mtwabpin.kolahal.script

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.KvStore
import com.mtwabpin.kolahal.data.ScriptLang
import com.mtwabpin.kolahal.data.ScriptRepository
import com.mtwabpin.kolahal.data.SharedSettings
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings the script process reads, daily times, and room in a script's store. */
@RunWith(AndroidJUnit4::class)
class ScriptFixesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val config = Config.get(app)
    private val scripts = ScriptRepository.get(app)

    @After fun tearDown() {
        config.reset(Keys.CLOCK_24H)
        config.reset(Keys.LOG_KEEP_LINES)
        scripts.byName("sftimes")?.let { scripts.delete(it.id) }
    }

    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(what, check())
    }

    /** Run headers come from the :script process, which reads settings home copies for it. */
    @Test fun theScriptProcessSeesClockAndKeepLines() {
        config.set(Keys.CLOCK_24H, "on")
        waitUntil("clock.24h on") { SharedSettings.get(app, Keys.CLOCK_24H) }
        assertTrue(SharedSettings.shortTime(app, 0L).matches(Regex("\\d\\d:\\d\\d")))
        config.set(Keys.CLOCK_24H, "off")
        waitUntil("clock.24h off") { !SharedSettings.get(app, Keys.CLOCK_24H) }
        assertTrue(SharedSettings.shortTime(app, 0L).endsWith("M"))

        config.set(Keys.LOG_KEEP_LINES, "50000")
        waitUntil("log.keep-lines") { SharedSettings.get(app, Keys.LOG_KEEP_LINES) == 50_000 }
    }

    @Test fun dailyTimesSwitchAScriptOn() {
        val s = scripts.create("sftimes", ScriptLang.LUA, "return 1", enabled = false)
        lateinit var vm: TerminalViewModel
        instrumentation.runOnMainSync { vm = TerminalViewModel(app, SavedStateHandle()) }
        assertFalse(vm.setScriptTimes(s, "8:00, 25:00"))
        assertTrue(vm.setScriptTimes(s, "18:30, 8:00"))
        waitUntil("switched on") { scripts.byId(s.id)!!.enabled }
        assertEquals(listOf("08:00", "18:30"), scripts.byId(s.id)!!.atTimes)
    }

    /** `http.cached` makes room among its own responses when a script's store is full. */
    @Test fun cachedResponsesGoTogetherAndNothingElse() {
        val store = KvStore(app, ScriptRepository.scopeOf(900_000_000L + System.nanoTime() % 1_000_000))
        store.set("http.cache:a", "1")
        store.set("http.cache:b", "2")
        store.set("http.cachex", "3")
        store.set("seen", "4")
        store.deleteStartingWith("http.cache:", except = "http.cache:b")
        assertNull(store.get("http.cache:a"))
        assertEquals(mapOf("http.cache:b" to "2", "http.cachex" to "3", "seen" to "4"), store.all())
        store.all().keys.forEach { store.set(it, null) }
    }
}
