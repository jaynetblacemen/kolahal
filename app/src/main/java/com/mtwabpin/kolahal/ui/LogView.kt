package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.mtwabpin.kolahal.notifications.NotifIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.wrapContentHeight
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.terminal.ActionIcon
import com.mtwabpin.kolahal.terminal.Gutter
import com.mtwabpin.kolahal.terminal.LogAction
import com.mtwabpin.kolahal.terminal.LogRow
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** How a line shows its time, from `log.time`. */
enum class TimeMode { OFF, COMMANDS, ALL }

/** What the long-press sheet shows: the line (for context) and its actions. */
/** [systemFont]: the system's font instead of the terminal's (quick actions, which reads as a menu). */
data class ActionMenu(val title: String, val actions: List<LogAction>, val entryId: Long?, val systemFont: Boolean = false)

@Composable
fun DayDivider(row: LogRow.Day) {
    val label = remember(row.day) {
        // Made here, not once per process, so the dividers follow a change of language.
        if (row.day == LocalDate.now().minusDays(1)) "Yesterday"
        else row.day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    Text(
        "── $label ──",
        style = Term.text.copy(color = Term.dim, fontSize = (Term.fontSize - 1).sp),
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** "── 3 new since you left ──" above the first unread script line. */
@Composable
fun UnreadDivider(row: LogRow.Unread) {
    Text(
        "── ${row.count} new since you left ──",
        style = Term.text.copy(color = Term.prompt, fontSize = (Term.fontSize - 1).sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** A stable colour per script name, so interleaved feeds are easy to tell apart. */
fun scriptColor(name: String?): Color {
    if (name == null) return Term.script
    val p = Term.palette
    val choices = listOf(p.script, p.link, p.info, p.work, p.private, p.prompt)
    return choices[Math.floorMod(name.hashCode(), choices.size)]
}

/**
 * One log entry. A thin gutter bar groups command blocks (prompt colour), timer output (script
 * colour) and errors; old output is dimmed; the time sits right-aligned so it never shifts text.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogLine(
    row: LogRow.Entry,
    timeMode: TimeMode,
    timeFormat: SimpleDateFormat,
    dim: Boolean,
    topGap: Dp,
    gutter: Boolean = true,
    charWidth: TextUnit,
    highlighted: Boolean,
    apps: (LogEntry) -> List<AppEntry>,
    onTap: (LogEntry) -> Unit,
    onLongPress: (LogEntry) -> Unit,
    onAppTap: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onSwipeCopy: (LogEntry) -> Unit = {},
    onSwipeDelete: (LogEntry) -> Unit = {},
) {
    val entry = row.entry
    // Swipe right: copy. Swipe left: delete (the terminal offers Undo). Past a third of the width.
    val swipe = remember { androidx.compose.animation.core.Animatable(0f) }
    val swipeScope = androidx.compose.runtime.rememberCoroutineScope()
    var rowWidth by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val haptics = LocalHapticFeedback.current
    val gutterColor = when (row.gutter) {
        Gutter.COMMAND -> Term.prompt
        Gutter.SCRIPT -> Term.script
        Gutter.ERROR -> Term.error
    }.copy(alpha = 0.45f)
    val showTime = timeMode == TimeMode.ALL || (timeMode == TimeMode.COMMANDS && entry.kind == LogKind.COMMAND)
    val desc = entry.ctx.values[LogContext.DESC]
    val isGrid = entry.ctx.values[LogContext.APPS] != null
    val image = entry.ctx.image
    // A notification line's saved status-bar icon replaces its app name at the end.
    val context = LocalContext.current
    val iconKey = entry.ctx.values[LogContext.ICON]?.takeIf { entry.kind == LogKind.SCRIPT && NotifIcons.has(context, it) }
    val inline = remember(iconKey) { iconKey?.let(::notifIconContent) ?: emptyMap() }

    // Lines that do something get a larger touch target than plain output.
    val actionable = entry.kind == LogKind.COMMAND || entry.ctx.values.keys.any { it in ACTIONABLE_KEYS }
    val minHeight = when {
        desc != null -> (Term.lineHeight + 14).dp
        actionable -> (Term.lineHeight + 10).dp
        else -> Term.lineHeight.dp
    }
    Box(Modifier.padding(top = topGap)) {
    // What the swipe will do, revealed under the line as it moves.
    if (swipe.value != 0f) {
        Text(
            if (swipe.value > 0) "⧉ Copy" else "Delete ✕",
            style = Term.text.copy(color = if (swipe.value > 0) Term.prompt else Term.error),
            modifier = Modifier.matchParentSize().wrapContentHeight().padding(horizontal = 16.dp),
            textAlign = if (swipe.value > 0) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .onSizeChanged { rowWidth = it.width.coerceAtLeast(1) }
            .offset { androidx.compose.ui.unit.IntOffset(swipe.value.toInt(), 0) }
            .pointerInput(entry.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val limit = rowWidth / 3f
                        swipeScope.launch {
                            when {
                                swipe.value > limit -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeCopy(entry)
                                    swipe.animateTo(0f)
                                }
                                swipe.value < -limit -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    swipe.animateTo(-rowWidth.toFloat())
                                    onSwipeDelete(entry)
                                }
                                else -> swipe.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { swipeScope.launch { swipe.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    swipeScope.launch { swipe.snapTo(swipe.value + dx) }
                }
            }
            .background(Term.bg)
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (dim && row.dimmed) Modifier.alpha(0.62f) else Modifier)
            .background(if (highlighted) Term.surface else Color.Transparent)
            .then(if (!gutter) Modifier else Modifier.drawBehind {
                drawRect(gutterColor, topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
            })
            .combinedClickable(
                onLongClickLabel = "actions",
                onClick = { onTap(entry) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress(entry)
                },
            )
            // Room for the gutter bar; without it the text lines up with the header and dividers.
            .padding(start = if (gutter) 10.dp else 0.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Box(Modifier.weight(1f)) {
            when {
                // Parsed once per entry: it decodes JSON and looks up every app.
                isGrid -> AppGrid(remember(entry.id, entry.ctx) { apps(entry) }, onAppTap, onAppLongPress)
                desc != null -> TwoColumns(entry, desc)
                else -> {
                    val text = remember(entry, Term.palette, charWidth, row.unread, iconKey) { renderLine(entry, charWidth, row.unread, iconKey != null) }
                    if (image == null) Text(text, style = Term.text, inlineContent = inline)
                    else Column {
                        if (entry.text.isNotEmpty()) Text(text, style = Term.text, inlineContent = inline)
                        LogImage(image, Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        if (showTime) {
            Spacer(Modifier.width(8.dp))
            Text(
                remember(entry.ts, timeFormat) { timeFormat.format(Date(entry.ts)) },
                style = Term.text.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
                // Beside the text line, not halfway down the image.
                modifier = Modifier.then(if (image != null) Modifier.align(Alignment.Top) else Modifier).padding(top = 2.dp),
            )
        }
    }
    }
}

private val ACTIONABLE_KEYS = setOf(
    LogContext.URL, LogContext.PKG, LogContext.COMMAND, LogContext.RUN, LogContext.SCRIPT, LogContext.APPS, LogContext.LINE,
    LogContext.PHONE, LogContext.SHORTCUT, "notif", LogContext.NOTE, LogContext.EVENT, LogContext.REMINDER,
)

/** `help`, `config` and `script ls` rows: name on the left, description wrapping on the right. */
@Composable
private fun TwoColumns(entry: LogEntry, desc: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            entry.text,
            style = Term.text.copy(color = Term.fg, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(0.46f).padding(end = 8.dp),
        )
        Text(desc, style = Term.text.copy(color = Term.dim), modifier = Modifier.weight(0.54f))
    }
}

/** `apps` output: one line of chips instead of one line per app. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(apps: List<AppEntry>, onTap: (AppEntry) -> Unit, onLongPress: (AppEntry) -> Unit) {
    val haptics = LocalHapticFeedback.current
    if (apps.isEmpty()) {
        Text("(apps no longer installed)", style = Term.text.copy(color = Term.dim))
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        apps.forEach { app ->
            val color = Term.profileColor(app.profile.kind)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = Term.chipHeight.dp)
                    .alpha(if (app.profile.quiet) 0.45f else 1f)
                    .background(color.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                    .combinedClickable(
                        onClick = { onTap(app) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(app)
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                if (app.profile.kind != ProfileKind.PERSONAL) {
                    Text(
                        app.profile.kind.tag + if (app.profile.quiet) "⏸" else "",
                        style = Term.text.copy(color = color, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(app.label, style = Term.text)
            }
        }
    }
}

/** A profile tag at the end of a line's first row (`Launched Maps [W]`, `Updated Maps [W] ↻`). */
private val profileTag = Regex(" \\[([PWSC])](?= \\S*$|$)")

/** Appends [text] in [color], with a trailing profile tag in its profile colour. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendTagged(text: String, color: Color, weight: FontWeight? = null) {
    val tag = profileTag.find(text)
    if (tag == null) {
        withStyle(SpanStyle(color = color, fontWeight = weight)) { append(text) }
        return
    }
    val kind = ProfileKind.entries.first { it.tag == tag.groupValues[1] }
    withStyle(SpanStyle(color = color, fontWeight = weight)) { append(text.substring(0, tag.range.first)) }
    withStyle(SpanStyle(color = Term.profileColor(kind), fontWeight = FontWeight.Bold)) { append(tag.value) }
    withStyle(SpanStyle(color = color, fontWeight = weight)) { append(text.substring(tag.range.last + 1)) }
}

/**
 * Styles one log line. Wrapped continuation lines are indented past the prefix (`$ `, `[hn] `) so
 * each item reads as one block; [charWidth] is the monospace advance.
 */
private const val NOTIF_ICON = "notif-icon"

/** The status-bar icon at the end of a notification line, tinted like the muted name it replaces. */
private fun notifIconContent(key: String) = mapOf(
    NOTIF_ICON to InlineTextContent(Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) { NotifIcon(key) },
)

@Composable
private fun NotifIcon(key: String) {
    val context = LocalContext.current
    val icon by produceState(NotifIcons.cached(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { NotifIcons.load(context.applicationContext, key) }
    }
    icon?.let { Image(it, contentDescription = null, colorFilter = ColorFilter.tint(Term.dim), modifier = Modifier.fillMaxSize()) }
}

/** [icon]: end a script line with its notification icon instead of `· app`. */
private fun renderLine(entry: LogEntry, charWidth: TextUnit, unread: Boolean, icon: Boolean = false) = buildAnnotatedString {
    val prefixLen = when (entry.kind) {
        LogKind.COMMAND -> 2
        LogKind.SCRIPT -> if (unread) 2 else 0
        else -> 2
    }
    withStyle(ParagraphStyle(textIndent = TextIndent(restLine = charWidth * prefixLen))) {
        when (entry.kind) {
            LogKind.COMMAND -> {
                withStyle(SpanStyle(color = Term.prompt)) { append("$ ") }
                withStyle(SpanStyle(color = Term.fg, fontWeight = FontWeight.Bold)) { append(entry.text) }
            }
            LogKind.ERROR -> withStyle(SpanStyle(color = Term.error)) { append(entry.text) }
            LogKind.INFO -> {
                val header = entry.ctx.values.containsKey(LogContext.RUN_HEADER)
                val color = if (header) scriptColor(entry.source) else Term.info
                // Lines after the first are details (e.g. a package name under an app event): dimmed.
                val first = entry.text.substringBefore('\n')
                appendTagged(first, color, if (header) FontWeight.Bold else null)
                if (first.length < entry.text.length) withStyle(SpanStyle(color = Term.dim)) { append(detailLines(entry.text.substring(first.length + 1), prefixLen)) }
            }
            LogKind.SCRIPT -> {
                if (unread) withStyle(SpanStyle(color = Term.prompt, fontWeight = FontWeight.Bold)) { append("• ") }
                withStyle(SpanStyle(color = if (entry.ctx.url != null) Term.link else Term.script)) { append(entry.text) }
                // The source goes at the end, muted, like other indicators (`… machine. · wikipedia`),
                // so wrapped lines use the full width. A no-break space keeps the dot with the name.
                entry.source?.let { src ->
                    withStyle(SpanStyle(color = Term.dim)) {
                        // A notification's icon reads as the app's name to screen readers, and stays with the last word.
                        if (icon) {
                            append("\u00A0")
                            appendInlineContent(NOTIF_ICON, src)
                        } else {
                            append(" ·\u00A0$src")
                        }
                    }
                }
            }
            LogKind.OUTPUT -> {
                val first = entry.text.substringBefore('\n')
                appendTagged(first, if (entry.ctx.url != null) Term.link else Term.fg)
                if (first.length < entry.text.length) withStyle(SpanStyle(color = Term.dim)) { append(detailLines(entry.text.substring(first.length + 1), prefixLen)) }
            }
        }
    }
}

/** Long-press actions as a compact bottom sheet: the line on top, destructive actions last in red. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(menu: ActionMenu, onDismiss: () -> Unit) {
    val state = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Term.surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            // Sized with the terminal's text either way, like the icons.
            val textStyle = if (!menu.systemFont) Term.text else MaterialTheme.typography.bodyLarge.copy(
                fontSize = (Term.fontSize + 2).sp,
                lineHeight = ((Term.fontSize + 2) * 1.4f).sp,
            )
            Text(
                menu.title,
                style = textStyle.copy(color = Term.dim),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            var dividerShown = false
            // The header's icon size; the column is as wide as its battery, so labels line up.
            val iconSize = (Term.fontSize + 3).dp
            val iconColumn = menu.actions.any { it.icon != null }
            menu.actions.forEach { action ->
                if (action.destructive && !dividerShown) {
                    HorizontalDivider(color = Term.dim.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                    dividerShown = true
                }
                val color = if (action.destructive) Term.error else Term.fg
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            onDismiss()
                            action.run()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (iconColumn) {
                        Box(Modifier.width(iconSize * 1.6f), contentAlignment = Alignment.Center) {
                            when (val icon = action.icon) {
                                is ActionIcon.Drawable -> Image(
                                    painter = painterResource(icon.id),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(
                                        when {
                                            icon.alert -> Term.error
                                            icon.profile != null -> Term.profileColor(icon.profile)
                                            else -> Term.dim
                                        },
                                    ),
                                    modifier = Modifier.size(iconSize),
                                )
                                is ActionIcon.Battery -> BatteryGlyph(
                                    icon.pct,
                                    icon.charging,
                                    when {
                                        icon.charging -> Term.prompt
                                        icon.pct <= 15 -> Term.error
                                        else -> Term.dim
                                    },
                                    iconSize,
                                    cutout = Term.surface,
                                )
                                null -> Unit
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(action.label, style = textStyle.copy(color = color))
                }
            }
        }
    }
}

/** Detail lines under a line's first one, indented to line up with its text after the prefix. */
private fun detailLines(rest: String, indent: Int): String {
    val pad = " ".repeat(indent)
    return rest.lines().joinToString("") { "\n$pad$it" }
}
