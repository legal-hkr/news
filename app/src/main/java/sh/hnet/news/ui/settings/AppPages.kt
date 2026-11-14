@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.settings

import sh.hnet.news.refresh.RefreshJobService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import sh.hnet.news.ui.components.DestructiveButton
import sh.hnet.news.ui.components.ConfirmDestructiveDialog
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.ui.components.SettingsDescription
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsPage
import sh.hnet.news.ui.components.SettingsRadioRow

/** A language the app is translated into; an empty tag follows the system. */
class AppLanguage(val tag: String, @param:StringRes val name: Int)

val appLanguages = listOf(
    AppLanguage("", R.string.language_system_default),
    AppLanguage("en", R.string.language_name_en),
    AppLanguage("pl", R.string.language_name_pl),
)

/** The app's language tag; empty when it follows the system. */
fun currentLanguageTag(context: Context): String {
    val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
    return if (locales.isEmpty) "" else locales.get(0)?.language.orEmpty()
}

private fun setLanguage(context: Context, tag: String) {
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
}

/** Language (as ComfyChair): one choice among the translations; the system recreates the activity in it. */
@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val current = remember { currentLanguageTag(context) }
    SettingsPage(title = stringResource(R.string.title_settings_language), onBack = onBack) {
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                appLanguages.forEach { language ->
                    row { shapes ->
                        SettingsRadioRow(shapes, stringResource(language.name), selected = language.tag == current, onSelect = {
                            if (language.tag != current) setLanguage(context, language.tag)
                        })
                    }
                }
            }
        }
    }
}

/**
 * Reset, as Music's page: Restore defaults, confirmed. Settings go back to their defaults and the
 * language follows the system; feeds and saved articles stay.
 */
@Composable
fun ResetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val preferences = app.preferences
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val done = stringResource(R.string.msg_defaults_restored)
    SettingsPage(title = stringResource(R.string.title_settings_reset), onBack = onBack) {
        SettingsDescription(stringResource(R.string.desc_settings_reset))
        // A single destructive action: an outlined button in the error color (owner's decision, as in Music)
        DestructiveButton(stringResource(R.string.button_restore_defaults), R.drawable.ic_history, { confirming = true }, Modifier.padding(top = 8.dp))
    }
    if (confirming) {
        ConfirmDestructiveDialog(
            icon = R.drawable.ic_history,
            title = stringResource(R.string.title_confirm_restore_defaults),
            text = AnnotatedString(stringResource(R.string.msg_confirm_restore_defaults)),
            confirmLabel = stringResource(R.string.button_restore),
            onConfirm = {
                confirming = false
                scope.launch {
                    preferences.restoreDefaults()
                    // The background job follows the settings it was scheduled from
                    RefreshJobService.schedule(context.applicationContext, preferences.refreshSettings())
                    // The icon badge is off by default: its notification goes
                    app.badge.off()
                    Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                    // Last: a language change recreates the activity
                    if (currentLanguageTag(context).isNotEmpty()) setLanguage(context, "")
                }
            },
            onDismiss = { confirming = false },
        )
    }
}
