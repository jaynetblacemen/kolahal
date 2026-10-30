package com.mtwabpin.kolahal.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.apps.Profile
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Config
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.status.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** What a tap on a header item does. */
enum class HeaderTarget { CLOCK, UNLOCKS, BATTERY, NETWORK, ALARM, SETTINGS, TODO }

/** Counts the phone's time-zone changes, for formatters to be made again in the new zone. */
@Composable
internal fun rememberZoneChanges(): Int {
    val context = androidx.compose.ui.platform.LocalContext.current
    var zone by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                zone++
            }
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return zone
}

/**
 * The time, a minute at a time, as the system ticks it (on waking too, where a delay that slept
 * with the phone wouldn't), and at once when the clock or the time zone is set. The second value
 * counts time-zone changes ([rememberZoneChanges]).
 */
@Composable
private fun rememberNow(): Pair<Date, Int> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by remember { androidx.compose.runtime.mutableStateOf(Date()) }
    val zone = rememberZoneChanges()
    androidx.compose.runtime.DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                now = Date()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_TICK)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        now = Date()
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Back on screen: the time now, whether or not a tick came while away.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        now = Date()
        onPauseOrDispose { }
    }
    return now to zone
}

/** One-line header while the log is in use: time, battery and how many status lines are hidden. */
@Composable
internal fun CompactHeader(status: Status, statusCount: Int, config: Config, version: Int, onExpand: () -> Unit, mediaPlaying: Boolean = false) {
    val (now, zone) = rememberNow()
    val is24h = remember(version) { config[Keys.CLOCK_24H] }
    val timeFmt = remember(is24h, zone) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    val small = Term.text.copy(fontSize = (Term.fontSize - 1).sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("header-compact")
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 24.dp.toPx()) onExpand() },
                ) { _, dy -> total += dy }
            }
            .heightIn(min = HEADER_ROW)
            .padding(horizontal = 12.dp),
    ) {
        Text(timeFmt.format(now), style = small.copy(color = Term.fg, fontWeight = FontWeight.Bold))
        status.batteryPct?.let { pct ->
            val color = when {
                status.charging -> Term.prompt
                pct <= 15 -> Term.error
                else -> Term.dim
            }
            if (config[Keys.HEADER_ICONS]) {
                Spacer(Modifier.width(10.dp))
                BatteryGlyph(pct, status.charging, color, (Term.fontSize + 2).dp)
                Text(" $pct%", style = small.copy(color = color))
            } else {
                Text("  bat $pct%" + if (status.charging) "+" else "", style = small.copy(color = color))
            }
        }
        if (mediaPlaying) {
            if (config[Keys.HEADER_ICONS]) {
                Spacer(Modifier.width(10.dp))
                TintedIcon(R.drawable.ic_music_note, Term.prompt, (Term.fontSize + 2).dp)
            } else {
                Text("  ♪", style = small.copy(color = Term.prompt))
            }
        }
        if (statusCount > 0) Text("  · $statusCount status", style = small.copy(color = Term.dim))
        Spacer(Modifier.weight(1f))
        TintedIcon(R.drawable.ic_expand_more, Term.dim, (Term.fontSize + 6).dp)
    }
}

/**
 * A Material icon from res/drawable, tinted. Icons are drawn rather than typed: symbols like ⚙, ♪
 * or ⏭ as text come from fallback fonts and look uneven next to them.
 */
@Composable
internal fun TintedIcon(res: Int, tint: Color, size: Dp, description: String? = null) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(res),
        contentDescription = description,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

/** Height of a header row: a fair touch target that keeps the header tight. */
private val HEADER_ROW = 40.dp

/**
 * Clock, date and the status items in one flowing line that wraps as whole items. Items are tappable
 * (alarms, unlock history, battery, network, profile toggle); swipe down opens notifications.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Header(
    unlocks: Int,
    profiles: List<Profile>,
    status: Status,
    statuses: Map<String, String>,
    config: Config,
    version: Int,
    onStatusTap: (String) -> Unit,
    onSwipeDown: () -> Unit,
    onTarget: (HeaderTarget) -> Unit,
    onProfileTap: (Profile) -> Unit,
    nowPlaying: com.mtwabpin.kolahal.control.NowPlaying? = null,
    onMediaToggle: () -> Unit = {},
    onMediaNext: () -> Unit = {},
    todoCount: Int = 0,
    /** Long-press on a script's line: its menu (run, hide from header, edit). */
    onStatusLongPress: (String) -> Unit = {},
) {
    val (now, zone) = rememberNow()
    val is24h = remember(version) { config[Keys.CLOCK_24H] }
    val timeFmt = remember(is24h, zone) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    val dateFmt = remember(zone) { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val alarmFmt = remember(is24h, zone) { SimpleDateFormat(if (is24h) "EEE HH:mm" else "EEE h:mm a", Locale.getDefault()) }
    val small = Term.text.copy(fontSize = (Term.fontSize - 1).sp)

    val useIcons = config[Keys.HEADER_ICONS]
    val iconSize = (Term.fontSize + 3).dp

    /** A header item: an icon (header.icons) or a dim text label, then the value. */
    @Composable
    fun Item(
        label: String,
        value: String,
        valueColor: Color = Term.fg,
        icon: (@Composable () -> Unit)? = null,
        /** What a screen reader says for an item its icon alone shows (⚙, the network kind). */
        description: String? = null,
        onClick: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                // Comfortable touch target even for short items like ⚙, without spreading the header out.
                .clickable(onClickLabel = label.ifEmpty { null }, onClick = onClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .heightIn(min = HEADER_ROW)
                .widthIn(min = 44.dp)
                .padding(horizontal = 6.dp),
        ) {
            if (useIcons && icon != null) {
                icon()
                if (value.isNotEmpty()) Spacer(Modifier.width(4.dp))
            }
            Text(
                buildAnnotatedString {
                    if (label.isNotEmpty() && !(useIcons && icon != null)) withStyle(SpanStyle(color = Term.dim)) { append("$label ") }
                    withStyle(SpanStyle(color = valueColor, fontWeight = FontWeight.Bold)) { append(value) }
                },
                style = small,
            )
        }
    }

    @Composable
    fun Glyph(res: Int, tint: Color = Term.dim) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(res),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
            modifier = Modifier.size(iconSize),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("header")
            // Swipe down on the header pulls down the notification shade.
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 48.dp.toPx()) onSwipeDown() },
                ) { _, dy -> total += dy }
            }
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        // One flowing line: clock and date, then the status items beside them. Items wrap as whole
        // items onto a second line only when they don't fit.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onTarget(HeaderTarget.CLOCK) }.heightIn(min = HEADER_ROW).padding(end = 6.dp),
        ) {
            Text(
                timeFmt.format(now),
                style = Term.text.copy(color = Term.fg, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize + 6).sp),
            )
            if (config[Keys.HEADER_DATE]) {
                Text(
                    "  " + dateFmt.format(now),
                    style = small.copy(color = Term.dim),
                )
            }
        }
            if (config[Keys.HEADER_UNLOCKS]) {
                Item("unlocks", unlocks.toString(), icon = { Glyph(R.drawable.ic_lock_open) }) { onTarget(HeaderTarget.UNLOCKS) }
            }
            val pct = status.batteryPct
            if (config[Keys.HEADER_BATTERY] && pct != null) {
                val color = when {
                    status.charging -> Term.prompt
                    pct <= 15 -> Term.error
                    else -> Term.fg
                }
                Item(
                    "bat", "$pct%" + if (status.charging && !useIcons) "+" else "", color,
                    icon = { BatteryGlyph(pct, status.charging, color, iconSize) },
                ) { onTarget(HeaderTarget.BATTERY) }
            }
            if (config[Keys.HEADER_NETWORK]) {
                val offline = status.network == "offline"
                val netIcon = when (status.network) {
                    "wifi" -> R.drawable.ic_wifi
                    "cell" -> R.drawable.ic_signal
                    "offline" -> R.drawable.ic_cloud_off
                    else -> null
                }
                // With an icon the kind is obvious; only unusual kinds (eth, vpn…) keep their name.
                Item(
                    "net", if (useIcons && netIcon != null) "" else status.network, if (offline) Term.error else Term.fg,
                    icon = netIcon?.let { res -> { Glyph(res, if (offline) Term.error else Term.fg) } },
                    description = "network ${status.network}",
                ) { onTarget(HeaderTarget.NETWORK) }
            }
            if (config[Keys.HEADER_ALARM] && status.nextAlarm != null) {
                Item("alarm", alarmFmt.format(Date(status.nextAlarm)), icon = { Glyph(R.drawable.ic_alarm) }) { onTarget(HeaderTarget.ALARM) }
            }
            if (config[Keys.HEADER_TODO] && todoCount > 0) {
                Item("todo", todoCount.toString(), icon = { Glyph(R.drawable.ic_check) }) { onTarget(HeaderTarget.TODO) }
            }
            Item("", if (useIcons) "" else "⚙", Term.dim, icon = { Glyph(R.drawable.ic_settings) }, description = "Settings") { onTarget(HeaderTarget.SETTINGS) }
            if (config[Keys.HEADER_PROFILES]) {
                profiles.filter { it.kind != ProfileKind.PERSONAL && it.kind != ProfileKind.CLONE }.forEach { p ->
                    val color = if (p.quiet) Term.dim else Term.profileColor(p.kind)
                    val res = if (p.kind == ProfileKind.WORK) R.drawable.ic_work else R.drawable.ic_lock
                    Item(p.kind.label, if (p.quiet) "off" else "on", color, icon = { Glyph(res, color) }) {
                        onProfileTap(p)
                    }
                }
            }
            if (config[Keys.HEADER_DEVICE]) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Term.prompt, fontWeight = FontWeight.Bold)) { append(config[Keys.USER_NAME]) }
                        withStyle(SpanStyle(color = Term.dim)) { append("@" + Build.MODEL.lowercase().replace(' ', '-')) }
                    },
                    style = small,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        // `now` ticks each minute, so a paused session drops out after 30 minutes.
        if (nowPlaying != null && nowPlaying.showInHeader(now.time)) NowPlayingLine(nowPlaying, onMediaToggle, onMediaNext)
        if (config[Keys.HEADER_STATUS]) {
            statuses.forEach { (name, text) ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Term.dim)) { append("$name ▸ ") }
                        withStyle(SpanStyle(color = Term.script)) { append(text) }
                    },
                    style = small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // As tall as a log line (log.line-height), so a few status lines stay compact.
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onLongClick = { onStatusLongPress(name) }) { onStatusTap(name) }
                        .heightIn(min = with(androidx.compose.ui.platform.LocalDensity.current) { Term.lineHeight.sp.toDp() })
                        .wrapContentHeight(),
                )
            }
        }
    }
}

/**
 * A battery drawn to scale: outline, a fill proportional to [pct], and a bolt cut out of the
 * fill while charging: a hole, so whatever the glyph sits on shows through it (home's page, a
 * menu, the wallpaper). Sized to sit on a line of header text.
 */
@Composable
internal fun BatteryGlyph(pct: Int, charging: Boolean, color: Color, height: Dp) {
    androidx.compose.foundation.Canvas(
        Modifier
            .size(width = height * 1.6f, height = height)
            // Its own layer, so the cut clears only the glyph's pixels.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        val stroke = 1.4.dp.toPx()
        val capW = size.width * 0.08f
        val bodyW = size.width - capW
        val bodyH = size.height * 0.62f
        val top = (size.height - bodyH) / 2
        val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        drawRoundRect(color, Offset(stroke / 2, top), Size(bodyW - stroke, bodyH), r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawRect(color, Offset(bodyW, top + bodyH * 0.3f), Size(capW, bodyH * 0.4f))
        val inset = stroke * 1.8f
        val fullW = bodyW - stroke - inset * 2
        val fillW = fullW * (pct.coerceIn(0, 100) / 100f)
        if (fillW > 0f) drawRect(color, Offset(stroke / 2 + inset, top + inset), Size(fillW, bodyH - inset * 2))
        if (charging) {
            // Cut out of the fill so it reads on top of it; over the empty part, drawn in the colour.
            val cx = stroke / 2 + inset + fullW / 2
            val h = bodyH - inset * 2
            val y0 = top + inset
            val bolt = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + h * 0.15f, y0)
                lineTo(cx - h * 0.35f, y0 + h * 0.58f)
                lineTo(cx, y0 + h * 0.58f)
                lineTo(cx - h * 0.15f, y0 + h)
                lineTo(cx + h * 0.35f, y0 + h * 0.42f)
                lineTo(cx, y0 + h * 0.42f)
                close()
            }
            if (fillW > fullW / 2) drawPath(bolt, Color.Black, blendMode = androidx.compose.ui.graphics.BlendMode.Clear) else drawPath(bolt, color)
        }
    }
}
