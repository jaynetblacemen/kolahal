package com.mtwabpin.kolahal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtwabpin.kolahal.R
import com.mtwabpin.kolahal.apps.AppRepository
import com.mtwabpin.kolahal.apps.ProfileKind
import com.mtwabpin.kolahal.terminal.Screen
import com.mtwabpin.kolahal.terminal.TerminalViewModel
import com.mtwabpin.kolahal.widgets.PlacedWidget
import com.mtwabpin.kolahal.widgets.ProviderEntry
import com.mtwabpin.kolahal.widgets.WidgetHost
import com.mtwabpin.kolahal.widgets.WidgetLayout
import com.mtwabpin.kolahal.widgets.WidgetLocation
import com.mtwabpin.kolahal.widgets.WidgetOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widgets page (Material 3 Expressive): every placed widget, live, in the order of the widgets
 * page. Long-press a widget or tap Edit for its toolbar: move, resize in 48dp steps, remove.
 * Entries restored from a backup show an "Add again" row.
 */
@Composable
fun WidgetsScreen(vm: TerminalViewModel, onReAdd: (ProviderEntry, PlacedWidget) -> Unit) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val all by widgets.widgets.collectAsStateWithLifecycle()
    val providersVersion by widgets.providersVersion.collectAsStateWithLifecycle()
    val profiles by remember { AppRepository.get(context).profiles }.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<PlacedWidget?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { widgets.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler { if (editing || selected != null) { editing = false; selected = null } else vm.closeScreen() }

    val panel = remember(all) { WidgetOrder.inLocation(all, WidgetLocation.PANEL) }
    val pending = remember(all) { WidgetOrder.sorted(all.filter { it.pending }) }
    val labels = remember(all, providersVersion) {
        all.associate { w -> w.key to (w.id?.let { widgets.info(it) }?.loadLabel(context.packageManager) ?: w.provider.substringAfterLast('.')) }
    }

    GuiTheme {
        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text("Widgets") },
                    subtitle = {
                        Text(if (panel.isEmpty()) "On the widgets page beside the terminal" else "${panel.size} on the widgets page")
                    },
                    navigationIcon = { BackButton(vm::closeScreen) },
                    actions = {
                        if (panel.isNotEmpty()) {
                            IconToggleButton(checked = editing, onCheckedChange = { editing = it; selected = null }) {
                                Icon(painterResource(if (editing) R.drawable.ic_check else R.drawable.ic_edit), if (editing) "Done" else "Edit widgets")
                            }
                        }
                    },
                    scrollBehavior = scroll,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { vm.openScreen(Screen.WIDGET_PICKER) },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = { Text("Add widget") },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp)
                    .padding(horizontal = 16.dp),
            ) {
                if (panel.isEmpty() && pending.isEmpty()) {
                    Text(
                        "No widgets yet. They live on the widgets page beside the terminal: swipe sideways on home to reach it.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                    ActionGroup(
                        listOf(
                            Action(R.drawable.ic_widgets, "Add a widget", "Clock, weather, calendar, music… from any app") {
                                vm.openScreen(Screen.WIDGET_PICKER)
                            },
                        )
                    )
                }
                panel.forEachIndexed { i, w ->
                    key(w.key) {
                        WidgetCard(
                            w = w,
                            label = labels[w.key] ?: w.provider,
                            index = i,
                            count = panel.size,
                            showTools = editing || selected == w.key,
                            pausedKind = profiles.pausedKind(w.profileSerial),
                            minHeight = remember(w.id, providersVersion) { w.id?.let { widgets.info(it) }?.let { widgets.minHeightDp(it) } ?: WidgetLayout.MIN_DP },
                            onLongPress = { selected = if (selected == w.key) null else w.key },
                            onMove = { widgets.move(w.key, it) },
                            onResize = { widgets.resize(w.key, it) },
                            onRemove = { confirmRemove = w },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (pending.isNotEmpty()) {
                    GroupHeader("Restored from backup")
                    Text(
                        "Widgets can't be copied between installs — add each one again with one tap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        pending.forEachIndexed { i, w ->
                            PendingRow(w, i, pending.size, providersVersion, onReAdd = onReAdd, onDismiss = { widgets.remove(w.key) })
                        }
                    }
                }
                if (panel.isNotEmpty()) {
                    Text(
                        "Long-press a widget for move, resize and remove. Terminal: widgets ls · widget add",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                }
            }
        }

        confirmRemove?.let { w ->
            AlertDialog(
                onDismissRequest = { confirmRemove = null },
                icon = { Icon(painterResource(R.drawable.ic_delete), null) },
                title = { Text("Remove ${labels[w.key] ?: "widget"}?") },
                text = { Text("The widget is deleted; add it again from the picker any time.") },
                confirmButton = {
                    TextButton(
                        onClick = { confirmRemove = null; selected = null; widgets.remove(w.key) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun WidgetCard(
    w: PlacedWidget,
    label: String,
    index: Int,
    count: Int,
    showTools: Boolean,
    pausedKind: ProfileKind?,
    minHeight: Int,
    onLongPress: () -> Unit,
    onMove: (Int) -> Unit,
    onResize: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            if (showTools) {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        "$label · ${w.heightDp}dp",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(painterResource(R.drawable.ic_arrow_up), "Move up") }
                        IconButton(onClick = { onMove(1) }, enabled = index < count - 1) { Icon(painterResource(R.drawable.ic_arrow_down), "Move down") }
                        IconButton(onClick = { onResize(-WidgetLayout.STEP_DP) }, enabled = w.heightDp > minHeight) { Icon(painterResource(R.drawable.ic_remove), "Shorter") }
                        IconButton(onClick = { onResize(WidgetLayout.STEP_DP) }, enabled = w.heightDp < WidgetLayout.MAX_DP) { Icon(painterResource(R.drawable.ic_add), "Taller") }
                        IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_delete), "Remove", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Box(Modifier.clip(if (showTools) RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp) else MaterialTheme.shapes.extraLarge)) {
                HostedWidget(w, w.heightDp.dp, pausedKind, onLongPress) { why ->
                    Box(
                        Modifier.fillMaxWidth().height(w.heightDp.dp).alpha(0.6f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            TextButton(onClick = onLongPress) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingRow(
    w: PlacedWidget,
    index: Int,
    count: Int,
    providersVersion: Int,
    onReAdd: (ProviderEntry, PlacedWidget) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val widgets = remember { WidgetHost.get(context) }
    val entry by produceState<ProviderEntry?>(null, w.provider, w.profileSerial, providersVersion) {
        value = withContext(Dispatchers.IO) { widgets.findProvider(w.provider, w.profileSerial) }
    }
    val found = entry
    val label = found?.info?.loadLabel(context.packageManager) ?: w.provider.substringAfterLast('.')
    SegmentedListItem(
        colors = segColors(),
        shapes = segShapes(index, count),
        leadingContent = { Icon(painterResource(R.drawable.ic_widgets), null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(w.packageName, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(if (found == null) "app not installed" else "${w.location} · ${w.heightDp}dp") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { found?.let { onReAdd(it, w) } }, enabled = found != null) { Text("Add again") }
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_delete), "Forget") }
            }
        },
    ) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}
