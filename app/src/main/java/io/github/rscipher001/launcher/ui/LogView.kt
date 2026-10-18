package io.github.rscipher001.launcher.ui

import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import io.github.rscipher001.launcher.apps.AppEntry
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.data.LogContext
import io.github.rscipher001.launcher.data.LogEntry
import io.github.rscipher001.launcher.data.LogKind
import io.github.rscipher001.launcher.terminal.Gutter
import io.github.rscipher001.launcher.terminal.LogAction
import io.github.rscipher001.launcher.terminal.LogRow
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** How a line shows its time, from `log.time`. */
enum class TimeMode { OFF, COMMANDS, ALL }

/** What the long-press sheet shows: the line (for context) and its actions. */
data class ActionMenu(val title: String, val actions: List<LogAction>, val entryId: Long?)

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

@Composable
fun DayDivider(row: LogRow.Day) {
    val label = remember(row.day) {
        if (row.day == LocalDate.now().minusDays(1)) "yesterday" else row.day.format(dayFormat)
    }
    Text(
        "── $label ──",
        style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 1).sp),
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

/** "── 3 new since you left ──" above the first unread script line. */
@Composable
fun UnreadDivider(row: LogRow.Unread) {
    Text(
        "── ${row.count} new since you left ──",
        style = Term.mono.copy(color = Term.prompt, fontSize = (Term.fontSize - 1).sp, fontWeight = FontWeight.Bold),
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
            if (swipe.value > 0) "⧉ copy" else "delete ✕",
            style = Term.mono.copy(color = if (swipe.value > 0) Term.prompt else Term.error),
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
            .padding(start = 10.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Box(Modifier.weight(1f)) {
            when {
                isGrid -> AppGrid(apps(entry), onAppTap, onAppLongPress)
                desc != null -> TwoColumns(entry, desc)
                else -> {
                    val text = remember(entry, Term.palette, charWidth, row.unread) { renderLine(entry, charWidth, row.unread) }
                    Text(text, style = Term.mono)
                }
            }
        }
        if (showTime) {
            Spacer(Modifier.width(8.dp))
            Text(
                remember(entry.ts, timeFormat) { timeFormat.format(Date(entry.ts)) },
                style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
                modifier = Modifier.padding(top = 2.dp),
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
            style = Term.mono.copy(color = Term.fg, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(0.46f).padding(end = 8.dp),
        )
        Text(desc, style = Term.mono.copy(color = Term.dim), modifier = Modifier.weight(0.54f))
    }
}

/** `apps` output: one line of chips instead of one line per app. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(apps: List<AppEntry>, onTap: (AppEntry) -> Unit, onLongPress: (AppEntry) -> Unit) {
    val haptics = LocalHapticFeedback.current
    if (apps.isEmpty()) {
        Text("(apps no longer installed)", style = Term.mono.copy(color = Term.dim))
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
                        style = Term.mono.copy(color = color, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(app.label, style = Term.mono)
            }
        }
    }
}

private val profileTag = Regex("^\\[([PWSC])] ")

/**
 * Styles one log line. Wrapped continuation lines are indented past the prefix (`$ `, `[hn] `,
 * `[W] `) so each item reads as one block; [charWidth] is the monospace advance.
 */
private fun renderLine(entry: LogEntry, charWidth: TextUnit, unread: Boolean) = buildAnnotatedString {
    val profile = if (entry.kind == LogKind.OUTPUT) profileTag.find(entry.text) else null
    val prefixLen = when (entry.kind) {
        LogKind.COMMAND -> 2
        LogKind.SCRIPT -> (entry.source?.let { it.length + 3 } ?: 0) + (if (unread) 2 else 0)
        LogKind.OUTPUT -> profile?.value?.length ?: 2
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
                withStyle(SpanStyle(color = color, fontWeight = if (header) FontWeight.Bold else null)) { append(first) }
                if (first.length < entry.text.length) withStyle(SpanStyle(color = Term.dim)) { append(detailLines(entry.text.substring(first.length + 1), prefixLen)) }
            }
            LogKind.SCRIPT -> {
                if (unread) withStyle(SpanStyle(color = Term.prompt, fontWeight = FontWeight.Bold)) { append("• ") }
                entry.source?.let { withStyle(SpanStyle(color = scriptColor(it))) { append("[$it] ") } }
                withStyle(SpanStyle(color = if (entry.ctx.url != null) Term.link else Term.script)) { append(entry.text) }
            }
            LogKind.OUTPUT -> {
                if (profile != null) {
                    val kind = ProfileKind.entries.first { it.tag == profile.groupValues[1] }
                    withStyle(SpanStyle(color = Term.profileColor(kind), fontWeight = FontWeight.Bold)) { append(profile.value) }
                    append(entry.text.substring(profile.value.length))
                } else {
                    val first = entry.text.substringBefore('\n')
                    withStyle(SpanStyle(color = if (entry.ctx.url != null) Term.link else Term.fg)) { append(first) }
                    if (first.length < entry.text.length) withStyle(SpanStyle(color = Term.dim)) { append(detailLines(entry.text.substring(first.length + 1), prefixLen)) }
                }
            }
        }
    }
}

/** Long-press actions as a compact bottom sheet: the line on top, destructive actions last in red. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(menu: ActionMenu, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Term.surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
            Text(
                menu.title,
                style = Term.mono.copy(color = Term.dim),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            var dividerShown = false
            menu.actions.forEach { action ->
                if (action.destructive && !dividerShown) {
                    HorizontalDivider(color = Term.dim.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                    dividerShown = true
                }
                Text(
                    action.label,
                    style = Term.mono.copy(color = if (action.destructive) Term.error else Term.fg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            onDismiss()
                            action.run()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** Detail lines under a line's first one, indented to line up with its text after the prefix. */
private fun detailLines(rest: String, indent: Int): String {
    val pad = " ".repeat(indent)
    return rest.lines().joinToString("") { "\n$pad$it" }
}
