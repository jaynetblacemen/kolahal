package com.mtwabpin.kolahal.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** What scripts get from `json.decode` and the feed parser. */
@RunWith(AndroidJUnit4::class)
class ScriptDataTest {
    private fun refused(text: String) {
        try {
            Json.decode(text)
            fail("decoded: $text")
        } catch (_: org.json.JSONException) {
        }
    }

    @Test fun decodeTakesJson() {
        assertEquals(mapOf("a" to 1), Json.decode(""" { "a": 1 } """))
        assertEquals("text", Json.decode("\"text\""))
        assertEquals(listOf(1, 2), Json.decode("[1, 2]\n"))
        assertEquals(null, Json.decode("null"))
    }

    @Test fun decodeRefusesWhatIsntJson() {
        refused("<!DOCTYPE html><html>…</html>")
        refused("""{"a": 1} trailing""")
        refused("[1] [2]")
    }

    @Test fun aBlankGuidIsNoId() {
        val feed = RssParser.parse(
            """<rss><channel><item><title>One</title><link>https://example.com/1</link><guid/></item>
               <item><title>Two</title><link>https://example.com/2</link><guid> </guid></item></channel></rss>""",
        )
        assertEquals(listOf("https://example.com/1", "https://example.com/2"), feed.items.map { it.id })
    }

    @Test fun anAtomSourceDoesntRenameTheEntry() {
        val feed = RssParser.parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>tag:entry</id><title>Entry</title>
               <source><id>tag:other-feed</id><title>Other feed</title></source></entry></feed>""",
        )
        val item = feed.items.single()
        assertEquals("Entry", item.title)
        assertEquals("tag:entry", item.id)
        assertTrue(feed.title == null || feed.title != "Other feed")
    }
}
