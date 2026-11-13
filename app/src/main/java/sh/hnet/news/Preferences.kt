package sh.hnet.news

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("settings")

/** How an article's row looks (SPEC §4.10); the looks differ only by the image. */
enum class Look { TEXT, THUMBNAIL, LARGE }

/** What the list is narrowed to (SPEC §3.2). */
sealed interface Source {
    data object All : Source
    data class Folder(val id: Long) : Source
    data class Feed(val id: Long) : Source

    fun encode(): String = when (this) {
        All -> "all"
        is Folder -> "folder:$id"
        is Feed -> "feed:$id"
    }

    companion object {
        fun decode(text: String?): Source {
            val id = text?.substringAfter(':', "")?.toLongOrNull()
            return when {
                id != null && text.startsWith("folder:") -> Folder(id)
                id != null && text.startsWith("feed:") -> Feed(id)
                else -> All
            }
        }
    }
}

/** When and how the app refreshes by itself (SPEC §14.5). [intervalHours] 0 is off. */
data class RefreshSettings(val intervalHours: Int = 3, val unmeteredOnly: Boolean = true, val chargingOnly: Boolean = false, val onOpen: Boolean = true)

/** The last refresh as the list's subtitle tells it (SPEC §4.8). [offline]: a later attempt found no network. */
data class LastRefresh(val at: Long, val new: Int, val offline: Boolean)

val REFRESH_INTERVALS = listOf(0, 1, 3, 6, 12, 24)
const val MAX_PREVIEW_LINES = 3
/** The reader's text sizes in percent (SPEC §5.5). */
val TEXT_SIZES = listOf(85, 100, 115, 130, 150)

/** Choices the user made in the UI that outlive the process (app-private, not backed up by the system). Every stage adds its settings here. */
class Preferences(private val context: Context) {
    private val sourceKey = stringPreferencesKey("source")
    private val unreadOnlyKey = booleanPreferencesKey("unread_only")
    private val oldestFirstKey = booleanPreferencesKey("oldest_first")
    private val ownOrderKey = booleanPreferencesKey("own_feed_order")
    private val textSizeKey = intPreferencesKey("reader_text_size")
    private val serifKey = booleanPreferencesKey("reader_serif")
    private val markOnScrollKey = booleanPreferencesKey("mark_read_on_scroll")
    private val lookKey = stringPreferencesKey("look")
    private val previewLinesKey = intPreferencesKey("preview_lines")
    private val intervalKey = intPreferencesKey("refresh_interval_hours")
    private val unmeteredKey = booleanPreferencesKey("refresh_unmetered_only")
    private val chargingKey = booleanPreferencesKey("refresh_charging_only")
    private val onOpenKey = booleanPreferencesKey("refresh_on_open")
    private val lastRefreshAtKey = longPreferencesKey("last_refresh_at")
    private val lastRefreshNewKey = intPreferencesKey("last_refresh_new")
    private val lastRefreshOfflineKey = booleanPreferencesKey("last_refresh_offline")

    /** The list's filters stay as the user set them, across starts too (SPEC §3.2). */
    val source: Flow<Source> = context.settings.data.map { Source.decode(it[sourceKey]) }.distinctUntilChanged()

    suspend fun setSource(source: Source) = context.settings.edit { it[sourceKey] = source.encode() }

    val unreadOnly: Flow<Boolean> = context.settings.data.map { it[unreadOnlyKey] ?: true }.distinctUntilChanged()

    suspend fun setUnreadOnly(on: Boolean) = context.settings.edit { it[unreadOnlyKey] = on }

    val oldestFirst: Flow<Boolean> = context.settings.data.map { it[oldestFirstKey] ?: false }.distinctUntilChanged()

    suspend fun setOldestFirst(on: Boolean) = context.settings.edit { it[oldestFirstKey] = on }

    /** Feeds and folders in the user's own order rather than by name (SPEC §6.6). */
    val ownOrder: Flow<Boolean> = context.settings.data.map { it[ownOrderKey] ?: false }.distinctUntilChanged()

    suspend fun setOwnOrder(on: Boolean) = context.settings.edit { it[ownOrderKey] = on }

    /** The reader's text size in percent, one of [TEXT_SIZES] (SPEC §5.5). */
    val textSize: Flow<Int> = context.settings.data.map { prefs -> (prefs[textSizeKey] ?: 100).takeIf { it in TEXT_SIZES } ?: 100 }.distinctUntilChanged()

    suspend fun setTextSize(percent: Int) = context.settings.edit { it[textSizeKey] = percent }

    val serif: Flow<Boolean> = context.settings.data.map { it[serifKey] ?: false }.distinctUntilChanged()

    suspend fun setSerif(on: Boolean) = context.settings.edit { it[serifKey] = on }

    /** A row is marked read when it leaves the list's top edge during a scroll by the user (SPEC §4.7). */
    val markOnScroll: Flow<Boolean> = context.settings.data.map { it[markOnScrollKey] ?: false }.distinctUntilChanged()

    suspend fun setMarkOnScroll(on: Boolean) = context.settings.edit { it[markOnScrollKey] = on }

    /** The app's default look of rows; a feed can have its own (SPEC §4.10). */
    val look: Flow<Look> = context.settings.data.map { prefs -> Look.entries.firstOrNull { it.name == prefs[lookKey] } ?: Look.THUMBNAIL }.distinctUntilChanged()

    suspend fun setLook(look: Look) = context.settings.edit { it[lookKey] = look.name }

    val previewLines: Flow<Int> = context.settings.data.map { (it[previewLinesKey] ?: 0).coerceIn(0, MAX_PREVIEW_LINES) }.distinctUntilChanged()

    suspend fun setPreviewLines(lines: Int) = context.settings.edit { it[previewLinesKey] = lines.coerceIn(0, MAX_PREVIEW_LINES) }

    val refresh: Flow<RefreshSettings> = context.settings.data.map { prefs ->
        RefreshSettings(
            intervalHours = (prefs[intervalKey] ?: 3).takeIf { it in REFRESH_INTERVALS } ?: 3,
            unmeteredOnly = prefs[unmeteredKey] ?: true,
            chargingOnly = prefs[chargingKey] ?: false,
            onOpen = prefs[onOpenKey] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun refreshSettings(): RefreshSettings = refresh.first()

    suspend fun setRefresh(settings: RefreshSettings) = context.settings.edit {
        it[intervalKey] = settings.intervalHours
        it[unmeteredKey] = settings.unmeteredOnly
        it[chargingKey] = settings.chargingOnly
        it[onOpenKey] = settings.onOpen
    }

    /** A memory, not a setting: it survives Restore defaults. */
    val lastRefresh: Flow<LastRefresh?> = context.settings.data.map { prefs ->
        prefs[lastRefreshAtKey]?.let { LastRefresh(it, prefs[lastRefreshNewKey] ?: 0, prefs[lastRefreshOfflineKey] ?: false) }
    }.distinctUntilChanged()

    suspend fun setLastRefresh(at: Long, new: Int) = context.settings.edit {
        it[lastRefreshAtKey] = at
        it[lastRefreshNewKey] = new
        it[lastRefreshOfflineKey] = false
    }

    /** An attempt found no network: the last refresh stays what it was, with that said (SPEC §4.9). */
    suspend fun setRefreshOffline() = context.settings.edit { if (it[lastRefreshAtKey] != null) it[lastRefreshOfflineKey] = true }

    /** Restore defaults (Settings › Reset): every setting back to its default, the list's filters included (SPEC §14.7). */
    suspend fun restoreDefaults() = context.settings.edit { prefs ->
        val at = prefs[lastRefreshAtKey]
        val new = prefs[lastRefreshNewKey]
        val offline = prefs[lastRefreshOfflineKey]
        prefs.clear()
        at?.let { prefs[lastRefreshAtKey] = it }
        new?.let { prefs[lastRefreshNewKey] = it }
        offline?.let { prefs[lastRefreshOfflineKey] = it }
    }
}
