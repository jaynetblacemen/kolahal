package com.mtwabpin.kolahal.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit

/**
 * `wallpaper match`: the home screen's wallpaper in the theme's colour, following the theme for as
 * long as it is still the one Kolahal set. Android gives a wallpaper a new id each time anything
 * sets one, and an app can read the id without seeing the picture, so a wallpaper set since is left
 * alone and forgotten. An id means something only on this phone: the file holding it stays out of
 * backups and transfers, as the same number on another phone is someone's own wallpaper.
 */
object WallpaperMatch {
    private const val PREFS = "wallpaper_match"
    private const val KEY_ID = "id"
    private const val KEY_COLOUR = "colour"

    /** The colour asked for last: when themes change faster than wallpapers do, it's the one to end on. */
    @Volatile private var wanted = 0

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The theme's colour as a wallpaper can show it: without any transparency. */
    fun themeColour(): Int = Term.bg.copy(alpha = 1f).toArgb()

    /** True while the home screen's wallpaper is still the one [set] put there. */
    fun isMatched(context: Context): Boolean {
        val id = prefs(context).getInt(KEY_ID, 0)
        return id != 0 && WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) == id
    }

    /** The home screen's wallpaper in [colour], and the id to know it by; false when Android won't. */
    @Synchronized
    fun set(context: Context, colour: Int): Boolean {
        val wallpapers = WallpaperManager.getInstance(context)
        if (!wallpapers.isWallpaperSupported || !wallpapers.isSetWallpaperAllowed) return false
        val solid = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        val id = runCatching { wallpapers.setBitmap(solid, null, true, WallpaperManager.FLAG_SYSTEM) }.getOrDefault(0)
        if (id == 0) return false
        prefs(context).edit { putInt(KEY_ID, id); putInt(KEY_COLOUR, colour) }
        return true
    }

    /**
     * The theme is [colour] now: the wallpaper follows if it is still Kolahal's. Called whenever the
     * theme applies, so what's nothing to do is quick; the rest goes off the main thread.
     */
    fun follow(context: Context, colour: Int) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ID) || prefs.getInt(KEY_COLOUR, colour) == colour) return
        wanted = colour
        val app = context.applicationContext
        Thread {
            synchronized(this) {
                val now = wanted
                if (!prefs.contains(KEY_ID) || prefs.getInt(KEY_COLOUR, now) == now) return@Thread
                if (isMatched(app)) set(app, now) else forget(app)
            }
        }.start()
    }

    /** Leaves the wallpaper as it is, for good: it isn't Kolahal's any more. */
    fun forget(context: Context) = prefs(context).edit { clear() }
}
