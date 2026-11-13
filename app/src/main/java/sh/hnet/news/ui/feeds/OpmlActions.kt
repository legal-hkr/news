package sh.hnet.news.ui.feeds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.news.NewsApplication
import sh.hnet.news.R
import sh.hnet.news.data.ImportPlan
import sh.hnet.news.feed.Opml
import sh.hnet.news.ui.components.ErrorDetails
import sh.hnet.news.ui.components.ErrorDetailsDialog
import sh.hnet.news.ui.components.reportFailure
import java.time.Instant

/** Import and export of subscriptions (SPEC §9), for the pages that offer them. */
class OpmlActions(val import: () -> Unit, val export: () -> Unit)

/** A larger file is not a list of subscriptions. */
private const val MAX_OPML = 16 * 1024 * 1024

/**
 * The two OPML actions with everything they show: the system's file pickers, the summary before an
 * import changes anything, the result in a snackbar, the error with its details.
 */
@Composable
fun rememberOpmlActions(snackbars: SnackbarHostState, messageScope: CoroutineScope): OpmlActions {
    val context = LocalContext.current
    val app = context.applicationContext as NewsApplication
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var error by remember { mutableStateOf<ErrorDetails?>(null) }
    val notOpml = stringResource(R.string.error_not_opml)
    val exportFailed = stringResource(R.string.error_export_failed)
    val show = stringResource(R.string.button_show)
    val imported = stringResource(R.string.msg_imported)
    val exported = stringResource(R.string.msg_exported)
    val title = stringResource(R.string.app_name)

    fun say(text: String) = messageScope.launch {
        snackbars.currentSnackbarData?.dismiss()
        snackbars.showSnackbar(text, duration = SnackbarDuration.Short)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        messageScope.launch {
            try {
                val feeds = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_OPML + 1) } ?: throw java.io.IOException("The file can't be opened")
                    if (bytes.size > MAX_OPML) throw java.io.IOException("The file is larger than $MAX_OPML bytes")
                    Opml.parse(bytes)
                }
                plan = app.feeds.plan(feeds)
            } catch (e: Exception) {
                reportFailure(snackbars, notOpml, e.toString(), show) { error = it }
            }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        messageScope.launch {
            try {
                val feeds = app.feeds.export()
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("The file can't be written")
                    stream.use { it.write(Opml.write(feeds, title, Instant.now()).toByteArray()) }
                }
                say(exported.format(feeds.size))
            } catch (e: Exception) {
                reportFailure(snackbars, exportFailed, e.toString(), show) { error = it }
            }
        }
    }

    plan?.let { current ->
        val nothingNew = current.new.isEmpty()
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text(stringResource(R.string.title_import_opml)) },
            text = {
                Text(
                    if (nothingNew) stringResource(R.string.msg_import_nothing_new, current.all)
                    else stringResource(R.string.msg_import_summary, current.all, current.folders, current.skipped),
                )
            },
            dismissButton = if (nothingNew) null else {
                { TextButton(onClick = { plan = null }) { Text(stringResource(R.string.button_cancel)) } }
            },
            confirmButton = {
                if (nothingNew) {
                    TextButton(onClick = { plan = null }) { Text(stringResource(R.string.button_close)) }
                } else {
                    TextButton(onClick = {
                        plan = null
                        messageScope.launch { say(imported.format(app.feeds.import(current))) }
                    }) { Text(stringResource(R.string.button_import)) }
                }
            },
        )
    }
    error?.let { ErrorDetailsDialog(it) { error = null } }

    return remember(picker, saver) {
        // OPML has no one media type that file providers agree on, so any file can be picked; what isn't OPML is refused on reading
        OpmlActions(import = { picker.launch(arrayOf("*/*")) }, export = { saver.launch("news-subscriptions.opml") })
    }
}
