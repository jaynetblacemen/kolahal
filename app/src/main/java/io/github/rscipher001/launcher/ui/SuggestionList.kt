package io.github.rscipher001.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rscipher001.launcher.apps.AppEntry
import io.github.rscipher001.launcher.apps.ProfileKind
import io.github.rscipher001.launcher.data.Keys
import io.github.rscipher001.launcher.terminal.CommandProcessor
import io.github.rscipher001.launcher.terminal.Suggestion

/** Short help shown next to a completion: a command's description, or a config key's. */
internal fun completionHelp(label: String, replacement: String): String? {
    // Top-level command completions replace the whole input with "name ".
    if (replacement.trim() == label) CommandProcessor.COMMANDS.firstOrNull { it.name == label }?.let { return it.help }
    return Keys.ALL.firstOrNull { it.key == label }?.help
}

/**
 * `suggestions.style list`: matches stacked above the prompt, best one at the bottom nearest the
 * thumb (it is what Enter / TAB act on, so it is highlighted). At most ~40% of the screen tall;
 * more scroll. Tap picks; long-press an app for its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SuggestionList(items: List<Suggestion>, onPick: (Suggestion) -> Unit, onAppLongPress: (AppEntry) -> Unit) {
    if (items.isEmpty()) return
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    // A lazy list keeps its first visible item in place when the data changes; the best match
    // (what Enter acts on) must always be in view, so jump back to it when it changes.
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(items.first().key) { state.scrollToItem(0) }
    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .testTag("suggestions"),
    ) {
        itemsIndexed(items, key = { _, s -> s.key }) { index, s ->
            val first = index == 0
            val color = when (s) {
                is Suggestion.App -> Term.profileColor(s.app.profile.kind)
                is Suggestion.Completion -> Term.prompt
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minOf(44, Term.chipHeight).dp)
                    .alpha(if (s is Suggestion.App && s.app.profile.quiet) 0.45f else 1f)
                    .background(if (first) color.copy(alpha = 0.16f) else Color.Transparent)
                    .combinedClickable(
                        onClick = { onPick(s) },
                        onLongClick = (s as? Suggestion.App)?.let { app -> { onAppLongPress(app.app) } },
                    ),
            ) {
                // A bar marks the row Enter / TAB act on.
                Box(Modifier.width(3.dp).heightIn(min = minOf(44, Term.chipHeight).dp).background(if (first) color else Color.Transparent))
                Spacer(Modifier.width(9.dp))
                when (s) {
                    is Suggestion.App -> {
                        val kind = s.app.profile.kind
                        if (kind != ProfileKind.PERSONAL) {
                            Text(
                                "[${kind.tag}] ",
                                style = Term.mono.copy(color = color, fontWeight = FontWeight.Bold, fontSize = (Term.fontSize - 2).sp),
                            )
                        }
                        Text(
                            s.label,
                            style = Term.mono.copy(color = Term.fg, fontWeight = if (first) FontWeight.Bold else FontWeight.Normal),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is Suggestion.Completion -> {
                        Text("$ ", style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold))
                        Text(
                            s.label,
                            style = Term.mono.copy(color = Term.fg, fontWeight = if (first) FontWeight.Bold else FontWeight.Normal),
                            maxLines = 1,
                        )
                        val help = completionHelp(s.label, s.replacement)
                        Text(
                            if (help != null) "  $help" else "",
                            style = Term.mono.copy(color = Term.dim, fontSize = (Term.fontSize - 2).sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (first) Text("⏎", style = Term.mono.copy(color = Term.dim), modifier = Modifier.padding(horizontal = 12.dp))
                else Spacer(Modifier.width(12.dp))
            }
        }
    }
}
