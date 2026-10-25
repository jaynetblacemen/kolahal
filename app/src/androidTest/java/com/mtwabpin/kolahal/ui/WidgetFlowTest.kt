package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The widgets page's rows run from the start: left to right, or right to left in such a language. */
@RunWith(AndroidJUnit4::class)
class WidgetFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowsRunFromTheStart() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.width(300.dp)) {
                    // Two half-width widgets side by side, no gap.
                    WidgetFlow(listOf(6, 6), 0.dp) {
                        Box(Modifier.height(40.dp).testTag("first"))
                        Box(Modifier.height(40.dp).testTag("second"))
                    }
                }
            }
        }
        fun left(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().left.value
        assertEquals(0f, left("first"), 0.5f)
        assertEquals(150f, left("second"), 0.5f)
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        assertEquals(150f, left("first"), 0.5f)
        assertEquals(0f, left("second"), 0.5f)
    }
}
