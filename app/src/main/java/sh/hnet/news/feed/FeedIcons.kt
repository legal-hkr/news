package sh.hnet.news.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import org.jsoup.Jsoup
import sh.hnet.news.net.FetchFailure
import sh.hnet.news.net.HttpFetcher
import sh.hnet.news.net.HttpLimits
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI

/**
 * A feed's icon (SPEC §6.3), fetched once at subscribing from the feed's own site and kept as a
 * file. The site's icon is tried before the feed's own picture: what RSS calls `image` is usually a
 * wide logo, which makes a poor square. A picture more than half again as wide as tall is passed over.
 */
class FeedIcons(private val context: Context, private val http: HttpFetcher) {
    private val directory: File get() = File(context.filesDir, "icons").apply { mkdirs() }

    fun file(feedId: Long): File = File(directory, "$feedId.png")

    fun delete(feedId: Long) {
        file(feedId).delete()
    }

    /** Fetches and stores the icon; true when one was stored. Never throws: a feed without an icon gets the stand-in. */
    suspend fun fetch(feedId: Long, feedUrl: String, siteUrl: String?, feedIconUrl: String?): Boolean {
        val site = siteUrl ?: origin(feedUrl) ?: return false
        val candidates = buildList {
            addAll(siteIcons(site))
            origin(site)?.let { add("$it/favicon.ico") }
            feedIconUrl?.let { add(it) }
        }.distinct()
        for (address in candidates) {
            val bitmap = try {
                val response = http.get(address, HttpLimits.ICON)
                BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
            } catch (_: FetchFailure) {
                null
            } ?: continue
            if (bitmap.width < MIN || bitmap.height < MIN || bitmap.width > bitmap.height * 3 / 2 || bitmap.height > bitmap.width * 3 / 2) continue
            val factor = SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
            val stored = if (factor < 1f) bitmap.scale((bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1)) else bitmap
            file(feedId).outputStream().use { stored.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return true
        }
        return false
    }

    /** The icons the site's page names, the largest first. */
    private suspend fun siteIcons(site: String): List<String> {
        val response = try {
            http.get(site, HttpLimits.DOCUMENT)
        } catch (_: FetchFailure) {
            return emptyList()
        }
        return IconLinks.find(Jsoup.parse(ByteArrayInputStream(response.body), null, response.url).outerHtml(), response.url)
    }

    private fun origin(address: String): String? = try {
        val uri = URI(address)
        if (uri.scheme != null && uri.rawAuthority != null) "${uri.scheme}://${uri.rawAuthority}" else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Stored at most this large: three times the 40 dp box at the densest screens' 96 px is plenty. */
        const val SIZE = 144
        const val MIN = 16
    }
}

/** The icons a page names in its head, the largest declared size first; vector icons are left out (not decodable here). */
object IconLinks {
    fun find(html: String, url: String): List<String> =
        Jsoup.parse(html, url).select("link[href]").mapNotNull { link ->
            val rel = link.attr("rel").lowercase().split(Regex("\\s+"))
            if ("icon" !in rel && "apple-touch-icon" !in rel && "apple-touch-icon-precomposed" !in rel) return@mapNotNull null
            val address = link.absUrl("href").takeIf { it.startsWith("http", ignoreCase = true) } ?: return@mapNotNull null
            if (link.attr("type").contains("svg") || address.substringBefore('?').endsWith(".svg", ignoreCase = true)) return@mapNotNull null
            // "32x32", "180x180", "any"; an Apple touch icon without sizes is 180 by convention
            val size = Regex("""(\d+)x\d+""").find(link.attr("sizes"))?.groupValues?.get(1)?.toIntOrNull() ?: if ("icon" in rel) 16 else 180
            address to size
        }.sortedByDescending { it.second }.map { it.first }.distinct()
}
