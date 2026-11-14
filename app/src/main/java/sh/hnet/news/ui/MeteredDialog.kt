@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.news.R
import sh.hnet.news.ui.components.RowIcon
import sh.hnet.news.ui.components.groupShapes

/**
 * "Refresh on a metered network?" (SPEC §10.5): everything, text only, or not now; asked once per
 * refresh, over whatever page caused it. The two ways to go on are rows that act at a tap, each
 * saying what it fetches, instead of the SPEC's three stacked text buttons (the owner found those
 * a list without order); Cancel is the dialog's one button.
 */
@Composable
fun MeteredDialog(onEverything: () -> Unit, onTextOnly: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_metered_refresh)) },
        text = {
            Column {
                Text(stringResource(R.string.msg_metered_refresh), modifier = Modifier.padding(bottom = 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Choice(groupShapes(0, 2), R.drawable.ic_image, stringResource(R.string.label_fetch_everything), stringResource(R.string.desc_fetch_everything), onEverything)
                    Choice(groupShapes(1, 2), R.drawable.ic_notes, stringResource(R.string.label_fetch_text_only), stringResource(R.string.desc_fetch_text_only), onTextOnly)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}

/** One way to go on: a segment a tone apart from the dialog, which sits on surfaceContainerHigh. */
@Composable
private fun Choice(shapes: ListItemShapes, @DrawableRes icon: Int, title: String, supporting: String, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { RowIcon(icon) },
        supportingContent = { Text(supporting) },
    ) {
        Text(title)
    }
}
