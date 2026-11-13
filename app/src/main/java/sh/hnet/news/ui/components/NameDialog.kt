package sh.hnet.news.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import sh.hnet.news.R

/**
 * A form dialog with one name field, as Music's and Clock's: the field focused with the keyboard
 * open and the current text all selected; Cancel and Save. The name is checked in the field
 * ([problem] gives the sentence shown under it, or null); an empty name can't be saved.
 */
@Composable
fun NameDialog(
    title: String,
    fieldLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    problem: @Composable (String) -> String? = { null },
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.text.trim()
    val error = if (name == initial) null else problem(name)
    val valid = name.isNotEmpty() && error == null
    val confirm = { if (valid) onConfirm(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= MAX_NAME) value = it },
                label = { Text(fieldLabel) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
        confirmButton = { TextButton(onClick = confirm, enabled = valid) { Text(stringResource(R.string.button_save)) } },
    )
}

const val MAX_NAME = 80
