package io.github.rscipher001.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import io.github.rscipher001.launcher.apps.AppEntry
import io.github.rscipher001.launcher.apps.ProfileKind
import kotlinx.coroutines.delay
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure maths of the alphabet rail (bucketing, hit testing, popup placement); unit-tested. */
internal object RailMath {
    const val OTHER = '#'

    /** Rail order: `#` (digits, symbols, other scripts) first, as in the sorted app list, then A–Z. */
    val LETTERS: List<Char> = listOf(OTHER) + ('A'..'Z')

    /** The rail letter of a label: its first letter or digit, accents stripped; anything outside A–Z is `#`. */
    fun letterOf(label: String): Char {
        val first = label.firstOrNull { it.isLetterOrDigit() } ?: return OTHER
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
        return if (base in 'A'..'Z') base else OTHER
    }

    /** Items per rail letter: pinned first, then by label, then by [profileRank] (personal before work). */
    fun <T> buckets(items: List<T>, label: (T) -> String, pinned: (T) -> Boolean, profileRank: (T) -> Int): Map<Char, List<T>> =
        items.groupBy { letterOf(label(it)) }.mapValues { (_, list) ->
            list.sortedWith(compareBy<T>({ !pinned(it) }, { label(it).lowercase() }, { profileRank(it) }))
        }

    /** Letter slot under [y] (rail-local px), clamped so sliding past either end keeps the end letter. */
    fun letterIndexAt(y: Float, slot: Float, count: Int): Int =
        floor(y / slot).toInt().coerceIn(0, count - 1)

    /** Scale of a letter [distance] slots from the touched one: the Niagara-style wave. */
    fun waveScale(distance: Int): Float = when (abs(distance)) {
        0 -> 2.1f
        1 -> 1.5f
        2 -> 1.2f
        else -> 1f
    }

    /** Popup top so its [rows] are centred on [fingerY], kept inside a container of [containerH]. */
    fun popupTop(fingerY: Float, rowH: Float, rows: Int, containerH: Float): Float {
        val h = rowH * rows
        return (fingerY - h / 2).coerceIn(0f, max(0f, containerH - h))
    }

    /** Visible popup row under [fingerY], or null when the finger is above or below the popup. */
    fun popupRowAt(fingerY: Float, top: Float, rowH: Float, rows: Int): Int? {
        if (rows <= 0) return null
        val i = floor((fingerY - top) / rowH).toInt()
        return i.takeIf { it in 0 until rows }
    }

    /** -1 / +1 when the finger is above / below the popup (scroll that way), else 0. */
    fun edgeDirection(fingerY: Float, top: Float, rowH: Float, rows: Int): Int = when {
        fingerY < top -> -1
        fingerY >= top + rowH * rows -> 1
        else -> 0
    }

    /** First visible item after scrolling [dir] from [first], for [count] items shown [rows] at a time. */
    fun scrolled(first: Int, dir: Int, count: Int, rows: Int): Int =
        (first + dir).coerceIn(0, max(0, count - rows))
}

private class RailState {
    /** Letter index under the finger while touching the rail (drives the wave); -1 at rest. */
    var touchIndex by mutableIntStateOf(-1)
    /** Letter whose popup is open. */
    var active by mutableStateOf<Char?>(null)
    var first by mutableIntStateOf(0)
    /** Highlighted item (index into the letter's list). */
    var selected by mutableStateOf<Int?>(null)
    var popupTop by mutableFloatStateOf(0f)
    /** Popup left open after release over the rail, waiting for a tap. */
    var held by mutableStateOf(false)
    var interaction by mutableIntStateOf(0)

    fun close() {
        active = null; held = false; selected = null; touchIndex = -1; first = 0
    }
}

private const val MAX_ROWS = 8
private const val LONG_PRESS_MS = 500L
private const val EDGE_SCROLL_MS = 110L
private const val HOLD_MS = 3_000L

/**
 * Niagara-style alphabet rail along the right edge (`selector.rail`). Touch and slide along it:
 * the letter under the finger swells with its neighbours, a light tick marks each letter, and a
 * popup to the left lists that letter's apps at finger height. Slide left into the popup to pick
 * a row and lift to launch; hold still on a row for its actions. Lifting over the rail leaves the
 * popup open for a tap (it closes on an outside tap or after 3 s).
 *
 * Gesture exclusion: Android honours at most 200dp of exclusion per screen edge, so only the
 * rail's central 200dp opts out of the back swipe. Above and below that band the back gesture
 * keeps working; vertical slides work everywhere on the rail, and the slide left into the popup
 * is safe inside the band (the popup centres on the finger, which is usually there).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRail(
    apps: List<AppEntry>,
    pinned: Set<String>,
    onLaunch: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buckets = remember(apps, pinned) {
        RailMath.buckets(apps.filter { !it.hidden }, { it.label }, { it.key in pinned }, { it.profile.kind.ordinal })
    }
    val state = remember { RailState() }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // An app list change (uninstall, hide) must not leave a stale selection behind.
    LaunchedEffect(buckets) { if (!state.held) state.close() }
    LaunchedEffect(state.held, state.interaction) {
        if (state.held) {
            delay(HOLD_MS)
            state.close()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val letters = RailMath.LETTERS
        // Letters share the height (leaving room for the jump-to-latest button), at most 18dp each.
        val slotDp = ((maxHeight - 96.dp) / letters.size).coerceAtMost(18.dp)
        if (slotDp < 9.dp) return@BoxWithConstraints
        val railH = slotDp * letters.size
        val touchW = 40.dp
        val rowH = 44.dp
        val containerH = constraints.maxHeight.toFloat()
        val containerW = constraints.maxWidth.toFloat()
        val slotPx = with(density) { slotDp.toPx() }
        val railHPx = with(density) { railH.toPx() }
        val rowHPx = with(density) { rowH.toPx() }
        val railTop = (containerH - railHPx) / 2
        val maxRows = min(MAX_ROWS, (containerH / rowHPx).toInt()).coerceAtLeast(1)
        val exclusionPx = with(density) { 200.dp.toPx() }
        val leavePx = with(density) { 6.dp.toPx() }

        val current by rememberUpdatedState(buckets)
        val launch by rememberUpdatedState(onLaunch)
        val longPress by rememberUpdatedState(onLongPress)
        fun appsOf(letter: Char?) = letter?.let { current[it] }.orEmpty()
        fun rowsOf(letter: Char?) = min(appsOf(letter).size, maxRows)

        val open = state.active != null && appsOf(state.active).isNotEmpty()

        // Held open: a tap anywhere else closes it (and is swallowed, like a menu).
        if (state.held) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            state.close()
                        }
                    }
            )
        }

        if (open) {
            val items = appsOf(state.active)
            val rows = rowsOf(state.active)
            val popupW = min(240f, (maxWidth - touchW - 24.dp).value).dp
            val popupWPx = with(density) { popupW.toPx() }
            val gapPx = with(density) { 4.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((containerW - with(density) { touchW.toPx() } - gapPx - popupWPx).roundToInt(), state.popupTop.roundToInt()) }
                    .width(popupW)
                    .height(rowH * rows)
                    .testTag("rail-popup")
                    .background(Term.surface, RoundedCornerShape(6.dp))
                    .border(1.dp, Term.prompt.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    // Held open: drag to scroll a long letter.
                    .pointerInput(rows, items.size) {
                        var acc = 0f
                        detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                            change.consume()
                            acc += dy
                            while (acc <= -rowHPx) { state.first = RailMath.scrolled(state.first, 1, items.size, rows); acc += rowHPx }
                            while (acc >= rowHPx) { state.first = RailMath.scrolled(state.first, -1, items.size, rows); acc -= rowHPx }
                            state.interaction++
                        }
                    },
            ) {
                Column(Modifier.fillMaxSize()) {
                    for (r in 0 until rows) {
                        val index = state.first + r
                        val app = items.getOrNull(index) ?: break
                        RailRow(
                            app = app,
                            pinned = app.key in pinned,
                            selected = state.selected == index,
                            height = rowH,
                            onClick = { state.close(); launch(app) },
                            onLongClick = { state.close(); longPress(app) },
                        )
                    }
                }
                val small = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 3).sp)
                if (state.first > 0) Text("▴${state.first}", style = small, modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp))
                val below = items.size - state.first - rows
                if (below > 0) Text("▾$below", style = small, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp))
            }
        }

        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(touchW)
                .height(railH)
                .testTag("app-rail")
                .semantics { contentDescription = "app index: slide to browse apps by letter" }
                // Only the central 200dp: Android ignores exclusion beyond 200dp per edge.
                .systemGestureExclusion { c ->
                    val h = c.size.height.toFloat()
                    val top = ((h - exclusionPx) / 2).coerceAtLeast(0f)
                    Rect(0f, top, c.size.width.toFloat(), min(h, top + exclusionPx))
                }
                .pointerInput(slotPx, railTop, rowHPx, maxRows, containerH) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        state.held = false
                        var lastIdx = -1
                        var rowSince = 0L
                        var lastEdgeScroll = 0L
                        var inPopup = false

                        fun trackRail(y: Float) {
                            val idx = RailMath.letterIndexAt(y, slotPx, letters.size)
                            if (idx != lastIdx) {
                                lastIdx = idx
                                state.touchIndex = idx
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                val letter = letters[idx]
                                state.active = letter.takeIf { current[it].orEmpty().isNotEmpty() }
                                state.first = 0
                            }
                            state.selected = null
                            state.popupTop = RailMath.popupTop(railTop + y, rowHPx, rowsOf(state.active), containerH)
                        }

                        fun trackPopup(y: Float, now: Long) {
                            val items = appsOf(state.active)
                            val rows = rowsOf(state.active)
                            if (rows == 0) return
                            val cy = railTop + y
                            val row = RailMath.popupRowAt(cy, state.popupTop, rowHPx, rows)
                            if (row == null) {
                                // Past the top or bottom edge of a long letter: scroll, one row per tick.
                                if (now - lastEdgeScroll >= EDGE_SCROLL_MS) {
                                    val dir = RailMath.edgeDirection(cy, state.popupTop, rowHPx, rows)
                                    val next = RailMath.scrolled(state.first, dir, items.size, rows)
                                    lastEdgeScroll = now
                                    if (next != state.first) state.first = next
                                    val edgeItem = state.first + if (dir < 0) 0 else rows - 1
                                    if (edgeItem != state.selected) {
                                        state.selected = edgeItem
                                        rowSince = now
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                }
                            } else {
                                val index = state.first + row
                                if (index != state.selected) {
                                    state.selected = index
                                    rowSince = now
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                            }
                        }

                        trackRail(down.position.y)
                        var lastY = down.position.y
                        while (true) {
                            val now = System.currentTimeMillis()
                            val selectedApp = state.selected?.let { appsOf(state.active).getOrNull(it) }
                            val edge = inPopup && RailMath.popupRowAt(railTop + lastY, state.popupTop, rowHPx, rowsOf(state.active)) == null
                            val wait = when {
                                edge -> EDGE_SCROLL_MS
                                inPopup && selectedApp != null -> (LONG_PRESS_MS - (now - rowSince)).coerceAtLeast(1L)
                                else -> Long.MAX_VALUE
                            }
                            val event = if (wait == Long.MAX_VALUE) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                            if (event == null) {
                                val t = System.currentTimeMillis()
                                if (edge) { trackPopup(lastY, t); continue }
                                // Held still on a row: its actions.
                                if (selectedApp != null && t - rowSince >= LONG_PRESS_MS) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    state.close()
                                    longPress(selectedApp)
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
                                    break
                                }
                                val app = state.selected?.let { appsOf(state.active).getOrNull(it) }
                                when {
                                    inPopup && app != null -> { state.close(); launch(app) }
                                    !inPopup && state.active != null -> {
                                        state.held = true
                                        state.touchIndex = -1
                                        state.interaction++
                                    }
                                    else -> state.close()
                                }
                                break
                            }
                            change.consume()
                            val p = change.position
                            lastY = p.y
                            inPopup = p.x < -leavePx
                            if (inPopup) trackPopup(p.y, System.currentTimeMillis()) else trackRail(p.y)
                        }
                        if (!state.held) state.touchIndex = -1
                    }
                },
        ) {
            // The visible strip: 24dp at the right edge, letters right-aligned.
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(24.dp)
                    .fillMaxHeight()
                    .background(Term.bg.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
            ) {
                val touching = state.touchIndex
                val activeIdx = state.active?.let { letters.indexOf(it) } ?: -1
                letters.forEachIndexed { i, letter ->
                    val has = current[letter].orEmpty().isNotEmpty()
                    val target = if (touching >= 0) RailMath.waveScale(i - touching) else 1f
                    val scale by animateFloatAsState(target, tween(90), label = "rail-wave")
                    val shiftPx = with(density) { 14.dp.toPx() }
                    val color = when {
                        i == activeIdx || i == touching -> Term.prompt
                        has -> Term.fg.copy(alpha = 0.75f)
                        else -> Term.dim.copy(alpha = 0.3f)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(slotDp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = -(scale - 1f) * shiftPx
                                transformOrigin = TransformOrigin(1f, 0.5f)
                            },
                    ) {
                        Text(
                            letter.toString(),
                            style = Term.mono.copy(
                                color = color,
                                fontSize = (slotDp.value * 0.7f).coerceAtMost(12f).sp,
                                lineHeight = (slotDp.value * 0.8f).sp,
                                fontWeight = if (i == activeIdx || i == touching) FontWeight.Bold else FontWeight.Normal,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** One popup row: profile tag in its colour, ★ for favourites, the label; quiet profiles faded. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailRow(
    app: AppEntry,
    pinned: Boolean,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val color = Term.profileColor(app.profile.kind)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (app.profile.quiet) 0.45f else 1f)
            .background(if (selected) Term.prompt.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp),
    ) {
        if (app.profile.kind != ProfileKind.PERSONAL) {
            Text(
                "[${app.profile.kind.tag}] ",
                style = Term.mono.copy(color = color, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp),
            )
        }
        if (pinned) Text("★ ", style = Term.mono.copy(color = Term.prompt))
        Text(
            app.label,
            style = Term.mono.copy(color = if (selected) Term.prompt else Term.fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
