@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.viewer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.ui.components.TooltipIconButton
import java.io.File

/**
 * An article's images full screen (SPEC §5.6): ComfyChair's viewer with its zoom ([zoomable]) and
 * its swipe down to close ([viewerSwipeToDismiss]) – a pager over the images, Back at the top
 * start, a counter when there are several, a tap toggles the overlays. Of ComfyChair's actions
 * News keeps Save (a copy into the phone's Pictures) and Share. Everything is read from the phone.
 */
@Composable
fun ImageViewerScreen(urls: List<String>, start: Int, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(start.coerceIn(0, urls.lastIndex)) { urls.size }
    val dismiss = rememberViewerDismissState()
    var overlays by remember { mutableStateOf(true) }
    val overlaysShown = overlays && !dismiss.active
    val window = LocalWindowInfo.current.containerSize
    // Twice the screen, so zooming in still has pixels to show
    val widthPx = (maxOf(window.width, window.height) * 2).coerceAtMost(4096)
    val saved = stringResource(R.string.msg_image_saved)
    val notSaved = stringResource(R.string.msg_image_not_saved)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    fun close() {
        if (dismiss.closing) return
        scope.launch {
            dismiss.fadeOut()
            onClose()
        }
    }
    BackHandler { close() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = dismiss.backdropAlpha))
            .onSizeChanged { dismiss.containerHeight = it.height }
            .viewerSwipeToDismiss(state = dismiss, onStart = { false }, onClose = { close() }, onSettle = {}),
    ) {
        // The image, moved, scaled and rounded by the swipe (the gesture above reads untransformed positions)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = dismiss.offset
                val scale = 1f - 0.15f * dismiss.progress
                scaleX = scale
                scaleY = scale
                alpha = dismiss.contentAlpha
                shape = RoundedCornerShape(28.dp * dismiss.progress)
                clip = dismiss.progress > 0f
            },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { urls[it] }) { page ->
                val bitmap by produceState<Bitmap?>(null, urls[page], widthPx) { value = app.images.load(urls[page], widthPx) }
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    bitmap?.let { ImageViewer(it, onSingleTap = { overlays = !overlays }) }
                }
            }
        }

        if (urls.size > 1) {
            AnimatedVisibility(overlaysShown, enter = fadeIn(effects), exit = fadeOut(effects), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Text(stringResource(R.string.label_image_counter, pager.currentPage + 1, urls.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        // Back at the top start, centered with the counter chip
        AnimatedVisibility(overlaysShown, enter = fadeIn(effects), exit = fadeOut(effects), modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 10.dp, start = 16.dp)) {
            FilledTonalIconButton(
                onClick = { close() },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.content_description_back))
            }
        }
        AnimatedVisibility(
            overlaysShown, enter = fadeIn(effects), exit = fadeOut(effects),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().offset(y = -FloatingToolbarDefaults.ScreenOffset),
        ) {
            HorizontalFloatingToolbar(expanded = true) {
                TooltipIconButton(R.drawable.ic_download, stringResource(R.string.button_save_image), {
                    val file = app.images.file(urls[pager.currentPage])
                    scope.launch { onMessage(if (saveToPictures(context, file)) saved else notSaved) }
                })
                TooltipIconButton(R.drawable.ic_share, stringResource(R.string.button_share), {
                    val file = app.images.file(urls[pager.currentPage])
                    scope.launch { shareImage(context, file) }
                })
            }
        }
    }
}

/** The stored file's type, read from its first bytes: the store keeps no extensions. */
private fun mimeOf(file: File): String {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return options.outMimeType ?: "image/jpeg"
}

private fun extensionOf(mime: String): String = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"

/** A copy of the image in the phone's Pictures, in the app's folder; done on the user's explicit action only. */
private suspend fun saveToPictures(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
    try {
        val mime = mimeOf(file)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "news-${file.name.take(12)}.${extensionOf(mime)}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/News")
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return@withContext false
        true
    } catch (_: Exception) {
        false
    }
}

/** The system's share sheet with the image: a copy under the cache, with its extension, handed out by the app's file provider. */
private suspend fun shareImage(context: Context, file: File) {
    val (shared, mime) = withContext(Dispatchers.IO) {
        val mime = mimeOf(file)
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        // Only the image being shared is kept
        directory.listFiles()?.forEach { it.delete() }
        val copy = File(directory, "news-${file.name.take(12)}.${extensionOf(mime)}")
        file.copyTo(copy, overwrite = true)
        copy to mime
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", shared)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = android.content.ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null))
}
