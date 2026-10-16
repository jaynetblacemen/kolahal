package io.github.rscipher001.launcher.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.data.LogRepository
import io.github.rscipher001.launcher.data.ScriptLang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun host() = HostApi(context, "test", scriptId = null, notifyEnabled = false)

    private fun lua(code: String, repl: Boolean = true, timeout: Long = 5_000) =
        runBlocking { LuaEngine().run(code, "t", host(), timeout, repl) }

    private fun js(code: String, repl: Boolean = true, timeout: Long = 5_000) =
        runBlocking { JsEngine().run(code, "t", host(), timeout, repl) }

    @Test fun luaIsVersion54() = assertEquals("Lua 5.4", lua("_VERSION"))

    @Test fun luaExpressionAndTables() {
        assertEquals("3", lua("1 + 2"))
        val out = lua("json.decode('{\"a\":1,\"b\":[1,2,3]}')")!!
        assertEquals(mapOf("a" to 1, "b" to listOf(1, 2, 3)), Json.parse(out))
    }

    @Test fun luaSandboxRemovesDangerousGlobals() {
        assertEquals("nil\tnil\tnil\tnil\tnil", lua("java, io, debug, require, os.execute"))
    }

    @Test fun luaInfiniteLoopTimesOut() {
        val started = System.currentTimeMillis()
        try {
            lua("while true do end", repl = false, timeout = 1_000)
            fail("expected timeout")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("timed out"))
        }
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test fun luaPcallCannotSwallowTimeout() {
        val started = System.currentTimeMillis()
        try {
            lua("while true do pcall(function() while true do end end) end", repl = false, timeout = 1_000)
            fail("expected timeout")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("timed out"))
        }
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test fun luaXpcallAndResumeCannotSwallowTimeout() {
        for (code in listOf(
            "while true do xpcall(function() while true do end end, function(e) return e end) end",
            "while true do local co = coroutine.create(function() while true do end end); coroutine.resume(co) end",
        )) {
            try {
                lua(code, repl = false, timeout = 1_000)
                fail("expected timeout for: $code")
            } catch (e: Exception) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("timed out"))
            }
        }
    }

    @Test fun luaPcallStillCatchesOrdinaryErrors() {
        assertEquals("false\tboom", lua("pcall(error, 'boom', 0)"))
    }

    @Test fun luaLoadKeepsGlobals() {
        assertEquals("2", lua("load('x = 1; return x + 1')()"))
        assertEquals("nil", lua("tostring(load('\\27Lua'))"))  // binary chunks are rejected
    }

    @Test fun luaMemoryCap() {
        try {
            lua("local t = {} for i = 1, 1e8 do t[i] = ('x'):rep(64) .. i end", repl = false, timeout = 20_000)
            fail("expected memory limit")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("memory limit"))
        }
    }

    @Test fun luaArgsAreExposed() {
        val out = runBlocking { LuaEngine().run("return argline .. '|' .. #args .. '|' .. args[2]", "t", host(), 5_000, false, listOf("a", "b c")) }
        assertEquals("a b c|2|b c", out)
    }

    @Test fun jsArgsAreExposed() {
        val out = runBlocking { JsEngine().run("return argline + '|' + args.length + '|' + args[1]", "t", host(), 5_000, false, listOf("a", "b c")) }
        assertEquals("a b c|2|b c", out)
    }

    @Test fun luaRssParseAndStore() {
        val out = lua(
            """
            local feed = rss.parse([[<rss><channel><title>T</title>
              <item><title>One</title><link>https://a/1</link><guid>1</guid></item>
              <item><title>Two</title><link>https://a/2</link></item>
            </channel></rss>]])
            store.set("k", "v")
            return feed.title .. ":" .. #feed.items .. ":" .. feed.items[2].link .. ":" .. store.get("k")
            """.trimIndent(),
            repl = false,
        )
        assertEquals("T:2:https://a/2:v", out)
    }

    @Test fun luaLogWritesContext() {
        val logs = LogRepository.get(context)
        lua("log('hello from lua', { url = 'https://example.com' })", repl = false)
        val last = logs.recent(5).last { it.kind == LogKind.SCRIPT }
        assertEquals("hello from lua", last.text)
        assertEquals("https://example.com", last.ctx.url)
    }

    @Test fun jsExpression() = assertEquals("42", js("6 * 7"))

    @Test fun jsAsyncScriptReturnsValue() {
        val out = js(
            """
            const feed = rss.parse('<feed><title>A</title><entry><title>E</title><link href="https://e"/></entry></feed>');
            store.set("x", "y");
            return feed.title + ":" + feed.items[0].link + ":" + store.get("x");
            """.trimIndent(),
            repl = false,
        )
        assertEquals("A:https://e:y", out)
    }

    @Test fun jsInfiniteLoopTimesOut() {
        try {
            js("while (true) {}", repl = false, timeout = 1_000)
            fail("expected timeout")
        } catch (e: Exception) {
            // QuickJsInterruptedException or a TimeoutCancellation, either is fine
        }
    }

    /** End to end: snippet goes through WorkManager into the :script process and back via the DB. */
    @Test fun replEvalRunsInScriptProcess() {
        val logs = LogRepository.get(context)
        val marker = "pid-check-" + System.nanoTime()
        ScriptScheduler.eval(context, ScriptLang.LUA, "log('$marker')")
        ScriptScheduler.eval(context, ScriptLang.JS, "log('$marker-js')")
        val deadline = System.currentTimeMillis() + 30_000
        var luaSeen = false
        var jsSeen = false
        while (System.currentTimeMillis() < deadline && !(luaSeen && jsSeen)) {
            val recent = logs.recent(50)
            luaSeen = recent.any { it.text == marker }
            jsSeen = recent.any { it.text == "$marker-js" }
            Thread.sleep(250)
        }
        assertTrue("lua snippet never logged", luaSeen)
        assertTrue("js snippet never logged", jsSeen)
        assertNotNull(logs.recent(50).firstOrNull { it.text == marker }?.source)
    }
}
