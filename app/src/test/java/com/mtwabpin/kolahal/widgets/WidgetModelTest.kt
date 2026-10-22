package com.mtwabpin.kolahal.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private fun w(id: Int?, order: Int, location: String = WidgetLocation.PANEL, provider: String = "com.a/.W$order", height: Int = 120) =
        PlacedWidget(id, provider, 0L, height, order, location)

    @Test fun codecRoundTrips() {
        val all = listOf(
            w(5, 0),
            w(7, 1, WidgetLocation.OLD_HOME, "com.example.clock/com.example.clock.ClockWidget\$Big"),
            w(null, 2, WidgetLocation.OLD_HOME),
        ).map { it.copy(profileSerial = 10L) }
        assertEquals(all, WidgetCodec.decode(WidgetCodec.encode(all)))
    }

    @Test fun decodeSkipsBrokenLinesAndClamps() {
        val text = "garbage\n3|com.a/.W|0|5|0|panel\n|nocomponent|0|100|1|home\n4|com.b/.W|x|99999|y|weird"
        val got = WidgetCodec.decode(text)
        assertEquals(2, got.size)
        assertEquals(WidgetLayout.MIN_DP, got[0].heightDp)
        assertEquals(WidgetLayout.MAX_DP, got[1].heightDp)
        assertEquals(0L, got[1].profileSerial)
        assertEquals(WidgetLocation.PANEL, got[1].location)
        assertTrue(WidgetCodec.decode(null).isEmpty())
    }

    @Test fun exportDropsIdsAndImportMakesPending() {
        val exported = WidgetCodec.export(listOf(w(9, 1, WidgetLocation.OLD_HOME, height = 200), w(3, 0)))
        assertEquals(listOf("com.a/.W0", "com.a/.W1"), exported.map { it["provider"] })
        assertTrue(exported.none { it.containsKey("id") })
        // JSON round trips may turn numbers into Double / String.
        val imported = WidgetCodec.fromExport(
            listOf(
                mapOf("provider" to "com.a/.W1", "profile" to 0.0, "height" to "200", "location" to "home", "order" to 1),
                mapOf("provider" to "not a component"),
            )
        )
        assertEquals(1, imported.size)
        val p = imported[0]
        assertNull(p.id)
        assertTrue(p.pending)
        assertEquals(200, p.heightDp)
        assertEquals(WidgetLocation.OLD_HOME, p.location)
    }

    @Test fun importTwiceDoesNotDuplicate() {
        val list = listOf(mapOf<String, Any?>("provider" to "com.a/.W", "order" to 0))
        val once = WidgetCodec.mergePending(emptyList(), WidgetCodec.fromExport(list))
        val twice = WidgetCodec.mergePending(once, WidgetCodec.fromExport(list))
        assertEquals(1, twice.size)
    }

    @Test fun moveStaysWithinLocation() {
        val all = listOf(w(1, 0), w(2, 1), w(3, 2), w(4, 0, WidgetLocation.OLD_HOME))
        val moved = WidgetOrder.move(all, "w3", -1)
        assertEquals(listOf(1, 3, 2), WidgetOrder.inLocation(moved, WidgetLocation.PANEL).map { it.id })
        assertEquals(0, moved.first { it.id == 4 }.order)
        // Edges are no-ops.
        assertEquals(all, WidgetOrder.move(all, "w1", -1))
        assertEquals(all, WidgetOrder.move(all, "w4", 1))
    }

    /** Widgets placed on home before 0.3.1 join the widgets page after its own, keeping their order. */
    @Test fun homeWidgetsMoveOntoThePage() {
        val all = listOf(w(1, 1), w(2, 0), w(4, 1, WidgetLocation.OLD_HOME), w(5, 0, WidgetLocation.OLD_HOME), w(null, 3, WidgetLocation.OLD_HOME))
        val page = WidgetOrder.onePage(all)
        assertTrue(page.all { it.location == WidgetLocation.PANEL })
        assertEquals(listOf(2, 1, 5, 4), WidgetOrder.inLocation(page, WidgetLocation.PANEL).map { it.id })
        assertEquals(listOf(0, 1, 2, 3), WidgetOrder.inLocation(page, WidgetLocation.PANEL).map { it.order })
        assertEquals(3, page.single { it.pending }.order)
        val onlyPage = listOf(w(1, 0), w(2, 1))
        assertEquals(onlyPage, WidgetOrder.onePage(onlyPage))
    }

    @Test fun resizeClamps() {
        assertEquals(168, WidgetLayout.resize(120, 48, 100))
        assertEquals(100, WidgetLayout.resize(120, -48, 100))
        assertEquals(WidgetLayout.MAX_DP, WidgetLayout.resize(WidgetLayout.MAX_DP, 48, 48))
    }

    @Test fun minAndDefaultHeights() {
        assertEquals(60, WidgetLayout.minHeight(110, 60, resizableVertically = true))
        assertEquals(110, WidgetLayout.minHeight(110, 60, resizableVertically = false))
        assertEquals(WidgetLayout.MIN_DP, WidgetLayout.minHeight(10, 0, true))
        assertEquals(110, WidgetLayout.defaultHeight(110))
        assertEquals(180, WidgetLayout.defaultHeight(40, targetCellHeight = 2))
        assertEquals(72, WidgetLayout.defaultHeight(20))
    }

    @Test fun sizeLabels() {
        assertEquals("4×2", WidgetLayout.sizeLabel(250, 110))
        assertEquals("1×1", WidgetLayout.sizeLabel(10, 10))
        assertEquals("3×1", WidgetLayout.sizeLabel(250, 110, targetCellWidth = 3, targetCellHeight = 1))
    }

    @Test fun widgetsSitSideBySideWhileTheyFit() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), WidgetLayout.rows(listOf(6, 6, 12)))
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), WidgetLayout.rows(listOf(4, 4, 4, 3)))
        // The next one doesn't fit beside the others: a new row, even with room left.
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), WidgetLayout.rows(listOf(8, 6, 12)))
        assertEquals(emptyList<List<Int>>(), WidgetLayout.rows(emptyList()))
        // Out-of-range spans count as their nearest allowed width.
        assertEquals(listOf(listOf(0), listOf(1)), WidgetLayout.rows(listOf(40, 0)))
    }

    @Test fun widthsOfARowFillThePage() {
        val page = 395
        assertEquals(page, WidgetLayout.widthDp(page, 12))
        // Two halves and the gap between them make the page, give or take a dp of rounding.
        val half = WidgetLayout.widthDp(page, 6)
        assertTrue(2 * half + WidgetLayout.GAP_DP in page - 1..page)
        val thirds = 3 * WidgetLayout.widthDp(page, 4) + 2 * WidgetLayout.GAP_DP
        assertTrue(thirds in page - 2..page)
    }

    @Test fun widthLabelsAndSteps() {
        assertEquals("full", WidgetLayout.spanLabel(12))
        assertEquals("1/2", WidgetLayout.spanLabel(6))
        assertEquals("2/3", WidgetLayout.spanLabel(8))
        assertEquals("1/6", WidgetLayout.spanLabel(2))
        assertEquals("5/12", WidgetLayout.spanLabel(5))
        assertEquals(WidgetLayout.COLUMNS, WidgetLayout.resizeSpan(12, 1))
        assertEquals(WidgetLayout.MIN_SPAN, WidgetLayout.resizeSpan(2, -1))
        assertEquals(5, WidgetLayout.resizeSpan(6, -1))
    }

    @Test fun widthsAreSavedAndOlderLinesAreFullWidth() {
        val half = w(5, 0).copy(span = 6)
        assertEquals(listOf(half), WidgetCodec.decode(WidgetCodec.encode(listOf(half))))
        // Saved by 0.3.1: six fields, no width.
        assertEquals(WidgetLayout.COLUMNS, WidgetCodec.decode("3|com.a/.W|0|120|0|panel").single().span)
        assertEquals(WidgetLayout.MIN_SPAN, WidgetCodec.decode("3|com.a/.W|0|120|0|panel|0").single().span)
    }

    @Test fun backupsCarryTheWidth() {
        val exported = WidgetCodec.export(listOf(w(9, 0).copy(span = 4)))
        assertEquals(4, exported.single()["span"])
        val imported = WidgetCodec.fromExport(
            listOf(
                mapOf("provider" to "com.a/.W0", "span" to 4.0),
                mapOf("provider" to "com.a/.W1"),
                mapOf("provider" to "com.a/.W2", "span" to "99"),
            )
        )
        assertEquals(listOf(4, WidgetLayout.COLUMNS, WidgetLayout.COLUMNS), imported.map { it.span })
    }
}
