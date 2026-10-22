package com.mtwabpin.kolahal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.Profile
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import com.mtwabpin.kolahal.widgets.KolahalWidgetView
import com.mtwabpin.kolahal.widgets.PlacedWidget
import com.mtwabpin.kolahal.widgets.WidgetHost
import com.mtwabpin.kolahal.widgets.WidgetLocation
import com.mtwabpin.kolahal.widgets.WidgetOrder
import com.mtwabpin.kolahal.widgets.WidgetLayout
import androidx.compose.ui.layout.Layout


/** Profile state for a widget: paused/locked profiles get a placeholder instead of the widget. */
internal fun List<Profile>.pausedKind(serial: Long): ProfileKind? = firstOrNull { it.serial == serial }?.takeIf { it.quiet }?.kind

/**
 * Renders placed widget [w] at [height] via the shared AppWidgetHost. A long-press anywhere on it
 * calls [onLongPress]. When its profile is paused, [placeholder] is shown instead.
 */
@Composable
internal fun HostedWidget(
    w: PlacedWidget,
    height: Dp,
    pausedKind: ProfileKind?,
    onLongPress: (() -> Unit)?,
    placeholder: @Composable (String) -> Unit,
) {
    val id = w.id ?: return
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val providersVersion by widgets.providersVersion.collectAsStateWithLifecycle()
    if (pausedKind != null) {
        placeholder(if (pausedKind == ProfileKind.PRIVATE) "private space locked" else "${pausedKind.label} profile paused")
        return
    }
    val info = remember(id, providersVersion) { widgets.info(id) }
    if (info == null) {
        placeholder("widget unavailable")
        return
    }
    val longPress by rememberUpdatedState(onLongPress)
    key(id) {
        AndroidView(
            factory = { ctx ->
                widgets.host.createView(ctx, id, info).apply {
                    (this as? KolahalWidgetView)?.onLongPress = { longPress?.invoke() }
                }
            },
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}


/**
 * Widgets in page order, [spans] twelfths of the width each ([WidgetLayout.COLUMNS]): side by side
 * while they fit, a new row when the next one doesn't, [gap] between them across and down. A row is
 * as tall as its tallest widget, the others at its top. One child per widget.
 */
@Composable
internal fun WidgetFlow(spans: List<Int>, gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val page = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val rows = WidgetLayout.rows(List(measurables.size) { spans.getOrElse(it) { WidgetLayout.COLUMNS } })
        val placeables = measurables.mapIndexed { i, m ->
            val span = spans.getOrElse(i) { WidgetLayout.COLUMNS }.coerceIn(WidgetLayout.MIN_SPAN, WidgetLayout.COLUMNS)
            val width = ((page + gapPx) * span / WidgetLayout.COLUMNS - gapPx).coerceAtLeast(0)
            m.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
        }
        val rowHeights = rows.map { row -> row.maxOf { placeables[it].height } }
        val height = rowHeights.sum() + gapPx * (rows.size - 1).coerceAtLeast(0)
        layout(page, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            rows.forEachIndexed { r, row ->
                var x = 0
                row.forEach { i ->
                    placeables[i].place(x, y)
                    x += placeables[i].width + gapPx
                }
                y += rowHeights[r] + gapPx
            }
        }
    }
}

/**
 * The widgets page beside the terminal (`widgets.page`): every widget placed on the "Widgets page",
 * at its width and height ([WidgetFlow]), scrolling, in the terminal's colours. Add / edit jump to
 * the GUI pages.
 */
@Composable
fun WidgetPanel(vm: TerminalViewModel) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val all by widgets.widgets.collectAsStateWithLifecycle()
    val panel = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.PANEL) }
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Term.bg)
            .cutoutSafePadding()
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("Widgets", style = Term.text.copy(color = Term.prompt, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(
                "+ Add",
                style = Term.text.copy(color = Term.prompt),
                modifier = Modifier
                    .clickable { vm.openScreen(Screen.WIDGET_PICKER) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(horizontal = 12.dp),
            )
            Text(
                "Edit",
                style = Term.text.copy(color = Term.dim),
                modifier = Modifier
                    .clickable { vm.openScreen(Screen.WIDGETS) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(start = 12.dp),
            )
        }
        if (panel.isEmpty()) {
            Text(
                "No widgets here yet · tap + Add",
                style = Term.text.copy(color = Term.dim),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
            return@Column
        }
        WidgetFlow(
            panel.map { it.span },
            WidgetLayout.GAP_DP.dp,
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = WidgetLayout.GAP_DP.dp, vertical = 4.dp),
        ) {
            panel.forEach { w ->
                key(w.key) {
                    // One child per widget for the flow, whatever HostedWidget shows.
                    Box {
                        HostedWidget(
                            w = w,
                            height = w.heightDp.dp,
                            pausedKind = profiles.pausedKind(w.profileSerial),
                            onLongPress = { vm.openScreen(Screen.WIDGETS) },
                        ) { why ->
                            Text(
                                "[$why] ${w.packageName}",
                                style = Term.text.copy(color = Term.dim),
                                modifier = Modifier.fillMaxWidth().alpha(0.7f).padding(vertical = 6.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
