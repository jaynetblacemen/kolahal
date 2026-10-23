package com.mtwabpin.kolahal.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.LogRepository
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Links and pictures on a script's lines follow the script's internet switch and its secrets' sites, as requests do. */
@RunWith(AndroidJUnit4::class)
class ScriptLinksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val logs = LogRepository.get(context)
    private val name = "sl${System.nanoTime()}"

    private fun host(internet: Boolean, secrets: HostApi.Secrets? = null) = HostApi(
        context, name, scriptId = null, notifyEnabled = false,
        grants = ScriptAccess.Grants(setOf(ScriptAccess.INTERNET), mapOf(ScriptAccess.INTERNET to internet)), secrets = secrets,
    )

    private fun lines() = logs.recent(50).filter { it.source == name }

    @After fun tearDown() = lines().forEach { logs.delete(it.id) }

    @Test fun withInternetOffLinesCarryNoLinkOrPicture() {
        val off = host(internet = false)
        off.log("Digest", null, mapOf("image" to "https://example.com/p.png", "url" to "https://example.com/", "copy" to "Digest"))
        off.flush()
        val ctx = lines().single().ctx
        assertNull(ctx.image)
        assertNull(ctx.url)
        assertEquals("Digest", ctx.copy)
        val on = host(internet = true)
        on.log("Digest", null, mapOf("image" to "https://example.com/p.png", "url" to "https://example.com/"))
        on.flush()
        assertEquals("https://example.com/p.png", lines().last().ctx.image)
        assertEquals("https://example.com/", lines().last().ctx.url)
    }

    @Test fun aSecretInALinkGoesOnlyToItsSiteOverHttps() {
        val secrets = HostApi.Secrets(listOf(ScriptMeta.Secret("KEY", listOf("api.example.com"), "")), mapOf("KEY" to "s3cr3tvalue"))
        val h = host(internet = true, secrets = secrets)
        assertThrows(IOException::class.java) { h.log("x", null, mapOf("url" to "https://evil.example/?k=s3cr3tvalue")) }
        assertThrows(IOException::class.java) { h.log("x", null, mapOf("image" to "http://api.example.com/p?k=s3cr3tvalue")) }
        assertThrows(IOException::class.java) { h.httpRequest("http://api.example.com/?k=s3cr3tvalue") }
        // Its own site over https: the line is written; the log never shows the value itself.
        h.log("x", null, mapOf("url" to "https://api.example.com/?k=s3cr3tvalue"))
        h.flush()
        val url = lines().single().ctx.url!!
        assertTrue(url, url.startsWith("https://api.example.com/") && "s3cr3tvalue" !in url)
    }
}
