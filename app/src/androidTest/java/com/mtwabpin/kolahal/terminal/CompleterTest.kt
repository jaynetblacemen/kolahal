package com.mtwabpin.kolahal.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.data.Script
import com.mtwabpin.kolahal.productivity.Todo
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What the suggestion strip offers for what is typed (with the real app list and settings). */
@RunWith(AndroidJUnit4::class)
class CompleterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = Config.get(context)
    private val apps = AppRepository.get(context)

    private var history = emptyList<String>()
    private var todos = emptyList<Todo>()
    private val completer = Completer(context, object : Completer.Data {
        override val history get() = this@CompleterTest.history
        override val scripts = emptyList<Script>()
        override val commandNames = emptyList<String>()
        override val scriptUsages = emptyMap<String, String>()
        override val statusNames = emptySet<String>()
        override val feeds = emptyList<com.mtwabpin.kolahal.feeds.FeedInfo>()
        override val todos get() = this@CompleterTest.todos
        override val notes = emptyList<com.mtwabpin.kolahal.productivity.Note>()
        override val reminders = emptyList<com.mtwabpin.kolahal.productivity.Reminder>()
    })

    /** `log only …` suggests the kinds (and the log's sources, when there are any). */
    @Test fun logOnlySuggestsTheKinds() {
        val all = completer.suggestions("log only ").map { (it as Suggestion.Completion).replacement }
        assertTrue(all.toString(), "log only feeds" in all && "log only scripts" in all)
        assertEquals(listOf("log only feeds"), completer.suggestions("log only fe").map { (it as Suggestion.Completion).replacement })
    }

    @Before fun setUp() {
        apps.refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (apps.apps.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @After fun tearDown() = Keys.ALL.forEach(config::reset)

    private fun labels(text: String) = completer.suggestions(text).map { it.label }
    private fun lines(text: String) = completer.suggestions(text).filterIsInstance<Suggestion.Completion>().map { it.replacement.trim() }

    @Test fun firstWordOffersCommands() {
        assertTrue(labels("hel").toString(), "help" in labels("hel"))
        assertTrue(labels("cras").toString(), "crashes" in labels("cras"))
    }

    @Test fun aWholeCommandOffersItsOptions() {
        assertTrue(labels("restart").toString(), "restart scripts" in labels("restart"))
        assertTrue(lines("crashes ").toString(), lines("crashes ").containsAll(listOf("crashes share", "crashes clear")))
        assertTrue(lines("apps w").toString(), "apps work" in lines("apps w"))
    }

    /** `[days]` or `[n]` in a usage line is a value to type, not a word to offer. */
    @Test fun placeholdersAreNotOffered() {
        for (cmd in listOf("unlocks", "usage", "calls", "installs", "agenda")) {
            val offered = labels(cmd) + lines("$cmd ")
            assertFalse("$cmd offers $offered", offered.any { it.endsWith(" days") || it.endsWith(" n") })
        }
    }

    @Test fun configKeysThenValues() {
        assertTrue("font.size" in labels("config set font.s"))
        assertTrue(lines("config set clock.24h ").containsAll(listOf("config set clock.24h on", "config set clock.24h off")))
        assertTrue("amber" in labels("theme am"))
        assertTrue(labels("config set no.such.key ").isEmpty())
    }

    @Test fun fontOffersSansAndItsModes() {
        assertTrue(lines("font s").toString(), "font sans" in lines("font s"))
        assertTrue(lines("font sans p").toString(), "font sans prompt" in lines("font sans p"))
        assertTrue(lines("font sans ").containsAll(listOf("font sans off", "font sans prompt", "font sans all")))
    }

    @Test fun historyContinuesTheLine() {
        history = listOf("search kotlin flow", "search compose", "note buy milk")
        assertTrue(lines("search ko").toString(), "search kotlin flow" in lines("search ko"))
        assertFalse("search compose" in lines("search ko"))
    }

    @Test fun todoDoneOffersOnlyOpenItems() {
        val now = System.currentTimeMillis()
        todos = listOf(Todo(1, "milk", false, now, null), Todo(2, "bread", true, now, now))
        assertTrue(lines("todo done ").toString(), "todo done 1" in lines("todo done "))
        assertFalse("todo done 2" in lines("todo done "))
        assertTrue("todo undo 2" in lines("todo undo "))
    }

    @Test fun theTypedLineIsNotOfferedAgain() {
        history = listOf("echo hi")
        assertFalse(lines("echo hi").contains("echo hi"))
    }

    @Test fun appsAreSuggestedByName() {
        val app = apps.apps.value.first { it.profile.kind == ProfileKind.PERSONAL && it.label.length >= 4 }
        val found = completer.suggestions(app.label.take(4)).filterIsInstance<Suggestion.App>()
        assertTrue("${app.label} not in ${found.map { it.label }}", found.any { it.app.key == app.key })
    }

    @Test fun emptyPromptFollowsSuggestionsIdle() {
        assertTrue(completer.suggestions("").isEmpty())
        config.set(Keys.SUGGEST_IDLE, "on")
        // Most-used apps (none yet on a fresh install), never more than `suggestions`.
        val idle = completer.suggestions("")
        assertTrue(idle.toString(), idle.size <= config[Keys.SUGGESTIONS] && idle.all { it is Suggestion.App })
    }
}
