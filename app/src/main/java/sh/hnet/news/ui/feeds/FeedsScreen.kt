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
import androidx.compose.ui.platform.LocalContext
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
import sh.hnet.news.ui.components.SettingsPage
import sh.hnet.news.ui.components.TooltipIconButton
import sh.hnet.news.ui.components.settingsRowColors

/**
 * Settings › Feeds (SPEC §6): the subscriptions, a group per folder and one for the feeds without,
 * by name; then OPML import and export. Nothing about articles here. A feed opens its page, the
 * FAB adds one.
 */
@Composable
fun FeedsScreen(onBack: () -> Unit, onAddFeed: () -> Unit, onOpenFeed: (Long) -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val feeds by app.feeds.feeds.collectAsStateWithLifecycle(null)
    val folders by app.feeds.folders.collectAsStateWithLifecycle(null)
    val opml = rememberOpmlActions(snackbars, messageScope)
    var renaming by remember { mutableStateOf<Folder?>(null) }
    val folderDeleted = stringResource(R.string.msg_folder_deleted)
    val undo = stringResource(R.string.button_undo)
    val allFeeds = feeds
    val allFolders = folders

    Box(Modifier.fillMaxSize()) {
        SettingsPage(title = stringResource(R.string.title_feeds), onBack = onBack) {
            // Nothing is drawn until both are read: no flash of the empty state
            if (allFeeds == null || allFolders == null) return@SettingsPage
            if (allFeeds.isEmpty() && allFolders.isEmpty()) {
                NoFeeds(onAddFeed, opml.import, Modifier.height(480.dp))
                return@SettingsPage
            }
            allFolders.foldersByName().forEach { folder ->
                val inside = allFeeds.filter { it.folderId == folder.id }.byName()
                SettingsGroup {
                    row { shapes ->
                        FolderRow(
                            shapes, folder,
                            onToggle = { messageScope.launch { app.feeds.setCollapsed(folder.id, !folder.collapsed) } },
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
                    if (!folder.collapsed) inside.forEach { feed -> row { shapes -> FeedRow(shapes, feed) { onOpenFeed(feed.id) } } }
                }
            }
            SettingsGroup {
                allFeeds.filter { feed -> feed.folderId == null || allFolders.none { it.id == feed.folderId } }.byName().forEach { feed ->
                    row { shapes -> FeedRow(shapes, feed) { onOpenFeed(feed.id) } }
                }
            }
            SettingsGroup(stringResource(R.string.title_opml)) {
                row { shapes -> ActionRow(shapes, R.drawable.ic_file_open, stringResource(R.string.button_import_opml), opml.import) }
                row { shapes -> ActionRow(shapes, R.drawable.ic_file_save, stringResource(R.string.button_export_opml), opml.export) }
            }
            Spacer(Modifier.height(FAB_CLEARANCE - 24.dp))
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
private fun FolderRow(shapes: ListItemShapes, folder: Folder, onToggle: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = onToggle,
        shapes = shapes,
        colors = settingsRowColors(),
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
private fun FeedRow(shapes: ListItemShapes, feed: Feed, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
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
