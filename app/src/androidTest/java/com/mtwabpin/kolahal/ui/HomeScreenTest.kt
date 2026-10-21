package com.mtwabpin.kolahal.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The home screen's layout rules, on a frame smaller than the phone. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val config = Config.get(app)

    @After fun tearDown() = Keys.ALL.forEach(config::reset)

    private fun show(width: Dp, height: Dp): TerminalViewModel {
        // On the main thread, as the activity makes it.
        lateinit var vm: TerminalViewModel
        compose.runOnUiThread { vm = TerminalViewModel(app, androidx.lifecycle.SavedStateHandle()) }
        compose.setContent {
            // The frame is all the room there is: the keyboard (the emulator shows one for the
            // focused prompt) and the system bars are taken out of the window, not of the frame.
            KolahalTheme {
                Box(Modifier.size(width, height).testTag("frame").consumeWindowInsets(WindowInsets.safeDrawing)) {
                    TerminalScreen(vm, onRequestNotifications = {}, onPickFolder = {})
                }
            }
        }
        return vm
    }

    private fun type(vm: TerminalViewModel, text: String) {
        compose.runOnIdle { vm.onInputChange(TextFieldValue(text, TextRange(text.length))) }
        compose.waitForIdle()
    }

    /**
     * The suggestion list is capped at 40% of the window, which can be more than the room left
     * above the key bar and prompt: the list must give way, not push them out (21 October 2026).
     */
    @Test fun longSuggestionListKeepsKeyBarAndPromptInside() {
        config.set(Keys.SUGGESTIONS, "30")
        config.set(Keys.KEYBAR_SHOW, "on")
        config.set(Keys.KEYBAR_AUTO, "off")
        val vm = show(360.dp, 320.dp)
        type(vm, "s")
        compose.onNodeWithTag("suggestions").assertExists()
        val frame = compose.onNodeWithTag("frame").getUnclippedBoundsInRoot()
        // Pushed out, or squeezed to nothing at the bottom: both lose the prompt.
        val prompt = compose.onNodeWithTag("prompt", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the prompt ends at ${prompt.bottom}, the frame at ${frame.bottom}", prompt.bottom <= frame.bottom)
        assertTrue("the prompt is ${prompt.bottom - prompt.top} tall", prompt.bottom - prompt.top >= 16.dp)
        val rows = compose.onAllNodesWithTag("keybar", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val bottom = with(compose.density) { row.boundsInRoot.bottom.toDp() }
            assertTrue("a key bar row ends at $bottom, the frame at ${frame.bottom}", bottom <= frame.bottom && row.size.height > 0)
        }
    }

    @Test fun headerIsOneLineWhileTyping() {
        val vm = show(360.dp, 700.dp)
        type(vm, "s")
        compose.onNodeWithTag("header-compact").assertExists()
        compose.onNodeWithTag("header").assertDoesNotExist()
    }

    @Test fun dollarOpensQuickActions() {
        show(360.dp, 700.dp)
        compose.onNodeWithTag("prompt-quick").performClick()
        compose.onNodeWithText("Quick actions").assertIsDisplayed()
        compose.onNodeWithText("Unlocks this week").assertIsDisplayed()
    }
}
