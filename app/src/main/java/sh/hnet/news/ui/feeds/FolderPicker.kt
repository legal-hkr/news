package sh.hnet.news.ui.feeds

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import sh.hnet.news.R
import sh.hnet.news.data.Folder
import sh.hnet.news.ui.components.NameDialog
import sh.hnet.news.ui.components.SingleChoiceDialog

/**
 * The folder choice (SPEC §8): "No folder", the folders, "New folder"; the last asks for the new
 * folder's name. A name already in use picks that folder.
 */
@Composable
fun FolderPicker(folders: List<Folder>, current: FolderChoice, onDismiss: () -> Unit, onPick: (FolderChoice) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    val sorted = folders.foldersByName()
    if (naming) {
        NameDialog(
            title = stringResource(R.string.label_new_folder),
            fieldLabel = stringResource(R.string.label_folder_name),
            initial = (current as? FolderChoice.New)?.name.orEmpty(),
            onDismiss = onDismiss,
        ) { name ->
            onPick(sorted.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { FolderChoice.Existing(it) } ?: FolderChoice.New(name))
        }
        return
    }
    // Indexes: 0 none, then the folders, last the new one
    val options = (0..sorted.size + 1).toList()
    val initial = when (current) {
        FolderChoice.None -> 0
        is FolderChoice.Existing -> sorted.indexOfFirst { it.id == current.folder.id } + 1
        is FolderChoice.New -> sorted.size + 1
    }
    val none = stringResource(R.string.label_no_folder)
    val new = stringResource(R.string.label_new_folder)
    SingleChoiceDialog(
        title = AnnotatedString(stringResource(R.string.label_folder)),
        options = options,
        initial = initial,
        label = { index -> if (index == 0) none else sorted.getOrNull(index - 1)?.name ?: new },
        confirmLabel = stringResource(R.string.button_ok),
        onConfirm = { index ->
            when {
                index == 0 -> onPick(FolderChoice.None)
                index <= sorted.size -> onPick(FolderChoice.Existing(sorted[index - 1]))
                else -> naming = true
            }
        },
        onDismiss = onDismiss,
    )
}
