package com.mtwabpin.kolahal.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlTest {
    @Test
    fun parsesNestedOutlinesAndAttributeVariants() {
        val xml = """
            <?xml version="1.0"?>
            <opml version="1.0">
              <head><title>subs</title></head>
              <body>
                <outline text="Tech">
                  <outline type="rss" text="Ars &amp; Co" xmlUrl="https://example.com/feed?a=1&amp;b=2"/>
                  <outline text="Nested">
                    <outline title='Single quoted' xmlurl='http://b.example/rss.xml' />
                  </outline>
                </outline>
                <!-- <outline text="commented" xmlUrl="https://gone.example/"/> -->
                <outline text="No url"/>
                <outline type="rss" xmlUrl="https://c.example/atom"></outline>
                <outline text="dupe" xmlUrl="https://c.example/atom"/>
              </body>
            </opml>
        """.trimIndent()
        val entries = Opml.parse(xml)
        assertEquals(
            listOf(
                Opml.Entry("Ars & Co", "https://example.com/feed?a=1&b=2"),
                Opml.Entry("Single quoted", "http://b.example/rss.xml"),
                Opml.Entry(null, "https://c.example/atom"),
            ),
            entries,
        )
    }

    @Test
    fun roundTrip() {
        val feeds = listOf(
            Opml.Entry("Quotes \"and\" <tags> & 'apos'", "https://x.example/feed?x=1&y=2"),
            Opml.Entry("Unicode — ünïcödé", "http://y.example/rss"),
        )
        assertEquals(feeds, Opml.parse(Opml.write(feeds)))
    }

    @Test
    fun untitledFeedExportsUrlAsText() {
        val parsed = Opml.parse(Opml.write(listOf(Opml.Entry(null, "https://z.example/"))))
        assertEquals(listOf(Opml.Entry("https://z.example/", "https://z.example/")), parsed)
    }

    @Test
    fun numericEntities() {
        assertEquals("a—b'", Opml.unescape("a&#8212;b&#x27;"))
    }
}

class FeedUrlTest {
    @Test
    fun normalizes() {
        assertEquals("https://example.com/", FeedUrl.normalize("example.com"))
        assertEquals("https://example.com/feed.xml", FeedUrl.normalize("  HTTPS://Example.COM:443/feed.xml#top "))
        assertEquals("http://example.com:8080/rss?x=1", FeedUrl.normalize("http://example.com:8080/rss?x=1"))
        assertEquals("https://example.com/rss", FeedUrl.normalize("feed://example.com/rss"))
        assertEquals("https://example.com/rss", FeedUrl.normalize("feed:https://example.com/rss"))
        assertEquals("https://example.com/Path", FeedUrl.normalize("https://example.com/Path"))
    }

    @Test
    fun rejectsNonHttp() {
        assertNull(FeedUrl.normalize(""))
        assertNull(FeedUrl.normalize("ftp://example.com/feed"))
        assertNull(FeedUrl.normalize("javascript:alert(1)"))
        assertNull(FeedUrl.normalize("http://"))
        assertNull(FeedUrl.normalize("exa mple.com"))
    }

    @Test
    fun shortName() {
        assertEquals("example.com", FeedUrl.shortName(null, "https://www.example.com/feed"))
        assertEquals("Blog", FeedUrl.shortName(" Blog ", "https://example.com/"))
        assertEquals("Hacker News", FeedUrl.shortName("Hacker News: Front Page", "https://hnrss.org/"))
        assertEquals("abcd…", FeedUrl.shortName("abcdefgh", "https://example.com/", max = 5))
    }
}

class FeedScheduleTest {
    private val min = 60_000L

    @Test
    fun dueByInterval() {
        val now = 1_000_000_000L
        assertTrue(FeedScheduler.isDue(true, null, 60, now))
        assertFalse(FeedScheduler.isDue(false, null, 60, now))
        assertFalse(FeedScheduler.isDue(true, now - 30 * min, 60, now))
        assertTrue(FeedScheduler.isDue(true, now - 60 * min, 60, now))
        // Slack: a 30-minute feed is picked up by a run that fires slightly early.
        assertTrue(FeedScheduler.isDue(true, now - 29 * min, 30, now))
        // Intervals below the floor are treated as 15 minutes.
        assertFalse(FeedScheduler.isDue(true, now - 5 * min, 1, now))
    }
}
