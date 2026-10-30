package com.mtwabpin.kolahal.ui

import android.app.Activity
import android.app.Application
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mtwabpin.kolahal.MainActivity
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** `wallpaper match`: the wallpaper follows the theme, but only while it's still the one Kolahal set. */
@RunWith(AndroidJUnit4::class)
class WallpaperMatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val wallpapers = WallpaperManager.getInstance(context)
    private val config = Config.get(context)
    private val system = WallpaperManager.FLAG_SYSTEM
    private val lock = WallpaperManager.FLAG_LOCK
    private var night = "yes"

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun solid(colour: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    /** Android works a wallpaper's colours out from its picture, so a little off, and after a moment. */
    private fun shows(which: Int, colour: Int): Boolean {
        val c = wallpapers.getWallpaperColors(which)?.primaryColor?.toArgb() ?: return false
        return abs(Color.red(c) - Color.red(colour)) <= 24 && abs(Color.green(c) - Color.green(colour)) <= 24 && abs(Color.blue(c) - Color.blue(colour)) <= 24
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 8_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("$what; matched ${WallpaperMatch.isMatched(context)}, primary ${wallpapers.getWallpaperColors(system)?.primaryColor}", check())
    }

    @Before fun setUp() {
        night = shell("cmd uimode night").substringAfter(':').trim()
        WallpaperMatch.forget(context)
    }

    @After fun tearDown() {
        WallpaperMatch.forget(context)
        wallpapers.clear(system or lock)
        config.reset(Keys.THEME)
        shell("cmd uimode night $night")
    }

    @Test fun itKnowsTheWallpaperItSet() {
        assertFalse(WallpaperMatch.isMatched(context))
        assertTrue(WallpaperMatch.set(context, Color.RED))
        assertTrue(WallpaperMatch.isMatched(context))
        waitFor("red") { shows(system, Color.RED) }
    }

    @Test fun itFollowsWhileTheWallpaperIsItsOwn() {
        WallpaperMatch.set(context, Color.RED)
        val first = wallpapers.getWallpaperId(system)
        WallpaperMatch.follow(context, Color.BLUE)
        waitFor("a new wallpaper") { wallpapers.getWallpaperId(system) != first }
        waitFor("blue") { shows(system, Color.BLUE) }
        // Its new id is known once Android says the wallpaper is done, a moment after it shows.
        waitFor("and still its own") { WallpaperMatch.isMatched(context) }
    }

    /** The heart of it: someone sets their own, and from then on Kolahal keeps its hands off. */
    @Test fun itLeavesAWallpaperSetSinceAlone() {
        WallpaperMatch.set(context, Color.RED)
        wallpapers.setBitmap(solid(Color.GREEN), null, true, system)
        val theirs = wallpapers.getWallpaperId(system)
        assertFalse(WallpaperMatch.isMatched(context))

        WallpaperMatch.follow(context, Color.BLUE)
        Thread.sleep(1_500)
        assertEquals(theirs, wallpapers.getWallpaperId(system))
        waitFor("still green") { shows(system, Color.GREEN) }

        // Forgotten, not just skipped: another theme later doesn't touch it either.
        WallpaperMatch.follow(context, Color.YELLOW)
        Thread.sleep(500)
        assertEquals(theirs, wallpapers.getWallpaperId(system))
    }

    @Test fun withoutMatchItNeverTouchesTheWallpaper() {
        wallpapers.setBitmap(solid(Color.GREEN), null, true, system)
        val theirs = wallpapers.getWallpaperId(system)
        WallpaperMatch.follow(context, Color.BLUE)
        Thread.sleep(1_000)
        assertEquals(theirs, wallpapers.getWallpaperId(system))
    }

    @Test fun theSameColourSetsNothing() {
        WallpaperMatch.set(context, Color.RED)
        val first = wallpapers.getWallpaperId(system)
        WallpaperMatch.follow(context, Color.RED)
        Thread.sleep(800)
        assertEquals(first, wallpapers.getWallpaperId(system))
    }

    /** Themes picked faster than wallpapers are set: it ends on the last one. */
    @Test fun itEndsOnTheLastTheme() {
        WallpaperMatch.set(context, Color.RED)
        WallpaperMatch.follow(context, Color.GREEN)
        WallpaperMatch.follow(context, Color.YELLOW)
        WallpaperMatch.follow(context, Color.BLUE)
        waitFor("blue, the last") { shows(system, Color.BLUE) }
        Thread.sleep(800)
        assertTrue(shows(system, Color.BLUE))
    }

    /** Home, for real: a theme picked, then dark mode turning off; the lock screen stays out of it; home doesn't restart. */
    @Test fun aThemeChangeOnHomeChangesTheWallpaper() {
        wallpapers.setBitmap(solid(Color.MAGENTA), null, true, system or lock)
        waitFor("magenta first") { shows(system, Color.MAGENTA) }
        shell("cmd uimode night yes")
        config.set(Keys.THEME, "gruvbox")
        var created = 0
        val count = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { if (a is MainActivity) created++ }
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        val app = context.applicationContext as Application
        app.registerActivityLifecycleCallbacks(count)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("the palette") { Term.palette == Palettes.gruvbox }
                assertTrue(WallpaperMatch.set(context, WallpaperMatch.themeColour()))
                val first = wallpapers.getWallpaperId(system)
                waitFor("gruvbox") { shows(system, Palettes.gruvbox.bg.toArgb()) }

                // Gruvbox and Dracula are close greys: the id says it changed, the colour that it's right.
                config.set(Keys.THEME, "dracula")
                waitFor("a new wallpaper") { wallpapers.getWallpaperId(system) != first }
                waitFor("dracula") { shows(system, Palettes.dracula.bg.toArgb()) }

                shell("cmd uimode night no")
                waitFor("dracula's light version") { shows(system, Palettes.draculaLight.bg.toArgb()) }
                waitFor("and still its own") { WallpaperMatch.isMatched(context) }

                assertTrue("the lock screen keeps its own", shows(lock, Color.MAGENTA))
                assertEquals("home doesn't restart for it", 1, created)
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(count)
        }
    }
}
