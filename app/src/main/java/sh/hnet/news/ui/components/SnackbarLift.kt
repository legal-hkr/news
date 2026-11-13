package sh.hnet.news.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The height a showing snackbar takes at the bottom of the screen; a page's FAB rises by it. */
val LocalSnackbarLift = compositionLocalOf { 0.dp }

/** Room a list leaves at its end for the FAB over it. */
val FAB_CLEARANCE: Dp = 88.dp
