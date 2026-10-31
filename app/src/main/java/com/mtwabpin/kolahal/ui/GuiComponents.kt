package com.mtwabpin.kolahal.ui

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtwabpin.kolahal.R

/** Single-select connected button group (Material 3 Expressive). */
@Composable
fun ConnectedChoices(choices: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth(),
    ) {
        choices.forEachIndexed { i, choice ->
            ToggleButton(
                checked = choice == selected,
                onCheckedChange = { onPick(choice) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(choice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

data class Action(val icon: Int, val title: String, val subtitle: String, val chevron: Boolean = false, val onClick: () -> Unit)

/** A segmented group of clickable rows. */
@Composable
fun ActionGroup(actions: List<Action>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        actions.forEachIndexed { i, a ->
            SegmentedListItem(
                colors = segColors(),
                onClick = a.onClick,
                shapes = segShapes(i, actions.size),
                leadingContent = { Icon(painterResource(a.icon), null, tint = MaterialTheme.colorScheme.primary) },
                supportingContent = { Text(a.subtitle) },
                trailingContent = if (a.chevron) {
                    { Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
            ) { Text(a.title) }
        }
    }
}

@Composable
fun GroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
}

/** "5 minutes ago"; [short] gives "5 min. ago" for one-line rows. */
internal fun relative(time: Long, short: Boolean = false): String =
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now" else relativeSpan(time, short)

private fun relativeSpan(time: Long, short: Boolean = false): String =
    DateUtils.getRelativeTimeSpanString(
        time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        // The three-argument form's flags, plus the short form when asked.
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or
            (if (short) DateUtils.FORMAT_ABBREV_RELATIVE else 0),
    ).toString()

/** Full-width row (≥ 56dp) whose whole surface toggles the switch; [color] is what it sits on. */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onChange(!checked) },
        shape = MaterialTheme.shapes.large,
        color = color,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Interval presets as plain words, with a pill on the chosen one alone: all of them show, each a tap away. */
@Composable
internal fun IntervalGroup(selected: Int, onPick: (Int) -> Unit, choices: List<Pair<Int, String>>) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        choices.forEach { (minutes, label) ->
            val chosen = minutes == selected
            val pill by animateColorAsState(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent)
            // The whole cell takes the tap; the pill only marks the chosen one.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .selectable(selected = chosen, role = Role.RadioButton) { onPick(minutes) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 1.dp).clip(CircleShape).background(pill),
                ) {
                    // Smaller rather than cut off, with a large font on a narrow phone.
                    Text(
                        label,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
