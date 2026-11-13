@file:OptIn(ExperimentalMaterial3Api::class)

package sh.hnet.news.ui.articles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.ui.components.EmptyState
import sh.hnet.news.ui.components.TooltipIconButton
import sh.hnet.news.ui.feeds.NoFeeds
import sh.hnet.news.ui.feeds.rememberOpmlActions

/**
 * The app's one screen (SPEC §3.1, §4): the list of articles. So far its frame and two states of
 * §4.9: "No feeds yet", with the two ways to start, and "No articles" once there are feeds (nothing
 * is fetched before stage 3). Search and Refresh join the top bar with the stages that make them work.
 */
@Composable
fun ArticlesScreen(onOpenSettings: () -> Unit, onAddFeed: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val feeds by app.feeds.feeds.collectAsStateWithLifecycle(null)
    val opml = rememberOpmlActions(snackbars, messageScope)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_articles), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { TooltipIconButton(R.drawable.ic_settings, stringResource(R.string.content_description_settings), onOpenSettings) },
        )
        when (feeds?.isEmpty()) {
            // Not read yet: nothing, rather than a flash of the wrong state
            null -> Unit
            true -> NoFeeds(onAddFeed, opml.import)
            // The app's mark is drawn on the launcher icon's canvas, hence the larger size
            false -> EmptyState(R.drawable.ic_launcher_monochrome, stringResource(R.string.msg_empty_no_articles), iconSize = 108.dp)
        }
    }
}
