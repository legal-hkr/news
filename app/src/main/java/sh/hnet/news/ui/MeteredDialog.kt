package sh.hnet.news.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import sh.hnet.news.R

/** "Refresh on a metered network?" (SPEC §10.5): everything, text only, or not now; asked once per refresh, over whatever page caused it. */
@Composable
fun MeteredDialog(onEverything: () -> Unit, onTextOnly: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_metered_refresh)) },
        text = { Text(stringResource(R.string.msg_metered_refresh)) },
        // Three actions with long names: stacked, the first one on top
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onClick = onEverything) { Text(stringResource(R.string.button_fetch_everything)) }
                TextButton(onClick = onTextOnly) { Text(stringResource(R.string.button_fetch_text_only)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
            }
        },
    )
}
