package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A script's **Runs every** and a feed's **Refresh every**. */
@RunWith(AndroidJUnit4::class)
class IntervalGroupTest {
    @get:Rule val compose = createComposeRule()
    private val choices = listOf(0 to "Off", 15 to "15m", 30 to "30m", 60 to "1h", 180 to "3h", 360 to "6h", 720 to "12h", 1440 to "1d")
    private var picked by mutableIntStateOf(0)

    /** The width a 360 dp phone leaves inside a sheet's padding. */
    private fun show(selected: Int, fontScale: Float = 1f) {
        picked = selected
        compose.setContent {
            KolahalTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.width(312.dp)) { IntervalGroup(picked, { picked = it }, choices) }
                }
            }
        }
    }

    private fun assertEveryChoiceFits() {
        for ((_, label) in choices) {
            compose.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val text = results.single()
            assertTrue("$label is cut off: ${text.multiParagraph.maxIntrinsicWidth} in ${text.size.width}", text.multiParagraph.maxIntrinsicWidth <= text.size.width + 1)
        }
    }

    @Test fun oneTapPicksAndOneIsChosen() {
        show(60)
        compose.onNodeWithText("1h").assertIsSelected()
        compose.onNodeWithText("3h").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(180, picked) }
        compose.onNodeWithText("3h").assertIsSelected()
        compose.onNodeWithText("1h").assertIsNotSelected()
    }

    /** `script every 2h` isn't a preset: nothing claims to be chosen. */
    @Test fun aTimeSetFromTheTerminalChoosesNone() {
        show(120)
        for ((_, label) in choices) compose.onNodeWithText(label).assertIsNotSelected()
    }

    @Test fun everyChoiceFitsAndIsEasyToHit() {
        show(0)
        assertEveryChoiceFits()
    }

    /** Android's largest font setting. */
    @Test fun everyChoiceFitsInTheLargestText() {
        show(0, fontScale = 2f)
        assertEveryChoiceFits()
    }
}
