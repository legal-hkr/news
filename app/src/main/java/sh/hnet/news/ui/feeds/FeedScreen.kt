@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.feeds

import android.content.ClipData
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.Feed
import sh.hnet.news.feed.Addresses
import sh.hnet.news.feed.Opml
import sh.hnet.news.ui.components.DestructiveButton
import sh.hnet.news.ui.components.NameDialog
import sh.hnet.news.ui.components.RowIcon
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsPage
import sh.hnet.news.ui.components.SettingsRadioRow
import sh.hnet.news.ui.components.SettingsSwitchRow
import sh.hnet.news.ui.components.settingsRowColors

/**
 * A feed's page (SPEC §8): its name, address and folder, its content source, whether its images are
 * stored and whether it refreshes in the background; Unsubscribe at the end, at once with Undo.
 * "On the list", "Keep articles" and "Last refresh" join with the stages that give them meaning.
 */
@Composable
fun FeedScreen(feedId: Long, onBack: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val loaded by remember(feedId) { app.feeds.feed(feedId) }.collectAsStateWithLifecycle(null)
    val folders by app.feeds.folders.collectAsStateWithLifecycle(emptyList())
    // The feed as last seen, so the page stays drawn while it closes after Unsubscribe
    var shown by remember { mutableStateOf<Feed?>(null) }
    loaded?.let { shown = it }
    val feed = shown ?: return
    var renaming by rememberSaveable { mutableStateOf(false) }
    var editingAddress by rememberSaveable { mutableStateOf(false) }
    var pickingFolder by rememberSaveable { mutableStateOf(false) }
    val unsubscribed = stringResource(R.string.msg_unsubscribed)
    val undo = stringResource(R.string.button_undo)
    val folder = folders.firstOrNull { it.id == feed.folderId }

    SettingsPage(title = feed.title, onBack = onBack) {
        SettingsGroup(stringResource(R.string.title_feed)) {
            row { shapes -> ValueRow(shapes, R.drawable.ic_label, stringResource(R.string.label_name), feed.title) { renaming = true } }
            row { shapes -> ValueRow(shapes, R.drawable.ic_link, stringResource(R.string.label_address), feed.url, middle = true) { editingAddress = true } }
            row { shapes -> ValueRow(shapes, R.drawable.ic_folder, stringResource(R.string.label_folder), folder?.name ?: stringResource(R.string.label_no_folder)) { pickingFolder = true } }
        }
        Column(Modifier.selectableGroup()) {
            SettingsGroup(stringResource(R.string.label_content)) {
                row { shapes ->
                    SettingsRadioRow(shapes, stringResource(R.string.label_full_page), selected = feed.fullPage, onSelect = { scope.launch { app.feeds.setFullPage(feed.id, true) } }, supporting = stringResource(R.string.desc_full_page))
                }
                row { shapes ->
                    SettingsRadioRow(shapes, stringResource(R.string.label_feed_content), selected = !feed.fullPage, onSelect = { scope.launch { app.feeds.setFullPage(feed.id, false) } }, supporting = stringResource(R.string.desc_feed_content))
                }
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_image, stringResource(R.string.label_store_images), feed.storeImages, { on -> scope.launch { app.feeds.setStoreImages(feed.id, on) } })
            }
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_sync, stringResource(R.string.label_refresh_in_background), feed.backgroundRefresh, { on -> scope.launch { app.feeds.setBackgroundRefresh(feed.id, on) } })
            }
        }
        // At once, with Undo (SPEC §8): the feed is gone for good when the snackbar goes without it
        DestructiveButton(stringResource(R.string.button_unsubscribe), R.drawable.ic_delete, {
            messageScope.launch {
                app.feeds.unsubscribe(feed.id)
                onBack()
                snackbars.currentSnackbarData?.dismiss()
                if (snackbars.showSnackbar(unsubscribed.format(feed.title), actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                    app.feeds.restore(feed.id)
                } else {
                    app.feeds.purge(feed.id)
                }
            }
        }, Modifier.padding(top = 8.dp))
    }

    if (renaming) {
        NameDialog(title = stringResource(R.string.label_name), fieldLabel = stringResource(R.string.label_name), initial = feed.title, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { app.feeds.rename(feed.id, name) }
        }
    }
    if (editingAddress) AddressDialog(feed, onDismiss = { editingAddress = false })
    if (pickingFolder) {
        FolderPicker(folders, folder?.let { FolderChoice.Existing(it) } ?: FolderChoice.None, onDismiss = { pickingFolder = false }) { choice ->
            pickingFolder = false
            scope.launch {
                when (choice) {
                    FolderChoice.None -> app.feeds.setFolder(feed.id, null)
                    is FolderChoice.Existing -> app.feeds.setFolder(feed.id, choice.folder.id)
                    is FolderChoice.New -> app.feeds.moveToNewFolder(feed.id, choice.name)
                }
            }
        }
    }
}

/** A row showing a value and opening the dialog that changes it; [middle] cuts a long value in its middle (addresses). */
@Composable
fun ValueRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, value: String, middle: Boolean = false, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        supportingContent = { Text(value, maxLines = 1, overflow = if (middle) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis) },
    ) {
        Text(title)
    }
}

/**
 * "Feed address" (SPEC §8): the address in a field, with Copy, Cancel and Save. Saving fetches the
 * new address and takes it only when a feed is there; what went wrong is said in the field.
 */
@Composable
private fun AddressDialog(feed: Feed, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val copied = stringResource(R.string.msg_copied)
    var address by rememberSaveable { mutableStateOf(feed.url) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val alreadyText = stringResource(R.string.msg_already_subscribed_plain)
    val noFeed = stringResource(R.string.error_no_feed_found)
    val resources = androidx.compose.ui.platform.LocalResources.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = {
        val typed = address.trim()
        if (typed == feed.url) {
            onDismiss()
        } else if (!busy && typed.isNotEmpty()) {
            busy = true
            error = null
            scope.launch {
                try {
                    // Exactly this address: over HTTPS first when no scheme was typed
                    var failure: Exception? = null
                    var saved = false
                    for (candidate in Addresses.candidates(typed)) {
                        val found = try {
                            app.finder.load(candidate)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failure = failure ?: e
                            continue
                        }
                        val other = app.feeds.subscribed(found.url)
                        if (other != null && other.id != feed.id) {
                            error = alreadyText.format(other.title)
                        } else {
                            app.feeds.setUrl(feed.id, found.url)
                            saved = true
                        }
                        failure = null
                        break
                    }
                    if (saved) onDismiss() else if (error == null) error = failure?.let { resources.getString(lookupError(it)) } ?: noFeed
                } finally {
                    busy = false
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.title_feed_address)) },
        text = {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; error = null },
                label = { Text(stringResource(R.string.label_address)) },
                singleLine = true,
                readOnly = busy,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, address.trim())))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.button_copy)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.button_cancel)) }
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = !busy && address.isNotBlank()) { Text(stringResource(R.string.button_save)) } },
    )
}
