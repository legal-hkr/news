@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import sh.hnet.news.R
import sh.hnet.news.about.WhatsNew
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.hnet.news.NewsApplication
import sh.hnet.news.ui.articles.ArticlesScreen
import sh.hnet.news.ui.articles.ArticlesViewModel
import sh.hnet.news.ui.settings.ArticleListSettingsScreen
import sh.hnet.news.ui.settings.ReadingSettingsScreen
import sh.hnet.news.ui.reader.ReaderScreen
import sh.hnet.news.ui.viewer.ImageViewerScreen
import sh.hnet.news.ui.settings.RefreshSettingsScreen
import sh.hnet.news.ui.components.LocalSnackbarLift
import sh.hnet.news.ui.feeds.AddFeedScreen
import sh.hnet.news.ui.feeds.FeedScreen
import sh.hnet.news.ui.feeds.FeedsScreen
import sh.hnet.news.ui.settings.AboutScreen
import sh.hnet.news.ui.settings.AppLicenseScreen
import sh.hnet.news.ui.settings.LanguageScreen
import sh.hnet.news.ui.settings.LicensesScreen
import sh.hnet.news.ui.settings.ResetScreen
import sh.hnet.news.ui.settings.SettingsRootScreen
import sh.hnet.news.ui.settings.WhatsNewScreen

/**
 * The app's shell (SPEC §3.1): one back stack with the article list at its root. There is no
 * navigation bar; Settings and everything else open over the list as pages, with Back.
 */
@Composable
fun NewsApp() {
    val context = LocalContext.current
    val stack = rememberNavBackStack(Screen.Articles)
    val app = context.applicationContext as NewsApplication
    // The list and its filters outlive the pages opened over it
    val articles: ArticlesViewModel = viewModel { ArticlesViewModel(app) }
    val snackbars = remember { SnackbarHostState() }
    // Outlives the page a message comes from (Unsubscribe closes its page before its snackbar ends)
    val scope = rememberCoroutineScope()

    // After an update, once: "Updated to x.y.z" with What's new (as ComfyChair, Music and Clock)
    val updatedTo = stringResource(R.string.whats_new_updated_snackbar)
    val whatsNew = stringResource(R.string.whats_new_title)
    val appContext = context.applicationContext
    LaunchedEffect(Unit) {
        val version = WhatsNew.takeUpdateNotice(appContext) ?: return@LaunchedEffect
        if (snackbars.showSnackbar(updatedTo.format(version), actionLabel = whatsNew, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            stack.add(Screen.Settings)
            stack.add(Screen.SettingsAbout)
            stack.add(Screen.SettingsWhatsNew)
        }
    }

    val motion = MaterialTheme.motionScheme
    val spatial: FiniteAnimationSpec<IntOffset> = motion.defaultSpatialSpec()
    val effects: FiniteAnimationSpec<Float> = motion.defaultEffectsSpec()
    val fastEffects: FiniteAnimationSpec<Float> = motion.fastEffectsSpec()
    val density = LocalDensity.current
    val shift = with(density) { 30.dp.roundToPx() }
    // A showing snackbar's height, which a page's FAB rises by
    var snackbarHeight by remember { mutableStateOf(0.dp) }
    val snackbarLift = if (snackbars.currentSnackbarData != null) snackbarHeight else 0.dp
    val subscribed = stringResource(R.string.msg_subscribed)

    // The image viewer lies over the reader rather than being a page: it is closed by fading, not by the pages' slide
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val open: (Screen) -> Unit = { stack.add(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = stack,
                onBack = back,
                // Shared axis X between the list and the pages over it (SPEC §3.4)
                transitionSpec = {
                    (slideInHorizontally(spatial) { shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { -shift } + fadeOut(fastEffects))
                },
                popTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(spatial) { -shift } + fadeIn(effects)) togetherWith
                        (slideOutHorizontally(spatial) { shift } + fadeOut(fastEffects))
                },
                entryProvider = entryProvider<NavKey> {
                    entry<Screen.Articles> {
                        ArticlesScreen(articles, onOpenSettings = { open(Screen.Settings) }, onOpenArticle = { open(Screen.Reader(it)) }, onAddFeed = { open(Screen.AddFeed) }, snackbars = snackbars, messageScope = scope)
                    }
                    entry<Screen.Reader> { key ->
                        ReaderScreen(articles, key.articleId, onBack = back, onShowImages = { urls, index -> if (urls.isNotEmpty()) viewer = urls to index }, snackbars = snackbars, messageScope = scope)
                    }
                    entry<Screen.Feeds> {
                        CompositionLocalProvider(LocalSnackbarLift provides snackbarLift) {
                            FeedsScreen(onBack = back, onAddFeed = { open(Screen.AddFeed) }, onOpenFeed = { open(Screen.Feed(it)) }, snackbars = snackbars, messageScope = scope)
                        }
                    }
                    entry<Screen.AddFeed> {
                        AddFeedScreen(
                            onBack = back,
                            // The feed's page takes this page's place: Back from it returns to where adding began
                            onOpenFeed = { id ->
                                back()
                                open(Screen.Feed(id))
                            },
                            onSubscribed = { name ->
                                back()
                                scope.launch {
                                    snackbars.currentSnackbarData?.dismiss()
                                    snackbars.showSnackbar(subscribed.format(name), duration = SnackbarDuration.Short)
                                }
                            },
                        )
                    }
                    entry<Screen.Feed> { key -> FeedScreen(key.id, onBack = back, snackbars = snackbars, messageScope = scope) }
                    entry<Screen.Settings> {
                        SettingsRootScreen(
                            onBack = back,
                            onOpenFeeds = { open(Screen.Feeds) },
                            onOpenRefresh = { open(Screen.SettingsRefresh) },
                            onOpenArticleList = { open(Screen.SettingsArticleList) },
                            onOpenReading = { open(Screen.SettingsReading) },
                            onOpenLanguage = { open(Screen.SettingsLanguage) },
                            onOpenReset = { open(Screen.SettingsReset) },
                            onOpenAbout = { open(Screen.SettingsAbout) },
                        )
                    }
                    entry<Screen.SettingsRefresh> { RefreshSettingsScreen(onBack = back) }
                    entry<Screen.SettingsArticleList> { ArticleListSettingsScreen(onBack = back) }
                    entry<Screen.SettingsReading> { ReadingSettingsScreen(onBack = back) }
                    entry<Screen.SettingsLanguage> { LanguageScreen(onBack = back) }
                    entry<Screen.SettingsReset> { ResetScreen(onBack = back) }
                    entry<Screen.SettingsAbout> {
                        AboutScreen(
                            onBack = back,
                            onOpenWhatsNew = { open(Screen.SettingsWhatsNew) },
                            onOpenLicense = { open(Screen.SettingsLicense) },
                            onOpenLicenses = { open(Screen.SettingsLicenses) },
                        )
                    }
                    entry<Screen.SettingsWhatsNew> { WhatsNewScreen(onBack = back) }
                    entry<Screen.SettingsLicense> { AppLicenseScreen(onBack = back) }
                    entry<Screen.SettingsLicenses> { LicensesScreen(onBack = back) }
                },
            )
            viewer?.let { (urls, index) ->
                ImageViewerScreen(urls, index, onClose = { viewer = null }) { text ->
                    scope.launch {
                        snackbars.currentSnackbarData?.dismiss()
                        snackbars.showSnackbar(text, duration = SnackbarDuration.Short)
                    }
                }
            }
            // One host for every page, above the system's navigation area; on the list, above its floating toolbar too
            // The reader has a floating toolbar of its own
            val onList = stack.lastOrNull().let { it == Screen.Articles || it is Screen.Reader }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (onList) 88.dp else 0.dp)) { data ->
                Snackbar(data, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 12.dp).onSizeChanged { snackbarHeight = with(density) { it.height.toDp() } })
            }
        }
    }
}
