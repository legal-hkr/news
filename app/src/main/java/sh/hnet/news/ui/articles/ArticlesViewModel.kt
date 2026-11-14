package sh.hnet.news.ui.articles

import sh.hnet.news.content.Fold
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.Source
import sh.hnet.news.data.ArticleRow

/**
 * What the list shows: its rows, and how many articles that pass the filters are held back (SPEC
 * §4.7). With a search under way, [query] is the text looked for, folded: the rows are its results.
 */
class ArticleList(val rows: List<ArticleRow>, val held: Int, val query: String? = null)

/**
 * The one list and its filters (SPEC §3.2, §4.7). The list is a snapshot: which articles it shows is
 * decided when it is rebuilt – a filter changed, the user refreshed, the "New articles" pill was
 * tapped, the screen was left – and until then every one of those rows stays where it is, redrawn as
 * its state changes (read with "Unread only" on, still fetching, complete), while articles that
 * arrive meanwhile are only counted (§2.8: rows do not move under the thumb). An empty list fills by
 * itself, a list resting at its top takes arrivals directly (nothing is being read past them), and
 * with "Oldest first" new articles join the end, where they move nothing.
 *
 * Search (§11) is a state of this list, not another one: the typed text is one more filter beside
 * the source and the two toggles, and its results come newest first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModel(private val app: NewsApplication) : ViewModel() {
    val source: StateFlow<Source?> = app.preferences.source.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val unreadOnly: StateFlow<Boolean?> = app.preferences.unreadOnly.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val savedOnly: StateFlow<Boolean?> = app.preferences.savedOnly.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether the top bar is the search field; never so at a start (SPEC §3.2). */
    var searching by mutableStateOf(false)
        private set

    /** The search field's text. */
    val query = TextFieldState()

    /** The typed text, folded, with the articles holding it; null ids when nothing is searched for. */
    private val found = snapshotFlow { if (searching) query.text.toString() else "" }
        .map { Fold.query(it) }
        .distinctUntilChanged()
        .mapLatest { text -> text to if (text.isEmpty()) null else app.search.find(text) }

    private class Filters(val unread: Boolean, val saved: Boolean, val oldest: Boolean, val rebuild: Int, val query: String, val found: Set<Long>?)

    private val rebuilds = MutableStateFlow(0)
    /** The articles of the snapshot, and what it was taken for (filters and rebuild count). */
    private var visible: Set<Long> = emptySet()
    private var visibleFor: Any? = null
    /** Whether the list rests at its top, as the screen reports it. */
    @Volatile var atTop = true

    val list: StateFlow<ArticleList?> = combine(
        app.preferences.source.flatMapLatest { source ->
            app.articles.observeRows((source as? Source.Feed)?.id, (source as? Source.Folder)?.id).map { source to it }
        }.conflate(),
        combine(app.preferences.unreadOnly, app.preferences.savedOnly, app.preferences.oldestFirst, rebuilds, found) { unread, saved, oldest, rebuild, (query, ids) ->
            // Results come newest first whatever the order of the list (SPEC §11)
            Filters(unread, saved, oldest && ids == null, rebuild, query, ids)
        },
    ) { (source, all), filters ->
        val oldest = filters.oldest
        val passing = all.filter { (!filters.unread || !it.article.read) && (!filters.saved || it.article.saved) && (filters.found == null || it.article.id in filters.found) }
        val taken = listOf(source, filters.unread, filters.saved, oldest, filters.rebuild, filters.query)
        visible = when {
            taken != visibleFor || all.none { it.article.id in visible } -> passing.mapTo(HashSet()) { it.article.id }
            oldest || atTop -> visible + passing.map { it.article.id }
            else -> visible
        }
        visibleFor = taken
        val shown = all.filter { it.article.id in visible }
        ArticleList(if (oldest) shown.asReversed() else shown, passing.count { it.article.id !in visible }, filters.query.takeIf { filters.found != null })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // A source that stops existing (its feed or folder is removed) falls back to All (SPEC §4.6)
        viewModelScope.launch {
            combine(app.preferences.source, app.feeds.feeds, app.feeds.folders) { source, feeds, folders ->
                when (source) {
                    Source.All -> true
                    is Source.Feed -> feeds.any { it.id == source.id }
                    is Source.Folder -> folders.any { it.id == source.id }
                }
            }.collect { exists -> if (!exists) app.preferences.setSource(Source.All) }
        }
    }

    /** The articles the reader pages over: the list as it was when an article was opened (SPEC §5.9). */
    var readerRows: List<ArticleRow> = emptyList()
        private set

    /** The article the reader showed last; the list returns to its row. */
    var returnTo: Long? = null

    fun openReader() {
        readerRows = list.value?.rows.orEmpty()
    }

    fun rebuild() {
        rebuilds.value++
    }

    fun setSource(source: Source) {
        viewModelScope.launch { app.preferences.setSource(source) }
    }

    fun setUnreadOnly(on: Boolean) {
        viewModelScope.launch { app.preferences.setUnreadOnly(on) }
    }

    fun setSavedOnly(on: Boolean) {
        viewModelScope.launch { app.preferences.setSavedOnly(on) }
    }

    fun setSaved(id: Long, saved: Boolean) {
        viewModelScope.launch { app.articles.setSaved(id, saved) }
    }

    /** Set when the search opens and taken by the field once: coming back from the reader must not raise the keyboard again. */
    var focusSearch = false

    fun openSearch() {
        searching = true
        focusSearch = true
    }

    /** Closing the search clears its text and the list returns (SPEC §11). */
    fun closeSearch() {
        searching = false
        query.clearText()
    }

    /** "Search everything": the filters that narrow the results are set wide, where the toolbar shows it. */
    fun searchEverything() {
        viewModelScope.launch {
            app.preferences.setSource(Source.All)
            app.preferences.setUnreadOnly(false)
            app.preferences.setSavedOnly(false)
        }
    }

    /** The place of the search text in an article's own text, for its row. */
    suspend fun passage(articleId: Long, query: String) = app.search.passage(articleId, query)

    /** Refresh by the user: the source's feeds only (SPEC §4.8); the list is rebuilt. On a metered network it may wait for an answer first (§10.5). False when a refresh already runs. */
    fun refresh(): Boolean {
        rebuild()
        viewModelScope.launch {
            val ids = when (val current = app.preferences.source.first()) {
                Source.All -> null
                is Source.Feed -> listOf(current.id)
                is Source.Folder -> app.feeds.feeds.first().filter { it.folderId == current.id }.map { it.id }
            }
            app.refreshRequests.request(ids)
        }
        return app.refresher.progress.value == null
    }

    fun setRead(ids: List<Long>, read: Boolean) {
        viewModelScope.launch { ids.chunked(500).forEach { app.articles.setRead(it, read) } }
    }

    /** Every unread article the list shows becomes read; their ids, for Undo. */
    fun markAllRead(): List<Long> {
        val ids = list.value?.rows.orEmpty().filter { !it.article.read }.map { it.article.id }
        setRead(ids, true)
        return ids
    }
}
