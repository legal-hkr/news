@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.reader

import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import sh.hnet.news.data.ArticleState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.content.Block
import sh.hnet.news.content.Blocks
import sh.hnet.news.data.Article
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.data.ArticleVersion
import sh.hnet.news.ui.articles.ArticlesViewModel
import sh.hnet.news.ui.articles.SmallFeedIcon
import sh.hnet.news.ui.components.MenuItem
import sh.hnet.news.ui.components.TooltipIconButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One article's state in the reader, shared by its page and the bars around the pager. */
private class PageState(val row: ArticleRow) {
    /** Null until the article's bodies are read. */
    var article by mutableStateOf<Article?>(null)
    var feed by mutableStateOf<List<Block>>(emptyList())
    var page by mutableStateOf<List<Block>?>(null)
    var version by mutableIntStateOf(ArticleVersion.DEFAULT)
    var saved by mutableStateOf(row.article.saved)
    /** The other version is being fetched. */
    var busy by mutableStateOf(false)
    /** The header has scrolled away: the top bar names the article instead of its feed. */
    var headerAway by mutableStateOf(false)

    val showsPage: Boolean get() = version != ArticleVersion.FEED && page != null
    val blocks: List<Block> get() = if (showsPage) page.orEmpty() else feed
}

/**
 * The reader (SPEC §5): one article per page of a pager over the list the user came from, each a
 * column of natively drawn blocks read from the phone alone. The top bar names what is read and
 * the floating toolbar acts on it; both stay in place.
 * Save is the toolbar's first control.
 */
@Composable
fun ReaderScreen(model: ArticlesViewModel, articleId: Long, onBack: () -> Unit, onShowImages: (List<String>, Int) -> Unit, snackbars: SnackbarHostState, messageScope: CoroutineScope) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val rows = remember { model.readerRows }
    val start = remember { rows.indexOfFirst { it.article.id == articleId } }
    if (start < 0) {
        // Nothing to page over (the process was restored without its list): back to the list
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val pager = rememberPagerState(start) { rows.size }
    val states = remember { HashMap<Long, PageState>() }
    fun stateOf(row: ArticleRow): PageState = states.getOrPut(row.article.id) { PageState(row) }
    val textSize by app.preferences.textSize.collectAsStateWithLifecycle(100)
    val serif by app.preferences.serif.collectAsStateWithLifecycle(false)
    var textSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // Articles marked unread here stay so while the reader is open
    val keptUnread = remember { HashSet<Long>() }
    val noBrowser = stringResource(R.string.msg_no_browser)
    val noNetwork = stringResource(R.string.error_no_network)
    val notFetched = stringResource(R.string.msg_page_not_fetched)

    val message: (String) -> Unit = { text ->
        messageScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(text, duration = SnackbarDuration.Short)
        }
    }
    val open: (String) -> Unit = { link ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
        } catch (_: ActivityNotFoundException) {
            message(noBrowser)
        }
    }

    // Both bars stay in place while reading, as on the list (the owner's decision over SPEC §5.1)
    // The article's page, asked for from the menu or from a note about what is missing (SPEC §5.8, §5.10)
    val fetchPage: (PageState) -> Unit = { state ->
        val article = state.article
        if (article != null && !state.busy) {
            if (context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                message(noNetwork)
            } else {
                scope.launch {
                    state.busy = true
                    val fetched = app.refresher.fetchPage(article)
                    load(app, state)
                    state.busy = false
                    if (fetched) {
                        // Remembered for this article alone; the feed's setting stays
                        state.version = ArticleVersion.PAGE
                        app.articles.setVersion(article.id, ArticleVersion.PAGE)
                    } else {
                        message(notFetched)
                    }
                }
            }
        }
    }
    val topBar = TopAppBarDefaults.pinnedScrollBehavior()
    val current = stateOf(rows[pager.currentPage.coerceIn(0, rows.lastIndex)])
    LaunchedEffect(pager.settledPage) {
        val id = rows[pager.settledPage].article.id
        model.returnTo = id
        if (id !in keptUnread) model.setRead(listOf(id), true)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    // Both titles sit at the bar's middle while one fades into the other: the two lines are taller than the one
                    AnimatedContent(current.headerAway, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentAlignment = Alignment.CenterStart, label = "reader title") { away ->
                        if (away) {
                            Column {
                                Text(current.row.article.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(current.row.feedTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallFeedIcon(current.row.article.feedId, current.row.feedHasIcon)
                                Spacer(Modifier.width(8.dp))
                                Text(current.row.feedTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
                scrollBehavior = topBar,
            )
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { rows[it].article.id }, beyondViewportPageCount = 0) { index ->
                val state = stateOf(rows[index])
                val reader = remember(textSize, serif, state) {
                    ReaderScope(
                        ReaderStyle(textSize, serif, state.row.feedLanguage), open, message,
                        showImage = { url ->
                            val urls = shownImages(state, app)
                            onShowImages(urls, urls.indexOf(url).coerceAtLeast(0))
                        },
                    )
                }
                CompositionLocalProvider(LocalReader provides reader) {
                    ArticlePage(
                        state, next = rows.getOrNull(index + 1),
                        Modifier.fillMaxSize().nestedScroll(topBar.nestedScrollConnection),
                        onNext = { scope.launch { pager.animateScrollToPage(index + 1) } },
                        onFetchPage = { fetchPage(state) },
                    )
                }
            }
        }

        val article = current.article
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            expandedShadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).offset(y = -FloatingToolbarDefaults.ScreenOffset),
        ) {
            val link = current.row.article.link
            // No snackbar: the star is the feedback, and the same tap undoes it (SPEC §12)
            val saveLabel = stringResource(if (current.saved) R.string.button_unsave else R.string.button_save)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(saveLabel) } },
                state = rememberTooltipState(),
            ) {
                FilledIconToggleButton(
                    checked = current.saved,
                    onCheckedChange = { on ->
                        val state = current
                        state.saved = on
                        model.setSaved(state.row.article.id, on)
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(painterResource(if (current.saved) R.drawable.ic_star_filled else R.drawable.ic_star), contentDescription = saveLabel)
                }
            }
            TooltipIconButton(R.drawable.ic_share, stringResource(R.string.button_share), { link?.let { shareText(context, current.row.article.title, it) } }, enabled = link != null)
            TooltipIconButton(R.drawable.ic_open_in_browser, stringResource(R.string.button_open_in_browser), { link?.let(open) }, enabled = link != null)
            TooltipIconButton(R.drawable.ic_format_size, stringResource(R.string.title_text), { textSheet = true })
            Box {
                if (current.busy) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(28.dp)) }
                } else {
                    TooltipIconButton(R.drawable.ic_more_vert, stringResource(R.string.content_description_more), { menu = true })
                }
                // The other version, when there is one to show or a page to fetch it from (SPEC §5.8)
                val other = when {
                    article == null -> null
                    current.showsPage -> R.string.menu_show_feed_content.takeIf { current.feed.isNotEmpty() }
                    else -> R.string.menu_show_full_page.takeIf { link != null }
                }
                val count = if (other == null) 1 else 2
                DropdownMenuPopup(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                        MenuItem(stringResource(R.string.menu_mark_unread), R.drawable.ic_circle_filled, 0, count) {
                            menu = false
                            keptUnread += current.row.article.id
                            model.setRead(listOf(current.row.article.id), false)
                        }
                        if (other != null && article != null) {
                            MenuItem(stringResource(other), if (current.showsPage) R.drawable.ic_rss_feed else R.drawable.ic_article, 1, count) {
                                menu = false
                                val state = current
                                when {
                                    state.showsPage -> state.version = ArticleVersion.FEED
                                    state.page != null -> state.version = ArticleVersion.PAGE
                                    else -> fetchPage(state)
                                }
                                // Remembered for this article alone; the feed's setting stays
                                if (state.version != ArticleVersion.DEFAULT) scope.launch { app.articles.setVersion(article.id, state.version) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (textSheet) {
        TextSheet(
            textSize, serif,
            onSize = { scope.launch { app.preferences.setTextSize(it) } },
            onSerif = { scope.launch { app.preferences.setSerif(it) } },
            onDismiss = { textSheet = false },
        )
    }
}

/** Reads an article's bodies and its own choices from the phone. */
private suspend fun load(app: NewsApplication, state: PageState) {
    val id = state.row.article.id
    val (article, feed, page) = withContext(Dispatchers.Default) {
        val content = app.articles.content(id)
        Triple(app.articles.article(id) ?: state.row.article, Blocks.decode(content?.feed), content?.page?.let { Blocks.decode(it) }?.takeIf { it.isNotEmpty() })
    }
    state.feed = feed
    state.page = page
    if (state.article == null) {
        state.version = article.version
        // The row is as old as the list the reader was opened from
        state.saved = article.saved
    }
    state.article = article
}

/** The article's lead image when the body does not show it itself; it then opens the article. */
private fun leadImage(state: PageState, app: NewsApplication): Block.Image? {
    val url = state.article?.imageUrl ?: return null
    if (!app.images.has(url) || Blocks.images(state.blocks).any { it.url == url }) return null
    return Block.Image(url)
}

/** The images of the article that are on the phone, in reading order: what the viewer pages over. */
private fun shownImages(state: PageState, app: NewsApplication): List<String> =
    (listOfNotNull(leadImage(state, app)?.url) + Blocks.images(state.blocks).map { it.url }).distinct().filter { app.images.has(it) }

@Composable
private fun ArticlePage(state: PageState, next: ArticleRow?, modifier: Modifier, onNext: () -> Unit, onFetchPage: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    LaunchedEffect(state) { if (state.article == null) load(app, state) }
    // Nothing is drawn until the article is read: it is on the phone, so this is a frame or two, not a loading state
    val article = state.article ?: return
    // Each version has its own length: the stored position belongs to the one the article was left in
    key(state.version) {
        val shown = state.version
        val restore = shown == article.version
        val list = rememberLazyListState(if (restore) article.readIndex else 0, if (restore) article.readOffset else 0)
        val away by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
        LaunchedEffect(away) { state.headerAway = away }
        DisposableEffect(Unit) {
            onDispose {
                // Where reading stopped (SPEC §5.9); an article read to its end reopens at the top
                val ended = !list.canScrollForward
                val index = if (ended) 0 else list.firstVisibleItemIndex
                val offset = if (ended) 0 else list.firstVisibleItemScrollOffset
                state.article = article.copy(readIndex = index, readOffset = offset, version = shown)
                app.appScope.launch { app.articles.setPosition(article.id, index, offset) }
            }
        }
        val blocks = state.blocks
        // The feed wants full pages and this article's is not on the phone (SPEC §5.10)
        val missing = (article.state == ArticleState.INCOMPLETE || article.state == ArticleState.NOT_EXTRACTED) && state.page == null && state.row.feedFullPage && article.link != null
        val offline = remember(article.state, state.busy) { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
        val lead = remember(blocks, article.imageUrl) { leadImage(state, app) }
        val column = Modifier.widthIn(max = 600.dp).fillMaxWidth()
        LazyColumn(
            state = list,
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = READER_SIDE, end = READER_SIDE, top = 8.dp, bottom = 112.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            if (missing && blocks.isNotEmpty()) item(key = "note", contentType = "note") { PageNote(article.state, state.busy, column, onFetchPage) }
            item(key = "header", contentType = "header") { Header(article, hasText = blocks.isNotEmpty(), column) }
            if (lead != null) item(key = "lead", contentType = "block") { Box(column) { BlockView(lead) } }
            if (blocks.isEmpty()) {
                item(key = "nothing", contentType = "state") {
                    if (missing) {
                        NotOnPhone(offline, state.busy, article.link, column, onFetchPage)
                    } else {
                        TitleOnly(article.link, canFetch = !state.row.feedFullPage && article.link != null && !offline, state.busy, column, onFetchPage)
                    }
                }
            } else {
                itemsIndexed(blocks, contentType = { _, block -> block::class }) { _, block -> Box(column) { BlockView(block) } }
            }
            if (next != null) item(key = "next", contentType = "next") { NextCard(next, column.padding(top = 16.dp), onNext) }
        }
    }
}

/** The article's title, never cut; its author; its date with the reading time (SPEC §5.2). The feed is in the top bar. */
@Composable
private fun Header(article: Article, hasText: Boolean, modifier: Modifier) {
    val reader = LocalReader.current
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(article.published, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(article.published).atZone(ZoneId.systemDefault()))
    }
    Column(modifier.padding(bottom = 24.dp)) {
        Text(article.title, style = reader.style.title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        article.author?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        // 230 words a minute, rounded up, at least one
        val minutes = ((article.words + 229) / 230).coerceAtLeast(1)
        Text(
            if (hasText && article.words > 0) stringResource(R.string.label_date_reading_time, date, minutes) else date,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Above the header of an article shown from the feed because its page is not on the phone (SPEC
 * §5.10): why, and Try again.
 */
@Composable
private fun PageNote(articleState: Int, busy: Boolean, modifier: Modifier, onTryAgain: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = modifier.padding(bottom = 24.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text(
                    stringResource(if (articleState == ArticleState.NOT_EXTRACTED) R.string.msg_extraction_failed else R.string.msg_page_missing),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 8.dp),
                )
                Fetching(busy) { TextButton(onClick = onTryAgain, modifier = Modifier.offset(x = (-12).dp)) { Text(stringResource(R.string.button_try_again)) } }
            }
        }
    }
}

/** A button, or while its work runs the loading indicator with "Fetching…" in its place. */
@Composable
private fun Fetching(busy: Boolean, button: @Composable () -> Unit) {
    if (busy) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoadingIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.msg_fetching), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        button()
    }
}

/** An article whose feed wants full pages and has nothing of it on the phone (SPEC §5.10). */
@Composable
private fun NotOnPhone(offline: Boolean, busy: Boolean, link: String?, modifier: Modifier, onTryAgain: () -> Unit) {
    val reader = LocalReader.current
    Column(modifier.padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (offline) R.string.msg_not_on_phone else R.string.msg_not_on_phone_online), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Fetching(busy) { FilledTonalButton(onClick = onTryAgain) { Text(stringResource(R.string.button_try_again)) } }
        if (link != null && !offline) FilledTonalButton(onClick = { reader.open(link) }) { Text(stringResource(R.string.button_open_in_browser)) }
    }
}

/**
 * An article the feed gave only a title and a link for (SPEC §5.10). A feed kept on its own
 * content offers the page of this one article ([canFetch]).
 */
@Composable
private fun TitleOnly(link: String?, canFetch: Boolean, busy: Boolean, modifier: Modifier, onFetchPage: () -> Unit) {
    val reader = LocalReader.current
    Column(modifier.padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.msg_title_only), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (canFetch || busy) Fetching(busy) { Button(onClick = onFetchPage) { Text(stringResource(R.string.button_fetch_full_page)) } }
        if (link != null) FilledTonalButton(onClick = { reader.open(link) }) { Text(stringResource(R.string.button_open_in_browser)) }
    }
}

/** At the article's end: the next one of the list, by a tap; nothing advances by itself (SPEC §5.9). */
@Composable
private fun NextCard(next: ArticleRow, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.label_next_article), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(next.article.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(next.feedTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
