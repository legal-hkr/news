@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.articles

import sh.hnet.news.ui.feeds.withProblems
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.hnet.news.Look
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.Source
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.ui.components.EmptyState
import sh.hnet.news.ui.components.TooltipIconButton
import sh.hnet.news.ui.feeds.NoFeeds
import sh.hnet.news.ui.feeds.rememberOpmlActions
import java.time.LocalDate
import java.time.ZoneId

/**
 * The app's one screen (SPEC §3.1, §4): the list of articles, with its refresh state as the top
 * bar's subtitle, the filters in a floating toolbar (Source, Unread only, Mark all as read) and the
 * states of §4.9. Search and "Saved only" join with stage 6; until the reader exists (stage 4) a
 * tap marks the article read and opens its link in the browser.
 */
@Composable
fun ArticlesScreen(model: ArticlesViewModel, onOpenSettings: () -> Unit, onOpenArticle: (Long) -> Unit, onAddFeed: () -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val feeds by app.feeds.feeds.collectAsStateWithLifecycle(null)
    val folders by app.feeds.folders.collectAsStateWithLifecycle(emptyList())
    val list by model.list.collectAsStateWithLifecycle()
    val source by model.source.collectAsStateWithLifecycle()
    val unreadOnly by model.unreadOnly.collectAsStateWithLifecycle()
    val progress by app.refresher.progress.collectAsStateWithLifecycle()
    val lastRefresh by app.preferences.lastRefresh.collectAsStateWithLifecycle(null)
    val stored by remember { app.articles.observeCount() }.collectAsStateWithLifecycle(null)
    val appLook by app.preferences.look.collectAsStateWithLifecycle(Look.THUMBNAIL)
    val appLines by app.preferences.previewLines.collectAsStateWithLifecycle(0)
    val unread by remember { app.articles.observeUnread() }.collectAsStateWithLifecycle(emptyList())
    val ownOrder by app.preferences.ownOrder.collectAsStateWithLifecycle(false)
    val opml = rememberOpmlActions(snackbars, messageScope)
    var choosingSource by remember { mutableStateOf(false) }
    // The pull's own indicator shows only for a refresh the pull started
    var pulled by remember { mutableStateOf(false) }
    if (progress == null) pulled = false
    val listState = rememberLazyListState()
    val pull = rememberPullToRefreshState()
    val markedRead = stringResource(R.string.msg_marked_read)
    val undo = stringResource(R.string.button_undo)

    // Leaving the screen rebuilds the list (SPEC §4.7): rows that stayed only because they were on screen go.
    // Not for the reader: it pages over this list, and Back returns to the row of the article then open (§5.9)
    var reading by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (!reading) model.rebuild() } }
    val markOnScroll by app.preferences.markOnScroll.collectAsStateWithLifecycle(false)
    var returnTo by remember { mutableStateOf(model.returnTo.also { model.returnTo = null }) }

    // Both bars stay while the list scrolls (owner's decision, 13 Nov 2026; SPEC §4.1 and §4.5 had them leave)
    val topBar = TopAppBarDefaults.pinnedScrollBehavior()
    // At its top the list takes arrivals directly; scrolled away from it they wait behind the pill
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(atTop) { model.atTop = atTop }
    val subtitle = refreshSubtitle(progress, lastRefresh, empty = stored == 0)
    val refreshSettings by app.preferences.refresh.collectAsStateWithLifecycle(null)
    // A refresh by the user; false when it did not start at once: one runs already, or the metered network is asked about first (SPEC §10.5)
    val refresh: () -> Boolean = {
        val asks = refreshSettings?.unmeteredOnly == true && app.refreshRequests.metered()
        model.refresh() && !asks
    }
    val problems = feeds?.withProblems() ?: 0
    val allFeeds = feeds

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_articles), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            subtitle = { subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            actions = {
                if (!allFeeds.isNullOrEmpty()) {
                    if (progress != null) {
                        // In place of the Refresh button while a refresh runs; it does not respond (SPEC §4.8)
                        val refreshing = stringResource(R.string.msg_refreshing_one)
                        Box(Modifier.size(48.dp).semantics { contentDescription = refreshing }, contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(28.dp)) }
                    } else {
                        TooltipIconButton(R.drawable.ic_refresh, stringResource(R.string.content_description_refresh), { refresh() })
                    }
                }
                SettingsButton(problems, onOpenSettings)
            },
            scrollBehavior = topBar,
        )
        val current = list
        val rows = current?.rows
        when {
            // Not read yet: nothing, rather than a flash of the wrong state
            allFeeds == null || source == null || unreadOnly == null -> Unit
            allFeeds.isEmpty() -> NoFeeds(onAddFeed, opml.import)
            else -> Box(Modifier.fillMaxSize()) {
                when {
                    rows == null -> Unit
                    rows.isEmpty() && stored == 0 && progress != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                    rows.isEmpty() -> EmptyState(
                        // The app's mark is drawn on the launcher icon's canvas, hence the larger size
                        R.drawable.ic_launcher_monochrome,
                        stringResource(if (unreadOnly == true && stored != 0) R.string.msg_empty_all_read else R.string.msg_empty_no_articles),
                        iconSize = 108.dp,
                    )
                    else -> PullToRefreshBox(
                        isRefreshing = pulled && progress != null,
                        onRefresh = { pulled = refresh() },
                        state = pull,
                        indicator = { PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = pulled && progress != null, modifier = Modifier.align(Alignment.TopCenter)) },
                    ) {
                        ArticleRows(
                            rows, appLook, appLines, listState, { atTop },
                            Modifier.fillMaxSize().nestedScroll(topBar.nestedScrollConnection),
                            returnTo = returnTo, onReturned = { returnTo = null },
                            onPassed = if (markOnScroll) { ids -> model.setRead(ids, true) } else null,
                        ) { row ->
                            // The reader marks it read once it is on screen
                            reading = true
                            model.openReader()
                            onOpenArticle(row.article.id)
                        }
                    }
                }
                // Articles that arrived while the list was visible wait behind this (SPEC §4.7)
                if (current != null && current.held > 0 && !rows.isNullOrEmpty()) {
                    Surface(
                        onClick = {
                            model.rebuild()
                            scope.launch { listState.scrollToItem(0) }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shadowElevation = 3.dp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.button_new_articles, countText(current.held)), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                // The filters (SPEC §4.5); it stays in every state, so the filter that emptied the list is one tap away
                val anyUnread = rows.orEmpty().any { !it.article.read }
                val sourceName = when (val chosen = source) {
                    is Source.Feed -> allFeeds.firstOrNull { it.id == chosen.id }?.title
                    is Source.Folder -> folders.firstOrNull { it.id == chosen.id }?.name
                    else -> null
                } ?: stringResource(R.string.label_source_all)
                val sourceDescription = stringResource(R.string.content_description_source, sourceName)
                HorizontalFloatingToolbar(
                    expanded = true,
                    // The toolbar's standard container is the rows' own role, and the list scrolls under it now that it stays: it takes the highest surface container, and M3's level 2 shadow (3 dp) as the pill does – the shadow alone does not show on a dark theme
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    expandedShadowElevation = FLOATING_ELEVATION,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).offset(y = -FloatingToolbarDefaults.ScreenOffset),
                ) {
                    FilledTonalButton(
                        onClick = { choosingSource = true },
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                        modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = sourceDescription },
                    ) {
                        Icon(painterResource(R.drawable.ic_filter_list), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val unreadLabel = stringResource(R.string.tooltip_unread_only)
                    Tooltip(unreadLabel) {
                        FilledIconToggleButton(checked = unreadOnly == true, onCheckedChange = { model.setUnreadOnly(it) }, shapes = IconButtonDefaults.toggleableShapes()) {
                            Icon(painterResource(if (unreadOnly == true) R.drawable.ic_circle_filled else R.drawable.ic_circle), contentDescription = unreadLabel)
                        }
                    }
                    val markAll = stringResource(R.string.content_description_mark_all_read)
                    Tooltip(markAll) {
                        IconButton(
                            enabled = anyUnread,
                            onClick = {
                                // At once, on every unread article the filters show, with Undo (SPEC §4.5)
                                val ids = model.markAllRead()
                                messageScope.launch {
                                    snackbars.currentSnackbarData?.dismiss()
                                    if (snackbars.showSnackbar(markedRead.format(ids.size), actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                                        model.setRead(ids, false)
                                    }
                                }
                            },
                        ) {
                            Icon(painterResource(R.drawable.ic_done_all), contentDescription = markAll)
                        }
                    }
                }
            }
        }
    }

    if (choosingSource && allFeeds != null) {
        SourceSheet(
            current = source ?: Source.All, feeds = allFeeds, folders = folders, ownOrder = ownOrder, unread = unread.associate { it.feedId to it.count },
            onDismiss = { choosingSource = false },
        ) { picked ->
            choosingSource = false
            model.setSource(picked)
            // A new source starts at its top
            scope.launch { listState.scrollToItem(0) }
        }
    }
}

/** M3 elevation level 2, as Music's mini player; the toolbar's own defaults are 0 and 1 dp. */
private val FLOATING_ELEVATION = 3.dp

/** A control's name on long press, as TooltipIconButton shows it. */
@Composable
private fun Tooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** The rows under day headers (SPEC §4.2, §4.4): the rows of one day form a segmented group, 2 dp apart, outer corners 20, inner 6. */
@Composable
private fun ArticleRows(
    rows: List<ArticleRow>, appLook: Look, appLines: Int, state: androidx.compose.foundation.lazy.LazyListState, atTop: () -> Boolean, modifier: Modifier,
    returnTo: Long?, onReturned: () -> Unit, onPassed: ((List<Long>) -> Unit)?, onOpen: (ArticleRow) -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(rows) { LocalDate.now(zone) }
    val days = remember(rows) { rows.map { dayOf(it.article.published, zone) } }
    // Rows that join above while the list rests at its top must show: without this the list stays anchored on the row that was first
    val firstId = rows.firstOrNull()?.article?.id
    var shownFirst by remember { mutableStateOf(firstId) }
    if (firstId != shownFirst) {
        if (atTop()) state.requestScrollToItem(0)
        shownFirst = firstId
    }
    // Back from the reader: the row of the article it showed last is brought into view (SPEC §5.9)
    LaunchedEffect(returnTo, rows) {
        val target = rows.indexOfFirst { it.article.id == returnTo }
        if (target < 0) return@LaunchedEffect
        if (state.layoutInfo.visibleItemsInfo.none { it.key == returnTo }) {
            val headers = (0..target).count { it == 0 || days[it - 1] != days[it] }
            state.scrollToItem(target + headers)
        }
        onReturned()
    }
    // Mark as read while scrolling past (SPEC §4.7): a row that leaves the top edge during a scroll by the user
    if (onPassed != null) {
        val passed by rememberUpdatedState(onPassed)
        LaunchedEffect(state) {
            var byUser = false
            launch { state.interactionSource.interactions.collect { if (it is DragInteraction.Start) byUser = true } }
            launch { snapshotFlow { state.isScrollInProgress }.collect { if (!it) byUser = false } }
            var seen = emptyList<Pair<Int, Any>>()
            snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index to it.key } }.collect { now ->
                val first = now.firstOrNull()?.first ?: return@collect
                if (byUser && state.lastScrolledForward) {
                    val gone = seen.filter { it.first < first }.mapNotNull { it.second as? Long }
                    if (gone.isNotEmpty()) passed(gone)
                }
                seen = now
            }
        }
    }
    LazyColumn(state = state, modifier = modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp)) {
        rows.forEachIndexed { index, row ->
            val first = index == 0 || days[index - 1] != days[index]
            val last = index == rows.lastIndex || days[index + 1] != days[index]
            if (first) {
                item(key = "day ${days[index]}", contentType = "day") {
                    Text(
                        dayHeader(days[index], today),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                    )
                }
            }
            item(key = row.article.id, contentType = "article") {
                val top = if (first) 20.dp else 6.dp
                val bottom = if (last) 20.dp else 6.dp
                ArticleRowItem(
                    row, row.look(appLook), row.previewLines(appLines), timeOfDay(context, row.article.published),
                    RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    Modifier.padding(bottom = if (last) 0.dp else 2.dp),
                ) { onOpen(row) }
            }
        }
    }
}

/** Settings, with the number of feeds with problems on it (SPEC §10.3): the one place the list tells of them. */
@Composable
private fun SettingsButton(problems: Int, onClick: () -> Unit) {
    val label = if (problems > 0) stringResource(R.string.content_description_settings_problems, problems) else stringResource(R.string.content_description_settings)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) {
            BadgedBox(badge = { if (problems > 0) Badge { Text(countText(problems)) } }) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = label)
            }
        }
    }
}
