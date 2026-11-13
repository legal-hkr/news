@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.news.ui.articles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.news.R
import sh.hnet.news.ui.components.EmptyState
import sh.hnet.news.ui.components.TooltipIconButton

/**
 * The app's one screen (SPEC §3.1, §4): the list of articles. So far only its frame and the
 * "No feeds yet" state (§4.9); Search and Refresh join the top bar, and the two buttons join the
 * empty state, with the stages that make them work.
 */
@Composable
fun ArticlesScreen(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_articles), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        // The app's mark is drawn on the launcher icon's canvas, hence the larger size
        EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_empty_no_feeds), iconSize = 108.dp)
    }
}
