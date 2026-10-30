package com.mtwabpin.kolahal.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.BuildConfig
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.data.StyledText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `fetch` (and `neofetch`, `fastfetch`): this phone and Kolahal, lined up to fit the log. */
@RunWith(AndroidJUnit4::class)
class FetchCommandTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logs = LogRepository.get(context)
    private val printed = mutableListOf<Long>()

    private val ui = object : TerminalUi {
        override fun pickScriptFolder() = Unit
        override fun requestFontImport() = Unit
        override fun openScreen(screen: Screen) = Unit
        override fun requestContactsPermission() = Unit
        override fun openEditor(script: Script) = Unit
        override fun askSecret(script: Script, name: String) = Unit
        override fun requestNotificationPermission() = Unit
        override fun clearScreen() = Unit
    }
    private val processor = CommandProcessor(context, ui)

    @After fun tearDown() = printed.forEach(logs::delete)

    private fun run(line: String): List<LogEntry> {
        val before = logs.maxId()
        processor.run(line)
        return logs.recent(100).filter { it.id > before }.also { lines -> printed += lines.map { it.id } }.filter { it.kind != LogKind.COMMAND }
    }

    @Test fun fetchShowsThePhoneAndKolahal() {
        val lines = run("fetch")
        val text = lines.map { StyledText.plain(it.text) }
        assertTrue(text.any { "kolahal@" in it })
        assertTrue(text.any { "OS" in it && "Android" in it })
        assertTrue(text.any { "Kolahal" in it && BuildConfig.VERSION_NAME in it })
        // In the terminal font, to keep its columns, and narrow enough not to wrap.
        assertTrue(lines.all { LogContext.MONO in it.ctx.values })
        val cols = Fetch.columns(context)
        assertTrue(text.joinToString("\n"), text.all { it.length <= cols })
    }

    @Test fun fetchHasYourName() {
        val conf = Config.get(context)
        try {
            conf.set(Keys.USER_NAME, "alex")
            assertTrue(run("fetch").any { StyledText.plain(it.text).contains("alex@") })
        } finally {
            conf.reset(Keys.USER_NAME)
        }
    }

    @Test fun neofetchAndFastfetchAreFetch() {
        val fetch = run("fetch").size
        assertEquals(fetch, run("neofetch").size)
        assertEquals(fetch, run("fastfetch").size)
    }
}
