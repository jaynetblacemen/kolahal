package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** One row of the selector list: an app under its section letter (or the favourites section). */
internal data class SelectorEntry<T>(
    val item: T,
    val key: String,
    /** Section letter shown in the gutter: `★`, `#` or A–Z. */
    val section: Char,
    /** Ordinal of the section in the list, for the per-section haptic tick. */
    val sectionIndex: Int,
    /** First row of its section: the gutter shows the letter here. */
    val sectionStart: Boolean,
    val favourite: Boolean,
)

/**
 * Pure maths of the app selector (list building, scrub mapping, fisheye); unit-tested.
 * Positions are in rows unless named `px`: row `i` spans `[i, i + 1)` of the list.
 */
internal object SelectorMath {
    const val OTHER = '#'
    const val FAV = '★'

    /** Section order: `#` (digits, symbols, other scripts) first, then A–Z. */
    val LETTERS: List<Char> = listOf(OTHER) + ('A'..'Z').toList()

    /** Section letter of a label: its first letter or digit, accents stripped; anything outside A–Z is `#`. */
    fun letterOf(label: String): Char {
        val first = label.firstOrNull { it.isLetterOrDigit() } ?: return OTHER
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
        return if (base in 'A'..'Z') base else OTHER
    }

    /**
     * The whole list: favourites (if any) as a `★` section at the top, then every item
     * alphabetically in `#`, A–Z sections; ties by label, then [profileRank] (personal first).
     * [favouritesLast] puts the `★` section at the end instead (Plan F: with the strip at the
     * bottom of the edge, that's where a resting thumb is).
     */
    fun <T> build(
        items: List<T>,
        label: (T) -> String,
        key: (T) -> String,
        pinned: (T) -> Boolean,
        profileRank: (T) -> Int,
        favouritesLast: Boolean = false,
    ): List<SelectorEntry<T>> {
        val byLabel = compareBy<T>({ label(it).lowercase() }, { profileRank(it) })
        val favs = items.filter(pinned).sortedWith(byLabel)
        val all = items.sortedWith(compareBy<T> { LETTERS.indexOf(letterOf(label(it))) }.then(byLabel))
        val out = ArrayList<SelectorEntry<T>>(favs.size + all.size)
        var section = -1
        fun favourites() = favs.forEachIndexed { i, it ->
            if (i == 0) section++
            out += SelectorEntry(it, "f:" + key(it), FAV, section, i == 0, true)
        }
        if (!favouritesLast) favourites()
        var prev: Char? = null
        for (it in all) {
            val letter = letterOf(label(it))
            val start = letter != prev
            if (start) section++
            prev = letter
            out += SelectorEntry(it, "a:" + key(it), letter, section, start, false)
        }
        if (favouritesLast) favourites()
        return out
    }

    /** Dead band at each end of the strip: the first and last few percent are the first and last letters, or with apps the list's ends. */
    const val DEAD = 0.04f

    /**
     * Where to draw labels wanted at [wanted] (px, top to bottom) so that no two are closer than
     * [gap] and all stay within [top]..[bottom]: moved as little as it takes, in order (a crowd
     * spreads around its middle). When they can't all fit, some are left out (NaN): each one kept is
     * at least [gap] below the one before.
     */
    fun labelPositions(wanted: FloatArray, gap: Float, top: Float, bottom: Float): FloatArray {
        val n = wanted.size
        if (n == 0) return wanted
        if ((n - 1) * gap > bottom - top) {
            val out = FloatArray(n) { Float.NaN }
            var last = Float.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val p = wanted[i].coerceIn(top, bottom)
                if (p - last >= gap) {
                    out[i] = p
                    last = p
                }
            }
            return out
        }
        // Least squares with gaps: with q = p - i * gap the gaps become q never going down, which
        // pooling adjacent violators solves exactly; the bounds then just clamp q.
        val value = FloatArray(n)
        val weight = IntArray(n)
        var blocks = 0
        for (i in 0 until n) {
            value[blocks] = wanted[i] - i * gap
            weight[blocks] = 1
            blocks++
            while (blocks > 1 && value[blocks - 2] > value[blocks - 1]) {
                val w = weight[blocks - 2] + weight[blocks - 1]
                value[blocks - 2] = (value[blocks - 2] * weight[blocks - 2] + value[blocks - 1] * weight[blocks - 1]) / w
                weight[blocks - 2] = w
                blocks--
            }
        }
        val hi = bottom - (n - 1) * gap
        val out = FloatArray(n)
        var k = 0
        for (b in 0 until blocks) {
            repeat(weight[b]) {
                out[k] = value[b].coerceIn(top, hi) + k * gap
                k++
            }
        }
        return out
    }

    /** How many entries each section has (★, #, A–Z, in list order): what [Sections] shares the strip by. */
    fun <T> sectionSizes(entries: List<SelectorEntry<T>>): List<Int> {
        val sizes = IntArray((entries.lastOrNull()?.sectionIndex ?: -1) + 1)
        for ((_, _, _, sectionIndex) in entries) sizes[sectionIndex]++
        return sizes.toList()
    }

    /** Where each section starts in [entries], then the end: section `i` is `entries[starts[i] until starts[i + 1]]`. */
    fun <T> sectionStarts(entries: List<SelectorEntry<T>>): IntArray {
        val sizes = sectionSizes(entries)
        val out = IntArray(sizes.size + 1)
        for (i in sizes.indices) out[i + 1] = out[i] + sizes[i]
        return out
    }

    /** `selector.browse letters`: a letter changes only this far (in letters) past the end of its stretch, so a thumb resting on the line doesn't flicker between two. */
    const val STICK = 0.25f

    /**
     * `selector.browse letters`: the letter at [pos] along the edge (in letters: letter `i` spans
     * `[i, i + 1)`, as [alongLetters] gives it), staying on [current] until [pos] is [STICK] past
     * either end of its stretch.
     */
    fun letterAt(pos: Float, current: Int, count: Int): Int {
        if (count <= 0) return -1
        val raw = floor(pos).toInt().coerceIn(0, count - 1)
        if (current !in 0 until count) return raw
        return if (pos >= current - STICK && pos < current + 1 + STICK) current else raw
    }

    /**
     * Where a letter's [size] apps start (px, the top of the first) for the letter at [letterY]: the
     * first one level with it, so they run down from the thumb, moved up only as far as keeps them all
     * above [bottom], and never above [top].
     */
    fun letterTop(letterY: Float, size: Int, rowPx: Float, top: Float, bottom: Float): Float {
        var start = letterY - rowPx / 2
        if (start + size * rowPx > bottom) start = bottom - size * rowPx
        return max(start, top)
    }

    /**
     * A list left open where it was, [shiftPx] off its place: the panel goes back, and room at the
     * list's start (when it was moved down) or end (up) makes up the difference, so it stays put and
     * still scrolls to every app. Returns (room at the start, room at the end, the scroll that keeps it).
     */
    fun keepInPlace(scrollPx: Float, shiftPx: Float): Triple<Float, Float, Float> =
        Triple(max(0f, shiftPx), max(0f, -shiftPx), scrollPx - min(0f, shiftPx))

    /**
     * `selector.filter`: the panel, [panelH] tall, moved ([shiftPx] down from its place at the
     * bottom of the [listH] it may use) no further than keeps it all on screen.
     */
    fun keepOnScreen(shiftPx: Float, panelH: Float, listH: Float): Float = shiftPx.coerceIn(-max(0f, listH - panelH), 0f)

    /** The wave of letters beside the thumb reaches this many letters on each side of the bubble. */
    const val WAVE_REACH = 3f

    /** Letters grow by up to this much toward the wave's peak. */
    const val WAVE_GROW = 0.6f

    /** How far out the wave carries a letter [x] letters from its centre: 1 at the centre, easing to 0 at [WAVE_REACH]. */
    fun waveOut(x: Float): Float = if (abs(x) >= WAVE_REACH) 0f else (1f + cos(PI.toFloat() * x / WAVE_REACH)) / 2f

    /** [waveOut] summed from the centre to [x] (letters): how much further from the centre the spread puts a letter. */
    private fun waveIntegral(x: Float): Float {
        val a = min(abs(x), WAVE_REACH)
        return sign(x) * (a + WAVE_REACH / PI.toFloat() * sin(PI.toFloat() * a / WAVE_REACH)) / 2f
    }

    /** How much the wave spreads letters [unit] px apart at rest, so the ones next to its centre sit [room] px from it. */
    fun waveSpread(unit: Float, room: Float): Float =
        if (unit <= 0f) 0f else max(0f, (room / unit - 1f) / waveIntegral(1f))

    /** Where a letter at rest at [p] (px along the strip) is drawn with the wave centred at [c]: pushed apart around it by [spread]. */
    fun waveY(p: Float, c: Float, unit: Float, spread: Float): Float {
        if (unit <= 0f) return p
        val x = (p - c) / unit
        return c + unit * (x + spread * waveIntegral(x))
    }

    /**
     * Which of the wave's letters there is room to draw, at [ys] and [scales] times their size
     * ([gap] px apart at 1): outward from the [active] one (in the bubble, so never itself), each
     * [clear] px off the bubble and at least its size from the last one drawn.
     */
    fun waveShown(ys: FloatArray, scales: FloatArray, active: Int, gap: Float, clear: Float): BooleanArray {
        val shown = BooleanArray(ys.size)
        if (active !in ys.indices) return shown
        for (step in intArrayOf(-1, 1)) {
            var last = Float.NaN
            var lastScale = 1f
            var i = active + step
            while (i in ys.indices) {
                val offBubble = abs(ys[i] - ys[active]) >= clear
                val offLast = last.isNaN() || abs(ys[i] - last) >= gap * max(scales[i], lastScale)
                if (offBubble && offLast) {
                    shown[i] = true
                    last = ys[i]
                    lastScale = scales[i]
                }
                i += step
            }
        }
        return shown
    }

    /**
     * Where each of [count] letters sits on the strip (0 top … 1 bottom): the middle of its stretch.
     * Every letter gets the same stretch between the dead bands, so one with a single app is as easy
     * to land on as one with many.
     */
    fun letterMiddles(count: Int): FloatArray = FloatArray(count) { DEAD + (it + 0.5f) / count * (1f - 2 * DEAD) }

    /** Strip position [t] (0 top … 1 bottom) → position in letters (letter `i` spans `[i, i + 1)`); the dead bands pin the ends. */
    fun alongLetters(t: Float, count: Int): Float {
        if (count <= 0) return 0f
        return (((t - DEAD) / (1f - 2 * DEAD)).coerceIn(0f, 1f) * count).coerceAtMost(count - 0.01f)
    }

    /**
     * `selector.browse apps` (Plan D): how the strip is shared out between the list's sections,
     * [sectionSizes] apps each in list order. Each gets room in proportion to the square root of its
     * size, so a letter with four times the apps gets twice the room, and a letter with a single app
     * is no sliver.
     */
    class Sections(sectionSizes: List<Int>) {
        private val sizes = sectionSizes.toIntArray()
        private val count = sizes.sum()

        /** How many sections there are. */
        val size: Int get() = sizes.size

        /** Each section's share of the strip between the dead bands; together they make 1. */
        val shares: FloatArray = FloatArray(sizes.size) { sqrt(sizes[it].toFloat()) }.let { roots ->
            val sum = roots.sum()
            FloatArray(roots.size) { if (sum > 0f) roots[it] / sum else 0f }
        }

        /** Where each section's room is centred, as strip positions (0 top … 1 bottom), in list order. */
        fun middles(): FloatArray {
            val out = FloatArray(sizes.size)
            var acc = 0f
            for (i in sizes.indices) {
                out[i] = DEAD + (acc + shares[i] / 2f) * (1f - 2 * DEAD)
                acc += shares[i]
            }
            return out
        }

        /**
         * Strip position [t] (0 top … 1 bottom) → list position (rows): the dead bands pin the list's
         * ends, and across a section's share its apps go by evenly.
         */
        fun along(t: Float): Float {
            if (count == 0) return 0f
            val u = ((t - DEAD) / (1f - 2 * DEAD)).coerceIn(0f, 1f)
            var acc = 0f
            var start = 0
            for (i in sizes.indices) {
                val w = shares[i]
                if (u <= acc + w || i == sizes.lastIndex) {
                    val f = if (w > 0f) ((u - acc) / w).coerceIn(0f, 1f) else 0f
                    return (start + f * sizes[i]).coerceIn(0f, count - 0.01f)
                }
                acc += w
                start += sizes[i]
            }
            return 0f
        }
    }

    /** Plan C: a slide along the strip slower than this (dp a second) aims instead of gliding (`selector.aim-speed`). */
    const val AIM_SPEED = 100

    /** Plan C: while aiming, the list moves one app per this many dp of the slide (`selector.aim`; 0 never aims). */
    const val AIM_STEP = 10

    /** From this many times the aim speed, a slide glides the whole list again. */
    const val GLIDE_FACTOR = 2.5f

    /** How quickly the slide's measured speed follows the thumb (ms): smooths out a jittery touch. */
    const val SPEED_TAU_MS = 60f

    /**
     * How much a slide at [speed] (dp a second) aims rather than glides: 1 below [aimSpeed], 0 from
     * [GLIDE_FACTOR] times it, easing between.
     */
    fun aimWeight(speed: Float, aimSpeed: Float): Float {
        if (aimSpeed <= 0f) return 0f
        val t = ((speed - aimSpeed) / (aimSpeed * (GLIDE_FACTOR - 1f))).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** The slide's speed (dp a second), [smoothed] eased toward the last move's [instant] speed over [SPEED_TAU_MS]. */
    fun smoothSpeed(smoothed: Float, instant: Float, dtMs: Long): Float =
        smoothed + (instant - smoothed) * (dtMs / SPEED_TAU_MS).coerceIn(0f, 1f)

    /**
     * Plan C: the list position (rows) after a slide of [dyPx] along the strip, from [at]. A glide
     * puts the list where the strip maps it, [glideFrom] before the move and [glideTo] after
     * ([Sections.along]). Aiming ([weight] 1) moves it one app per [stepPx] of the slide instead, never
     * faster than the glide; part way ([weight] between), it also drifts back toward the glide's place,
     * so speeding up again lands where a glide would.
     */
    fun aim(at: Float, glideFrom: Float, glideTo: Float, dyPx: Float, stepPx: Float, weight: Float, count: Int): Float {
        val end = max(0f, count - 0.01f)
        // No step: it never aims.
        if (stepPx <= 0f) return glideTo.coerceIn(0f, end)
        val aimed = sign(dyPx) * min(abs(dyPx) / stepPx, abs(glideTo - glideFrom))
        return (at + weight * aimed + (1f - weight) * (glideTo - at)).coerceIn(0f, end)
    }

    /** Where the list must be (px) to draw list position [at] (rows) level with [yInPanel]: can be past either end. */
    fun levelWith(at: Float, yInPanel: Float, rowPx: Float): Float = at * rowPx - yInPanel

    /**
     * The list at [listY] (px): scrolled as far as 0..[maxScrollPx] goes, and the panel moved the
     * rest of the way (down when positive). Returns (scroll, shift).
     */
    fun place(listY: Float, maxScrollPx: Float): Pair<Float, Float> {
        val scroll = listY.coerceIn(0f, maxScrollPx)
        return scroll to scroll - listY
    }

    fun maxScroll(count: Int, rowPx: Float, viewportPx: Float): Float = max(0f, count * rowPx - viewportPx)

    /**
     * Where on a strip [stripH] long a touch may start the selector, as (top, height) within it:
     * with Android's back swipe on the edge ([backGesture]), only the middle [limit] (Android keeps
     * the swipe off at most 200dp of an edge for an app); otherwise the whole strip.
     */
    fun startZone(stripH: Float, limit: Float, backGesture: Boolean): Pair<Float, Float> =
        if (!backGesture || stripH <= limit) 0f to stripH else (stripH - limit) / 2f to limit

    /** The list position (rows) under a finger [yInPanel] px below the panel's top. */
    fun contact(scrollPx: Float, yInPanel: Float, rowPx: Float): Float = (scrollPx + yInPanel) / rowPx

    /** Row index at [contact], clamped into the list; -1 for no contact (NaN) or an empty list. */
    fun focusedIndex(contact: Float, count: Int): Int =
        if (contact.isNaN() || count <= 0) -1 else floor(contact).toInt().coerceIn(0, count - 1)

    /** Peak magnification of the focused row, and the reach (rows) of the fisheye on each side. */
    const val PEAK = 0.35f
    const val REACH = 3f

    /** Fisheye bell: 1 at the contact, tapering smoothly to 0 at ±[REACH] rows. */
    private fun bell(x: Float): Float =
        if (abs(x) >= REACH) 0f else (1f + cos(PI.toFloat() * x / REACH)) / 2f

    /** Scale of a row whose centre is [x] rows from the contact point. */
    fun fisheyeScale(x: Float): Float = 1f + PEAK * bell(x)

    /**
     * Extra vertical displacement (rows) of a row centred [x] rows from the contact, so magnified
     * rows push their neighbours apart instead of overlapping: the integral of the extra scale.
     */
    fun fisheyeShift(x: Float): Float {
        val a = min(abs(x), REACH)
        val integral = (a + REACH / PI.toFloat() * sin(PI.toFloat() * a / REACH)) / 2f
        return sign(x) * PEAK * integral
    }

    /**
     * Edge auto-scroll speed (px/ms, negative = up) for a finger [y] px below the panel's top,
     * ramping up over a [zone] at each end of a panel [height] px tall.
     */
    fun edgeSpeed(y: Float, height: Float, zone: Float, maxSpeed: Float): Float {
        val z = min(zone, height / 4f)
        if (z <= 0f) return 0f
        return when {
            y < z -> -maxSpeed * ((z - y) / z).coerceIn(0f, 1f)
            y > height - z -> maxSpeed * ((y - (height - z)) / z).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    /** Held still this long on an app while picking: it launches, as lifting would. */
    const val HOLD_MS = 800L

    /** The held row starts to fill this long into a hold, so the launch never comes as a surprise (Plan B). */
    const val FILL_FROM_MS = 300L

    /** A fill that stops rising drains away in at most this long (ms, for a full row). */
    const val DRAIN_MS = 120f

    /** The panel's far side, for actions with `selector.actions far-side`: this share of its width, away from the strip. */
    const val FAR_SHARE = 0.3f

    /** How full the held row is after [heldMs] still: empty for [FILL_FROM_MS], full at [HOLD_MS]. */
    fun holdFill(heldMs: Long): Float = ((heldMs - FILL_FROM_MS).toFloat() / (HOLD_MS - FILL_FROM_MS)).coerceIn(0f, 1f)

    /** The fill drawn: up to [target] at once, and down from [shown] no faster than a full row in [DRAIN_MS]. */
    fun holdShown(shown: Float, target: Float, dtMs: Long): Float =
        if (target >= shown) target else max(target, shown - dtMs / DRAIN_MS)

    /** Whether [fingerX] is on the far side of the panel ([panelX], [panelW] wide) from the strip: its last [FAR_SHARE]. */
    fun farSide(fingerX: Float, panelX: Float, panelW: Float, left: Boolean): Boolean =
        if (left) fingerX > panelX + panelW * (1f - FAR_SHARE) else fingerX < panelX + panelW * FAR_SHARE

    /** What a move feels like: nothing, a letter passed along the edge, an app passed in the list, or a switch between the two. */
    enum class Feel { NONE, LETTER, APP, SWITCH }

    /**
     * The feel of a move to [row] in [section], from [lastRow] in [lastSection] (-1: the first
     * move), [selecting] in the list or along the letters, [wasSelecting] before it. [aiming] along
     * the edge (apps, Plan C) adds a light tick per app, as picking has.
     */
    fun feel(selecting: Boolean, wasSelecting: Boolean, row: Int, lastRow: Int, section: Int, lastSection: Int, aiming: Boolean = false): Feel = when {
        selecting != wasSelecting -> Feel.SWITCH
        selecting -> if (row != lastRow && lastRow != -1) Feel.APP else Feel.NONE
        section != lastSection && lastSection != -1 -> Feel.LETTER
        aiming && row != lastRow && lastRow != -1 -> Feel.APP
        else -> Feel.NONE
    }
}
