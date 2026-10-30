package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.control.NowPlaying
import kotlinx.coroutines.delay

/** Header media line with a live playback progress indicator. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingLine(
    np: NowPlaying,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    val small = Term.text.copy(fontSize = (Term.fontSize - 1).sp)
    
    var position by remember(np.pkg, np.title, np.artist) {
        mutableLongStateOf(np.positionNow())
    }

    // Update position locally without requiring MediaRepository events.
    LaunchedEffect(np.pkg, np.title, np.artist, np.playing) {
        while (np.playing) {
            position = np.positionNow()
            delay(250)
        }
        position = np.positionNow()
    }

    val progress = if (np.duration > 0 && position >= 0) {
        (position.toFloat() / np.duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("header-media")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val iconSize = (Term.fontSize + 2).dp

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = onToggle,
                        onLongClick = onNext
                    )
                    .heightIn(min = 48.dp)
            ) {
                if (np.playing) {
                    TintedIcon(
                        R.drawable.ic_music_note,
                        Term.prompt,
                        iconSize,
                        "Playing"
                    )
                } else {
                    TintedIcon(
                        R.drawable.ic_pause,
                        Term.dim,
                        iconSize,
                        "Paused"
                    )
                }

                Spacer(Modifier.width(6.dp))

                Text(
                    np.line,
                    style = small.copy(color = Term.dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clickable(
                        onClickLabel = "next track",
                        onClick = onNext
                    )
                    .heightIn(min = 48.dp)
                    .widthIn(min = 48.dp)
            ) {
                TintedIcon(
                    R.drawable.ic_skip_next,
                    Term.dim,
                    (Term.fontSize + 4).dp,
                    "Next"
                )
            }
        }

        // Playback progress track.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(50))
                .background(Term.dim.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(Term.prompt)
            )
        }
    }
}
