@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.welcome

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.core.content.edit
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.ui.components.AppLogo
import sh.hnet.news.ui.components.FormPage
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.settingsRowColors

/** The ways to begin (SPEC §15). */
enum class WelcomeChoice { START_FRESH, IMPORT, RESTORE }

object Welcome {
    private const val SEEN = "welcome_seen"

    /**
     * Whether News starts with nothing at all: no feed (not even one kept for its saved articles),
     * no setting stored, and the welcome page never answered – a fresh install, or its data cleared.
     */
    suspend fun isNeeded(app: NewsApplication): Boolean =
        !app.stamps.getBoolean(SEEN, false) && app.feeds.feeds.first().isEmpty() && !app.preferences.hasAnySetting()

    fun seen(app: NewsApplication) = app.stamps.edit { putBoolean(SEEN, true) }
}

/**
 * The first start (SPEC §15, board 16), laid out as Music's welcome page: the app's mark, its
 * name, one sentence, then one choice and Next. Start fresh goes to Add a feed, the other two to
 * a file picker; whichever it is, the page does not come back.
 */
@Composable
fun WelcomeScreen(onChosen: (WelcomeChoice) -> Unit) {
    var choice by rememberSaveable { mutableStateOf<WelcomeChoice?>(null) }
    FormPage(
        title = null,
        confirmLabel = stringResource(R.string.button_next),
        onConfirm = { choice?.let(onChosen) },
        confirmEnabled = choice != null,
        contentSpacing = 0.dp,
    ) {
        Spacer(Modifier.height(16.dp))
        AppLogo(titleInset = TITLE_INSET)
        Text(stringResource(R.string.title_welcome), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = TITLE_INSET).semantics { heading() })
        Text(
            stringResource(R.string.msg_welcome),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 12.dp),
        )
        Text(
            stringResource(R.string.label_welcome_question),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = TITLE_INSET, end = TITLE_INSET, top = 32.dp, bottom = 12.dp).semantics { heading() },
        )
        // One choice, nothing preselected
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                row { shapes -> ChoiceRow(shapes, R.string.button_start_fresh, R.string.desc_start_fresh, R.drawable.ic_add, choice == WelcomeChoice.START_FRESH) { choice = WelcomeChoice.START_FRESH } }
                row { shapes -> ChoiceRow(shapes, R.string.button_import_subscriptions, R.string.desc_import_subscriptions, R.drawable.ic_file_open, choice == WelcomeChoice.IMPORT) { choice = WelcomeChoice.IMPORT } }
                row { shapes -> ChoiceRow(shapes, R.string.button_restore_backup, R.string.desc_restore_from_backup, R.drawable.ic_settings_backup_restore, choice == WelcomeChoice.RESTORE) { choice = WelcomeChoice.RESTORE } }
            }
        }
    }
}

/** A radio row with its icon at the end, as the rows of Music's and ComfyChair's welcome pages. */
@Composable
private fun ChoiceRow(shapes: ListItemShapes, title: Int, supporting: Int, @DrawableRes icon: Int, selected: Boolean, onSelect: () -> Unit) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = { Icon(painterResource(icon), contentDescription = null) },
        supportingContent = { Text(stringResource(supporting)) },
    ) {
        Text(stringResource(title))
    }
}

/** Start inset of the title and texts, which the logo lines up with. */
private val TITLE_INSET = 8.dp
