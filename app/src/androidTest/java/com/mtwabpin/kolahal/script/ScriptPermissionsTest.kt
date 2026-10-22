package com.mtwabpin.kolahal.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a script may reach is what its `@uses` line declares: private data (notifications, the
 * clipboard, the app list) and this phone or the local network are refused otherwise.
 */
@RunWith(AndroidJUnit4::class)
class ScriptPermissionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A saved script's host; [uses] as its `@uses` line declares them (none: an empty line). */
    private fun script(vararg uses: String) = HostApi(context, "permtest", scriptId = null, notifyEnabled = false, grants = com.mtwabpin.kolahal.script.ScriptAccess.Grants(uses.toSet(), uses.associateWith { true }))

    /** Code typed at the prompt: may use everything. */
    private fun prompt() = HostApi(context, "repl", scriptId = null, notifyEnabled = false)

    private fun luaError(code: String, host: HostApi): String? =
        runCatching { runBlocking { LuaEngine().run(code, "t", host, 10_000, true) } }.exceptionOrNull()?.let { it.message ?: it.toString() }

    private fun jsError(code: String, host: HostApi): String? =
        runCatching { runBlocking { JsEngine().run(code, "t", host, 10_000, true) } }.exceptionOrNull()?.let { it.message ?: it.toString() }

    @Test fun privateDataNeedsItsUsesLine() {
        for ((call, what) in listOf("notifications.recent(5)" to "notifications", "clipboard.get()" to "clipboard", "apps.list()" to "apps")) {
            val error = luaError(call, script())
            assertTrue("$call: $error", error.orEmpty().contains("needs `@uses $what`"))
            // Declaring something else doesn't help.
            assertTrue(call, luaError(call, script("lan")).orEmpty().contains("needs `@uses $what`"))
        }
        assertTrue(jsError("clipboard.set('x')", script()).orEmpty().contains("needs `@uses clipboard`"))
        assertTrue(jsError("notifications.recent(5)", script()).orEmpty().contains("needs `@uses notifications`"))
    }

    @Test fun declaredUsesAreAllowed() {
        assertNull(luaError("clipboard.get()", script("clipboard")))
        assertNull(luaError("apps.list()", script("apps")))
        assertNull(luaError("apps.list()", prompt()))
    }

    /** A web server on this phone that counts the connections it gets. */
    private class TinyServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicInteger()
        val url = "http://127.0.0.1:${socket.localPort}/"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    accepted.incrementAndGet()
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test fun thisPhoneNeedsUsesLan() {
        TinyServer().use { server ->
            val refused = luaError("http.get('${server.url}')", script())
            assertTrue(refused, refused.orEmpty().contains("not allowed"))
            // Not even a connection: it would tell the script which ports are open.
            assertEquals(0, server.accepted.get())
            // With `@uses lan`, or at the prompt, the request goes through.
            for (host in listOf(script("lan"), prompt())) assertNull(luaError("http.get('${server.url}')", host))
            assertEquals(2, server.accepted.get())
        }
    }
}
