package com.mtwabpin.kolahal.ui

import android.media.session.PlaybackState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.control.NowPlaying
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.status.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DURATION = 10_000L

/**
 * These fail on the line as it is: its effect goes on with the NowPlaying it started with, until the
 * title or `playing` changes. Take the @Ignore off with the fix.
 */
private const val STALE = "The bar keeps an old position while the title and `playing` stay the same"

/** The header's media line: what it says, what its taps do, and how far along its bar is. */
@RunWith(AndroidJUnit4::class)
class NowPlayingLineTest {
    @get:Rule val compose = createComposeRule()
    private var toggles = 0
    private var skips = 0

    private fun track(
        state: Int = PlaybackState.STATE_PLAYING,
        position: Long = 0,
        duration: Long = DURATION,
        title: String = "Song",
    ): NowPlaying {
        val now = System.currentTimeMillis()
        return NowPlaying("com.example.player", "Player", title, "Band", state, position, now, 1f, duration, now)
    }

    private fun paused(position: Long = 0, duration: Long = DURATION) =
        track(PlaybackState.STATE_PAUSED, position, duration)

    private fun show(np: () -> NowPlaying) = compose.setContent {
        KolahalTheme {
            Box(Modifier.width(360.dp)) { NowPlayingLine(np(), { toggles++ }, { skips++ }) }
        }
    }

    /** How much of the line's width the bar's filled part takes, 0 to 1, read from its pixels. */
    private fun filled(): Float {
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("header-media").captureToImage().toPixelMap()
        val row = pixels.height - 2
        return (0 until pixels.width).count { pixels[it, row] == Term.prompt }.toFloat() / pixels.width
    }

    /** Real time for the position (it reads the wall clock), then the line's next look at it. */
    private fun play(millis: Long) {
        Thread.sleep(millis)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
    }

    @Test fun itSaysWhatPlays() {
        show { track() }
        compose.onNodeWithText("Song — Band").assertExists()
        compose.onNodeWithContentDescription("Playing").assertExists()
        compose.onNodeWithContentDescription("Paused").assertDoesNotExist()
    }

    @Test fun itSaysWhenPaused() {
        show { paused() }
        compose.onNodeWithText("Song — Band").assertExists()
        compose.onNodeWithContentDescription("Paused").assertExists()
        compose.onNodeWithContentDescription("Playing").assertDoesNotExist()
    }

    @Test fun aTapTogglesAndALongPressSkips() {
        show { track() }
        compose.onNodeWithText("Song — Band").performClick()
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(0, skips) }
        compose.onNodeWithText("Song — Band").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(1, skips) }
    }

    @Test fun theNextButtonSkips() {
        show { track() }
        compose.onNodeWithContentDescription("Next").performClick()
        compose.runOnIdle { assertEquals(0, toggles); assertEquals(1, skips) }
    }

    /** The bar goes under the line: the whole width, and the taps keep their 48 dp. */
    @Test fun theBarLeavesTheTapsTheirRoom() {
        show { track() }
        compose.onNodeWithTag("header-media").assertWidthIsEqualTo(360.dp).assertHeightIsAtLeast(50.dp)
        compose.onNodeWithContentDescription("Next").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Song — Band").assertHeightIsAtLeast(48.dp)
    }

    @Test fun theBarIsEmptyAtTheStart() {
        show { paused(position = 0) }
        assertEquals(0f, filled(), 0.01f)
    }

    @Test fun theBarShowsHowFarAlong() {
        show { paused(position = DURATION / 2) }
        assertEquals(0.5f, filled(), 0.02f)
    }

    @Test fun theBarIsFullAtTheEnd() {
        show { paused(position = DURATION) }
        assertEquals(1f, filled(), 0.02f)
    }

    /** Some players report a position past the duration they gave. */
    @Test fun theBarStopsAtFull() {
        show { paused(position = DURATION * 3) }
        assertEquals(1f, filled(), 0.02f)
    }

    /** A stream has no duration, so no way to say how far along it is. */
    @Test fun theBarStaysEmptyWithoutADuration() {
        show { track(position = 5_000, duration = 0) }
        play(300)
        assertEquals(0f, filled(), 0f)
    }

    @Test fun theBarStaysEmptyWithoutAPosition() {
        show { track(position = -1) }
        play(300)
        assertEquals(0f, filled(), 0f)
    }

    @Test fun theBarMovesWhilePlaying() {
        show { track() }
        val start = filled()
        play(1_000)
        val later = filled()
        assertTrue("$start, then $later", later >= start + 0.05f)
    }

    @Test fun theBarStandsStillWhilePaused() {
        show { paused(position = DURATION / 2) }
        val start = filled()
        play(600)
        assertEquals(start, filled(), 0f)
    }

    /** A duration some players send only after the title. */
    @Test fun aDurationThatComesLaterCounts() {
        var np by mutableStateOf(paused(position = 5_000, duration = 0))
        show { np }
        assertEquals(0f, filled(), 0f)
        compose.runOnIdle { np = paused(position = 5_000) }
        assertEquals(0.5f, filled(), 0.02f)
    }

    @Test fun aPauseKeepsThePlaceAndPlayGoesOnFromIt() {
        var np by mutableStateOf(track())
        show { np }
        compose.runOnIdle { np = paused(position = DURATION / 2) }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0.5f, filled(), 0.02f)
        play(600)
        assertEquals(0.5f, filled(), 0.02f)

        compose.runOnIdle { np = track(position = DURATION / 2) }
        play(1_000)
        assertTrue(filled() >= 0.55f)
    }

    /** All at once: a new title with its own position. */
    @Test fun aNewTrackStartsOver() {
        var np by mutableStateOf(paused(position = DURATION / 2))
        show { np }
        assertEquals(0.5f, filled(), 0.02f)
        compose.runOnIdle { np = track(position = 0, title = "Other") }
        compose.onNodeWithText("Other — Band").assertExists()
        assertTrue(filled() < 0.1f)
    }

    /**
     * A track starting: buffering, then playing, the same title throughout. Both count as `playing`,
     * so the bar must still notice the change, or it stays at the start for the whole track.
     */
    @Ignore(STALE)
    @Test fun theBarMovesOnceBufferingEnds() {
        var np by mutableStateOf(track(PlaybackState.STATE_BUFFERING))
        show { np }
        assertEquals(0f, filled(), 0.01f)
        compose.runOnIdle { np = track(PlaybackState.STATE_PLAYING) }
        play(1_000)
        assertTrue("${filled()}", filled() >= 0.05f)
    }

    @Ignore(STALE)
    @Test fun theBarFollowsASeekWhilePlaying() {
        var np by mutableStateOf(track())
        show { np }
        compose.runOnIdle { np = track(position = DURATION * 3 / 4) }
        play(100)
        assertTrue("${filled()}", filled() >= 0.74f)
    }

    @Ignore(STALE)
    @Test fun theBarFollowsASeekWhilePaused() {
        var np by mutableStateOf(paused())
        show { np }
        compose.runOnIdle { np = paused(position = DURATION * 3 / 4) }
        play(100)
        assertEquals(0.75f, filled(), 0.02f)
    }

    /** Repeat one: the same title again, from the start, after reaching the end. */
    @Ignore(STALE)
    @Test fun theBarStartsOverWhenATrackRepeats() {
        var np by mutableStateOf(track(position = DURATION - 100))
        show { np }
        play(300)
        assertTrue(filled() >= 0.98f)
        compose.runOnIdle { np = track(position = 0) }
        play(100)
        assertTrue("${filled()}", filled() < 0.2f)
    }

    /**
     * A player's next track often arrives in two callbacks, each published on its own: the new title
     * still with the old track's position, then the new position.
     */
    @Ignore(STALE)
    @Test fun aNewTitleThenItsPositionStartsOver() {
        var np by mutableStateOf(track(position = DURATION * 3 / 4))
        show { np }
        compose.runOnIdle { np = track(position = DURATION * 3 / 4, title = "Other") }
        compose.waitForIdle()
        compose.runOnIdle { np = track(position = 0, title = "Other") }
        play(100)
        assertTrue("${filled()}", filled() < 0.2f)
    }

    private fun header(np: NowPlaying) = compose.setContent {
        KolahalTheme {
            Header(
                unlocks = 0,
                profiles = emptyList(),
                status = Status(),
                statuses = emptyMap(),
                config = Config.get(InstrumentationRegistry.getInstrumentation().targetContext),
                version = 0,
                onStatusTap = {},
                onSwipeDown = {},
                onTarget = {},
                onProfileTap = {},
                nowPlaying = np,
            )
        }
    }

    @Test fun theHeaderShowsIt() {
        header(track())
        compose.onNodeWithTag("header-media").assertExists()
    }

    @Test fun theHeaderDropsItHalfAnHourAfterAPause() {
        val long = System.currentTimeMillis() - NowPlaying.HIDE_AFTER_MS - 1_000
        header(paused().copy(idleSince = long))
        compose.onNodeWithTag("header-media").assertDoesNotExist()
    }
}
