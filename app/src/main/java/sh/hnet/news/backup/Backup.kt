package sh.hnet.news.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * News's backup file (SPEC §14.6), shaped as Music's: the feeds and folders, the settings with the
 * list's filters, and the saved articles with their read state and their full text, so the archive
 * survives a move to another phone when a page is gone from the web. Not in it: images, articles
 * that are not saved, the app language. Pure Kotlin, so the format is unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
    val folders: List<BackupFolder> = emptyList(),
    val feeds: List<BackupFeed> = emptyList(),
    val saved: List<BackupArticle> = emptyList(),
) {
    companion object {
        const val FORMAT = "sh.hnet.news.backup"
        const val FORMAT_VERSION = 1
    }
}

/** Every setting may be absent (a backup from before it existed): a restore then leaves the default. Enum values by name. */
@Serializable
data class BackupSettings(
    /** The list's source: "all", "folder" with [sourceFolder], or "feed" with [sourceFeed] (its address). */
    val source: String? = null,
    val sourceFolder: String? = null,
    val sourceFeed: String? = null,
    val unreadOnly: Boolean? = null,
    val savedOnly: Boolean? = null,
    val oldestFirst: Boolean? = null,
    val ownFeedOrder: Boolean? = null,
    val look: String? = null,
    val previewLines: Int? = null,
    val textSize: Int? = null,
    val serif: Boolean? = null,
    val markReadOnScroll: Boolean? = null,
    val keepDays: Int? = null,
    val iconBadge: Boolean? = null,
    val refreshIntervalHours: Int? = null,
    val refreshUnmeteredOnly: Boolean? = null,
    val refreshChargingOnly: Boolean? = null,
    val refreshOnOpen: Boolean? = null,
)

@Serializable
data class BackupFolder(val name: String, val position: Int = 0, val collapsed: Boolean = false)

/** A subscription; [folder] by name. */
@Serializable
data class BackupFeed(
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val folder: String? = null,
    val position: Int = 0,
    val fullPage: Boolean = false,
    val contentChosen: Boolean = true,
    val storeImages: Boolean = true,
    val backgroundRefresh: Boolean = true,
    val ownLook: Boolean = false,
    val look: String? = null,
    val previewLines: Int = 0,
    val keepDays: Int? = null,
    val language: String? = null,
)

/**
 * A saved article. [feedUrl] is its feed's address when the feed is still subscribed; without one
 * the article comes back under [feedTitle] alone. The bodies are the blocks as stored.
 */
@Serializable
data class BackupArticle(
    val feedUrl: String? = null,
    val feedTitle: String = "",
    val guid: String,
    val title: String,
    val link: String? = null,
    val author: String? = null,
    /** Milliseconds since the epoch. */
    val published: Long,
    val read: Boolean = false,
    val preview: String? = null,
    val imageUrl: String? = null,
    val words: Int = 0,
    val feed: JsonElement? = null,
    val page: JsonElement? = null,
)

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version of News with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** A stored body (blocks in JSON) as part of the file; null for none or one that can't be read. */
    fun body(stored: String?): JsonElement? = stored?.let {
        try {
            Json.parseToJsonElement(it)
        } catch (_: SerializationException) {
            null
        }
    }

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        val version = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}
