package com.mtwabpin.kolahal.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The tour: how Kolahal works, and the first few settings on the way, each one tap. */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val config = Config.get(app)
    private lateinit var vm: TerminalViewModel

    @After fun tearDown() = Keys.ALL.forEach(config::reset)

    /** The tour, opened from home, on a phone-sized frame. */
    private fun show() {
        compose.runOnUiThread {
            vm = TerminalViewModel(app, SavedStateHandle())
            vm.openScreen(Screen.ONBOARDING)
        }
        compose.setContent {
            KolahalTheme {
                Box(Modifier.size(360.dp, 720.dp).consumeWindowInsets(WindowInsets.safeDrawing)) { OnboardingScreen(vm) }
            }
        }
    }

    private fun next() {
        compose.onNodeWithTag("tour-next").performClick()
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertIsDisplayed()

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun theTourGoesPageByPageAndEndsAtHome() {
        show()
        shows("A home screen you type into.")
        for (title in listOf("Type to open", "Everything lands in the log", "Make it yours", "Within your thumb's reach", "Let Kolahal in", "You're set")) {
            next()
            shows(title)
        }
        compose.onNodeWithText("Start").assertIsDisplayed()
        next()
        assertEquals(Screen.TERMINAL, vm.screen.value)
    }

    @Test fun backGoesToThePageBeforeAndSkipLeaves() {
        show()
        next()
        shows("Type to open")
        compose.onNodeWithTag("tour-back").performClick()
        compose.waitForIdle()
        shows("A home screen you type into.")
        compose.onNodeWithTag("tour-skip").performClick()
        compose.waitForIdle()
        assertEquals(Screen.TERMINAL, vm.screen.value)
    }

    @Test fun eachSettingTakesOneTap() {
        show()
        next()
        tap("Chips")
        assertEquals("chips", config[Keys.SUGGESTIONS_STYLE])
        tap("Apps")
        assertEquals("apps", config[Keys.LAUNCH_INSTANT])
        tap("All")
        assertEquals("all", config[Keys.LAUNCH_INSTANT])
        next(); next()
        shows("Make it yours")
        tap("amber")
        assertEquals("amber", config[Keys.THEME])
        tap("16")
        assertEquals(16, config[Keys.FONT_SIZE])
        tap("24-hour")
        assertTrue(config[Keys.CLOCK_24H])
        next()
        tap("Left edge")
        assertTrue(config[Keys.SELECTOR_RAIL])
        assertEquals("left", config[Keys.SELECTOR_SIDE])
        tap("Off")
        assertFalse(config[Keys.SELECTOR_RAIL])
        tap("Key bar")
        assertTrue(config[Keys.KEYBAR_SHOW])
    }

    /** The permissions page offers the ones most people want, with the rest left to the checklist. */
    @Test fun thePermissionsPageOffersTheHomeApp() {
        show()
        repeat(5) { next() }
        shows("Let Kolahal in")
        compose.waitUntil(5_000) { vm.setupChecks.value.isNotEmpty() }
        compose.onNodeWithText("Default home app").performScrollTo().assertIsDisplayed()
    }

    @Test fun tourOpensIt() {
        compose.runOnUiThread { vm = TerminalViewModel(app, SavedStateHandle()) }
        vm.runCommand("tour")
        compose.waitUntil(5_000) { vm.screen.value == Screen.ONBOARDING }
    }
}
