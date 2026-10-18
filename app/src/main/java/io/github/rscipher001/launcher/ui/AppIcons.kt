package io.github.rscipher001.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import io.github.rscipher001.launcher.apps.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App icons for the suggestions (`suggestions.icons`). Loaded off the main thread through
 * LauncherApps, so work and private apps carry the system's profile badge, then kept in a small
 * memory cache keyed by app and pixel size.
 */
object AppIcons {
    private val cache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Icons can change when an app updates; the next load fetches them again. */
    fun clear() = cache.evictAll()

    fun cached(app: AppEntry, px: Int): ImageBitmap? = cache.get("${app.key}@$px")

    fun load(context: Context, app: AppEntry, px: Int): ImageBitmap? {
        cached(app, px)?.let { return it }
        val icon = runCatching {
            val apps = context.getSystemService(LauncherApps::class.java)
            val info = apps.resolveActivity(Intent().setComponent(app.component), app.profile.user) ?: return null
            info.getBadgedIcon(0).toBitmap(px, px).asImageBitmap()
        }.getOrNull() ?: return null
        cache.put("${app.key}@$px", icon)
        return icon
    }
}

/** [app]'s icon at [size]; an empty space of the same size while it loads (no layout jump). */
@Composable
fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val icon by produceState(AppIcons.cached(app, px), app.key, px) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIcons.load(context.applicationContext, app, px) }
    }
    val bitmap = icon
    if (bitmap == null) Box(modifier.size(size)) else Image(bitmap, contentDescription = null, modifier = modifier.size(size))
}
