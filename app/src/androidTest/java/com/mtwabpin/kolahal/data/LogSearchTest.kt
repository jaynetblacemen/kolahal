package com.mtwabpin.kolahal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `/words` at the prompt: what [LogRepository.search] finds. */
@RunWith(AndroidJUnit4::class)
class LogSearchTest {
    private val logs = LogRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
    private val token = "zq${System.nanoTime()}"
    private val added = ArrayList<Long>()

    private fun add(text: String, source: String? = null, ctx: LogContext = LogContext.EMPTY) {
        added += logs.add(LogKind.OUTPUT, text, source, ctx)
    }

    @After fun tearDown() = added.forEach(logs::delete)

    private fun texts(vararg words: String) = logs.search(words.toList()).entries.map { it.text }.toSet()

    @Test fun wordsAreFoundInTextSourceAndLink() {
        add("$token one")
        add("two", source = "$token-feed")
        add("three", ctx = LogContext.of(LogContext.URL to "https://example.com/$token"))
        add("four")
        assertEquals(setOf("$token one", "two", "three"), texts(token))
        assertEquals(3, logs.search(listOf(token)).total)
    }

    @Test fun aScriptLinesBodyIsSearchedToo() {
        added += logs.add(LogKind.SCRIPT, "title", "s", LogContext.of(LogContext.TITLE to "1", LogContext.BODY to "$token in the body"))
        assertEquals(setOf("title"), texts(token))
    }

    @Test fun everyWordMustMatchIgnoringCase() {
        add("$token Battery LOW")
        add("$token battery full")
        assertEquals(setOf("$token Battery LOW"), texts(token.uppercase(), "low", "battery"))
    }

    @Test fun newestFirstAndCapped() {
        repeat(5) { add("$token $it") }
        val found = logs.search(listOf(token), limit = 2)
        assertEquals(5, found.total)
        assertEquals(listOf("$token 4", "$token 3"), found.entries.map { it.text })
    }

    @Test fun skippedLinesAreLeftOut() {
        add("$token personal")
        add("$token private", ctx = LogContext.of(LogContext.USER to "77"))
        val found = logs.search(listOf(token), skip = { it.ctx.userSerial == 77L })
        assertEquals(listOf("$token personal"), found.entries.map { it.text })
        assertTrue(found.searched > 0)
    }
}
