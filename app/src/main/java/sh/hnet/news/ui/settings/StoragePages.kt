@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.settings

import sh.hnet.news.ui.components.SingleChoiceDialog
import sh.hnet.news.refresh.Refetch
import androidx.compose.runtime.LaunchedEffect
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.hnet.news.KEEP_DAYS
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.Usage
import sh.hnet.news.ui.components.ConfirmDestructiveDialog
import sh.hnet.news.ui.components.DestructiveButton
import sh.hnet.news.ui.components.PageActionButton
import sh.hnet.news.ui.components.RowIcon
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsNote
import sh.hnet.news.ui.components.SettingsPage
import sh.hnet.news.ui.components.SettingsRadioRow
import sh.hnet.news.ui.components.settingsRowColors

/** "A week", "2 weeks", "A month", "3 months", "A year", "No limit". */
@Composable
fun keepName(days: Int): String = stringResource(
    when (days) {
        7 -> R.string.label_keep_week
        14 -> R.string.label_keep_2_weeks
        30 -> R.string.label_keep_month
        90 -> R.string.label_keep_3_months
        365 -> R.string.label_keep_year
        else -> R.string.label_keep_no_limit
    },
)

/**
 * Settings › Storage (SPEC §13): for how long articles are kept, what is in use, and the two ways
 * to free space at once. Both confirm with what goes – there is no Undo for megabytes – and both
 * leave saved articles whole.
 */
@Composable
fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val keepDays by app.preferences.keepDays.collectAsStateWithLifecycle(null)
    // Measured again after each change
    var measured by remember { mutableIntStateOf(0) }
    val usage by produceState<Usage?>(null, measured) { value = app.storage.usage() }
    var clearingImages by remember { mutableStateOf(false) }
    var clearingArticles by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val progress by app.refresher.progress.collectAsStateWithLifecycle()
    // What a run fetched shows in the numbers above, once it ends
    LaunchedEffect(Unit) { app.refresher.progress.map { it == null }.distinctUntilChanged().drop(1).filter { it }.collect { measured++ } }
    fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    SettingsPage(title = stringResource(R.string.title_storage), onBack = onBack) {
        val days = keepDays ?: return@SettingsPage
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.selectableGroup()) {
                SettingsGroup(stringResource(R.string.label_keep_articles)) {
                    KEEP_DAYS.forEach { option ->
                        row { shapes ->
                            SettingsRadioRow(shapes, keepName(option), selected = days == option, onSelect = {
                                scope.launch {
                                    app.preferences.setKeepDays(option)
                                    // A shorter period applies at once
                                    app.storage.removeOld()
                                    measured++
                                }
                            })
                        }
                    }
                }
            }
            SettingsNote(stringResource(R.string.msg_saved_not_removed))
        }
        SettingsGroup(stringResource(R.string.title_in_use)) {
            row { shapes -> UsageRow(shapes, R.drawable.ic_article, stringResource(R.string.title_articles), usage?.let { size(it.textBytes) }) }
            row { shapes -> UsageRow(shapes, R.drawable.ic_image, stringResource(R.string.label_images), usage?.let { size(it.imageBytes) }) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The one action here that removes nothing: the same button, without the error color. While such a run is
            // under way it says how far it is and stops it; it is not to be started while a refresh runs
            val running = progress
            if (running?.again == true) {
                PageActionButton(stringResource(R.string.button_stop_refetch, running.done, running.total), R.drawable.ic_close, { app.refresher.current()?.cancel() })
            } else {
                PageActionButton(stringResource(R.string.label_refetch), R.drawable.ic_download, { choosing = true }, enabled = running == null)
            }
            DestructiveButton(stringResource(R.string.button_clear_images), R.drawable.ic_delete, { clearingImages = true }, enabled = (usage?.clearableImageBytes ?: 0) > 0)
            DestructiveButton(stringResource(R.string.button_clear_articles), R.drawable.ic_delete, { clearingArticles = true }, enabled = (usage?.clearableArticles ?: 0) > 0)
        }
    }

    if (choosing) {
        // How many articles each choice means, so the size of what is asked for is plain before it starts
        val counts by produceState<Map<Refetch, Int>>(emptyMap()) {
            value = mapOf(
                Refetch.ALL to app.articles.refetchableCount(unread = false, saved = false),
                Refetch.UNREAD to app.articles.refetchableCount(unread = true, saved = false),
                Refetch.SAVED to app.articles.refetchableCount(unread = false, saved = true),
            )
        }
        val names = mapOf(Refetch.ALL to R.string.label_refetch_all, Refetch.UNREAD to R.string.label_refetch_unread, Refetch.SAVED to R.string.label_refetch_saved)
        SingleChoiceDialog(
            title = AnnotatedString(stringResource(R.string.title_refetch)),
            options = Refetch.entries,
            initial = Refetch.ALL,
            label = { which -> counts[which]?.let { stringResource(R.string.label_name_count, stringResource(names.getValue(which)), it) } ?: stringResource(names.getValue(which)) },
            confirmLabel = stringResource(R.string.button_fetch),
            onConfirm = { which ->
                choosing = false
                app.refreshRequests.requestRefetch(which)
            },
            onDismiss = { choosing = false },
            description = stringResource(R.string.msg_refetch),
        )
    }

    val current = usage
    if (clearingImages && current != null) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_delete,
            title = stringResource(R.string.title_clear_images),
            text = AnnotatedString(stringResource(R.string.msg_clear_images, size(current.clearableImageBytes))),
            confirmLabel = stringResource(R.string.button_clear),
            onConfirm = {
                clearingImages = false
                scope.launch {
                    app.storage.clearImages()
                    measured++
                }
            },
            onDismiss = { clearingImages = false },
        )
    }
    if (clearingArticles && current != null) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_delete,
            title = stringResource(R.string.title_clear_articles),
            text = AnnotatedString(stringResource(R.string.msg_clear_articles, current.clearableArticles, size(current.clearableImageBytes + current.clearableTextBytes))),
            confirmLabel = stringResource(R.string.button_clear),
            onConfirm = {
                clearingArticles = false
                scope.launch {
                    app.storage.clearArticles()
                    measured++
                }
            },
            onDismiss = { clearingArticles = false },
        )
    }
}

/** A row that only shows a value; nothing happens on a tap, so it has no chevron and no ripple. */
@Composable
private fun UsageRow(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, value: String?) {
    SegmentedListItem(
        onClick = {},
        enabled = false,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RowIcon(icon) },
        trailingContent = { Text(value.orEmpty()) },
    ) {
        Text(title)
    }
}
