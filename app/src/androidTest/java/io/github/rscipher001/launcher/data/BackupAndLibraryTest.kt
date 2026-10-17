package io.github.rscipher001.launcher.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.QuickJs
import io.github.rscipher001.launcher.apps.AppPrefs
import io.github.rscipher001.launcher.script.ScriptLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import party.iroiro.luajava.lua54.Lua54
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupAndLibraryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = Config.get(context)
    private val prefs = AppPrefs.get(context)
    private val scripts = ScriptRepository.get(context)

    @After fun tearDown() {
        Keys.ALL.forEach(config::reset)
        prefs.setAlias("bk", null)
        listOf("bktest", "bknew").forEach { n -> scripts.byName(n)?.let { scripts.delete(it.id) } }
    }

    /** The path that broke in release builds: a set() must bump the version immediately. */
    @Test fun configChangeBumpsVersion() {
        val before = config.version.value
        assertTrue(config.set(Keys.THEME, "amber"))
        assertEquals("amber", config[Keys.THEME])
        assertTrue(config.version.value > before)
        val mid = config.version.value
        config.reset(Keys.THEME)
        assertTrue(config.version.value > mid)
    }

    @Test fun backupRoundTrip() {
        config.set(Keys.THEME, "gruvbox")
        config.set(Keys.LOG_SPACING, "20")
        prefs.setAlias("bk", "echo backup")
        val existing = scripts.create("bktest", ScriptLang.LUA, "return 1")
        scripts.setInterval(existing.id, 30)

        val file = File(context.cacheDir, "kolahal-backup-test.json")
        val uri = Uri.fromFile(file)
        assertEquals(scripts.all().size, Backup(context).export(uri))

        // Change everything, then add a new script only present in the file.
        config.reset(Keys.THEME)
        config.reset(Keys.LOG_SPACING)
        prefs.setAlias("bk", null)
        val json = file.readText()
        val withNew = json.replace(
            "\"scripts\":[",
            "\"scripts\":[{\"name\":\"bknew\",\"lang\":\"lua\",\"code\":\"return 2\",\"every\":5,\"enabled\":true},",
        ).replace("\"aliases\":{", "\"aliases\":{\"help\":\"clear all\",")
        file.writeText(withNew)

        val r = Backup(context).import(uri)
        assertEquals("gruvbox", config[Keys.THEME])
        assertEquals(20, config[Keys.LOG_SPACING])
        assertEquals("echo backup", prefs.alias("bk"))
        assertNull("built-in names must not become aliases", prefs.alias("help"))
        assertTrue(r.skipped.any { it.contains("help") })

        val imported = scripts.byName("bknew")
        assertNotNull(imported)
        assertFalse("new scripts from a backup arrive disabled", imported!!.enabled)
        assertEquals("interval below the minimum is clamped", 15, imported.intervalMin)
        assertEquals(30, scripts.byName("bktest")!!.intervalMin)
        file.delete()
    }

    /** "Backup everything": todos, notes, feeds, script stores and pins survive export → wipe → import. */
    @Test fun fullBackupRoundTrip() {
        val todos = io.github.rscipher001.launcher.productivity.TodoStore.get(context)
        val notes = io.github.rscipher001.launcher.productivity.NoteStore.get(context)
        val feeds = io.github.rscipher001.launcher.feeds.FeedRepository.get(context)
        val todoId = todos.add("bk todo")
        notes.add("bk note")
        val feed = feeds.add("https://example.com/bk-feed.xml")
        val script = scripts.create("bktest", ScriptLang.LUA, "return 1")
        KvStore(context, ScriptRepository.scopeOf(script.id)).set("seen", "42")
        prefs.setPinned("bk/pin#0", true)

        val file = File(context.cacheDir, "kolahal-full-backup-test.json")
        val uri = Uri.fromFile(file)
        Backup(context).export(uri)

        // Wipe: the script comes back with a new id, so its store must follow it by name.
        todos.remove(todoId)
        notes.all().filter { it.text == "bk note" }.forEach { notes.remove(it.id) }
        feeds.remove(feed.id)
        scripts.delete(script.id)
        prefs.setPinned("bk/pin#0", false)

        val r = Backup(context).import(uri)
        assertTrue(r.full)
        assertTrue(todos.all().any { it.text == "bk todo" })
        assertTrue(notes.all().any { it.text == "bk note" })
        assertTrue(feeds.all().any { it.url == "https://example.com/bk-feed.xml" })
        assertTrue(AppPrefs.get(context).isPinned("bk/pin#0"))
        val restored = scripts.byName("bktest")!!
        assertEquals("42", KvStore(context, ScriptRepository.scopeOf(restored.id)).get("seen"))

        todos.all().filter { it.text == "bk todo" }.forEach { todos.remove(it.id) }
        notes.all().filter { it.text == "bk note" }.forEach { notes.remove(it.id) }
        feeds.all().filter { it.url.contains("bk-feed") }.forEach { feeds.remove(it.id) }
        prefs.setPinned("bk/pin#0", false)
        file.delete()
    }

    @Test fun libraryLuaScriptsCompile() {
        val lib = ScriptLibrary(context)
        val lua = lib.entries.filter { it.lang == ScriptLang.LUA }
        assertTrue(lua.isNotEmpty())
        for (e in lua) {
            val state = Lua54()
            try {
                state.load(lib.code(e)) // throws LuaException on a syntax error
            } catch (t: Throwable) {
                throw AssertionError("${e.file}: ${t.message}", t)
            } finally {
                state.close()
            }
        }
    }

    @Test fun libraryJsScriptsCompile() = runBlocking {
        val lib = ScriptLibrary(context)
        val js = lib.entries.filter { it.lang == ScriptLang.JS }
        assertTrue(js.isNotEmpty())
        val engine = QuickJs.create(Dispatchers.Default)
        try {
            for (e in js) {
                // Same wrapping as JsEngine, so top-level await and `return` are valid.
                runCatching { engine.compile("__kolahal_done(await (async () => {\n${lib.code(e)}\n})());", "${e.name}.js") }
                    .onFailure { throw AssertionError("${e.file}: ${it.message}", it) }
            }
        } finally {
            engine.close()
        }
    }

    @Test fun libraryIndexMatchesFiles() {
        val lib = ScriptLibrary(context)
        for (e in lib.entries) {
            assertEquals("${e.name}.${e.lang.ext}", e.file)
            val code = lib.code(e)
            assertEquals("${e.file}: command flag", e.command, Regex("""^\s*(--|//)\s*@command\b""", RegexOption.MULTILINE).containsMatchIn(code))
        }
    }
}
