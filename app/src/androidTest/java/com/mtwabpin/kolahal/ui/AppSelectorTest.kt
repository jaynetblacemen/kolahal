package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private fun show(left: Boolean, position: String) = compose.setContent {
        Box(Modifier.size(360.dp, 700.dp)) {
            AppSelector(apps.apps.value, emptySet(), onLaunch = {}, onLongPress = {}, heightDp = 300, left = left, position = position)
        }
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
