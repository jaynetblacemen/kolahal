package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.apps.AppEntry
import com.mtwabpin.kolahal.data.LogContext
import com.mtwabpin.kolahal.data.LogEntry
import com.mtwabpin.kolahal.data.LogKind
import com.mtwabpin.kolahal.data.shownText
import com.mtwabpin.kolahal.terminal.LogRow
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import java.text.SimpleDateFormat

/**
 * A log search (`/words` at the prompt) in place of the log: the lines that match, newest at the
 * bottom as in the log, with the words marked and each line's time. Tapping one shows it in the log;
 * long-press and swipes work as they do there.
 */
@Composable
fun LogSearchResults(
    search: TerminalViewModel.LogSearch,
    timeFormat: SimpleDateFormat,
    gutter: Boolean,
    indents: LineIndents,
    menuEntryId: Long?,
    apps: (LogEntry) -> List<AppEntry>,
    onOpen: (LogEntry) -> Unit,
    onLongPress: (LogEntry) -> Unit,
    onAppTap: (AppEntry) -> Unit,
    onAppLongPress: (AppEntry) -> Unit,
    onSwipeCopy: (LogEntry) -> Unit,
    onSwipeDelete: (LogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reversed = remember(search.rows) { search.rows.asReversed() }
    val state = rememberLazyListState()
    // Other words start again at the newest match, beside the prompt.
    LaunchedEffect(search.words) { state.scrollToItem(0) }
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = modifier.testTag("log-search"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(reversed, key = { it.key }, contentType = { if (it is LogRow.Entry) it.entry.kind else it.javaClass }) { row ->
            when (row) {
                is LogRow.Day -> DayDivider(row)
                is LogRow.Unread -> Unit
                is LogRow.Entry -> Column {
                    LogLine(
                        row = row,
                        timeMode = TimeMode.ALL,
                        timeFormat = timeFormat,
                        dim = false,
                        topGap = 0.dp,
                        gutter = gutter,
                        indents = indents,
                        highlighted = menuEntryId == row.entry.id,
                        apps = apps,
                        onTap = onOpen,
                        onLongPress = onLongPress,
                        onAppTap = onAppTap,
                        onAppLongPress = onAppLongPress,
                        onSwipeCopy = onSwipeCopy,
                        onSwipeDelete = onSwipeDelete,
                        marks = search.words,
                    )
                    FoundIn(
                        row.entry,
                        search.words,
                        Modifier
                            .fillMaxWidth()
                            .then(if (!gutter) Modifier else Modifier.drawBehind {
                                drawRect(gutterColor(row.gutter), topLeft = Offset.Zero, size = Size(2.dp.toPx(), size.height))
                            })
                            .padding(start = if (gutter) 10.dp else 0.dp, bottom = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * Where words the line doesn't show were found: its link, or where it came from. A line found by
 * its link alone would otherwise give no sign of why.
 */
@Composable
private fun FoundIn(entry: LogEntry, words: List<String>, modifier: Modifier) {
    val text = remember(entry, words) {
        val shown = listOfNotNull(entry.shownText, entry.source.takeIf { entry.kind == LogKind.SCRIPT }, entry.ctx.values[LogContext.DESC])
        val unseen = words.filter { w -> shown.none { it.contains(w, ignoreCase = true) } }
        if (unseen.isEmpty()) null
        else listOfNotNull(entry.ctx.url?.let { it to it }, entry.source?.let { it to "from $it" }, entry.ctx.script?.let { it to "from $it" })
            .filter { (place, _) -> unseen.any { place.contains(it, ignoreCase = true) } }
            .map { it.second }.distinct()
            .joinToString(" · ").ifEmpty { null }
    } ?: return
    Text(
        markMatches(AnnotatedString(text), words),
        style = Term.text.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Under the log while searching: how many lines match, or how to search when nothing is typed yet. */
@Composable
fun LogSearchSummary(search: TerminalViewModel.LogSearch, modifier: Modifier = Modifier) {
    val shown = remember(search.rows) { search.rows.count { it is LogRow.Entry } }
    val text = when {
        search.words.isEmpty() -> "Search the log: type words after / to find lines, commands, sources and links"
        search.total == 0 -> "No line has “${search.words.joinToString(" ")}” · Esc or back to close"
        else -> "${search.total} of ${search.searched} lines" +
            (if (shown < search.total) " · the newest $shown here" else "") +
            " · tap one to see it in the log"
    }
    Text(
        text,
        style = Term.text.copy(color = Term.dim, fontSize = (Term.fontSize - 1).sp),
        modifier = modifier.fillMaxWidth().testTag("log-search-summary").padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
