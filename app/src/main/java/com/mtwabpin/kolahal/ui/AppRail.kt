package com.mtwabpin.kolahal.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.ProfileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
     */
    fun <T> build(
        items: List<T>,
        label: (T) -> String,
        key: (T) -> String,
        pinned: (T) -> Boolean,
        profileRank: (T) -> Int,
    ): List<SelectorEntry<T>> {
        val byLabel = compareBy<T>({ label(it).lowercase() }, { profileRank(it) })
        val favs = items.filter(pinned).sortedWith(byLabel)
        val all = items.sortedWith(compareBy<T> { LETTERS.indexOf(letterOf(label(it))) }.then(byLabel))
        val out = ArrayList<SelectorEntry<T>>(favs.size + all.size)
        var section = -1
        favs.forEachIndexed { i, it ->
            if (i == 0) section++
            out += SelectorEntry(it, "f:" + key(it), FAV, section, i == 0, true)
        }
        var prev: Char? = null
        for (it in all) {
            val letter = letterOf(label(it))
            val start = letter != prev
            if (start) section++
            prev = letter
            out += SelectorEntry(it, "a:" + key(it), letter, section, start, false)
        }
        return out
    }

    /** Dead band at each end of the strip: the first and last few percent pin the list's ends. */
    const val DEAD = 0.04f

    /**
     * Strip position [t] (0 top … 1 bottom) → list scroll fraction: proportional, with dead
     * bands and a slight ease at the ends so the first and last apps are easy to land on.
     */
    fun scrollFraction(t: Float): Float {
        val u = ((t - DEAD) / (1f - 2 * DEAD)).coerceIn(0f, 1f)
        val smooth = u * u * (3f - 2f * u)
        return 0.7f * u + 0.3f * smooth
    }

    /** The list position (rows) [fraction] of the way along a list of [count]: 0 the first row's top, count the last's bottom. */
    fun alongList(fraction: Float, count: Int): Float = (fraction * count).coerceIn(0f, max(0f, count - 0.01f))

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

    fun close() {
        mode = Mode.CLOSED
        contact = Float.NaN
        shiftPx = 0f
    }
}

private val ROW_H = 38.dp
/** Past the strip's inner side by this much, the finger is picking from the list, not sliding the edge. */
private val LEAVE = 32.dp
private val STRIP_W = 36.dp
private val TOP_GAP = 8.dp
/** Leaves the "jump to latest" button in the log's bottom-right corner reachable. */
private val BOTTOM_GAP = 72.dp
/** Held still this long on an app while picking: its actions. Long enough to find it on the far side first. */
private const val HOLD_MS = 800L
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
 * (favourites first, then A–Z), away from the thumb. Sliding along the strip glides through the
 * whole list proportionally (its top is the first app, its bottom the last); the focused row is
 * magnified with its neighbours (fisheye), level with the thumb, and shows its section's letter,
 * ticking once per section; past the list's ends the panel itself moves to keep it level. Slide off the edge, toward the panel, to freeze the list and pick a
 * row (auto-scrolling at the panel's ends); lift to launch, or hold still for its actions. Lifting
 * on the edge leaves the panel open as a normal list (fling, tap, long-press); an outside tap,
 * Back or 4 s idle closes it.
 *
 * The strip consumes all its touches, so the home pager never turns the page under it.
 * Gesture exclusion: Android honours at most 200dp per screen edge, so only the strip's central
 * 200dp opts out of the back swipe; elsewhere an edge swipe still goes back (and cancels).
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
    /** selector.side: the strip on the left edge, the panel on the right. */
    left: Boolean = false,
    /** selector.position: the strip at the edge's `bottom`, `middle` or `top`. */
    position: String = "bottom",
) {
    val entries = remember(apps, pinned) {
        SelectorMath.build(
            apps.filter { !it.hidden },
            label = { it.label },
            key = { it.key },
            pinned = { it.key in pinned },
            profileRank = { it.profile.kind.ordinal },
        )
    }
    val state = remember { SelectorState() }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val launch by rememberUpdatedState(onLaunch)
    val longPress by rememberUpdatedState(onLongPress)

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

    // The panel can move past the log's edges to keep the focused app level with the finger.
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        // selector.height: a strip of the edge, in the thumb's reach (selector.position), instead of
        // the whole log height; the full list still maps onto it (its top = the first app).
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
        val rowPx = with(density) { ROW_H.roundToPx() }
        // The panel uses the whole height, whatever the strip's; a short list sits at the bottom.
        val listHPx = containerH - 2 * topPx
        val panelHPx = min(listHPx, (count * rowPx).toFloat()).coerceAtLeast(rowPx.toFloat())
        val panelTopPx = topPx + listHPx - panelHPx
        val stripWPx = with(density) { STRIP_W.toPx() }
        // On the other side from the strip.
        val panelW = with(density) { (containerW * 0.7f).toDp() }.coerceAtLeast(220.dp)
            .coerceAtMost(maxWidth - STRIP_W - 24.dp)
        val panelWPx = with(density) { panelW.toPx() }
        val panelXPx = if (left) containerW - panelWPx else 0f
        val stripXPx = if (left) 0f else containerW - stripWPx
        val exclusionPx = with(density) { 200.dp.toPx() }
        // How far off the edge picking starts: a thumb drifts a little while it slides along the edge.
        val leavePx = with(density) { LEAVE.toPx() }
        val slopPx = with(density) { 8.dp.toPx() }
        val edgeZonePx = with(density) { 56.dp.toPx() }
        val edgeMaxSpeed = with(density) { 1.1.dp.toPx() } // px per ms at full depth
        val open = state.mode != Mode.CLOSED

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
                itemsIndexed(entries, key = { _, e -> e.key }) { index, entry ->
                    SelectorRow(
                        entry = entry,
                        index = index,
                        count = count,
                        state = state,
                        rowPx = rowPx,
                        pinned = entry.item.key in pinned,
                        enabled = state.mode == Mode.OPEN,
                        onClick = { state.close(); launch(entry.item) },
                        onLongClick = { state.close(); longPress(entry.item) },
                    )
                }
            }
        }

        // The edge strip: invisible touch area with a slim handle; owns every touch it gets.
        Box(
            Modifier
                .offset { IntOffset(stripXPx.roundToInt(), stripTopPx.roundToInt()) }
                .width(STRIP_W)
                .height(with(density) { stripHPx.toDp() })
                .testTag("app-selector")
                .semantics { contentDescription = "app selector: slide along the edge to browse all apps" }
                // Only the central 200dp: Android ignores exclusion beyond 200dp per edge.
                .systemGestureExclusion { c ->
                    val h = c.size.height.toFloat()
                    val top = ((h - exclusionPx) / 2).coerceAtLeast(0f)
                    Rect(0f, top, c.size.width.toFloat(), min(h, top + exclusionPx))
                }
                .pointerInput(entries, rowPx, panelHPx, panelTopPx, stripTopPx, stripHPx, left) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (count == 0) {
                            while (awaitPointerEvent().changes.onEach { it.consume() }.any { it.pressed }) {}
                            return@awaitEachGesture
                        }
                        val maxScrollPx = SelectorMath.maxScroll(count, rowPx.toFloat(), panelHPx)
                        var x = down.position.x
                        var y = down.position.y
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

                        fun update(now: Long) {
                            val cy = stripTopPx + y
                            state.fingerY = cy
                            // The finger's height in the panel (past its ends when the strip reaches
                            // beyond it): the focused app is drawn level with it, as Niagara does, so the
                            // eye never leaves the thumb's height.
                            val yIn = cy - panelTopPx
                            if (!selecting) {
                                // Along the strip, the whole list (its top the first app, its bottom the
                                // last), brought level with the finger: scrolled, and past the list's ends
                                // the panel moved.
                                val at = SelectorMath.alongList(SelectorMath.scrollFraction(y / stripHPx), count)
                                state.listY = SelectorMath.levelWith(at, yIn, rowPx.toFloat())
                            }
                            val (scroll, shift) = SelectorMath.place(state.listY, maxScrollPx)
                            state.scrollPx = scroll
                            state.shiftPx = shift
                            listState.scrollToPx(state.scrollPx, rowPx)
                            // Picking: the app level with the finger, as it moves up and down.
                            state.contact = SelectorMath.contact(state.listY, yIn, rowPx.toFloat())
                            val f = SelectorMath.focusedIndex(state.contact, count)
                            val section = entries[f].sectionIndex
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
                        }

                        state.interaction++
                        state.mode = Mode.SCRUB
                        try {
                            update(rowSince)
                            while (true) {
                                val now = SystemClock.uptimeMillis()
                                val speed = if (selecting) {
                                    SelectorMath.edgeSpeed(stripTopPx + y - panelTopPx, panelHPx, edgeZonePx, edgeMaxSpeed)
                                } else 0f
                                val scrolling = speed != 0f &&
                                    !(speed < 0f && state.listY <= 0f) && !(speed > 0f && state.listY >= maxScrollPx)
                                val wait = when {
                                    scrolling -> FRAME_MS
                                    selecting -> (HOLD_MS - (now - rowSince)).coerceAtLeast(1L)
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
                                    if (selecting && t - rowSince >= HOLD_MS) {
                                        // Held still on a row: its actions.
                                        val app = entries[focus].item
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        state.close()
                                        longPress(app)
                                        break
                                    }
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
                                        val app = entries[focus].item
                                        state.close()
                                        launch(app)
                                    } else {
                                        // Lifted on the edge: leave the list open for a tap or a fling, back
                                        // in its place (scrolled as far as it went).
                                        state.contact = Float.NaN
                                        state.listY = state.scrollPx
                                        state.shiftPx = 0f
                                        state.mode = Mode.OPEN
                                        state.interaction++
                                    }
                                    break
                                }
                                change.consume()
                                x = change.position.x
                                y = change.position.y
                                // Off the edge, toward the panel: the list holds still to pick from.
                                selecting = if (left) x > stripWPx + leavePx else x < -leavePx
                                state.mode = if (selecting) Mode.SELECT else Mode.SCRUB
                                update(SystemClock.uptimeMillis())
                            }
                        } catch (e: CancellationException) {
                            state.close()
                            throw e
                        }
                    }
                },
        ) {
            // The handle: a slim bar hugging the edge, following the finger while touched.
            val touching = state.mode == Mode.SCRUB || state.mode == Mode.SELECT
            if (showHandle || touching) {
            val handleH = 64.dp
            val handleHPx = with(density) { handleH.toPx() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(start = if (left) 3.dp else 0.dp, end = if (left) 0.dp else 3.dp)
                    .graphicsLayer {
                        translationY = if (touching) {
                            (state.fingerY - stripTopPx - stripHPx / 2).coerceIn(-(stripHPx - handleHPx) / 2, (stripHPx - handleHPx) / 2)
                        } else 0f
                        alpha = if (open && !touching) 0.35f else 1f
                    },
            ) {
                val small = Term.text.copy(color = Term.dim.copy(alpha = 0.45f), fontSize = 8.sp, lineHeight = 9.sp)
                if (!touching) Text("A", style = small)
                Box(
                    Modifier
                        .padding(vertical = 2.dp)
                        .width(3.dp)
                        .height(handleH)
                        .background(if (touching) Term.prompt else Term.dim.copy(alpha = 0.55f), RoundedCornerShape(2.dp))
                )
                if (!touching) Text("Z", style = small)
            }
            }
        }
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
    pinned: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val app = entry.item
    val focused by remember(index, count) {
        derivedStateOf { SelectorMath.focusedIndex(state.contact, count) == index }
    }
    val quiet = app.profile.quiet
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_H)
            // Fisheye in the draw phase only: no recomposition or relayout per frame.
            .graphicsLayer {
                val c = state.contact
                if (c.isNaN()) {
                    scaleX = 1f; scaleY = 1f; translationY = 0f
                } else {
                    val x = index + 0.5f - c
                    val s = SelectorMath.fisheyeScale(x)
                    scaleX = s; scaleY = s
                    translationY = SelectorMath.fisheyeShift(x) * rowPx
                }
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = if (quiet) 0.45f else 1f
            }
            .background(if (focused) Term.prompt.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(6.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(end = 12.dp),
    ) {
        // Its section's letter where a section starts, and on the focused row, in the accent colour:
        // the letter you are at, where you look.
        Text(
            if (entry.sectionStart || focused) entry.section.toString() else "",
            style = Term.text.copy(
                color = if (focused) Term.prompt else if (entry.favourite) Term.prompt.copy(alpha = 0.7f) else Term.dim.copy(alpha = 0.7f),
                fontWeight = if (focused) FontWeight.Bold else null,
                fontSize = (Term.fontSize - 3).sp,
            ),
            modifier = Modifier.width(26.dp).padding(start = 10.dp),
            maxLines = 1,
        )
        if (app.profile.kind != ProfileKind.PERSONAL) {
            Text(
                "[${app.profile.kind.tag}] ",
                style = Term.text.copy(color = Term.profileColor(app.profile.kind), fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp),
            )
        }
        if (pinned && !entry.favourite) Text("★ ", style = Term.text.copy(color = Term.prompt.copy(alpha = 0.7f)))
        Text(
            app.label,
            style = Term.text.copy(
                color = if (focused) Term.prompt else Term.fg,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
