package sh.hnet.news.ui.settings

import sh.hnet.news.data.Usage
import androidx.compose.runtime.produceState
import android.text.format.Formatter
import sh.hnet.news.ui.feeds.withProblems
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.news.NewsApplication
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import sh.hnet.news.BuildConfig
import sh.hnet.news.R
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsNavigationRow
import sh.hnet.news.ui.components.SettingsPage

/**
 * The Settings root (SPEC §14.1), a page over the list. Grouped as Music's and Clock's: App
 * (language), Data (storage, backup, reset) and About on its own, after Feeds.
 */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenFeeds: () -> Unit, onOpenRefresh: () -> Unit, onOpenArticleList: () -> Unit, onOpenReading: () -> Unit, onOpenStorage: () -> Unit, onOpenBackup: () -> Unit, onOpenLanguage: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME.removePrefix("v")
    // Read on every composition: changing it recreates the activity anyway
    val language = appLanguages.firstOrNull { it.tag == currentLanguageTag(context) } ?: appLanguages.first()
    val app = context.applicationContext as NewsApplication
    val feeds by app.feeds.feeds.collectAsStateWithLifecycle(null)
    val folders by app.feeds.folders.collectAsStateWithLifecycle(null)
    SettingsPage(title = stringResource(R.string.title_settings), onBack = onBack) {
        // Feeds stands first and alone: content that is managed, not a preference (SPEC §14.1)
        SettingsGroup {
            row { shapes ->
                val summary = feeds?.let { stringResource(R.string.desc_feeds_summary, it.size, folders?.size ?: 0) }
                // How many feeds have problems (SPEC §10.3)
                val problems = feeds?.withProblems() ?: 0
                SettingsNavigationRow(shapes, R.drawable.ic_rss_feed, stringResource(R.string.title_feeds), summary, badge = problems.takeIf { it > 0 }?.toString(), error = problems > 0, onClick = onOpenFeeds)
            }
        }
        SettingsGroup(stringResource(R.string.title_group_reading)) {
            row { shapes ->
                val refresh by app.preferences.refresh.collectAsStateWithLifecycle(null)
                val summary = refresh?.let { stringResource(R.string.desc_refresh_background, intervalName(it.intervalHours).replaceFirstChar { c -> c.lowercase() }) }
                SettingsNavigationRow(shapes, R.drawable.ic_refresh, stringResource(R.string.title_refresh), summary, onClick = onOpenRefresh)
            }
            row { shapes ->
                val look by app.preferences.look.collectAsStateWithLifecycle(null)
                val lines by app.preferences.previewLines.collectAsStateWithLifecycle(0)
                val summary = look?.let { stringResource(R.string.desc_article_list, lookSummary(it), pluralStringResource(R.plurals.label_preview_lines, lines, lines)) }
                SettingsNavigationRow(shapes, R.drawable.ic_list, stringResource(R.string.title_article_list), summary, onClick = onOpenArticleList)
            }
            row { shapes ->
                val size by app.preferences.textSize.collectAsStateWithLifecycle(null)
                val summary = size?.let { stringResource(R.string.desc_reading, stringResource(R.string.label_percent, it)) }
                SettingsNavigationRow(shapes, R.drawable.ic_menu_book, stringResource(R.string.title_reading), summary, onClick = onOpenReading)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
            row { shapes ->
                val usage by produceState<Usage?>(null) { value = app.storage.usage() }
                val summary = usage?.let { stringResource(R.string.desc_storage, Formatter.formatShortFileSize(context, it.textBytes), Formatter.formatShortFileSize(context, it.imageBytes)) }
                SettingsNavigationRow(shapes, R.drawable.ic_database, stringResource(R.string.title_storage), summary, onClick = onOpenStorage)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_settings_backup_restore, stringResource(R.string.title_backup), stringResource(R.string.desc_backup_row), onClick = onOpenBackup)
            }
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_restart_alt, stringResource(R.string.title_settings_reset), stringResource(R.string.desc_settings_reset_row), onClick = onOpenReset)
            }
        }
        SettingsGroup {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_info, stringResource(R.string.title_settings_about), stringResource(R.string.label_version_value, version), onClick = onOpenAbout)
            }
        }
    }
}
