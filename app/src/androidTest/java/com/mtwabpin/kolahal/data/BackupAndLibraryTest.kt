package com.mtwabpin.kolahal.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.QuickJs
import com.mtwabpin.kolahal.apps.AppPrefs
import com.mtwabpin.kolahal.script.ScriptLibrary
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

    /** Settings saved before 0.3.0 move to today's names when Config starts. */
    @Test fun savedSettingsMoveToNewNames() {
        val saved = context.getSharedPreferences("config", android.content.Context.MODE_PRIVATE)
        val old = listOf("theme", "keyboard.onhome", Keys.OLD_SWIPE_UP_ON, "gesture.swipeup")
        try {
            saved.edit().putString("theme", "dracula").putString("keyboard.onhome", "on")
                .putString(Keys.OLD_SWIPE_UP_ON, "on").putString("gesture.swipeup", "quick").commit()
            val before = config.version.value
            config.migrate()
            assertEquals("dracula", config[Keys.THEME])
            assertTrue(config[Keys.FOCUS_ON_HOME])
            assertEquals("quick", config[Keys.GESTURE_SWIPE_UP])
            assertTrue("the screens redraw with the moved values", config.version.value > before)
            assertTrue(saved.all.keys.toString(), old.none(saved::contains))
        } finally {
            saved.edit().apply { old.forEach(::remove) }.commit()
        }
    }

    /** A backup from before 0.3.0 has the old setting names; they land on today's settings. */
    @Test fun oldBackupNamesImport() {
        val file = File(context.cacheDir, "kolahal-backup-old-names.json")
        file.writeText("""{"kolahal":1,"config":{"theme":"amber","keybar2":"TAB","log.lineheight":"30","gesture.swipeup.on":"on"}}""")
        val r = Backup(context).import(Uri.fromFile(file))
        assertEquals("amber", config[Keys.THEME])
        assertEquals("TAB", config[Keys.KEYBAR2])
        assertEquals(30, config[Keys.LOG_LINE_HEIGHT])
        assertEquals("apps", config[Keys.GESTURE_SWIPE_UP])
        assertTrue(r.skipped.toString(), r.skipped.isEmpty())
        file.delete()
    }

    /** Themes made in the theme builder travel in backups, ahead of settings: the one in use comes back in use. */
    @Test fun themesTravelInBackups() {
        val colours = com.mtwabpin.kolahal.ui.Palettes.colorsOf(com.mtwabpin.kolahal.ui.Palettes.gruvbox)
        CustomThemes.save(context, "bk-theme", colours)
        config.set(Keys.THEME, "bk-theme")
        val file = File(context.cacheDir, "kolahal-backup-themes.json")
        try {
            Backup(context).export(Uri.fromFile(file))
            CustomThemes.delete(context, "bk-theme")
            config.reset(Keys.THEME)
            Backup(context).import(Uri.fromFile(file))
            assertEquals(colours, CustomThemes.colors("bk-theme"))
            assertEquals("bk-theme", config[Keys.THEME])
        } finally {
            CustomThemes.delete(context, "bk-theme")
            file.delete()
        }
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
        val todos = com.mtwabpin.kolahal.productivity.TodoStore.get(context)
        val notes = com.mtwabpin.kolahal.productivity.NoteStore.get(context)
        val feeds = com.mtwabpin.kolahal.feeds.FeedRepository.get(context)
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

    /** Your own script whose name an app also has comes back from a backup (switched off, as all do). */
    @Test fun aScriptNamedLikeAnAppComesBack() {
        val apps = com.mtwabpin.kolahal.apps.AppRepository.get(context)
        apps.refresh()
        val until = System.currentTimeMillis() + 5_000
        while (apps.apps.value.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(50)
        val name = apps.apps.value.map { it.label.lowercase().replace(Regex("[^a-z0-9]"), "") }
            .first { it.length in 3..40 && it !in com.mtwabpin.kolahal.terminal.CommandProcessor.RESERVED_NAMES && scripts.byName(it) == null }
        assertNotNull(com.mtwabpin.kolahal.script.ScriptNames.clash(context, name))
        val file = File(context.cacheDir, "kolahal-backup-app-name.json")
        try {
            scripts.create(name, ScriptLang.LUA, "return 1")
            Backup(context).export(Uri.fromFile(file))
            scripts.byName(name)?.let { scripts.delete(it.id) }
            val r = Backup(context).import(Uri.fromFile(file))
            assertTrue(r.skipped.toString(), r.skipped.none { name in it })
            assertFalse(scripts.byName(name)!!.enabled)
        } finally {
            scripts.byName(name)?.let { scripts.delete(it.id) }
            file.delete()
        }
    }

    /** A backup from 0.4.1 or before brings its log back without the old `── name · 22:40 ──` lines. */
    @Test fun oldRunHeadersStayBehindOnRestore() {
        val logs = LogRepository.get(context)
        val header = logs.add(LogKind.INFO, "── bk · 1:00 PM ──", "bk", LogContext.of(LogContext.RUN_HEADER to "1"))
        val line = logs.add(LogKind.SCRIPT, "bk line", "bk")
        val file = File(context.cacheDir, "kolahal-backup-headers.json")
        try {
            Backup(context).export(Uri.fromFile(file))
            Backup(context).import(Uri.fromFile(file))
            val back = logs.recent(limit = 50).filter { it.source == "bk" }.map { it.text }
            assertEquals(listOf("bk line"), back)
        } finally {
            logs.delete(header)
            logs.delete(line)
            logs.recent(limit = 50).filter { it.source == "bk" }.forEach { logs.delete(it.id) }
            file.delete()
        }
    }

    /** What this phone knows of its own calls and apps stays out of a backup, and a restore keeps it. */
    @Test fun thisPhonesRecordsStayOutOfBackups() {
        val packages = KvStore(context, "packages")
        packages.set("bk-device", "this phone")
        val file = File(context.cacheDir, "kolahal-backup-device.json")
        try {
            Backup(context).export(Uri.fromFile(file))
            assertFalse(file.readText().contains("bk-device"))
            Backup(context).import(Uri.fromFile(file))
            assertEquals("this phone", packages.get("bk-device"))
        } finally {
            packages.set("bk-device", null)
            file.delete()
        }
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
