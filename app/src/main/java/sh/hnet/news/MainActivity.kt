package sh.hnet.news

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
        setContent {
            NewsTheme { NewsApp() }
        }
    }
}
