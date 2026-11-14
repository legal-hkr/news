package sh.hnet.news

import sh.hnet.news.widget.Widgets
import sh.hnet.news.widget.WidgetRequest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.launch
import sh.hnet.news.about.WhatsNew
import sh.hnet.news.ui.NewsApp
import sh.hnet.news.ui.theme.NewsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            WhatsNew.recordStart(this)
            // Refresh when the app opens (SPEC §14.5), once per start of the activity
            val app = application as NewsApplication
            app.refreshRequests.onOpen()
        }
        if (savedInstanceState == null) share(intent)
        setContent {
            NewsTheme { NewsApp() }
        }
    }

    /** Whatever was read, added or moved while the app was in front shows on the widgets when it leaves (SPEC-widgets §3.6). */
    override fun onStop() {
        super.onStop()
        Widgets.update(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        share(intent)
    }

    /** What the activity was started for, beyond showing the app: a widget's request, or "Share" on a page in a browser (SPEC §7.3), where the first address in the shared text goes to Add a feed. */
    private fun share(intent: Intent?) {
        // A tap on a widget (SPEC-widgets §7.2)
        WidgetRequest.of(intent?.data)?.let {
            (application as NewsApplication).widgetRequest.value = it
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val address = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(it)?.value } ?: return
        (application as NewsApplication).sharedAddress.value = address
    }
}
