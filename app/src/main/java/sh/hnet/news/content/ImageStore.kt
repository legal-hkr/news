package sh.hnet.news.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.hnet.news.net.FetchFailure
import sh.hnet.news.net.HttpFetcher
import java.io.File
import java.security.MessageDigest

/**
 * The articles' images on the phone (SPEC §2.1): each fetched once at refresh and kept as the file
 * the site sent, named by its address's hash. Drawing reads only these files, never the network.
 */
class ImageStore(context: Context, private val http: HttpFetcher) {
    private val directory = File(context.filesDir, "images").apply { mkdirs() }
    /** Decoded pictures for the list, by address and size; a few screens' worth. */
    private val decoded = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun file(url: String): File = File(directory, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40))

    fun has(url: String): Boolean = file(url).isFile

    /** What fetching an image gave. */
    sealed interface Fetched {
        /** On the phone; its size in pixels. */
        class Stored(val width: Int, val height: Int) : Fetched
        /** Not reached this time: worth another try. */
        data object Failed : Fetched
        /** Nothing at the address, or not a picture the phone can draw: another try would give the same. */
        data object NotAPicture : Fetched
    }

    /** Fetches and stores an image unless it is there. */
    suspend fun fetchResult(url: String): Fetched = withContext(Dispatchers.IO) {
        val file = file(url)
        if (!file.isFile) {
            val body = try {
                http.get(url, MAX_BYTES).body
            } catch (_: FetchFailure.NotFound) {
                return@withContext Fetched.NotAPicture
            } catch (_: FetchFailure) {
                return@withContext Fetched.Failed
            }
            if (bounds(body) == null) return@withContext Fetched.NotAPicture
            // Written whole or not at all: a half-written file would pass for the image
            val partial = File(directory, file.name + ".part")
            try {
                partial.writeBytes(body)
            } catch (_: java.io.IOException) {
                // The phone's storage is full: not stored this time
                partial.delete()
                return@withContext Fetched.Failed
            }
            partial.renameTo(file)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth > 0 && options.outHeight > 0) Fetched.Stored(options.outWidth, options.outHeight) else Fetched.NotAPicture
    }

    /** As [fetchResult]; the image's size in pixels, or null when it is not on the phone. */
    suspend fun fetch(url: String): Pair<Int, Int>? = (fetchResult(url) as? Fetched.Stored)?.let { it.width to it.height }

    private fun bounds(body: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body, 0, body.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** The stored image scaled down to about [widthPx] wide, or null when it is not on the phone. */
    suspend fun load(url: String, widthPx: Int): Bitmap? {
        val key = "$widthPx $url"
        decoded.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = file(url)
            if (!file.isFile) return@withContext null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth <= 0) return@withContext null
            var sample = 1
            while (options.outWidth / (sample * 2) >= widthPx) sample *= 2
            // A very tall or very large picture (a long infographic) is taken down further: what cannot be drawn would end the app
            while (maxOf(options.outWidth, options.outHeight) / sample > MAX_SIDE || (options.outWidth.toLong() / sample) * (options.outHeight / sample) * 4 > MAX_DECODED) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { decoded.put(key, it) }
        }
    }

    fun delete(urls: Collection<String>) {
        urls.forEach { file(it).delete() }
    }

    /** Deletes every image but those of [urls] ("Clear images", SPEC §13.5). */
    fun keepOnly(urls: Collection<String>) {
        val kept = urls.mapTo(HashSet()) { file(it).name }
        directory.listFiles()?.forEach { if (it.name !in kept) it.delete() }
        decoded.evictAll()
    }

    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    fun bytesOf(urls: Collection<String>): Long = urls.sumOf { file(it).length() }

    private companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
        /** A decoded picture's longest side and size in memory: under what a texture and the canvas take (100 MB). */
        const val MAX_SIDE = 8192
        const val MAX_DECODED = 64L * 1024 * 1024
    }
}
