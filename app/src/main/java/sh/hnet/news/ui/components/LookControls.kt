@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.news.Look
import sh.hnet.news.MAX_PREVIEW_LINES
import sh.hnet.news.R
import sh.hnet.news.data.ArticleRow
import sh.hnet.news.ui.articles.ArticleRowItem
import kotlin.math.roundToInt

/**
 * The look of article rows (SPEC §4.10), the same on Settings › Article list and on a feed's page:
 * "Look" as three connected toggles, "Preview text" as a slider of four stops with its value, and
 * a sample row drawn with the current values.
 *
 * @param sample The article shown as the sample; none before anything was fetched
 */
@Composable
fun LookControls(look: Look, previewLines: Int, onLook: (Look) -> Unit, onPreviewLines: (Int) -> Unit, sample: ArticleRow?, sampleTime: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val lookLabel = stringResource(R.string.label_look)
                Text(lookLabel, style = MaterialTheme.typography.bodyLarge)
                Row(
                    Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = lookLabel },
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    Look.entries.forEachIndexed { index, option ->
                        ToggleButton(
                            checked = option == look,
                            onCheckedChange = { if (it) onLook(option) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                Look.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
                        ) {
                            Text(stringResource(lookName(option)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val lines = pluralStringResource(R.plurals.label_preview_lines, previewLines, previewLines)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.label_preview_text), style = MaterialTheme.typography.bodyLarge)
                    Text(lines, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val previewLabel = stringResource(R.string.label_preview_text)
                val slider = rememberSliderState(previewLines.toFloat(), MAX_PREVIEW_LINES - 1, 0f..MAX_PREVIEW_LINES.toFloat())
                // A value changed elsewhere (the feed's switch, a restore) moves the thumb
                LaunchedEffect(previewLines) { if (slider.value.roundToInt() != previewLines) slider.value = previewLines.toFloat() }
                Slider(
                    state = slider,
                    onValueChange = {
                        slider.value = it
                        if (it.roundToInt() != previewLines) onPreviewLines(it.roundToInt())
                    },
                    modifier = Modifier.semantics { contentDescription = previewLabel; stateDescription = lines },
                )
            }
        }
        if (sample != null) {
            Column {
                SettingsGroupHeader(stringResource(R.string.title_sample))
                ArticleRowItem(sample, look, previewLines, sampleTime, MaterialTheme.shapes.large)
            }
        }
    }
}

fun lookName(look: Look): Int = when (look) {
    Look.TEXT -> R.string.label_look_text
    Look.THUMBNAIL -> R.string.label_look_thumbnail
    Look.LARGE -> R.string.label_look_large
}
