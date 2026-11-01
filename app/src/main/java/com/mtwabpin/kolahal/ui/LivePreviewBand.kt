package com.mtwabpin.kolahal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtwabpin.kolahal.terminal.LivePreview

/**
 * Says home shows what the prompt's line would change, not what's in use: right above the prompt,
 * where the eyes are, in the theme's solid colour. Its size doesn't follow a font size being tried.
 */
@Composable
internal fun LivePreviewBand(live: LivePreview?) {
    // What it said stays as it slides away.
    val shown = remember { arrayOfNulls<LivePreview>(1) }
    if (live != null) shown[0] = live
    AnimatedVisibility(
        visible = live != null,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Term.prompt)
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("live-preview")
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Preview: ${shown[0]?.label}  ·  Enter keeps it",
                color = Term.bg,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
