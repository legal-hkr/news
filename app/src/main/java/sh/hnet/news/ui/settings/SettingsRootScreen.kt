package sh.hnet.news.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.hnet.news.NewsApplication
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import sh.hnet.news.BuildConfig
import sh.hnet.news.R
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsNavigationRow
import sh.hnet.news.ui.components.SettingsPage

/**
 * The Settings root (SPEC §14.1), a page over the list. Grouped as Music's and Clock's: App
 * (language), Data (reset) and About on its own, after Feeds. The Reading group, Storage and Backup
 * join with their stages.
 */
@Composable
fun SettingsRootScreen(onBack: () -> Unit, onOpenFeeds: () -> Unit, onOpenLanguage: () -> Unit, onOpenReset: () -> Unit, onOpenAbout: () -> Unit) {
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
                SettingsNavigationRow(shapes, R.drawable.ic_rss_feed, stringResource(R.string.title_feeds), summary, onClick = onOpenFeeds)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_app)) {
            row { shapes ->
                SettingsNavigationRow(shapes, R.drawable.ic_translate, stringResource(R.string.title_settings_language), stringResource(language.name), onClick = onOpenLanguage)
            }
        }
        SettingsGroup(stringResource(R.string.title_settings_group_data)) {
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
