@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.settings

import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import sh.hnet.news.ui.components.settingsRowColors
import sh.hnet.news.ui.components.RowIcon
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.SegmentedListItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.Intent
import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import sh.hnet.news.Look
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.REFRESH_INTERVALS
import sh.hnet.news.RefreshSettings
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.refresh.RefreshJobService
import sh.hnet.news.ui.articles.timeOfDay
import sh.hnet.news.ui.components.LookControls
import sh.hnet.news.ui.components.SettingsGroupHeader
import sh.hnet.news.ui.reader.ReaderStyle
import sh.hnet.news.ui.reader.TextControls
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsNote
import sh.hnet.news.ui.components.SettingsPage
import sh.hnet.news.ui.components.SettingsRadioRow
import sh.hnet.news.ui.components.SettingsSwitchRow

/** "Off", "Every hour", "Every 3 hours", "Once a day". */
@Composable
fun intervalName(hours: Int): String = when (hours) {
    0 -> stringResource(R.string.label_refresh_off)
    1 -> stringResource(R.string.label_every_hour)
    24 -> stringResource(R.string.label_once_a_day)
    else -> pluralStringResource(R.plurals.label_every_n_hours, hours, hours)
}

/** Settings › Refresh (SPEC §14.5). Every change is saved at once and the background job follows it. */
@Composable
fun RefreshSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val settings by app.preferences.refresh.collectAsStateWithLifecycle(null)
    val current = settings
    fun change(next: RefreshSettings) {
        scope.launch {
            app.preferences.setRefresh(next)
            RefreshJobService.schedule(context.applicationContext, next)
        }
    }
    SettingsPage(title = stringResource(R.string.title_refresh), onBack = onBack) {
        if (current == null) return@SettingsPage
        Column(Modifier.selectableGroup()) {
            SettingsGroup(stringResource(R.string.label_in_the_background)) {
                REFRESH_INTERVALS.forEach { hours ->
                    row { shapes -> SettingsRadioRow(shapes, intervalName(hours), selected = current.intervalHours == hours, onSelect = { change(current.copy(intervalHours = hours)) }) }
                }
            }
        }
        SettingsGroup {
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_wifi, stringResource(R.string.label_unmetered_only), current.unmeteredOnly, { change(current.copy(unmeteredOnly = it)) }) }
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_bolt, stringResource(R.string.label_charging_only), current.chargingOnly, { change(current.copy(chargingOnly = it)) }) }
        }
        SettingsNote(stringResource(R.string.msg_refresh_note))
        SettingsGroup {
            row { shapes -> SettingsSwitchRow(shapes, R.drawable.ic_refresh, stringResource(R.string.label_refresh_on_open), current.onOpen, { change(current.copy(onOpen = it)) }) }
        }
        IconBadgeGroup()
    }
}

/**
 * "App icon" (SPEC-widgets §8.1, §8.2): the badge's switch, whose own text is the explanation.
 * Turning it on asks for the notification permission, then and nowhere else. While it is on and the
 * notification cannot be shown, a row under it says so and opens the system's settings – drawn as
 * the checks of Clock's reliability page rather than with the SPEC's text button; it goes by itself
 * once notifications are allowed.
 */
@Composable
private fun IconBadgeGroup() {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val badge by app.preferences.iconBadge.collectAsStateWithLifecycle(null)
    var canShow by remember { mutableStateOf(app.badge.canShow()) }
    // Asked again on coming back from the system's settings
    LifecycleResumeEffect(Unit) {
        canShow = app.badge.canShow()
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canShow = app.badge.canShow() }
    val on = badge ?: return
    SettingsGroup(stringResource(R.string.header_app_icon)) {
        row { shapes ->
            SettingsSwitchRow(
                shapes, R.drawable.ic_notifications, stringResource(R.string.label_icon_badge), on,
                { turnedOn ->
                    scope.launch {
                        app.preferences.setIconBadge(turnedOn)
                        if (turnedOn) {
                            // Nothing shows until new articles come
                            app.badge.startOver()
                            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            app.badge.off()
                        }
                    }
                },
                supporting = stringResource(R.string.desc_icon_badge),
            )
        }
        if (on && !canShow) {
            // As a check of Clock's reliability page: the state and its consequence as two sentences in the error role, the whole row opening the system's page
            row { shapes ->
                SegmentedListItem(
                    onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
                    shapes = shapes,
                    colors = settingsRowColors(),
                    leadingContent = { RowIcon(R.drawable.ic_notifications_off, error = true) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.msg_notifications_off), color = MaterialTheme.colorScheme.error) },
                ) {
                    Text(stringResource(R.string.label_notifications), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Settings › Article list (SPEC §14.2): the app's default look of rows with a sample, and the order. */
@Composable
fun ArticleListSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val look by app.preferences.look.collectAsStateWithLifecycle(null)
    val lines by app.preferences.previewLines.collectAsStateWithLifecycle(null)
    val oldestFirst by app.preferences.oldestFirst.collectAsStateWithLifecycle(null)
    // The newest article that has an image
    val sample by produceState<ArticleRow?>(null) { value = app.articles.sample(null, preferImage = true) }
    SettingsPage(title = stringResource(R.string.title_article_list), onBack = onBack) {
        val currentLook = look ?: return@SettingsPage
        val currentLines = lines ?: return@SettingsPage
        val oldest = oldestFirst ?: return@SettingsPage
        Column {
            sh.hnet.news.ui.components.SettingsGroupHeader(stringResource(R.string.title_look_of_articles))
            LookControls(
                currentLook, currentLines,
                onLook = { scope.launch { app.preferences.setLook(it) } },
                onPreviewLines = { scope.launch { app.preferences.setPreviewLines(it) } },
                sample = sample, sampleTime = sample?.let { timeOfDay(context, it.article.published) }.orEmpty(),
            )
        }
        SettingsNote(stringResource(R.string.msg_feed_own_look))
        Column(Modifier.selectableGroup()) {
            SettingsGroup(stringResource(R.string.label_order)) {
                row { shapes -> SettingsRadioRow(shapes, stringResource(R.string.label_newest_first), selected = !oldest, onSelect = { scope.launch { app.preferences.setOldestFirst(false) } }) }
                row { shapes -> SettingsRadioRow(shapes, stringResource(R.string.label_oldest_first), selected = oldest, onSelect = { scope.launch { app.preferences.setOldestFirst(true) } }) }
            }
        }
    }
}

/** "thumbnail", for the Settings row's summary. */
@Composable
fun lookSummary(look: Look): String = stringResource(
    when (look) {
        Look.TEXT -> R.string.label_look_text_value
        Look.THUMBNAIL -> R.string.label_look_thumbnail_value
        Look.LARGE -> R.string.label_look_large_value
    },
)

/**
 * Settings › Reading (SPEC §14.3): the reader's text size and typeface – the same values as its
 * "Text" sheet – with a sample paragraph in the reader's styles, and marking read while scrolling.
 */
@Composable
fun ReadingSettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val textSize by app.preferences.textSize.collectAsStateWithLifecycle(null)
    val serif by app.preferences.serif.collectAsStateWithLifecycle(null)
    val markOnScroll by app.preferences.markOnScroll.collectAsStateWithLifecycle(null)
    SettingsPage(title = stringResource(R.string.title_reading), onBack = onBack) {
        val size = textSize ?: return@SettingsPage
        val serifOn = serif ?: return@SettingsPage
        val mark = markOnScroll ?: return@SettingsPage
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                TextControls(size, serifOn, onSize = { scope.launch { app.preferences.setTextSize(it) } }, onSerif = { scope.launch { app.preferences.setSerif(it) } })
            }
        }
        Column {
            SettingsGroupHeader(stringResource(R.string.title_sample))
            val style = remember(size, serifOn) { ReaderStyle(size, serifOn, null) }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.msg_reading_sample), style = style.paragraph, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsSwitchRow(shapes, R.drawable.ic_done_all, stringResource(R.string.label_mark_read_on_scroll), mark, { on -> scope.launch { app.preferences.setMarkOnScroll(on) } })
            }
        }
    }
}
