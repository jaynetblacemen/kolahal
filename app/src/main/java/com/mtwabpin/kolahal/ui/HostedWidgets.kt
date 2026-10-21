package com.mtwabpin.kolahal.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.Profile
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.data.Keys
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import com.mtwabpin.kolahal.widgets.KolahalWidgetView
import com.mtwabpin.kolahal.widgets.PlacedWidget
import com.mtwabpin.kolahal.widgets.WidgetHost
import com.mtwabpin.kolahal.widgets.WidgetLocation
import com.mtwabpin.kolahal.widgets.WidgetOrder


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
 * Widgets placed on home (`widgets.home`), directly under the header. Collapses to nothing with
 * the header ([visible] = false) so the log keeps its room; views stay alive while collapsed.
 * Long-press opens the Widgets page to edit them.
 */
@Composable
fun HomeWidgets(vm: TerminalViewModel, visible: Boolean) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val all by widgets.widgets.collectAsStateWithLifecycle()
    val version by vm.config.version.collectAsStateWithLifecycle()
    val enabled = remember(version) { vm.config[Keys.WIDGETS_HOME] }
    val cap = remember(version) { vm.config[Keys.WIDGETS_HEIGHT].dp }
    val home = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.HOME) }
    if (!enabled || home.isEmpty()) return
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxWidth()
            // Folds and unfolds with the header animation instead of snapping.
            .animateContentSize()
            .then(if (visible) Modifier.heightIn(max = cap) else Modifier.height(0.dp))
            .clipToBounds()
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            home.forEach { w ->
                key(w.key) {
                    HostedWidget(
                        w = w,
                        height = minOf(w.heightDp.dp, cap),
                        pausedKind = profiles.pausedKind(w.profileSerial),
                        onLongPress = { vm.openScreen(Screen.WIDGETS) },
                    ) { why ->
                        Text(
                            "[$why] ${w.packageName}",
                            style = Term.mono.copy(color = Term.dim),
                            modifier = Modifier.fillMaxWidth().alpha(0.7f).padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}


/**
 * The widgets page beside the terminal (`widgets.page`): every widget placed on the "Widgets page",
 * full width and scrolling, in the terminal's colours. Add / edit jump to the GUI pages.
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
            Text("Widgets", style = Term.mono.copy(color = Term.prompt, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(
                "+ Add",
                style = Term.mono.copy(color = Term.prompt),
                modifier = Modifier
                    .clickable { widgets.addLocation = WidgetLocation.PANEL; vm.openScreen(Screen.WIDGET_PICKER) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(horizontal = 12.dp),
            )
            Text(
                "Edit",
                style = Term.mono.copy(color = Term.dim),
                modifier = Modifier
                    .clickable { vm.openScreen(Screen.WIDGETS) }
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .padding(start = 12.dp),
            )
        }
        if (panel.isEmpty()) {
            Text(
                "No widgets here yet · tap + Add, or long-press a widget on home and move it to this page",
                style = Term.mono.copy(color = Term.dim),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            panel.forEach { w ->
                key(w.key) {
                    HostedWidget(
                        w = w,
                        height = w.heightDp.dp,
                        pausedKind = profiles.pausedKind(w.profileSerial),
                        onLongPress = { vm.openScreen(Screen.WIDGETS) },
                    ) { why ->
                        Text(
                            "[$why] ${w.packageName}",
                            style = Term.mono.copy(color = Term.dim),
                            modifier = Modifier.fillMaxWidth().alpha(0.7f).padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
