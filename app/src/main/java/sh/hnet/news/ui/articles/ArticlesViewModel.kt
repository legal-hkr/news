package sh.hnet.news.ui.articles

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

/** What the list shows: its rows, and how many articles that pass the filters are held back (SPEC §4.7). */
class ArticleList(val rows: List<ArticleRow>, val held: Int)

/**
 * The one list and its filters (SPEC §3.2, §4.7). The list is a snapshot: which articles it shows is
 * decided when it is rebuilt – a filter changed, the user refreshed, the "New articles" pill was
 * tapped, the screen was left – and until then every one of those rows stays where it is, redrawn as
 * its state changes (read with "Unread only" on, still fetching, complete), while articles that
 * arrive meanwhile are only counted (§2.8: rows do not move under the thumb). An empty list fills by
 * itself, a list resting at its top takes arrivals directly (nothing is being read past them), and
 * with "Oldest first" new articles join the end, where they move nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticlesViewModel(private val app: NewsApplication) : ViewModel() {
    val source: StateFlow<Source?> = app.preferences.source.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val unreadOnly: StateFlow<Boolean?> = app.preferences.unreadOnly.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        app.preferences.unreadOnly,
        app.preferences.oldestFirst,
        rebuilds,
    ) { (source, all), unread, oldest, rebuild ->
        val passing = all.filter { !unread || !it.article.read }
        val taken = listOf(source, unread, oldest, rebuild)
        visible = when {
            taken != visibleFor || all.none { it.article.id in visible } -> passing.mapTo(HashSet()) { it.article.id }
            oldest || atTop -> visible + passing.map { it.article.id }
            else -> visible
        }
        visibleFor = taken
        val shown = all.filter { it.article.id in visible }
        ArticleList(if (oldest) shown.asReversed() else shown, passing.count { it.article.id !in visible })
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

    fun rebuild() {
        rebuilds.value++
    }

    fun setSource(source: Source) {
        viewModelScope.launch { app.preferences.setSource(source) }
    }

    fun setUnreadOnly(on: Boolean) {
        viewModelScope.launch { app.preferences.setUnreadOnly(on) }
    }

    /** Refresh by the user: the source's feeds only (SPEC §4.8); the list is rebuilt. False when a refresh already runs. */
    fun refresh(): Boolean {
        rebuild()
        viewModelScope.launch {
            val ids = when (val current = app.preferences.source.first()) {
                Source.All -> null
                is Source.Feed -> listOf(current.id)
                is Source.Folder -> app.feeds.feeds.first().filter { it.folderId == current.id }.map { it.id }
            }
            app.refresher.start(ids)
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
