package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.data.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app selector's strip on the edge you choose, and its list on the other side, the whole height. */
@RunWith(AndroidJUnit4::class)
class AppSelectorTest {
    @get:Rule val compose = createComposeRule()
    private val apps = AppRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before fun setUp() {
        apps.refresh()
        val until = System.currentTimeMillis() + 5_000
        while (apps.apps.value.size < 5 && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    @Volatile private var launched: AppEntry? = null
    @Volatile private var pressed: AppEntry? = null

    private fun show(
        left: Boolean,
        position: String,
        list: List<AppEntry> = apps.apps.value,
        backGesture: Boolean = false,
        actions: String = "hold",
        icons: Boolean = false,
    ) = compose.setContent {
        Box(Modifier.size(360.dp, 700.dp)) {
            AppSelector(
                list, emptySet(), onLaunch = { launched = it }, onLongPress = { pressed = it }, heightDp = 300, left = left,
                position = position, edgeBackGesture = backGesture, actions = actions, icons = icons,
            )
        }
    }

    private fun iconsShown() = compose.onAllNodesWithTag("app-selector-icon", useUnmergedTree = true).fetchSemanticsNodes().size

    /** Plan E: with suggestions.icons on, each row starts with the app's icon; off, none. */
    @Test fun rowsShowIconsWhenSuggestionsDo() {
        show(left = false, position = "bottom", icons = true)
        compose.onNodeWithTag("app-selector").performTouchInput { down(center) }
        compose.waitForIdle()
        assertTrue("icons in the list", iconsShown() > 0)
    }

    @Test fun rowsShowNoIconsWhenSuggestionsDont() {
        show(left = false, position = "bottom", icons = false)
        compose.onNodeWithTag("app-selector").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(0, iconsShown())
    }

    /** Plan B: holding still while picking opens the app's actions, once its row has filled. */
    @Test fun holdingStillOpensTheActions() {
        show(left = false, position = "bottom", actions = "hold")
        compose.onNodeWithTag("app-selector").performTouchInput { down(center); moveTo(Offset(centerX - 60.dp.toPx(), centerY)) }
        compose.waitUntil(3_000) { pressed != null }
        assertEquals(null, launched)
    }

    /** B2: lifting on the panel's far side opens the actions; nearer the strip it launches. */
    @Test fun theFarSideOpensTheActions() {
        show(left = false, position = "bottom", actions = "far-side")
        compose.onNodeWithTag("app-selector").performTouchInput {
            down(center)
            moveTo(Offset(centerX - 60.dp.toPx(), centerY))
            moveTo(Offset(-290.dp.toPx(), centerY))
            up()
        }
        compose.runOnIdle { assertTrue(pressed != null); assertEquals(null, launched) }
        pressed = null
        compose.onNodeWithTag("app-selector").performTouchInput { down(center); moveTo(Offset(centerX - 60.dp.toPx(), centerY)); up() }
        compose.runOnIdle { assertTrue(launched != null); assertEquals(null, pressed) }
    }

    /** Off: no actions while picking, however long you hold still; lifting launches. */
    @Test fun withActionsOffAHoldJustLaunches() {
        show(left = false, position = "bottom", actions = "off")
        compose.onNodeWithTag("app-selector").performTouchInput { down(center); moveTo(Offset(centerX - 60.dp.toPx(), centerY)) }
        Thread.sleep(1_100)
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithTag("app-selector").performTouchInput { up() }
        compose.runOnIdle { assertTrue(launched != null); assertEquals(null, pressed) }
    }

    private fun height(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    /** Plan A: with gesture navigation only the middle 200dp of the strip starts the selector, and the handle shows it. */
    @Test fun withGestureNavigationOnlyTheMiddleStarts() {
        show(left = false, position = "bottom", backGesture = true)
        val strip = compose.onNodeWithTag("app-selector-strip").getUnclippedBoundsInRoot()
        val zone = compose.onNodeWithTag("app-selector").getUnclippedBoundsInRoot()
        assertEquals(300f, height("app-selector-strip"), 1f)
        assertEquals(200f, height("app-selector"), 1f)
        assertEquals(strip.top.value + 50f, zone.top.value, 1f)
        assertEquals(200f, height("app-selector-handle"), 1f)
        // Near the strip's top end the edge is Android's: nothing opens.
        compose.onNodeWithTag("app-selector-strip").performTouchInput { down(Offset(centerX, 20.dp.toPx())); up() }
        compose.onNodeWithTag("app-selector-panel").assertDoesNotExist()
        compose.onNodeWithTag("app-selector").performTouchInput { down(center) }
        compose.onNodeWithTag("app-selector-panel").assertExists()
    }

    /**
     * Started in the middle, the thumb still reaches the first app at the strip's top end and the last at its
     * bottom. Picking starts 32dp off the edge: from the 36dp strip's centre, a 60dp slide is well past it.
     */
    @Test fun onceDownTheThumbGlidesTheWholeLength() {
        val list = apps.apps.value
        val entries = SelectorMath.build(list.filter { !it.hidden }, { it.label }, { it.key }, { false }, { it.profile.kind.ordinal })
        show(left = false, position = "bottom", list = list, backGesture = true)
        compose.onNodeWithTag("app-selector").performTouchInput {
            down(center)
            moveTo(Offset(centerX, -48.dp.toPx()))
            moveTo(Offset(centerX - 60.dp.toPx(), -48.dp.toPx()))
            up()
        }
        compose.runOnIdle { assertEquals(entries.first().item.key, launched?.key) }
        launched = null
        compose.onNodeWithTag("app-selector").performTouchInput {
            down(center)
            moveTo(Offset(centerX, height + 48.dp.toPx()))
            moveTo(Offset(centerX - 60.dp.toPx(), height + 48.dp.toPx()))
            up()
        }
        compose.runOnIdle { assertEquals(entries.last().item.key, launched?.key) }
    }

    /** Three-button navigation: the whole strip starts it, as before, with the short handle. */
    @Test fun withButtonsTheWholeStripStarts() {
        show(left = false, position = "bottom", backGesture = false)
        assertEquals(300f, height("app-selector"), 1f)
        assertEquals(64f, height("app-selector-handle"), 1f)
        compose.onNodeWithTag("app-selector").performTouchInput { down(Offset(centerX, 20.dp.toPx())) }
        compose.onNodeWithTag("app-selector-panel").assertExists()
    }

    /** The app you're at sits level with the thumb: a short list (it can't scroll) with the strip high up too. */
    @Test fun theListComesLevelWithTheFingerEvenWhenItCantScroll() {
        show(left = false, position = "top", list = apps.apps.value.take(5))
        val strip = compose.onNodeWithTag("app-selector")
        strip.performTouchInput { down(center) }
        val finger = strip.getUnclippedBoundsInRoot().let { (it.top + it.bottom) / 2 }
        val panel = compose.onNodeWithTag("app-selector-panel").getUnclippedBoundsInRoot()
        assertTrue("the panel (${panel.top}..${panel.bottom}) covers the finger ($finger)", panel.top <= finger && finger <= panel.bottom)
    }

    @Test fun offUnlessTurnedOn() {
        assertEquals(false, Keys.SELECTOR_RAIL.default)
        assertEquals("right", Keys.SELECTOR_SIDE.default)
        assertEquals("bottom", Keys.SELECTOR_POSITION.default)
    }

    @Test fun onTheRightAtTheBottomTheListOpensOnTheLeft() {
        show(left = false, position = "bottom")
        val strip = compose.onNodeWithTag("app-selector").getUnclippedBoundsInRoot()
        assertEquals(360f, strip.right.value, 1f)
        // Low on the edge: 72dp above the bottom, 300dp long.
        assertEquals(628.dp.value, strip.bottom.value, 1f)
        assertEquals(300.dp.value, (strip.bottom - strip.top).value, 1f)
        compose.onNodeWithTag("app-selector").performTouchInput { down(center) }
        val list = compose.onNodeWithTag("app-selector-panel").getUnclippedBoundsInRoot()
        assertEquals(0f, list.left.value, 1f)
        assertTrue("the list ends before the strip", list.right < strip.left)
    }

    @Test fun onTheLeftAtTheTopTheListOpensOnTheRight() {
        show(left = true, position = "top")
        val strip = compose.onNodeWithTag("app-selector").getUnclippedBoundsInRoot()
        assertEquals(0f, strip.left.value, 1f)
        assertEquals(8.dp.value, strip.top.value, 1f)
        compose.onNodeWithTag("app-selector").performTouchInput { down(center) }
        val list = compose.onNodeWithTag("app-selector-panel").getUnclippedBoundsInRoot()
        assertEquals(360.dp.value, list.right.value, 1f)
        assertTrue("the list starts after the strip", list.left > strip.right)
    }

    @Test fun inTheMiddleTheStripIsCentred() {
        show(left = false, position = "middle")
        val strip = compose.onNodeWithTag("app-selector").getUnclippedBoundsInRoot()
        // Between the top gap (8dp) and the bottom one (72dp from the bottom).
        assertEquals(((8 + 628) / 2f), ((strip.top + strip.bottom) / 2).value, 1f)
    }
}
