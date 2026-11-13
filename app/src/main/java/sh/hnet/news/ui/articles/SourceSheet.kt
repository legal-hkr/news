@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.news.ui.articles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.news.R
import sh.hnet.news.Source
import sh.hnet.news.data.Feed
import sh.hnet.news.data.Folder
import sh.hnet.news.ui.components.TooltipIconButton
import sh.hnet.news.ui.feeds.byName
import sh.hnet.news.ui.feeds.foldersByName

/**
 * The source sheet (SPEC §4.6): All, each folder with its feeds shown or hidden in place, then the
 * feeds without a folder; one choice. Counts are unread counts whatever the toggles say; a count of
 * zero is not drawn. Folders open collapsed, except the one holding the current source.
 */
@Composable
fun SourceSheet(current: Source, feeds: List<Feed>, folders: List<Folder>, unread: Map<Long, Int>, onDismiss: () -> Unit, onPick: (Source) -> Unit) {
    val sortedFolders = remember(folders) { folders.foldersByName() }
    val sortedFeeds = remember(feeds) { feeds.byName() }
    var expanded by remember { mutableStateOf(setOfNotNull((current as? Source.Feed)?.let { source -> feeds.firstOrNull { it.id == source.id }?.folderId })) }

    class Entry(val source: Source, val name: String, val count: Int, val feed: Feed? = null, val folder: Folder? = null)
    val entries = buildList {
        add(Entry(Source.All, stringResource(R.string.label_source_all), unread.values.sum()))
        sortedFolders.forEach { folder ->
            val inside = sortedFeeds.filter { it.folderId == folder.id }
            add(Entry(Source.Folder(folder.id), folder.name, inside.sumOf { unread[it.id] ?: 0 }, folder = folder))
            if (folder.id in expanded) inside.forEach { add(Entry(Source.Feed(it.id), it.title, unread[it.id] ?: 0, feed = it)) }
        }
        sortedFeeds.filter { feed -> feed.folderId == null || folders.none { it.id == feed.folderId } }.forEach { add(Entry(Source.Feed(it.id), it.title, unread[it.id] ?: 0, feed = it)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.title_source), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp).semantics { heading() })
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                entries.forEachIndexed { index, entry ->
                    val chosen = entry.source == current
                    val top = if (index == 0) 20.dp else 6.dp
                    val bottom = if (index == entries.lastIndex) 20.dp else 6.dp
                    Surface(
                        onClick = { onPick(entry.source) },
                        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                        color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().semantics { selected = chosen; role = Role.RadioButton },
                    ) {
                        Row(
                            // A folder's feeds stand in from the folder
                            Modifier.heightIn(min = 56.dp).padding(start = if (entry.feed?.folderId != null && entry.feed.folderId in expanded) 32.dp else 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (chosen) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp))
                            entry.feed?.let { SmallFeedIcon(it.id, it.hasIcon) }
                            Text(
                                entry.name,
                                style = if (entry.feed == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (entry.count > 0) Text(countText(entry.count), style = MaterialTheme.typography.labelLarge)
                            val folder = entry.folder
                            if (folder != null) {
                                val open = folder.id in expanded
                                TooltipIconButton(
                                    if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                                    stringResource(if (open) R.string.content_description_hide_feeds else R.string.content_description_show_feeds, folder.name),
                                    { expanded = if (open) expanded - folder.id else expanded + folder.id },
                                )
                            } else {
                                // Keeps the counts in one column with the folders' rows
                                Box(Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
