@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.feeds

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import sh.hnet.news.ui.components.LazySettingsPage
import sh.hnet.news.ui.components.SETTINGS_GROUP_GAP
import sh.hnet.news.ui.components.SETTINGS_ROW_GAP
import sh.hnet.news.ui.components.SettingsRadioRow
import sh.hnet.news.ui.components.dragAfterLongPress
import sh.hnet.news.ui.components.groupShapes
import sh.hnet.news.ui.components.moveActions
import sh.hnet.news.ui.components.rememberReorderState
import sh.hnet.news.ui.components.reorderableItem
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.Feed
import sh.hnet.news.data.Folder
import sh.hnet.news.ui.components.EmptyState
import sh.hnet.news.ui.components.FAB_CLEARANCE
import sh.hnet.news.ui.components.FeedIconBox
import sh.hnet.news.ui.components.LocalSnackbarLift
import sh.hnet.news.ui.components.MenuItem
import sh.hnet.news.ui.components.NameDialog
import sh.hnet.news.ui.components.RowIcon
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.TooltipIconButton
import sh.hnet.news.ui.components.settingsRowColors

/**
 * Settings › Feeds (SPEC §6): the subscriptions, a group per folder and one for the feeds without,
 * by name or in the user's own order (§6.6); then the order's choice and OPML import and export.
 * Nothing about articles here. A feed opens its page, the FAB adds one. In "My own" order press and
 * hold drags a row: folders among folders (their feeds step aside meanwhile), feeds within their group.
 */
@Composable
fun FeedsScreen(onBack: () -> Unit, onAddFeed: () -> Unit, onOpenFeed: (Long) -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val feeds by app.feeds.feeds.collectAsStateWithLifecycle(null)
    val folders by app.feeds.folders.collectAsStateWithLifecycle(null)
    val ownOrder by app.preferences.ownOrder.collectAsStateWithLifecycle(null)
    val opml = rememberOpmlActions(snackbars, messageScope)
    var renaming by remember { mutableStateOf<Folder?>(null) }
    val folderDeleted = stringResource(R.string.msg_folder_deleted)
    val undo = stringResource(R.string.button_undo)
    val allFeeds = feeds
    val allFolders = folders
    val own = ownOrder
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // The order while a row is dragged and until the stored one arrives; the stored order leads otherwise
    var movedFolders by remember(allFolders, own) { mutableStateOf<List<Long>?>(null) }
    var movedFeeds by remember(allFeeds, own) { mutableStateOf<List<Long>?>(null) }
    val shownFolders = remember(allFolders, own, movedFolders) {
        val stored = allFolders.orEmpty().foldersOrdered(own == true)
        movedFolders?.let { ids -> stored.sortedBy { ids.indexOf(it.id) } } ?: stored
    }
    val shownFeeds = remember(allFeeds, own, movedFeeds) {
        val stored = allFeeds.orEmpty().ordered(own == true)
        movedFeeds?.let { ids -> stored.sortedBy { ids.indexOf(it.id) } } ?: stored
    }
    // A feed whose folder is gone counts as having none
    fun groupOf(feed: Feed): Long? = feed.folderId?.takeIf { id -> shownFolders.any { it.id == id } }
    fun moveFolder(from: Long, to: Long): List<Long> {
        val ids = shownFolders.map { it.id }.toMutableList()
        val a = ids.indexOf(from)
        val b = ids.indexOf(to)
        if (a >= 0 && b >= 0) ids.add(b, ids.removeAt(a))
        return ids
    }
    fun moveFeed(from: Long, to: Long): List<Long> {
        val ids = shownFeeds.map { it.id }.toMutableList()
        val a = ids.indexOf(from)
        val b = ids.indexOf(to)
        if (a >= 0 && b >= 0) ids.add(b, ids.removeAt(a))
        return ids
    }
    var dragging by remember { mutableStateOf<String?>(null) }
    val reorder = rememberReorderState(
        listState = list,
        canMove = { key ->
            val from = dragging
            when {
                from == null || key !is String -> false
                from.startsWith(FOLDER_KEY) -> key.startsWith(FOLDER_KEY)
                !key.startsWith(FEED_KEY) -> false
                else -> {
                    val a = shownFeeds.firstOrNull { it.id == idOf(from) }
                    val b = shownFeeds.firstOrNull { it.id == idOf(key) }
                    a != null && b != null && groupOf(a) == groupOf(b)
                }
            }
        },
        onStart = { dragging = it as? String },
        onMove = { from, to ->
            if (from is String && to is String) {
                if (from.startsWith(FOLDER_KEY)) movedFolders = moveFolder(idOf(from), idOf(to)) else movedFeeds = moveFeed(idOf(from), idOf(to))
            }
        },
        onDrop = {
            dragging = null
            val folderIds = movedFolders
            val feedIds = movedFeeds
            messageScope.launch {
                folderIds?.let { app.feeds.orderFolders(it) }
                feedIds?.let { app.feeds.orderFeeds(it) }
            }
        },
    )
    // While a folder is dragged the folders stand alone, so they pass each other row by row
    val foldersOnly = reorder.draggedKey?.let { it is String && it.startsWith(FOLDER_KEY) } == true

    Box(Modifier.fillMaxSize()) {
        LazySettingsPage(title = stringResource(R.string.title_feeds), onBack = onBack, state = list) {
            // Nothing is drawn until everything is read: no flash of the empty state
            if (allFeeds == null || allFolders == null || own == null) return@LazySettingsPage
            if (allFeeds.isEmpty() && allFolders.isEmpty()) {
                item(key = "empty") { NoFeeds(onAddFeed, opml.import, Modifier.height(480.dp)) }
                return@LazySettingsPage
            }

            /** One row of a group of [count], at [index]: the gaps, and in the user's own order the drag and its TalkBack actions. */
            fun row(key: String, index: Int, count: Int, position: Int, siblings: Int, move: (Int) -> Unit, content: @Composable (ListItemShapes, Boolean) -> Unit) {
                item(key = key, contentType = key.substringBefore(':')) {
                    val dragged = reorder.draggedKey == key
                    val moves = if (own) moveActions(position, siblings, move = move) else emptyList()
                    // The gap goes outside the lifted layer, so the shadow falls only under the row
                    Box(
                        Modifier
                            .then(if (dragged) Modifier else Modifier.animateItem())
                            .padding(bottom = if (index < count - 1) SETTINGS_ROW_GAP else SETTINGS_GROUP_GAP)
                            .reorderableItem(reorder, key, liftedShape = LIFTED_SHAPE, liftedElevation = 1.dp)
                            .then(if (own) Modifier.dragAfterLongPress(reorder, key) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } else Modifier)
                            .semantics { customActions = moves },
                    ) {
                        val lifted = LIFTED_SHAPE
                        val shapes = if (dragged) {
                            ListItemDefaults.shapes(shape = lifted, selectedShape = lifted, pressedShape = lifted, focusedShape = lifted, hoveredShape = lifted, draggedShape = lifted)
                        } else groupShapes(index, count)
                        content(shapes, dragged)
                    }
                }
            }
            fun feedRow(feed: Feed, index: Int, count: Int, group: List<Feed>) {
                val position = group.indexOf(feed)
                row("$FEED_KEY${feed.id}", index, count, position, group.size, move = { to ->
                    val ids = moveFeed(feed.id, group[to].id)
                    movedFeeds = ids
                    messageScope.launch { app.feeds.orderFeeds(ids) }
                }) { shapes, dragged ->
                    // The press that ends a drag is not a tap
                    FeedRow(shapes, feed, dragged) { if (reorder.draggedKey == null) onOpenFeed(feed.id) }
                }
            }

            shownFolders.forEachIndexed { position, folder ->
                val inside = if (folder.collapsed || foldersOnly) emptyList() else shownFeeds.filter { it.folderId == folder.id }
                row("$FOLDER_KEY${folder.id}", 0, inside.size + 1, position, shownFolders.size, move = { to ->
                    val ids = moveFolder(folder.id, shownFolders[to].id)
                    movedFolders = ids
                    messageScope.launch { app.feeds.orderFolders(ids) }
                }) { shapes, dragged ->
                    FolderRow(
                        shapes, folder, dragged,
                        onToggle = { if (reorder.draggedKey == null) messageScope.launch { app.feeds.setCollapsed(folder.id, !folder.collapsed) } },
                        onRename = { renaming = folder },
                        onDelete = {
                            // At once, with Undo (SPEC §6.4): the feeds move out and stay subscribed
                            messageScope.launch {
                                val deleted = app.feeds.deleteFolder(folder)
                                snackbars.currentSnackbarData?.dismiss()
                                if (snackbars.showSnackbar(folderDeleted.format(folder.name), actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                    app.feeds.restoreFolder(deleted)
                                }
                            }
                        },
                    )
                }
                inside.forEachIndexed { index, feed -> feedRow(feed, index + 1, inside.size + 1, inside) }
            }
            val outside = shownFeeds.filter { groupOf(it) == null }
            outside.forEachIndexed { index, feed -> feedRow(feed, index, outside.size, outside) }

            item(key = "order", contentType = "group") {
                Column(Modifier.padding(bottom = SETTINGS_GROUP_GAP).selectableGroup()) {
                    SettingsGroup(stringResource(R.string.title_feed_order)) {
                        row { shapes -> SettingsRadioRow(shapes, stringResource(R.string.label_order_by_name), selected = !own, onSelect = { messageScope.launch { app.preferences.setOwnOrder(false) } }) }
                        row { shapes ->
                            SettingsRadioRow(shapes, stringResource(R.string.label_order_own), selected = own, onSelect = { messageScope.launch { app.preferences.setOwnOrder(true) } }, supporting = stringResource(R.string.desc_order_own))
                        }
                    }
                }
            }
            item(key = "opml", contentType = "group") {
                SettingsGroup(stringResource(R.string.title_opml)) {
                    row { shapes -> ActionRow(shapes, R.drawable.ic_file_open, stringResource(R.string.button_import_opml), opml.import) }
                    row { shapes -> ActionRow(shapes, R.drawable.ic_file_save, stringResource(R.string.button_export_opml), opml.export) }
                }
            }
            item(key = "clearance", contentType = "space") { Spacer(Modifier.height(FAB_CLEARANCE - 24.dp)) }
        }
        val lift by animateDpAsState(LocalSnackbarLift.current, MaterialTheme.motionScheme.defaultSpatialSpec())
        FloatingActionButton(onClick = onAddFeed, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp + lift)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.button_add_feed))
        }
    }

    renaming?.let { folder ->
        val inUse = stringResource(R.string.error_folder_name_in_use)
        NameDialog(
            title = stringResource(R.string.menu_rename),
            fieldLabel = stringResource(R.string.label_folder_name),
            initial = folder.name,
            onDismiss = { renaming = null },
            problem = { name -> inUse.takeIf { allFolders.orEmpty().any { it.id != folder.id && it.name.equals(name, ignoreCase = true) } } },
        ) { name ->
            renaming = null
            messageScope.launch { app.feeds.renameFolder(folder.id, name) }
        }
    }
}

private const val FOLDER_KEY = "folder:"
private const val FEED_KEY = "feed:"

private fun idOf(key: String): Long = key.substringAfter(':').toLong()

/** A lifted row, as in Music and Clock: radius 16, elevation 1, one surface step up. */
private val LIFTED_SHAPE = RoundedCornerShape(16.dp)

@Composable
private fun rowColors(dragged: Boolean) =
    if (dragged) ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) else settingsRowColors()

/** "No feeds yet." with the two ways to start (SPEC §4.9, §6.7). */
@Composable
fun NoFeeds(onAddFeed: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    // The app's mark is drawn on the launcher icon's canvas, hence the larger size
    EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_empty_no_feeds), modifier, iconSize = 108.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddFeed) { Text(stringResource(R.string.button_add_feed)) }
            FilledTonalButton(onClick = onImport) { Text(stringResource(R.string.button_import_opml)) }
        }
    }
}

@Composable
private fun FolderRow(shapes: ListItemShapes, folder: Folder, dragged: Boolean, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = onToggle,
        shapes = shapes,
        colors = rowColors(dragged),
        leadingContent = {
            Box(Modifier.padding(horizontal = 8.dp)) {
                Icon(
                    painterResource(if (folder.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less),
                    contentDescription = stringResource(if (folder.collapsed) R.string.content_description_expand_folder else R.string.content_description_collapse_folder),
                )
            }
        },
        trailingContent = {
            Box {
                TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.content_description_more), { menu = true })
                DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                        MenuItem(stringResource(R.string.menu_rename), R.drawable.ic_edit, 0, 1) { menu = false; onRename() }
                    }
                    Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                        MenuItem(stringResource(R.string.menu_delete_folder), R.drawable.ic_delete, 0, 1, error = true) { menu = false; onDelete() }
                    }
                }
            }
        },
    ) {
        Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeedRow(shapes: ListItemShapes, feed: Feed, dragged: Boolean, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = rowColors(dragged),
        leadingContent = { FeedIconBox(feed) },
    ) {
        Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row that does something at once (opens a file picker), so without a chevron. */
@Composable
private fun ActionRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick, shapes = shapes, colors = settingsRowColors(), leadingContent = { RowIcon(icon) }) {
        Text(title)
    }
}
