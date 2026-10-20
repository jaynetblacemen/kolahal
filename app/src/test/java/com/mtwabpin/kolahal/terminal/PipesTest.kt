package com.mtwabpin.kolahal.terminal

import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.LogRepository.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipesTest {
    private fun lines(vararg t: String) = t.mapIndexed { i, s -> Pending(LogKind.OUTPUT, s, null, LogContext.EMPTY, i.toLong()) }
    private fun run(stages: String, vararg t: String) = Pipes.apply(lines(*t), Pipes.split("x | $stages").drop(1)).getOrThrow().map { it.text }

    @Test fun splitOnlyOnKnownFilters() {
        assertEquals(listOf("help", "grep app", "head 3"), Pipes.split("help | grep app | head 3"))
        assertEquals(listOf("js a || b"), Pipes.split("js a || b"))
        assertEquals(listOf("lua return 1 | 2"), Pipes.split("lua return 1 | 2"))
    }

    @Test fun grepIsCaseInsensitiveAndInvertible() {
        assertEquals(listOf("Apple", "pineapple"), run("grep apple", "Apple", "pear", "pineapple"))
        assertEquals(listOf("pear"), run("grep -v apple", "Apple", "pear", "pineapple"))
        assertEquals("bad regex falls back to a literal", listOf("a(b"), run("grep a(", "a(b", "ab"))
    }

    @Test fun headTailCountSortUniq() {
        assertEquals(listOf("1", "2"), run("head 2", "1", "2", "3"))
        assertEquals(listOf("3"), run("tail 1", "1", "2", "3"))
        assertEquals(listOf("2"), run("grep a | count", "a", "b", "ca"))
        assertEquals(listOf("a", "b", "c"), run("sort", "c", "a", "b"))
        assertEquals(listOf("a", "b"), run("uniq", "a", "a", "b"))
    }

    @Test fun errorsPassThrough() {
        val input = lines("x") + Pending(LogKind.ERROR, "boom", null, LogContext.EMPTY, 5)
        val out = Pipes.apply(input, listOf("grep nothing")).getOrThrow()
        assertEquals(listOf("boom"), out.map { it.text })
    }

    @Test fun unknownStageFails() {
        assertTrue(Pipes.apply(lines("a"), listOf("grepx")).isFailure)
    }

    @Test fun headAndTailAcceptDashN() {
        val t = (1..12).map { "l$it" }.toTypedArray()
        assertEquals(listOf("l1", "l2", "l3"), run("head -n 3", *t))
        assertEquals(listOf("l11", "l12"), run("tail -n 2", *t))
        assertEquals(listOf("l1", "l2"), run("head -2", *t))
    }
}
