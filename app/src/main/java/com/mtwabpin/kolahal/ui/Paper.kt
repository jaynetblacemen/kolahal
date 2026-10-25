package com.mtwabpin.kolahal.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * `theme.paper`: an old book's page behind home, in a light version. The theme's background turns
 * toward a yellowed page, with fine grain, uneven ageing, specks, fibres and a few faint brown age
 * spots, all stronger as the setting goes up (0: the theme's flat colour). The texture is made here
 * once, as a tile whose edges meet; a theme or strength only colours it again.
 */
object Paper {
    /** One tile, a screen pixel per texel: specks stay specks, and it repeats only every couple of inches. */
    const val SIZE = 512

    private val AGED = Color(0xFFECDCB2)
    private const val BROWN_R = 118f
    private const val BROWN_G = 78f
    private const val BROWN_B = 30f

    /** The darker page edges' colour. */
    private val EDGE = Color(0xFF78521E)

    /** The page's colour under its texture: the theme's background yellowed with [strength] (0–100). */
    fun base(bg: Color, strength: Int): Color = lerp(bg, AGED, strength.coerceIn(0, 100) / 100f * 0.6f)

    /** How far the grain moves the page's lightness, in 0–255 steps, at [strength]. */
    fun grain(strength: Int): Float = 0.055f * 255f * strength.coerceIn(0, 100) / 100f

    /** Lightness offsets (grain and ageing, about -1..1) and brown stains (specks, fibres, age spots, 0..1). */
    private class Texture(val light: FloatArray, val stain: FloatArray)

    private val texture by lazy { make() }

    /** The tile's pixels (ARGB) for [bg] at [strength]. */
    fun pixels(bg: Color, strength: Int): IntArray {
        val t = texture
        val base = base(bg, strength)
        val r0 = base.red * 255f
        val g0 = base.green * 255f
        val b0 = base.blue * 255f
        val amp = grain(strength)
        val stains = strength.coerceIn(0, 100) / 100f * 1.1f
        return IntArray(SIZE * SIZE) { i ->
            val o = t.light[i] * amp
            var r = r0 + o
            var g = g0 + o * 0.96f
            var b = b0 + o * 0.85f
            val s = min(1f, t.stain[i] * stains) * 0.55f
            r += (BROWN_R - r) * s
            g += (BROWN_G - g) * s
            b += (BROWN_B - b) * s
            (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }

    private fun channel(v: Float) = v.toInt().coerceIn(0, 255)

    fun tile(bg: Color, strength: Int): ImageBitmap =
        Bitmap.createBitmap(pixels(bg, strength), SIZE, SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun make(): Texture {
        val rand = Random(29)
        val n = SIZE * SIZE
        val mottle = valueNoise(3, rand)
        val mid = valueNoise(24, rand)
        val fine = valueNoise(160, rand)
        val light = FloatArray(n) { i -> 0.55f * mottle[i] + 0.25f * mid[i] + 0.25f * fine[i] + 0.45f * (rand.next() * 2 - 1) }
        val stain = FloatArray(n)
        fun put(x: Float, y: Float, v: Float) {
            val i = Math.floorMod(y.toInt(), SIZE) * SIZE + Math.floorMod(x.toInt(), SIZE)
            stain[i] = min(1f, stain[i] + v)
        }
        // Fibres: short straight threads.
        repeat(26) {
            val x0 = rand.next() * SIZE
            val y0 = rand.next() * SIZE
            val length = 4 + rand.next() * 14
            val angle = rand.next() * 2 * Math.PI.toFloat()
            val depth = 0.25f + rand.next() * 0.25f
            var t = 0f
            while (t < length) {
                put(x0 + kotlin.math.cos(angle) * t, y0 + kotlin.math.sin(angle) * t, depth)
                t += 0.5f
            }
        }
        // Specks, some two pixels wide.
        repeat(70) {
            val x = rand.next() * SIZE
            val y = rand.next() * SIZE
            val v = 0.45f + rand.next() * 0.5f
            put(x, y, v)
            if (rand.next() < 0.4f) put(x + 1, y, v * 0.7f)
            if (rand.next() < 0.25f) put(x, y + 1, v * 0.6f)
        }
        // Foxing: a few soft brown spots of different sizes.
        repeat(7) {
            val cx = rand.next() * SIZE
            val cy = rand.next() * SIZE
            val r = 4 + rand.next() * 14
            val v = 0.12f + rand.next() * 0.18f
            val reach = (r * 2).toInt()
            for (y in -reach..reach) for (x in -reach..reach) {
                val q = sqrt((x * x + y * y).toFloat()) / r
                if (q <= 2) put(cx + x, cy + y, v * exp(-q * q * 1.6f) * (0.75f + 0.5f * rand.next()))
            }
        }
        return Texture(light, stain)
    }

    /** Smooth noise over [cells] × [cells] lattice points that wrap, so the tile's edges meet. */
    private fun valueNoise(cells: Int, rand: Random): FloatArray {
        val lattice = FloatArray(cells * cells) { rand.next() * 2 - 1 }
        fun at(x: Int, y: Int) = lattice[Math.floorMod(y, cells) * cells + Math.floorMod(x, cells)]
        fun smooth(t: Float) = t * t * (3 - 2 * t)
        return FloatArray(SIZE * SIZE) { i ->
            val fx = (i % SIZE).toFloat() / SIZE * cells
            val fy = (i / SIZE).toFloat() / SIZE * cells
            val ix = floor(fx).toInt()
            val iy = floor(fy).toInt()
            val tx = smooth(fx - ix)
            val ty = smooth(fy - iy)
            val top = at(ix, iy) + (at(ix + 1, iy) - at(ix, iy)) * tx
            val bottom = at(ix, iy + 1) + (at(ix + 1, iy + 1) - at(ix, iy + 1)) * tx
            top + (bottom - top) * ty
        }
    }

    /** The same numbers on every phone and every start: the page doesn't change under you. */
    private class Random(private var seed: Int) {
        fun next(): Float {
            seed += 0x6D2B79F5
            var t = (seed xor (seed ushr 15)) * (1 or seed)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return ((t xor (t ushr 14)).toUInt().toFloat()) / 4294967296f
        }
    }

    /** Darker toward the corners, like an old page's edges, at [strength]. */
    fun edges(strength: Int, center: androidx.compose.ui.geometry.Offset, radius: Float): Brush = Brush.radialGradient(
        0.55f to Color.Transparent,
        1f to EDGE.copy(alpha = 0.16f * strength.coerceIn(0, 100) / 100f),
        center = center,
        radius = radius,
    )
}

/**
 * Home's background: the theme's colour, or with `theme.paper` in a light version ([Term.paper]),
 * an old page. Its tile is made off the main thread; the last one shows until the next is ready.
 * With [wallpaper] above 0 (`log.wallpaper`, `widgets.wallpaper`) the phone's wallpaper, which the
 * window lets through then, shows that many percent: the background is drawn that much fainter.
 */
@Composable
fun Modifier.page(wallpaper: Int = 0): Modifier {
    val strength = Term.paper
    val bg = Term.bg
    val alpha = 1f - wallpaper.coerceIn(0, 100) / 100f
    var tile by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bg, strength) {
        tile = if (strength <= 0) null else withContext(Dispatchers.Default) { Paper.tile(bg, strength) }
    }
    if (strength <= 0) return background(bg.copy(alpha = bg.alpha * alpha))
    val base = Paper.base(bg, strength)
    val brush = remember(tile) { tile?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    return drawBehind {
        drawRect(base, alpha = alpha)
        brush?.let { drawRect(it, alpha = alpha) }
        drawRect(Paper.edges(strength, center, kotlin.math.hypot(size.width, size.height) / 2), alpha = alpha)
    }
}
