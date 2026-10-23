package com.mtwabpin.kolahal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the log keeps: `log.keep-lines`, typed commands counted apart, and each line's context whole. */
@RunWith(AndroidJUnit4::class)
class LogTrimTest {
    private val logs = LogRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private val token = "trim${System.nanoTime()}"
    private val keepBefore = logs.keepLines

    private fun mine() = logs.recent(limit = 2_000).filter { it.text.startsWith(token) }

    @After fun tearDown() {
        logs.keepLines = keepBefore
        mine().forEach { logs.delete(it.id) }
    }

    @Test fun typedCommandsAreCountedApart() {
        repeat(20) { logs.add(LogKind.COMMAND, "$token command $it") }
        repeat(600) { logs.add(LogKind.OUTPUT, "$token output $it") }
        logs.trim(keep = 500)
        val left = mine()
        assertEquals(20, left.count { it.kind == LogKind.COMMAND })
        val outputs = left.filter { it.kind == LogKind.OUTPUT }.map { it.text }
        assertEquals(500, outputs.size)
        assertEquals("$token output 100", outputs.first())
    }

    @Test fun theTrimUsesTheSettingEveryProcessReads() {
        logs.keepLines = 500
        assertEquals(500, logs.keepLines)
        repeat(600) { logs.add(LogKind.OUTPUT, "$token output $it") }
        logs.trim()
        assertEquals(500, mine().size)
    }

    @Test fun aContextTooBigKeepsItsFirstValuesAsWholeJson() {
        val many = (1..2_000).associate { "k$it" to "y".repeat(100) }
        val id = logs.add(LogKind.OUTPUT, "$token many", ctx = LogContext(mapOf(LogContext.SCRIPT to token) + many))
        val ctx = logs.recent(limit = 50).single { it.id == id }.ctx
        assertEquals(token, ctx.script)
        assertTrue(ctx.values.size.toString(), ctx.values.size in 2 until 2_001)
        assertTrue(LogRepository.jsonLength(ctx.values) <= LogRepository.MAX_FIELD)
    }

    @Test fun aValueThatWouldOverflowIsLeftOut() {
        val ctx = LogContext(
            linkedMapOf(LogContext.SCRIPT to token, "junk" to "\u0001".repeat(16_000), LogContext.URL to "https://example.com/$token"),
        )
        val id = logs.add(LogKind.OUTPUT, "$token junk", ctx = ctx)
        val stored = logs.recent(limit = 50).single { it.id == id }.ctx
        assertEquals(token, stored.script)
        assertEquals("https://example.com/$token", stored.url)
        assertFalse("junk" in stored.values)
    }

    @Test fun jsonLengthIsNeverShortOfOrgJson() {
        val samples = listOf(
            "", "plain", "a/b\"c\\d", "\t\b\n\r\u000c", "\u0001\u001f\u007f", "é😀",
            String(charArrayOf(Char(0x2028), Char(0x2029))),
        )
        for (s in samples) {
            val values = mapOf("k$s" to s, "url" to "https://example.com/?q=$s")
            val written = JSONObject(values).toString().length
            assertTrue("$s: $written", LogRepository.jsonLength(values) >= written)
        }
    }
}
