package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import com.mtwabpin.kolahal.notifications.NotifIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
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
import com.mtwabpin.kolahal.data.StyledText
import com.mtwabpin.kolahal.terminal.ActionIcon
import com.mtwabpin.kolahal.terminal.Gutter
import com.mtwabpin.kolahal.terminal.LogAction
import com.mtwabpin.kolahal.terminal.LogRow
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** How a line shows its time, from `log.time`: `groups` on a group's first line and when the minute changes. */
enum class TimeMode { OFF, COMMANDS, GROUPS, ALL }

/**
 * A long-press menu. Its actions are in the system's font, as a menu; its [title] (the line pressed)
 * in the terminal's, as in the log, unless [systemFont] (quick actions, whose title is a heading).
 * [url]: the line's link, shown under the title so you see where Open link goes.
 */
data class ActionMenu(
    val title: String,
    val actions: List<LogAction>,
    val entryId: Long?,
    val systemFont: Boolean = false,
    val url: String? = null,
    /** Where the line came from and when, to the second ([com.mtwabpin.kolahal.terminal.lineDetail]). */
    val detail: String? = null,
)

@Composable
fun DayDivider(row: LogRow.Day) {
    // Made each time, not remembered: "Yesterday" is the day before today, which moves at midnight
    // under a launcher that stays composed for days; and the dividers follow a change of language.
    val label =
        if (row.day == LocalDate.now().minusDays(1)) "Yesterday"
        else row.day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
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
    indents: LineIndents,
    highlighted: Boolean,
    apps: (LogEntry) -> List<AppEntry>,
    onTap: (LogEntry) -> Unit,
    onLongPress: (LogEntry) -> Unit,
    onAppTap: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onSwipeCopy: (LogEntry) -> Unit = {},
    onSwipeDelete: (LogEntry) -> Unit = {},
    /** Words a log search found: marked where the line shows them. */
    marks: List<String> = emptyList(),
) {
    val entry = row.entry
    // Swipe right: copy. Swipe left: delete (the terminal offers Undo). Past a third of the width.
    val swipe = remember { androidx.compose.animation.core.Animatable(0f) }
    val swipeScope = androidx.compose.runtime.rememberCoroutineScope()
    var rowWidth by remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val haptics = LocalHapticFeedback.current
    val gutterColor = gutterColor(row.gutter)
    val showTime = when (timeMode) {
        TimeMode.ALL -> true
        TimeMode.COMMANDS -> entry.kind == LogKind.COMMAND
        TimeMode.GROUPS -> row.showTime
        TimeMode.OFF -> false
    }
    val desc = entry.ctx.values[LogContext.DESC]
    val isGrid = entry.ctx.values[LogContext.APPS] != null
    val image = entry.ctx.image
    // A notification line's saved status-bar icon replaces its app name at the end.
    val context = LocalContext.current
    val iconKey = entry.ctx.values[LogContext.ICON]?.takeIf { row.showSource && entry.kind == LogKind.SCRIPT && NotifIcons.has(context, it) }
    val inline = remember(iconKey) { iconKey?.let(::notifIconContent) ?: emptyMap() }

    // log.line-height sets the padding above and below a line too: up to 3dp each, less as the
    // height nears one line of text, none at it. Below that, the leading above the first line and
    // under the last goes as well, so lines get as short as the text itself allows.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textLine = with(density) { (Term.fontSize * TEXT_LINE).sp.toDp() }
    // In sp: the line height follows the phone's text size, as the text does, so a larger system
    // text size doesn't squeeze the lines (22 is 22dp at the usual size).
    val lineHeight = with(density) { Term.lineHeight.sp.toDp() }
    val padding = ((lineHeight - textLine) / 2).coerceIn(0.dp, LINE_PADDING.dp)
    val dense = lineHeight + 0.5.dp < textLine
    // Lines that do something get a larger touch target than plain output (log.taller-taps).
    val actionable = entry.kind == LogKind.COMMAND || entry.ctx.values.keys.any { it in ACTIONABLE_KEYS }
    val minHeight = when {
        !Term.tallerTaps -> lineHeight
        desc != null -> lineHeight + 14.dp
        actionable -> lineHeight + 10.dp
        else -> lineHeight
    }
    Box(Modifier.padding(top = topGap)) {
    // What the swipe will do, revealed as the line moves: drawn only where the line has moved away
    // from, so the line needs no background of its own and home's page (or wallpaper) shows through.
    if (swipe.value != 0f) {
        Text(
            if (swipe.value > 0) "⧉ Copy" else "Delete ✕",
            style = Term.text.copy(color = if (swipe.value > 0) Term.prompt else Term.error),
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    val s = swipe.value
                    clipRect(left = if (s > 0) 0f else size.width + s, right = if (s > 0) s else size.width) {
                        this@drawWithContent.drawContent()
                    }
                }
                .wrapContentHeight()
                .padding(horizontal = 16.dp),
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
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .testTag("line-${entry.id}")
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
            .padding(start = if (gutter) 10.dp else 0.dp, top = padding, bottom = padding),
    ) {
        Box(Modifier.weight(1f)) {
            when {
                // Parsed once per entry (it decodes JSON and looks up every app), and again when the
                // apps change: a chip uninstalled or hidden from it goes.
                isGrid -> {
                    val installed by com.mtwabpin.kolahal.apps.AppRepository.get(context).apps.collectAsStateWithLifecycle()
                    AppGrid(remember(entry.id, entry.ctx, installed) { apps(entry) }, onAppTap, onAppLongPress)
                }
                desc != null -> TwoColumns(entry, desc, marks, dense)
                else -> {
                    val text = remember(entry, Term.palette, indents, row.unread, iconKey, marks, row.showSource) {
                        markMatches(renderLine(entry, indents, row.unread, iconKey != null, row.showSource), marks)
                    }
                    if (image == null) Text(text, style = entry.style().dense(dense), inlineContent = inline)
                    else Column {
                        if (entry.text.isNotEmpty()) Text(text, style = entry.style().dense(dense), inlineContent = inline)
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

/** The bar beside a line: commands in the prompt colour, timer output in the script colour, errors red. */
fun gutterColor(gutter: Gutter): Color = when (gutter) {
    Gutter.COMMAND -> Term.prompt
    Gutter.SCRIPT -> Term.script
    Gutter.ERROR -> Term.error
}.copy(alpha = 0.45f)

private val ACTIONABLE_KEYS = setOf(
    LogContext.URL, LogContext.PKG, LogContext.COMMAND, LogContext.RUN, LogContext.SCRIPT, LogContext.APPS, LogContext.LINE,
    LogContext.PHONE, LogContext.SHORTCUT, "notif", LogContext.NOTE, LogContext.EVENT, LogContext.REMINDER,
)

/** `help`, `config` and `script ls` rows: name on the left, description wrapping on the right. */
@Composable
private fun TwoColumns(entry: LogEntry, desc: String, marks: List<String> = emptyList(), dense: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            markMatches(AnnotatedString(entry.text), marks),
            style = entry.style().copy(color = Term.fg, fontWeight = FontWeight.Bold).dense(dense),
            modifier = Modifier.weight(0.46f).padding(end = 8.dp),
        )
        Text(markMatches(AnnotatedString(desc), marks), style = Term.text.copy(color = Term.dim).dense(dense), modifier = Modifier.weight(0.54f))
    }
}

/** [text] with every place one of [words] appears (ignoring case) marked, as a log search shows them. */
fun markMatches(text: AnnotatedString, words: List<String>): AnnotatedString {
    if (words.isEmpty()) return text
    val mark = SpanStyle(background = Term.prompt.copy(alpha = 0.35f))
    return AnnotatedString.Builder(text).apply {
        for (w in words) {
            var at = text.text.indexOf(w, ignoreCase = true)
            while (at >= 0) {
                addStyle(mark, at, at + w.length)
                at = text.text.indexOf(w, at + w.length, ignoreCase = true)
            }
        }
    }.toAnnotatedString()
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
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendTagged(text: String, color: Color) {
    val tag = profileTag.find(text)
    if (tag == null) {
        withStyle(SpanStyle(color = color)) { append(text) }
        return
    }
    val kind = ProfileKind.entries.first { it.tag == tag.groupValues[1] }
    withStyle(SpanStyle(color = color)) { append(text.substring(0, tag.range.first)) }
    withStyle(SpanStyle(color = Term.profileColor(kind), fontWeight = FontWeight.Bold)) { append(tag.value) }
    withStyle(SpanStyle(color = color)) { append(text.substring(tag.range.last + 1)) }
}

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

/** A log line's text height, in em ([Term.text]'s line height), and the padding above and below it (dp). */
private const val TEXT_LINE = 1.36f
private const val LINE_PADDING = 3

/** Short lines (log.line-height below the text's own): no leading above the first line or under the last. */
private fun TextStyle.dense(on: Boolean) =
    if (on) copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)) else this

/** The terminal's text style, or always the terminal font for a line that lines columns up ([LogContext.MONO]). */
private fun LogEntry.style() = if (ctx.values.containsKey(LogContext.MONO)) Term.code else Term.text

/** How far wrapped and detail lines are indented: the width of `$ ` in the log's font, and of `• `. */
@androidx.compose.runtime.Immutable
data class LineIndents(val text: TextUnit, val code: TextUnit, val bullet: TextUnit)

/**
 * Styles one log line. Wrapped continuation lines are indented past the prefix (`$ `, `• `) so
 * each item reads as one block; the widths are measured ([LineIndents]), so this holds in any font.
 * [icon]: end a script line with its notification icon instead of `· app`; [source]: name it at all
 * (the first line of a run).
 */
private fun renderLine(entry: LogEntry, indents: LineIndents, unread: Boolean, icon: Boolean = false, source: Boolean = true) = buildAnnotatedString {
    val indent = when (entry.kind) {
        LogKind.SCRIPT -> if (unread) indents.bullet else 0.sp
        else -> if (entry.ctx.values.containsKey(LogContext.MONO)) indents.code else indents.text
    }
    withStyle(ParagraphStyle(textIndent = TextIndent(restLine = indent))) {
        when (entry.kind) {
            LogKind.COMMAND -> {
                withStyle(SpanStyle(color = Term.prompt)) { append("$ ") }
                withStyle(SpanStyle(color = Term.fg, fontWeight = FontWeight.Bold)) { append(entry.text) }
            }
            LogKind.ERROR -> withStyle(SpanStyle(color = Term.error)) { append(StyledText.plain(entry.text)) }
            LogKind.INFO -> {
                // Lines after the first are details (e.g. a package name under an app event): dimmed.
                val first = entry.text.substringBefore('\n')
                appendTagged(first, Term.info)
            }
            LogKind.SCRIPT -> {
                if (unread) withStyle(SpanStyle(color = Term.prompt, fontWeight = FontWeight.Bold)) { append("• ") }
                // A line a script logged is a title: bold, its body muted under it.
                val title = entry.ctx.values.containsKey(LogContext.TITLE)
                // A title is bold already: its bold parts stand out in the text colour.
                appendStyled(
                    entry.text,
                    SpanStyle(color = if (entry.ctx.url != null) Term.link else Term.script, fontWeight = if (title) FontWeight.Bold else null),
                    strong = Term.fg,
                )
                // The source goes at the end, muted, like other indicators (`… machine. · wikipedia`),
                // so wrapped lines use the full width. A no-break space keeps the dot with the name.
                entry.source?.takeIf { source }?.let { src ->
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
                val color = if (entry.ctx.url != null) Term.link else Term.fg
                // A script's result may carry styles ([StyledText]); command output never does.
                if (StyledText.has(first)) appendStyled(first, SpanStyle(color = color)) else appendTagged(first, color)
            }
        }
        Unit
    }
    // Lines after the first (details, dimmed) are paragraphs of their own, indented by layout to line
    // up with the text after the prefix: padding with spaces only lines up in a monospace font.
    if ((entry.kind == LogKind.INFO || entry.kind == LogKind.OUTPUT) && '\n' in entry.text) {
        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent))) {
            appendStyled(entry.text.substringAfter('\n'), SpanStyle(color = Term.dim), strong = Term.fg)
        }
    }
    // A script line's body, under its title and lined up with it.
    val body = entry.ctx.body
    if (entry.kind == LogKind.SCRIPT && body != null) appendBody(body, indent, indents.bullet)
}

/**
 * [text] with its [StyledText] styles over [base]: bold also takes [strong] when given (a muted
 * body or a bold title bolds to the text colour), code shows on a shaded chip. A list item shows its bullet or
 * number in the accent colour.
 */
private fun AnnotatedString.Builder.appendStyled(text: String, base: SpanStyle, strong: Color? = null) {
    if (!StyledText.has(text)) {
        withStyle(base) { append(text) }
        return
    }
    StyledText.lines(text).forEachIndexed { i, line ->
        if (i > 0) append('\n')
        if (line.level > 0) withStyle(base.merge(SpanStyle(color = Term.prompt))) { append("  ".repeat(line.level - 1) + line.prefix + " ") }
        appendSpans(line.spans, base, strong)
    }
}

private fun AnnotatedString.Builder.appendSpans(spans: List<StyledText.Span>, base: SpanStyle, strong: Color?) {
    for (span in spans) {
        if (span.styles.isEmpty() && span.colour == null) withStyle(base) { append(span.text) }
        else withStyle(base.merge(styleOf(span.styles, strong)).merge(SpanStyle(color = colourOf(span.colour)))) { append(span.text) }
    }
}

/** A script's colour by role, from the theme in use (its dark or light version). */
private fun colourOf(colour: StyledText.Colour?): Color = when (colour) {
    null -> Color.Unspecified
    StyledText.Colour.ACCENT -> Term.prompt
    StyledText.Colour.LINK -> Term.link
    StyledText.Colour.INFO -> Term.info
    StyledText.Colour.MUTED -> Term.dim
    StyledText.Colour.TEXT -> Term.fg
    StyledText.Colour.GOOD -> Term.palette.good
    StyledText.Colour.WARN -> Term.palette.warn
    StyledText.Colour.BAD -> Term.error
}

private fun styleOf(styles: Set<StyledText.Style>, strong: Color?): SpanStyle {
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { StyledText.Style.UNDERLINE in styles },
        TextDecoration.LineThrough.takeIf { StyledText.Style.STRIKE in styles },
    )
    val bold = StyledText.Style.BOLD in styles
    val code = StyledText.Style.CODE in styles
    return SpanStyle(
        color = if (bold && strong != null) strong else Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (StyledText.Style.ITALIC in styles) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        // The terminal font is monospace already; with font.sans on, code still is.
        fontFamily = if (code) Term.code.fontFamily else null,
        background = if (code) Term.surface else Color.Unspecified,
    )
}

/**
 * A script line's body, muted, lined up under its title at [indent]. Each list item is a paragraph
 * of its own whose wrapped lines start under the item's text; [bullet] is the width of "• ".
 */
private fun AnnotatedString.Builder.appendBody(body: String, indent: TextUnit, bullet: TextUnit) {
    val base = SpanStyle(color = Term.dim)
    if (!StyledText.has(body)) {
        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent))) { withStyle(base) { append(body) } }
        return
    }
    val lines = StyledText.lines(body)
    val char = bullet.value / 2 // the log's text lines up in characters: "• " is two
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.level == 0) {
            // Plain lines in a row share a paragraph.
            val run = lines.drop(i).takeWhile { it.level == 0 }
            withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = indent, restLine = indent))) {
                run.forEachIndexed { k, l ->
                    if (k > 0) append('\n')
                    appendSpans(l.spans, base, Term.fg)
                }
            }
            i += run.size
            continue
        }
        val start = indent.value + char * 2 * (line.level - 1)
        val prefix = "${line.prefix} "
        withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = start.sp, restLine = (start + char * prefix.length).sp))) {
            withStyle(SpanStyle(color = Term.prompt)) { append(prefix) }
            appendSpans(line.spans, base, Term.fg)
        }
        i++
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
        // Scrolls when there are more actions than fit, so the last one can be reached.
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(vertical = 8.dp)) {
            // Sized with the terminal's text, like the icons.
            val menuStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (Term.fontSize + 2).sp,
                lineHeight = ((Term.fontSize + 2) * 1.4f).sp,
            )
            Text(
                menu.title,
                style = (if (menu.systemFont) menuStyle else Term.text).copy(color = Term.dim),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Where the line came from and when: what the log may leave off the line.
            menu.detail?.let { detail ->
                Text(
                    detail,
                    style = Term.text.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("menu-detail"),
                )
            }
            menu.url?.let { url ->
                Text(
                    url,
                    style = Term.text.copy(color = Term.link, fontSize = (Term.fontSize - 1).sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
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
                                )
                                null -> Unit
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(action.label, style = menuStyle.copy(color = color))
                }
            }
        }
    }
}

