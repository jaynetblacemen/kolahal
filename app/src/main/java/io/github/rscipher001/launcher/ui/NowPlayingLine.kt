package io.github.rscipher001.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rscipher001.launcher.control.NowPlaying

/** Header line "♪ Title — Artist  ⏭": tap toggles play/pause, long-press or ⏭ skips to the next track. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingLine(np: NowPlaying, onToggle: () -> Unit, onNext: () -> Unit) {
    val small = Term.mono.copy(fontSize = (Term.fontSize - 1).sp)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("header-media")) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (np.playing) Term.prompt else Term.dim)) { append(if (np.playing) "♪ " else "‖ ") }
                withStyle(SpanStyle(color = Term.dim)) { append(np.line) }
            },
            style = small,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = onToggle, onLongClick = onNext)
                .heightIn(min = 48.dp)
                .wrapContentHeight(),
        )
        Text(
            "⏭",
            style = small.copy(color = Term.dim),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(onClick = onNext)
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .wrapContentHeight()
                .padding(horizontal = 6.dp),
        )
    }
}
