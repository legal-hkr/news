@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.hnet.news.R
import sh.hnet.news.TEXT_SIZES
import kotlin.math.roundToInt

/**
 * The reader's text size and typeface (SPEC §5.5), the same controls in the reader's sheet and on
 * Settings › Reading: "Size" with its value and a slider of five stops between a small and a large
 * "A", "Typeface" as two connected toggles.
 */
@Composable
fun TextControls(size: Int, serif: Boolean, onSize: (Int) -> Unit, onSerif: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sizeLabel = stringResource(R.string.label_size)
        val percent = stringResource(R.string.label_percent, size)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(sizeLabel, style = MaterialTheme.typography.bodyLarge)
            Text(percent, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val stop = TEXT_SIZES.indexOf(size).coerceAtLeast(0)
        val slider = rememberSliderState(stop.toFloat(), TEXT_SIZES.size - 2, 0f..(TEXT_SIZES.size - 1).toFloat())
        // A value changed elsewhere (a restore) moves the thumb
        LaunchedEffect(stop) { if (slider.value.roundToInt() != stop) slider.value = stop.toFloat() }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
            Slider(
                state = slider,
                onValueChange = {
                    slider.value = it
                    if (it.roundToInt() != stop) onSize(TEXT_SIZES[it.roundToInt().coerceIn(0, TEXT_SIZES.lastIndex)])
                },
                modifier = Modifier.weight(1f).semantics { contentDescription = sizeLabel; stateDescription = percent },
            )
            Text("A", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
        }
        Choice(stringResource(R.string.label_typeface), listOf(false to R.string.label_sans_serif, true to R.string.label_serif), serif, onSerif)
    }
}

/** One of two, as connected toggles under their label. */
@Composable
private fun Choice(label: String, options: List<Pair<Boolean, Int>>, selected: Boolean, onSelect: (Boolean) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    Row(
        Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (value, name) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { if (it) onSelect(value) },
                shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.weight(1f).height(40.dp).semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(name), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Text" from the reader's toolbar: changes apply to the article behind the sheet as they are made. */
@Composable
fun TextSheet(size: Int, serif: Boolean, onSize: (Int) -> Unit, onSerif: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.title_text), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp).semantics { heading() })
            TextControls(size, serif, onSize, onSerif)
        }
    }
}
