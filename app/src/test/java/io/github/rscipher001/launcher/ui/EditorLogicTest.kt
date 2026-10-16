package io.github.rscipher001.launcher.ui

import io.github.rscipher001.launcher.data.ScriptLang
import io.github.rscipher001.launcher.script.ApiDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLogicTest {

    // ---- find ----

    @Test fun findsNonOverlappingMatches() {
        val m = EditorLogic.findMatches("aaaa", "aa", ignoreCase = false)
        assertEquals(listOf(0, 2), m.starts.toList())
        assertEquals(2, m.length)
    }

    @Test fun findRespectsCase() {
        val text = "Log log LOG"
        assertEquals(listOf(4), EditorLogic.findMatches(text, "log", false).starts.toList())
        assertEquals(listOf(0, 4, 8), EditorLogic.findMatches(text, "log", true).starts.toList())
    }

    @Test fun emptyQueryFindsNothing() {
        assertEquals(0, EditorLogic.findMatches("abc", "", true).size)
        assertEquals(0, EditorLogic.findMatches("ab", "abc", true).size)
    }

    @Test fun findIsCapped() {
        val m = EditorLogic.findMatches("x".repeat(50), "x", false, limit = 10)
        assertEquals(10, m.size)
        assertTrue(m.capped)
        assertFalse(EditorLogic.findMatches("xx", "x", false).capped)
    }

    @Test fun findHandlesLargeText() {
        val text = "local x = http.get(url)\n".repeat(90_000) // ~2 MB
        val m = EditorLogic.findMatches(text, "HTTP", true)
        assertEquals(90_000, m.size)
    }

    @Test fun indexAtOrAfterWraps() {
        val m = EditorLogic.findMatches("a.a.a", "a", false) // 0, 2, 4
        assertEquals(0, m.indexAtOrAfter(0))
        assertEquals(1, m.indexAtOrAfter(1))
        assertEquals(2, m.indexAtOrAfter(4))
        assertEquals(0, m.indexAtOrAfter(5))
        assertEquals(-1, EditorLogic.Matches.EMPTY.indexAtOrAfter(0))
    }

    @Test fun replaceAllCountsAndReplaces() {
        val (out, n) = EditorLogic.replaceAll("Foo foo FOO", "foo", "bar", ignoreCase = true)
        assertEquals("bar bar bar", out)
        assertEquals(3, n)
        assertEquals("Foo bar FOO" to 1, EditorLogic.replaceAll("Foo foo FOO", "foo", "bar", ignoreCase = false))
        assertEquals("abc" to 0, EditorLogic.replaceAll("abc", "z", "y", ignoreCase = true))
    }

    // ---- prefix ----

    @Test fun prefixPlainIdentifier() =
        assertEquals(EditorLogic.Prefix(null, "lo", 6), EditorLogic.prefixAt("x = 1 lo", 8))

    @Test fun prefixAfterNamespace() {
        assertEquals(EditorLogic.Prefix("http", "ge", 5), EditorLogic.prefixAt("http.ge", 7))
        assertEquals(EditorLogic.Prefix("http", "", 5), EditorLogic.prefixAt("http.", 5))
    }

    @Test fun prefixRejectsChainsAndNumbers() {
        assertNull(EditorLogic.prefixAt("a.http.ge", 9))
        assertNull(EditorLogic.prefixAt("s:http.ge", 9))
        assertNull(EditorLogic.prefixAt("x = 12", 6))
    }

    // ---- completion ----

    private fun names(code: String, lang: ScriptLang = ScriptLang.LUA) =
        EditorLogic.completions(code, code.length, lang).map { it.name }

    @Test fun completesNamespaceMembers() {
        assertEquals(listOf("http.get", "http.cached", "http.request"), names("local r = http."))
        assertEquals(listOf("http.get"), names("http.g"))
    }

    @Test fun needsTwoCharsWithoutQualifier() {
        assertTrue(names("l").isEmpty())
        assertTrue("log" in names("lo"))
        assertTrue("local" in names("lo"))
    }

    @Test fun apiRanksBeforeKeywords() {
        // `log` (API) before `local` (keyword) for the same prefix.
        assertEquals(listOf("log", "local"), names("lo"))
    }

    @Test fun caseSensitivePrefixRanksFirst() {
        val docs = listOf(
            ApiDoc("Store", "", ""),
            ApiDoc("store", "", "", kind = ApiDoc.Kind.NAMESPACE),
            ApiDoc("storeAll", "", ""),
        )
        val ranked = EditorLogic.rank(docs, EditorLogic.Prefix(null, "st", 0)).map { it.name }
        assertEquals(listOf("store", "storeAll", "Store"), ranked)
    }

    @Test fun completionIsLanguageSpecific() {
        assertTrue(names("json.").isNotEmpty())
        assertTrue(names("json.", ScriptLang.JS).isEmpty())
        assertEquals(listOf("console.log", "console.error"), names("console.", ScriptLang.JS))
        assertTrue("const" in names("co", ScriptLang.JS))
        assertFalse("const" in names("co", ScriptLang.LUA))
    }

    @Test fun unknownQualifierCompletesNothing() = assertTrue(names("foo.ge").isEmpty())

    @Test fun noCompletionInCommentsOrStrings() {
        assertTrue(names("-- http.").isEmpty())
        assertTrue(names("log(\"http.").isEmpty())
        assertTrue(names("// http.", ScriptLang.JS).isEmpty())
        assertTrue(names("log(\"a\") http.").isNotEmpty())
    }

    @Test fun finishedKeywordIsNotOffered() = assertFalse("end" in names("end"))

    @Test fun insertionAddsParenOrDot() {
        val get = EditorLogic.completions("http.g", 6, ScriptLang.LUA).first()
        assertEquals("get(", EditorLogic.insertion(get, null))
        assertEquals("get", EditorLogic.insertion(get, '('))
        val ns = EditorLogic.completions("ht", 2, ScriptLang.LUA).first { it.kind == ApiDoc.Kind.NAMESPACE }
        assertEquals("http.", EditorLogic.insertion(ns, null))
    }

    // ---- inline docs ----

    @Test fun docInsideCall() {
        val code = "local r = http.get(\"https://x\", { a = 1 }"
        assertEquals("http.get", EditorLogic.docAt(code, code.length, ScriptLang.LUA)?.name)
    }

    @Test fun docOnIdentifier() {
        assertEquals("rss.fresh", EditorLogic.docAt("rss.fresh", 5, ScriptLang.LUA)?.name)
        assertEquals("rss", EditorLogic.docAt("rss.", 4, ScriptLang.LUA)?.name)
    }

    @Test fun docAfterClosedCallIsNone() {
        assertNull(EditorLogic.docAt("log(\"x\") ", 9, ScriptLang.LUA))
        assertNull(EditorLogic.docAt("foo(", 4, ScriptLang.LUA))
    }

    @Test fun docUsesLanguageSignature() {
        assertTrue(EditorLogic.docAt("http.get(", 9, ScriptLang.JS)!!.signature.startsWith("await"))
        assertFalse(EditorLogic.docAt("http.get(", 9, ScriptLang.LUA)!!.signature.startsWith("await"))
    }

    // ---- lines ----

    @Test fun lineStartOffsets() {
        val t = "a\nbb\nccc"
        assertEquals(0, EditorLogic.lineStart(t, 1))
        assertEquals(2, EditorLogic.lineStart(t, 2))
        assertEquals(5, EditorLogic.lineStart(t, 3))
        assertEquals(t.length, EditorLogic.lineStart(t, 9))
    }
}
