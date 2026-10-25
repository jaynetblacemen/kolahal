package com.mtwabpin.kolahal.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.ProfileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

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
    val LETTERS: List<Char> = listOf(OTHER) + ('A'..'Z')

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

    /** Dead band at each end of the strip: the first and last few percent are the first and last letters. */
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
        val lo = top
        val hi = bottom - (n - 1) * gap
        val out = FloatArray(n)
        var k = 0
        for (b in 0 until blocks) {
            repeat(weight[b]) {
                out[k] = value[b].coerceIn(lo, hi) + k * gap
                k++
            }
        }
        return out
    }

    /** How many entries each section has (★, #, A–Z, in list order). */
    fun <T> sectionSizes(entries: List<SelectorEntry<T>>): List<Int> {
        val sizes = IntArray((entries.lastOrNull()?.sectionIndex ?: -1) + 1)
        for (e in entries) sizes[e.sectionIndex]++
        return sizes.toList()
    }

    /** Where each section starts in [entries], then the end: section `i` is `entries[starts[i] until starts[i + 1]]`. */
    fun <T> sectionStarts(entries: List<SelectorEntry<T>>): IntArray {
        val sizes = sectionSizes(entries)
        val out = IntArray(sizes.size + 1)
        for (i in sizes.indices) out[i + 1] = out[i] + sizes[i]
        return out
    }

    /** A letter changes only this far (in letters) past the end of its stretch, so a thumb resting on the line doesn't flicker between two. */
    const val STICK = 0.25f

    /**
     * The letter at [pos] along the edge (in letters: letter `i` spans `[i, i + 1)`, as
     * [alongLetters] gives it), staying on [current] until [pos] is [stick] past either end of its
     * stretch.
     */
    fun letterAt(pos: Float, current: Int, count: Int, stick: Float = STICK): Int {
        if (count <= 0) return -1
        val raw = floor(pos).toInt().coerceIn(0, count - 1)
        if (current !in 0 until count) return raw
        return if (pos >= current - stick && pos < current + 1 + stick) current else raw
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
     * move), [selecting] in the list or along the letters, [wasSelecting] before it.
     */
    fun feel(selecting: Boolean, wasSelecting: Boolean, row: Int, lastRow: Int, section: Int, lastSection: Int): Feel = when {
        selecting != wasSelecting -> Feel.SWITCH
        selecting -> if (row != lastRow && lastRow != -1) Feel.APP else Feel.NONE
        section != lastSection && lastSection != -1 -> Feel.LETTER
        else -> Feel.NONE
    }
}

private enum class Mode { CLOSED, SCRUB, SELECT, OPEN }

/**
 * Whether the selector's edge ([left], else the right) has Android's back swipe: gesture navigation
 * reports a system gesture inset there, three-button navigation doesn't.
 */
@Composable
internal fun hasEdgeBackGesture(left: Boolean): Boolean {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemGestures
    return (if (left) insets.getLeft(density, direction) else insets.getRight(density, direction)) > 0
}

private class SelectorState {
    var mode by mutableStateOf(Mode.CLOSED)
    /** List position (rows) under the finger while it is down; NaN when not touching. */
    var contact by mutableFloatStateOf(Float.NaN)
    /** Finger Y in the selector's coordinates (moves the handle). */
    var fingerY by mutableFloatStateOf(0f)
    /** Scroll offset (px) set by scrubbing. */
    var scrollPx = 0f
    /**
     * Where the list wants to be (px, as [scrollPx]) to put the focused app level with the finger;
     * past the list's ends, the panel itself moves the rest of the way ([shiftPx]).
     */
    var listY = 0f
    /** How far the panel is moved down (up when negative) for what scrolling couldn't do. */
    var shiftPx by mutableFloatStateOf(0f)
    var interaction by mutableIntStateOf(0)
    /** How full the held row is (0 to 1), drawn on the focused row while picking (Plan B). */
    var hold by mutableFloatStateOf(0f)
    /** Room at the list's start and end (px) that keeps a tapped letter's apps where they were ([SelectorMath.keepInPlace]). */
    var padTop by mutableFloatStateOf(0f)
    var padBottom by mutableFloatStateOf(0f)
    /** Picking on the panel's far side: lifting opens the app's actions (`selector.actions far-side`). */
    var far by mutableStateOf(false)
    /** The letter the thumb is on along the edge (its section); -1 before the first touch. The bubble shows it. */
    var letter by mutableIntStateOf(-1)

    fun close() {
        mode = Mode.CLOSED
        contact = Float.NaN
        hold = 0f
        far = false
        // shiftPx and letter stay: the panel fades out where it is, as it was, and the next touch
        // places it again.
    }
}

private val ROW_H = 38.dp
/** Past the strip's inner side by this much, the finger is picking from the list, not sliding the edge. */
private val LEAVE = 32.dp
private val STRIP_W = 36.dp
private val TOP_GAP = 8.dp
/** The bubble with the letter you're at, beside the thumb. */
private val BUBBLE = 40.dp
/** The wave's letters next to the bubble sit this far from its middle. */
private val WAVE_ROOM = 38.dp
/** A letter's middle from the screen's edge, at rest. */
private val LETTER_REST = 6.dp
/** Leaves the "jump to latest" button in the log's bottom-right corner reachable. */
private val BOTTOM_GAP = 72.dp
private const val IDLE_MS = 4_000L
private const val FRAME_MS = 16L

private fun LazyListState.scrollToPx(px: Float, rowPx: Int) {
    val index = (px / rowPx).toInt()
    requestScrollToItem(index, (px - index * rowPx).roundToInt())
}

/**
 * App selector on an edge of the log (`selector.rail`, `selector.side`): a slim handle at rest, on
 * a strip of the edge (`selector.height` long, at the bottom, middle or top: `selector.position`).
 * Touch it and a panel slides in on the other side, the whole height, listing every app
 * (favourites first, then A–Z; at the bottom of the edge favourites last, under the thumb), away
 * from the thumb. Every letter has the same stretch of the strip and sliding goes a letter at a
 * time, ticking once per letter: the letter shows big in a bubble beside the thumb, the letters
 * around it bulging out in a wave, and the list holds still with the letter's apps starting level
 * with its place on the edge (the others dimmed), or with `selector.filter` lists only them; past
 * the list's ends the panel itself moves to keep them there. Slide off the edge, toward the panel,
 * to pick a row, magnified with its neighbours (fisheye; auto-scrolling at the panel's ends); lift
 * to launch, or hold still until its row fills. Lifting on the edge leaves the panel open as a
 * normal list, the letter's apps still beside it (fling, tap, long-press); an outside tap, Back or
 * 4 s idle closes it.
 *
 * The strip consumes all its touches, so the home pager never turns the page under it.
 * Gesture navigation: Android keeps the back swipe off at most 200dp of an edge for an app, so a
 * touch starts the selector only in the strip's middle 200dp, where the handle is drawn at rest;
 * elsewhere the edge is Android's. Once down, the thumb uses the strip's whole length.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppSelector(
    apps: List<AppEntry>,
    pinned: Set<String>,
    onLaunch: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
    heightDp: Int = 350,
    /** selector.handle: draw the A–Z handle when idle (it still shows under the finger). */
    showHandle: Boolean = true,
    /** selector.letters: each letter along the strip, where its apps are, so you can see where to touch. */
    letters: Boolean = false,
    /** selector.side: the strip on the left edge, the panel on the right. */
    left: Boolean = false,
    /** selector.position: the strip at the edge's `bottom`, `middle` or `top`. */
    position: String = "bottom",
    /** Android's back swipe on this edge (gesture navigation): a touch starts the selector only where it is kept off. */
    edgeBackGesture: Boolean = hasEdgeBackGesture(left),
    /**
     * selector.actions: while picking, lifting on the panel's far side opens an app's actions
     * (`far-side`), or nothing does (`off`: long-press it in the open list). A full hold launches
     * the app either way.
     */
    actions: String = "off",
    /** selector.icons: each row starts with the app's icon (Plan E). */
    icons: Boolean = false,
    /** selector.filter: the list holds only the letter's apps. */
    filter: Boolean = false,
    /** selector.system-font: the list, the letters and the bubble in the phone's font, not the terminal's. */
    systemFont: Boolean = false,
    /** Drawn and taking touches; off (the keyboard up), nothing shows but the list stays built. */
    active: Boolean = true,
) {
    val text = if (systemFont) Term.phoneText else Term.text
    // Plan F: with the strip at the bottom of the edge, favourites go last, where the thumb rests.
    val favouritesLast = position == "bottom"
    val entries = remember(apps, pinned, favouritesLast) {
        SelectorMath.build(
            apps.filter { !it.hidden },
            label = { it.label },
            key = { it.key },
            pinned = { it.key in pinned },
            profileRank = { it.profile.kind.ordinal },
            favouritesLast = favouritesLast,
        )
    }
    val onlyLetter = filter
    val starts = remember(entries) { SelectorMath.sectionStarts(entries) }
    // A letter at a time: every letter gets the same stretch of the edge, a stop of its own.
    val letterCount = starts.size - 1
    val edgeMiddles = remember(letterCount) { SelectorMath.letterMiddles(letterCount) }
    /** Each section's letter (★, #, A–Z), in list order: only letters that have apps. */
    val sectionLetters = remember(entries) { entries.filter { it.sectionStart }.map { it.section } }
    val state = remember { SelectorState() }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val launch by rememberUpdatedState(onLaunch)
    val longPress by rememberUpdatedState(onLongPress)
    val appContext = LocalContext.current.applicationContext
    val letter = state.letter
    /** What the panel lists: every app, or with selector.filter only the letter's. */
    val shown = remember(entries, starts, letter, onlyLetter) {
        if (onlyLetter && letter in 0 until starts.size - 1) entries.subList(starts[letter], starts[letter + 1]) else entries
    }
    // The bubble and the wave come out from the edge while the thumb slides along it.
    val sliding = state.mode == Mode.SCRUB && letter in sectionLetters.indices
    val waveIn by animateFloatAsState(if (sliding) 1f else 0f, tween(140), label = "wave")
    /** Where the wave is centred along the strip (0 top … 1 bottom): the letter's middle, gliding to the next one's. */
    val waveAt = remember { Animatable(0f) }
    LaunchedEffect(letter, sliding, edgeMiddles) {
        val to = edgeMiddles.getOrNull(letter) ?: return@LaunchedEffect
        // Out from the edge at the letter itself, then a short glide from letter to letter.
        if (!sliding || waveIn < 0.05f) waveAt.snapTo(to) else waveAt.animateTo(to, spring(stiffness = 2500f))
    }
    val measurer = rememberTextMeasurer()
    val waveStyle = text.copy(fontSize = 9.sp, lineHeight = 10.sp)
    val waveLetters = remember(sectionLetters, waveStyle) { sectionLetters.map { measurer.measure(it.toString(), waveStyle) } }

    BackHandler(enabled = state.mode == Mode.OPEN) { state.close() }
    // Left open: closes after a few idle seconds (scrolling it counts as use).
    LaunchedEffect(state.mode, state.interaction) {
        if (state.mode != Mode.OPEN) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) {
                delay(IDLE_MS)
                state.close()
            }
        }
    }

    LaunchedEffect(active) { if (!active) state.close() }
    if (!active) return

    // The panel can move past the log's edges to keep a letter's apps level with the thumb.
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        // selector.height: a strip of the edge, in the thumb's reach (selector.position), instead of
        // the whole log height; every letter still has its stretch of it (its top = the first letter).
        val topPx = with(density) { TOP_GAP.toPx() }
        val bottomPx = containerH - with(density) { BOTTOM_GAP.toPx() }
        val stripHPx = min(bottomPx - topPx, with(density) { heightDp.dp.toPx() })
        val stripTopPx = when (position) {
            "top" -> topPx
            "middle" -> topPx + (bottomPx - topPx - stripHPx) / 2
            else -> bottomPx - stripHPx
        }
        if (stripHPx < with(density) { 160.dp.toPx() }) return@BoxWithConstraints
        val count = entries.size
        // Rows grow with the text: at a large text size a fixed row would overlap the next.
        val rowH = maxOf(ROW_H, with(density) { (Term.fontSize * 1.36f).sp.toDp() } + 8.dp)
        val rowPx = with(density) { rowH.roundToPx() }
        // Plan E: a 26dp icon in a 38dp row, growing with the row up to 32dp.
        val iconSize = minOf(rowH - 12.dp, 32.dp)
        val iconPx = with(density) { iconSize.roundToPx() }.coerceAtLeast(1)
        // Loaded ahead, off the main thread, so a fast glide never shows an empty space where one goes.
        if (icons) LaunchedEffect(entries, iconPx, AppIcons.generation) {
            withContext(Dispatchers.IO) { entries.forEach { AppIcons.load(appContext, it.item, iconPx) } }
        }
        // The panel uses the whole height, whatever the strip's; a short list (or a letter's apps)
        // sits at the bottom until it is moved level with the thumb.
        val listHPx = containerH - 2 * topPx
        fun panelH(n: Int) = min(listHPx, (n * rowPx).toFloat()).coerceAtLeast(rowPx.toFloat())
        fun panelTop(n: Int) = topPx + listHPx - panelH(n)
        val panelHPx = panelH(shown.size)
        val panelTopPx = panelTop(shown.size)
        val stripWPx = with(density) { STRIP_W.toPx() }
        // On the other side from the strip: narrower while the thumb is down, so the wave and the
        // bubble swing out clear of it, and wider once left open, for the names.
        val wideW = with(density) { (containerW * 0.7f).toDp() }.coerceAtLeast(220.dp)
            .coerceAtMost(maxWidth - STRIP_W - 24.dp)
        val narrowW = with(density) { (containerW * 0.55f).toDp() }.coerceAtLeast(minOf(200.dp, wideW)).coerceAtMost(wideW)
        val panelW by animateDpAsState(if (state.mode == Mode.OPEN) wideW else narrowW, tween(160), label = "panel")
        val panelWPx = with(density) { panelW.toPx() }
        val panelXPx = if (left) containerW - panelWPx else 0f
        // While the thumb is down.
        val touchWPx = with(density) { narrowW.toPx() }
        val touchXPx = if (left) containerW - touchWPx else 0f
        val stripXPx = if (left) 0f else containerW - stripWPx
        val exclusionPx = with(density) { 200.dp.toPx() }
        // With gesture navigation, a touch starts the selector only in the strip's middle 200dp.
        val (zoneTopPx, zoneHPx) = SelectorMath.startZone(stripHPx, exclusionPx, edgeBackGesture)
        // How far off the edge picking starts: a thumb drifts a little while it slides along the edge.
        val leavePx = with(density) { LEAVE.toPx() }
        val slopPx = with(density) { 8.dp.toPx() }
        val edgeZonePx = with(density) { 56.dp.toPx() }
        val edgeMaxSpeed = with(density) { 1.1.dp.toPx() } // px per ms at full depth
        // The bubble sits between the strip and the list, as far from the edge as the gap allows.
        val bubblePx = with(density) { BUBBLE.toPx() }
        val marginPx = with(density) { 4.dp.toPx() }
        val gapPx = containerW - touchWPx - stripWPx
        val bubbleFromEdge = max(bubblePx / 2 + marginPx, min(stripWPx + gapPx / 2, stripWPx + gapPx - bubblePx / 2 - marginPx))
        val restPx = with(density) { LETTER_REST.toPx() }
        val open = state.mode != Mode.CLOSED

        // While it's open the selector has the screen: home dims behind it.
        val dimmed by animateFloatAsState(if (open && count > 0) 1f else 0f, tween(160), label = "dim")
        if (dimmed > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = dimmed }.background(Term.bg.copy(alpha = 0.9f)).testTag("app-selector-scrim"))
        }

        // Left open: a tap anywhere else closes it, and the whole touch is swallowed.
        if (state.mode == Mode.OPEN) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            state.close()
                            while (true) {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                                if (e.changes.none { it.pressed }) break
                            }
                        }
                    }
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = open && count > 0,
            // Opaque from the first frame (no fade): readable while it slides in.
            enter =
                slideInHorizontally(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) { if (left) it / 4 else -it / 4 },
            exit = fadeOut(tween(140)) + slideOutHorizontally(tween(160)) { if (left) it / 4 else -it / 4 },
            modifier = Modifier.offset { IntOffset(panelXPx.roundToInt(), (panelTopPx + state.shiftPx).roundToInt()) },
        ) {
            val shape = if (left) RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp) else RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
            LazyColumn(
                state = listState,
                userScrollEnabled = state.mode == Mode.OPEN,
                contentPadding = with(density) { PaddingValues(top = state.padTop.toDp(), bottom = state.padBottom.toDp()) },
                modifier = Modifier
                    .width(panelW)
                    .height(with(density) { panelHPx.toDp() })
                    .testTag("app-selector-panel")
                    .clip(shape)
                    .background(Term.surface, shape)
                    .border(1.dp, Term.dim.copy(alpha = 0.25f), shape)
                    // A sideways drag on the open panel must not turn the home page.
                    .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume() } },
            ) {
                itemsIndexed(shown, key = { _, e -> e.key }) { index, entry ->
                    SelectorRow(
                        entry = entry,
                        index = index,
                        count = shown.size,
                        state = state,
                        rowPx = rowPx,
                        height = rowH,
                        pinned = entry.item.key in pinned,
                        icon = if (icons) iconSize else null,
                        enabled = state.mode == Mode.OPEN,
                        // A letter's few apps on their own: no room to magnify the ends.
                        fisheye = !onlyLetter,
                        dimOthers = !onlyLetter,
                        text = text,
                        onClick = { state.close(); launch(entry.item) },
                        onLongClick = { state.close(); longPress(entry.item) },
                    )
                }
            }
        }

        // The strip's whole length, drawn: at rest the handle marks where a touch starts, or the
        // letters stand where they are.
        Box(
            Modifier
                .offset { IntOffset(stripXPx.roundToInt(), stripTopPx.roundToInt()) }
                .width(STRIP_W)
                .height(with(density) { stripHPx.toDp() })
                .testTag("app-selector-strip"),
        ) {
            val touching = state.mode == Mode.SCRUB || state.mode == Mode.SELECT
            // selector.letters: each letter in the middle of its stretch of the strip, nudged apart where
            // they crowd; fainter outside the part a touch may start on. At rest they take the handle's
            // place, in the edge's gutter, clear of the log's text; under the thumb the wave takes over.
            if (letters && !touching && waveIn == 0f && count > 0) {
                val linePx = with(density) { 9.sp.toPx() }
                val gapPx = linePx + with(density) { 1.dp.toPx() }
                val placed = remember(edgeMiddles, stripHPx, gapPx) {
                    SelectorMath.labelPositions(FloatArray(edgeMiddles.size) { edgeMiddles[it] * stripHPx }, gapPx, gapPx / 2, stripHPx - gapPx / 2)
                }
                sectionLetters.forEachIndexed { i, letter ->
                    val p = placed.getOrElse(i) { Float.NaN }
                    if (!p.isNaN()) {
                        val inZone = p >= zoneTopPx && p <= zoneTopPx + zoneHPx
                        Text(
                            letter.toString(),
                            style = text.copy(color = Term.dim.copy(alpha = if (inZone) 0.8f else 0.4f), fontSize = 8.sp, lineHeight = 9.sp),
                            maxLines = 1,
                            modifier = Modifier
                                .align(if (left) Alignment.TopStart else Alignment.TopEnd)
                                .offset { IntOffset(0, (p - linePx / 2).roundToInt()) }
                                .padding(start = if (left) 3.dp else 0.dp, end = if (left) 0.dp else 3.dp)
                                .testTag("app-selector-letter"),
                        )
                    }
                }
            }
            if (showHandle && !letters && !touching) {
                // 64dp in the middle, or with gesture navigation the whole part a touch may start on.
                val handleH = if (edgeBackGesture) with(density) { zoneHPx.toDp() } else 64.dp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(start = if (left) 3.dp else 0.dp, end = if (left) 0.dp else 3.dp)
                        .graphicsLayer { alpha = if (open) 0.35f else 1f },
                ) {
                    val small = text.copy(color = Term.dim.copy(alpha = 0.45f), fontSize = 8.sp, lineHeight = 9.sp)
                    Text("A", style = small)
                    Box(
                        Modifier
                            .padding(vertical = 2.dp)
                            .width(3.dp)
                            .height(handleH)
                            .testTag("app-selector-handle")
                            .background(Term.dim.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                    )
                    Text("Z", style = small)
                }
            }
        }

        // The letter you're at, big in a bubble beside the thumb, and the letters around it bulging
        // out from the edge toward it in a wave, growing a little, the nearest pushed apart to make
        // room for it: you see where you are and which way the letter you want is.
        if (waveIn > 0f && letter in sectionLetters.indices) {
            val unit = stripHPx * (1f - 2 * SelectorMath.DEAD) / sectionLetters.size
            val spread = SelectorMath.waveSpread(unit, with(density) { WAVE_ROOM.toPx() })
            val letterGap = with(density) { 10.dp.toPx() }
            val clear = bubblePx / 2 + with(density) { 6.dp.toPx() }
            val dim = Term.dim
            val fg = Term.fg
            // One pass a frame, in the draw phase only.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("app-selector-wave")
                    .drawBehind {
                        val ease = waveIn
                        val c = waveAt.value * stripHPx
                        val n = waveLetters.size
                        val ys = FloatArray(n) { SelectorMath.waveY(edgeMiddles[it] * stripHPx, c, unit, spread * ease) }
                        val outs = FloatArray(n) { SelectorMath.waveOut((edgeMiddles[it] * stripHPx - c) / unit) * ease }
                        val scales = FloatArray(n) { 1f + SelectorMath.WAVE_GROW * outs[it] }
                        val drawn = SelectorMath.waveShown(ys, scales, letter, letterGap, clear * ease)
                        for (i in 0 until n) {
                            if (!drawn[i]) continue
                            val fromEdge = restPx + (bubbleFromEdge - restPx) * outs[i]
                            val layout = waveLetters[i]
                            val w = layout.size.width.toFloat()
                            val h = layout.size.height.toFloat()
                            withTransform({
                                translate((if (left) fromEdge else size.width - fromEdge) - w / 2, stripTopPx + ys[i] - h / 2)
                                scale(scales[i], scales[i], pivot = Offset(w / 2, h / 2))
                            }) {
                                drawText(layout, color = lerp(dim, fg, outs[i]), alpha = ease * (0.55f + 0.45f * outs[i]))
                            }
                        }
                    },
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset {
                        // Out from the edge with the wave.
                        val fromEdge = restPx + (bubbleFromEdge - restPx) * waveIn
                        val cx = if (left) fromEdge else containerW - fromEdge
                        IntOffset((cx - bubblePx / 2).roundToInt(), (stripTopPx + waveAt.value * stripHPx - bubblePx / 2).roundToInt())
                    }
                    .size(BUBBLE)
                    .graphicsLayer { scaleX = waveIn; scaleY = waveIn; alpha = waveIn }
                    .background(Term.prompt, CircleShape),
            ) {
                Text(
                    sectionLetters[letter].toString(),
                    style = text.copy(color = Term.bg, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp),
                    maxLines = 1,
                    modifier = Modifier.testTag("app-selector-bubble"),
                )
            }
        }

        // Where a touch starts the selector: the whole strip, or with gesture navigation only its
        // middle 200dp, the part Android keeps the back swipe off; anywhere else the edge is
        // Android's, and Back works there as usual. It owns every touch it gets, so the home pager
        // never turns the page under it, and it follows the finger past its own ends: the slide
        // uses the strip's whole length.
        Box(
            Modifier
                .offset { IntOffset(stripXPx.roundToInt(), (stripTopPx + zoneTopPx).roundToInt()) }
                .width(STRIP_W)
                .height(with(density) { zoneHPx.toDp() })
                .testTag("app-selector")
                .semantics { contentDescription = "app selector: slide along the edge to a letter, then onto the list to pick an app" }
                // At most 200dp: Android ignores exclusion beyond 200dp per edge.
                .systemGestureExclusion { c ->
                    val h = c.size.height.toFloat()
                    val top = ((h - exclusionPx) / 2).coerceAtLeast(0f)
                    Rect(0f, top, c.size.width.toFloat(), min(h, top + exclusionPx))
                }
                .pointerInput(entries, rowPx, listHPx, topPx, stripTopPx, stripHPx, zoneTopPx, left, actions, onlyLetter) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (count == 0) {
                            while (awaitPointerEvent().changes.onEach { it.consume() }.any { it.pressed }) {}
                            return@awaitEachGesture
                        }
                        var x = down.position.x
                        var y = down.position.y + zoneTopPx
                        var selecting = false
                        var lastSection = -1
                        // The row last felt as a tick (the list) and the state last felt (letters or list).
                        var tickedRow = -1
                        var wasSelecting = false
                        var focus = -1
                        var rowSince = SystemClock.uptimeMillis()
                        var anchorY = y
                        var anchorX = x
                        var lastFrame = rowSince
                        val farMode = actions == "far-side"
                        var holdAt = rowSince
                        // Letters: the one the thumb is on, and what the panel lists for it.
                        var onLetter = -1
                        var list = entries

                        fun update(now: Long) {
                            val cy = stripTopPx + y
                            state.fingerY = cy
                            if (!selecting) {
                                // A letter at a time along the edge; the list moves only when it changes.
                                val l = SelectorMath.letterAt(SelectorMath.alongLetters(y / stripHPx, letterCount), onLetter, letterCount)
                                if (l != onLetter) {
                                    onLetter = l
                                    state.letter = l
                                    list = if (onlyLetter) entries.subList(starts[l], starts[l + 1]) else entries
                                }
                            }
                            val n = list.size
                            val panelH = panelH(n)
                            val panelTop = panelTop(n)
                            // The finger's height in the panel (past its ends when the strip reaches
                            // beyond it).
                            val yIn = cy - panelTop
                            if (!selecting) {
                                // The letter's apps start level with its place on the edge, the first one at
                                // the thumb's height, so every one is a short move away; moved up only as far
                                // as keeps them on screen. The list holds still until the letter changes.
                                val size = starts[onLetter + 1] - starts[onLetter]
                                val start = if (onlyLetter) 0 else starts[onLetter]
                                val letterY = stripTopPx + edgeMiddles[onLetter] * stripHPx
                                val top = SelectorMath.letterTop(letterY, size, rowPx.toFloat(), topPx, topPx + listHPx)
                                state.listY = SelectorMath.levelWith(start.toFloat(), top - panelTop, rowPx.toFloat())
                            }
                            // Scrolled, and past the list's ends the panel moved; a letter's apps on
                            // their own stay on screen.
                            val (scroll, shift) = SelectorMath.place(state.listY, SelectorMath.maxScroll(n, rowPx.toFloat(), panelH))
                            state.scrollPx = scroll
                            state.shiftPx = if (onlyLetter) SelectorMath.keepOnScreen(shift, panelH, listHPx) else shift
                            state.listY = scroll - state.shiftPx
                            listState.scrollToPx(state.scrollPx, rowPx)
                            // Picking: the app level with the finger, as it moves up and down. Along the
                            // edge no app is at the thumb yet: the letter is.
                            state.contact = if (!selecting) Float.NaN else SelectorMath.contact(state.listY, yIn, rowPx.toFloat())
                            val f = SelectorMath.focusedIndex(state.contact, n)
                            val section = if (f >= 0) list[f].sectionIndex else onLetter
                            // Feel where the finger is: crossing between the letters and the list, a
                            // firm tick per letter along the edge, a light one per app in the list.
                            when (SelectorMath.feel(selecting, wasSelecting, f, tickedRow, section, lastSection)) {
                                SelectorMath.Feel.SWITCH -> haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                SelectorMath.Feel.APP -> haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                SelectorMath.Feel.LETTER -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                SelectorMath.Feel.NONE -> Unit
                            }
                            wasSelecting = selecting
                            tickedRow = f
                            lastSection = section
                            // Any real movement (either axis) restarts the hold: sliding sideways
                            // along one row toward the panel is not "holding still".
                            if (f != focus || abs(y - anchorY) > slopPx || abs(x - anchorX) > slopPx) {
                                focus = f
                                rowSince = now
                                anchorY = y
                                anchorX = x
                            }
                            // B2: on the panel's far side, lifting opens the actions instead of launching.
                            val far = farMode && selecting && SelectorMath.farSide(stripXPx + x, touchXPx, touchWPx, left)
                            if (far != state.far) {
                                if (far) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                state.far = far
                            }
                            // Plan B: the held row fills as the hold counts and launches when full, so the
                            // launch never comes as a surprise; a move restarts the hold and the fill drains.
                            // Not on the far side, where lifting means the app's actions.
                            val target = if (selecting && !far) SelectorMath.holdFill(now - rowSince) else 0f
                            state.hold = SelectorMath.holdShown(state.hold, target, now - holdAt)
                            holdAt = now
                        }

                        state.interaction++
                        state.mode = Mode.SCRUB
                        // A list left open where it was goes back to the panel's own placing.
                        state.padTop = 0f
                        state.padBottom = 0f
                        try {
                            update(rowSince)
                            while (true) {
                                val now = SystemClock.uptimeMillis()
                                val panelH = panelH(list.size)
                                val maxScrollPx = SelectorMath.maxScroll(list.size, rowPx.toFloat(), panelH)
                                // A letter's apps on their own scroll only when they don't fit.
                                val speed = if (selecting && (!onlyLetter || maxScrollPx > 0f)) {
                                    SelectorMath.edgeSpeed(stripTopPx + y - (panelTop(list.size) + state.shiftPx), panelH, edgeZonePx, edgeMaxSpeed)
                                } else 0f
                                val scrolling = speed != 0f &&
                                    !(speed < 0f && state.listY <= 0f) && !(speed > 0f && state.listY >= maxScrollPx)
                                val held = now - rowSince
                                val holding = selecting && !state.far
                                // The fill rises, then drains when the hold stops: a frame at a time for both.
                                val filling = holding && held >= SelectorMath.FILL_FROM_MS
                                val draining = state.hold > 0f && !filling
                                val wait = when {
                                    scrolling || filling || draining -> FRAME_MS
                                    holding -> (SelectorMath.FILL_FROM_MS - held).coerceAtLeast(1L)
                                    else -> Long.MAX_VALUE
                                }
                                if (!scrolling) lastFrame = now
                                val event = if (wait == Long.MAX_VALUE) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                                if (event == null) {
                                    val t = SystemClock.uptimeMillis()
                                    if (scrolling) {
                                        // Near the panel's top or bottom: glide on, faster the deeper.
                                        state.listY = (state.listY + speed * (t - lastFrame)).coerceIn(min(0f, state.listY), max(maxScrollPx, state.listY))
                                        lastFrame = t
                                        update(t)
                                        continue
                                    }
                                    if (holding && t - rowSince >= SelectorMath.HOLD_MS) {
                                        // Held still on a row, its fill full: it launches, as lifting would.
                                        val app = list[focus].item
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        state.close()
                                        launch(app)
                                        break
                                    }
                                    // The fill rising or draining.
                                    update(t)
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    state.close()
                                    break
                                }
                                if (!change.pressed) {
                                    // A cancel (e.g. the system took the touch for the back gesture)
                                    // arrives as an already-consumed release: never launch on it.
                                    if (change.isConsumed) {
                                        state.close()
                                    } else if (selecting) {
                                        val app = list[focus].item
                                        val far = state.far
                                        state.close()
                                        if (far) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            longPress(app)
                                        } else {
                                            launch(app)
                                        }
                                    } else {
                                        // Lifted on the edge: leave the list open for a tap or a fling, the
                                        // letter's apps where they are, beside it. The whole list keeps them
                                        // there with room at its ends, so it still scrolls to every app.
                                        state.contact = Float.NaN
                                        if (!onlyLetter) {
                                            val (top, bottom, scroll) = SelectorMath.keepInPlace(state.scrollPx, state.shiftPx)
                                            state.padTop = top
                                            state.padBottom = bottom
                                            state.listY = scroll
                                            state.scrollPx = scroll
                                            state.shiftPx = 0f
                                            listState.scrollToPx(scroll, rowPx)
                                        }
                                        state.mode = Mode.OPEN
                                        state.interaction++
                                    }
                                    break
                                }
                                change.consume()
                                x = change.position.x
                                y = change.position.y + zoneTopPx
                                // Off the edge, toward the panel: the list holds still to pick from.
                                // Picking starts LEAVE off the edge and ends only halfway back, so a
                                // thumb resting at the line doesn't flip between the two.
                                val off = if (left) x - stripWPx else -x
                                selecting = if (selecting) off > leavePx / 2 else off > leavePx
                                // Back on the edge, it carries on from the letter the thumb is on.
                                state.mode = if (selecting) Mode.SELECT else Mode.SCRUB
                                update(SystemClock.uptimeMillis())
                            }
                        } catch (e: CancellationException) {
                            state.close()
                            throw e
                        }
                    }
                },
        )
    }
}

/** One list row: section letter in the gutter, profile tag in its colour, ★, the label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectorRow(
    entry: SelectorEntry<AppEntry>,
    index: Int,
    count: Int,
    state: SelectorState,
    rowPx: Int,
    height: androidx.compose.ui.unit.Dp,
    pinned: Boolean,
    /** The app's icon at this size at the row's start (Plan E), or none. */
    icon: androidx.compose.ui.unit.Dp?,
    enabled: Boolean,
    /** Magnify the row at the thumb and its neighbours while picking. */
    fisheye: Boolean,
    /** Letters: while the thumb is down, the other letters' apps fade back (unless picked). */
    dimOthers: Boolean,
    /** The row's text style: the terminal's font, or the phone's with selector.system-font. */
    text: TextStyle,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val app = entry.item
    val focused by remember(index, count) {
        derivedStateOf { SelectorMath.focusedIndex(state.contact, count) == index }
    }
    val quiet = app.profile.quiet
    val holdColor = Term.prompt.copy(alpha = 0.3f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            // Fisheye in the draw phase only: no recomposition or relayout per frame.
            .graphicsLayer {
                val c = state.contact
                if (c.isNaN() || !fisheye) {
                    scaleX = 1f; scaleY = 1f; translationY = 0f
                } else {
                    val x = index + 0.5f - c
                    val s = SelectorMath.fisheyeScale(x)
                    scaleX = s; scaleY = s
                    translationY = SelectorMath.fisheyeShift(x) * rowPx
                }
                transformOrigin = TransformOrigin(0f, 0.5f)
                val faded = dimOthers && (state.mode == Mode.SCRUB || state.mode == Mode.SELECT) &&
                    entry.sectionIndex != state.letter && SelectorMath.focusedIndex(c, count) != index
                alpha = (if (quiet) 0.45f else 1f) * (if (faded) 0.4f else 1f)
            }
            .background(if (focused) Term.prompt.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(6.dp))
            // Plan B: the held row fills from its start as the hold counts (read in the draw phase only).
            .drawBehind {
                if (SelectorMath.focusedIndex(state.contact, count) == index) {
                    val p = state.hold
                    if (p > 0f) drawRoundRect(holdColor, size = Size(size.width * p, size.height), cornerRadius = CornerRadius(6.dp.toPx()))
                }
            }
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(end = 12.dp),
    ) {
        // Its section's letter where a section starts, and on the focused row, in the accent colour:
        // the letter you are at, where you look.
        Text(
            if (entry.sectionStart || focused) entry.section.toString() else "",
            style = text.copy(
                color = if (focused) Term.prompt else if (entry.favourite) Term.prompt.copy(alpha = 0.7f) else Term.dim.copy(alpha = 0.7f),
                fontWeight = if (focused) FontWeight.Bold else null,
                fontSize = (Term.fontSize - 3).sp,
            ),
            modifier = Modifier.width(26.dp).padding(start = 10.dp),
            maxLines = 1,
        )
        // Plan E: the app's icon, as the suggestions show it; it grows with the row in the fisheye.
        if (icon != null) AppIcon(app, icon, Modifier.padding(end = 8.dp).testTag("app-selector-icon"))
        if (app.profile.kind != ProfileKind.PERSONAL) {
            Text(
                "[${app.profile.kind.tag}] ",
                style = text.copy(color = Term.profileColor(app.profile.kind), fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp),
            )
        }
        if (pinned && !entry.favourite) Text("★ ", style = text.copy(color = Term.prompt.copy(alpha = 0.7f)))
        Text(
            app.label,
            style = text.copy(
                color = if (focused) Term.prompt else Term.fg,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // B2: on the far side, lifting opens the app's actions; the row says so.
        if (focused && state.far) {
            Text("⋯ Actions", style = text.copy(color = Term.prompt, fontSize = (Term.fontSize - 2).sp), maxLines = 1)
        }
    }
}
