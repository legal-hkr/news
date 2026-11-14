@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.Source
import sh.hnet.news.ui.components.FeedIconBox
import sh.hnet.news.ui.components.FormPage
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsNote
import sh.hnet.news.ui.components.settingsRowColors
import sh.hnet.news.ui.feeds.foldersOrdered
import sh.hnet.news.ui.feeds.ordered
import sh.hnet.news.ui.theme.NewsTheme

/**
 * Setting a widget up (SPEC-widgets §7.1): its source – All, a folder or a feed – chosen on a page
 * that opens when the widget is placed and again from the launcher's edit button. Close or Back
 * leaves without adding (or without changing). With no feeds yet there is nothing to choose: the
 * widget is added with All at once.
 */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as NewsApplication
        fun done(source: Source) {
            WidgetSources.set(this, id, source)
            Widgets.update(this)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
        val editing = WidgetSources.has(this, id)
        val name = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.loadLabel(packageManager).orEmpty()
        lifecycleScope.launch {
            val feeds = app.feeds.feeds.first()
            if (feeds.isEmpty()) {
                done(Source.All)
                return@launch
            }
            // A source that is no longer there shows as All, as the widget does
            val folders = app.feeds.folders.first()
            val current = WidgetSources.get(this@WidgetSetupActivity, id).takeIf { it.exists(feeds.mapTo(HashSet()) { feed -> feed.id }, folders.mapTo(HashSet()) { folder -> folder.id }) } ?: Source.All
            setContent {
                NewsTheme { SetupPage(name, editing, current, onClose = ::finish, onDone = ::done) }
            }
        }
    }
}

@Composable
private fun SetupPage(name: String, editing: Boolean, current: Source, onClose: () -> Unit, onDone: (Source) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as NewsApplication
    val feeds by app.feeds.feeds.collectAsStateWithLifecycle(emptyList())
    val folders by app.feeds.folders.collectAsStateWithLifecycle(emptyList())
    val ownOrder by app.preferences.ownOrder.collectAsStateWithLifecycle(false)
    var chosen by rememberSaveable { mutableStateOf(current.encode()) }
    FormPage(
        title = name,
        confirmLabel = stringResource(if (editing) R.string.button_save else R.string.button_add_widget),
        onConfirm = { onDone(Source.decode(chosen)) },
        onBack = onClose,
        navigationIcon = R.drawable.ic_close,
        navigationLabel = stringResource(R.string.button_close),
        contentSpacing = 8.dp,
    ) {
        // A flat list, folders first, in the order of Settings › Feeds
        Column(Modifier.selectableGroup()) {
            SettingsGroup(stringResource(R.string.title_source)) {
                row { shapes ->
                    SourceRow(shapes, stringResource(R.string.label_source_all), chosen == Source.All.encode(), { chosen = Source.All.encode() }) {
                        Icon(painterResource(R.drawable.ic_rss_feed), contentDescription = null)
                    }
                }
                folders.foldersOrdered(ownOrder).forEach { folder ->
                    val key = Source.Folder(folder.id).encode()
                    row { shapes -> SourceRow(shapes, folder.name, chosen == key, { chosen = key }) { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) } }
                }
                feeds.ordered(ownOrder).forEach { feed ->
                    val key = Source.Feed(feed.id).encode()
                    row { shapes -> SourceRow(shapes, feed.title, chosen == key, { chosen = key }) { FeedIconBox(feed, 24.dp) } }
                }
            }
        }
        SettingsNote(stringResource(R.string.msg_widget_unread_only))
    }
}

/** A radio row with what it stands for at the end, as the rows of the welcome page. */
@Composable
private fun SourceRow(shapes: ListItemShapes, title: String, selected: Boolean, onSelect: () -> Unit, icon: @Composable () -> Unit) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = icon,
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
