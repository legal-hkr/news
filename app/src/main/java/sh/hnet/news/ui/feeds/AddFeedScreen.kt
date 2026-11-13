@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.feeds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.Feed
import sh.hnet.news.feed.ContentAdvice
import sh.hnet.news.feed.FoundFeed
import sh.hnet.news.ui.components.FeedIconBox
import sh.hnet.news.ui.components.MAX_NAME
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsGroupHeader
import sh.hnet.news.ui.components.SettingsNote
import sh.hnet.news.ui.components.SettingsRadioRow
import sh.hnet.news.ui.components.TooltipIconButton
import sh.hnet.news.ui.components.boldNamesResource
import sh.hnet.news.ui.components.settingsRowColors

/**
 * Add a feed (SPEC §7): a page with one field that takes a feed's or a site's address, then, on the
 * same page, the preview of the feed found, where it gets its name, folder and content source.
 *
 * @param onOpenFeed Opens the page of a feed that turned out to be subscribed already
 * @param onSubscribed Called with the new feed's name once it is subscribed; the caller leaves the page
 */
@Composable
fun AddFeedScreen(onBack: () -> Unit, onOpenFeed: (Long) -> Unit, onSubscribed: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var looking by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    // Several feeds to choose from
    var offered by remember { mutableStateOf<List<FoundFeed>>(emptyList()) }
    var chosen by remember { mutableIntStateOf(0) }
    var already by remember { mutableStateOf<Feed?>(null) }
    var preview by remember { mutableStateOf<FoundFeed?>(null) }

    /** One feed, fetched: either subscribed already or shown in the preview. */
    suspend fun arrived(found: FoundFeed) {
        val subscribed = app.feeds.subscribed(found.url)
        if (subscribed != null) already = subscribed else preview = found
    }

    fun look(work: suspend () -> Unit) {
        error = null
        looking = scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = lookupError(e)
            } finally {
                looking = null
            }
        }
    }

    val find = {
        if (address.isNotBlank() && looking == null) look {
            val found = app.finder.find(address)
            if (found.size == 1) arrived(found.single()) else { offered = found; chosen = 0 }
        }
    }

    preview?.let { found ->
        // Back from the preview returns to the address, not out of the flow
        BackHandler { preview = null }
        Preview(found, onBack = { preview = null }, onSubscribed = onSubscribed)
        return
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_add_feed), maxLines = 1, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    // Editing starts over (SPEC §7.1)
                    error = null; offered = emptyList(); already = null
                },
                label = { Text(stringResource(R.string.label_address)) },
                singleLine = true,
                readOnly = looking != null,
                isError = error != null,
                supportingText = {
                    val subscribed = already
                    when {
                        error != null -> Text(stringResource(error!!))
                        subscribed != null -> Text(boldNamesResource(R.string.msg_already_subscribed, subscribed.title))
                        else -> Text(stringResource(R.string.hint_address))
                    }
                },
                trailingIcon = when {
                    error != null -> ({ Icon(painterResource(R.drawable.ic_error), contentDescription = null) })
                    address.isNotEmpty() && looking == null -> ({
                        TooltipIconButton(R.drawable.ic_close, stringResource(R.string.content_description_clear), {
                            address = ""; error = null; offered = emptyList(); already = null
                        })
                    })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { find() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (offered.isNotEmpty()) {
                Column {
                    SettingsGroupHeader(stringResource(R.string.title_feeds_found, offered.size))
                    Column(Modifier.selectableGroup()) {
                        SettingsGroup {
                            offered.forEachIndexed { index, feed ->
                                row { shapes -> FoundRow(shapes, feed, selected = index == chosen) { chosen = index } }
                            }
                        }
                    }
                }
            }
        }
        if (looking != null) {
            TextButton(onClick = { looking?.cancel() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.button_cancel)) }
        }
        val subscribed = already
        Button(
            onClick = {
                when {
                    // Does not respond while looking
                    looking != null -> Unit
                    subscribed != null -> onOpenFeed(subscribed.id)
                    offered.isNotEmpty() -> look { arrived(app.finder.load(offered[chosen].url)) }
                    else -> find()
                }
            },
            enabled = looking != null || address.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            if (looking != null) {
                LoadingIndicator(color = LocalContentColor.current, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.msg_looking))
            } else {
                Text(stringResource(when {
                    subscribed != null -> R.string.button_open_feed
                    offered.isNotEmpty() -> R.string.button_continue
                    else -> R.string.button_find_feed
                }))
            }
        }
    }
}

/** One of several feeds found: its name, and its address cut at the start so the end of the path shows. */
@Composable
private fun FoundRow(shapes: ListItemShapes, feed: FoundFeed, selected: Boolean, onSelect: () -> Unit) {
    SegmentedListItem(
        selected = selected,
        onClick = onSelect,
        shapes = shapes,
        colors = settingsRowColors(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = feed.title?.let { { Text(feed.url, maxLines = 1, overflow = TextOverflow.StartEllipsis) } },
    ) {
        Text(feed.title ?: feed.url, maxLines = 1, overflow = if (feed.title == null) TextOverflow.StartEllipsis else TextOverflow.Ellipsis)
    }
}

/** The preview before subscribing (SPEC §7.2). */
@Composable
private fun Preview(found: FoundFeed, onBack: () -> Unit, onSubscribed: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val feed = found.feed
    val folders by app.feeds.folders.collectAsStateWithLifecycle(emptyList())
    val fallback = remember(found) { found.title ?: feed?.title ?: runCatching { java.net.URI(found.url).host?.removePrefix("www.") }.getOrNull() ?: found.url }
    var name by rememberSaveable(found.url) { mutableStateOf(fallback.take(MAX_NAME)) }
    var folder by remember { mutableStateOf<FolderChoice>(FolderChoice.None) }
    var pickingFolder by remember { mutableStateOf(false) }
    val advice = remember(found) { feed?.let { ContentAdvice.of(it) } ?: ContentAdvice.UNKNOWN }
    var fullPage by rememberSaveable(found.url) { mutableStateOf(advice == ContentAdvice.FULL_PAGE) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        TopAppBar(
            title = { Text(stringResource(R.string.title_add_feed), maxLines = 1, modifier = Modifier.semantics { heading() }) },
            navigationIcon = { TooltipIconButton(R.drawable.ic_arrow_back, stringResource(R.string.content_description_back), onBack, enabled = !busy) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // The icon is fetched with the subscription; until then the stand-in
                FeedIconBox(null, size = 56.dp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= MAX_NAME) name = it },
                    label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }
            val latest = feed?.items.orEmpty().mapNotNull { it.title }.take(3)
            if (latest.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.title_latest_articles)) {
                    latest.forEach { title ->
                        row { shapes ->
                            // Not tappable: a preview, not a list
                            SegmentedListItem(onClick = {}, enabled = false, shapes = shapes, colors = settingsRowColors()) {
                                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            SettingsGroup {
                row { shapes ->
                    ValueRow(
                        shapes, R.drawable.ic_folder, stringResource(R.string.label_folder),
                        when (val choice = folder) {
                            FolderChoice.None -> stringResource(R.string.label_no_folder)
                            is FolderChoice.Existing -> choice.folder.name
                            is FolderChoice.New -> choice.name
                        },
                    ) { pickingFolder = true }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.selectableGroup()) {
                    SettingsGroup(stringResource(R.string.label_content)) {
                        row { shapes -> SettingsRadioRow(shapes, stringResource(R.string.label_full_page), selected = fullPage, onSelect = { fullPage = true }, supporting = stringResource(R.string.desc_full_page)) }
                        row { shapes -> SettingsRadioRow(shapes, stringResource(R.string.label_feed_content), selected = !fullPage, onSelect = { fullPage = false }, supporting = stringResource(R.string.desc_feed_content)) }
                    }
                }
                when (advice) {
                    ContentAdvice.FULL_PAGE -> SettingsNote(stringResource(R.string.msg_recommend_full_page))
                    ContentAdvice.FEED -> SettingsNote(stringResource(R.string.msg_feed_sends_full_text))
                    ContentAdvice.UNKNOWN -> Unit
                }
            }
        }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val title = name.trim()
                    val choice = folder
                    app.feeds.subscribe(
                        url = found.url, title = title, siteUrl = feed?.siteUrl, iconUrl = feed?.iconUrl,
                        folderId = (choice as? FolderChoice.Existing)?.folder?.id, newFolder = (choice as? FolderChoice.New)?.name, fullPage = fullPage,
                    )
                    onSubscribed(title)
                }
            },
            enabled = name.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            Text(stringResource(R.string.button_subscribe))
        }
    }
    if (pickingFolder) {
        FolderPicker(folders, folder, onDismiss = { pickingFolder = false }) {
            folder = it
            pickingFolder = false
        }
    }
}
