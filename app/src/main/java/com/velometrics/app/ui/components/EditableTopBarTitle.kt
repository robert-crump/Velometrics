package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Rename-in-toolbar state shared by RepeatedRouteDetailScreen/RepeatedIntervalDetailScreen's
 * `TopAppBar`: an editable title that swaps to an `OutlinedTextField` while editing, committing
 * via IME "Done" or the checkmark action, discarding via re-sync whenever the underlying name
 * changes out from under an unedited title.
 */
class EditableTopBarTitleState internal constructor() {
    var isEditing by mutableStateOf(false)
        internal set
    var editName by mutableStateOf(TextFieldValue(""))
    val focusRequester = FocusRequester()

    fun startEditing(currentName: String) {
        editName = TextFieldValue(currentName, TextRange(currentName.length))
        isEditing = true
    }

    fun commit(onRename: (String) -> Unit) {
        onRename(editName.text)
        isEditing = false
    }
}

@Composable
fun rememberEditableTopBarTitleState(name: String?): EditableTopBarTitleState {
    val state = remember { EditableTopBarTitleState() }

    LaunchedEffect(name) {
        if (!state.isEditing) {
            name?.let { state.editName = TextFieldValue(it) }
        }
    }
    LaunchedEffect(state.isEditing) {
        if (state.isEditing) state.focusRequester.requestFocus()
    }

    return state
}

@Composable
fun EditableTopBarTitle(
    state: EditableTopBarTitleState,
    displayName: String,
    onRename: (String) -> Unit
) {
    if (state.isEditing) {
        OutlinedTextField(
            value = state.editName,
            onValueChange = { state.editName = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(state.focusRequester),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.commit(onRename) }),
            textStyle = MaterialTheme.typography.titleMedium
        )
    } else {
        Text(displayName)
    }
}

/**
 * @param extraActions additional actions shown only in the non-editing state, after the rename
 * button (e.g. RepeatedRouteDetailScreen's "Export GPX" — RepeatedIntervalDetailScreen passes none).
 */
@Composable
fun RowScope.EditableTopBarActions(
    state: EditableTopBarTitleState,
    currentName: String?,
    renameContentDescription: String,
    onRename: (String) -> Unit,
    extraActions: @Composable RowScope.() -> Unit = {}
) {
    if (state.isEditing) {
        IconButton(onClick = { state.commit(onRename) }) {
            Icon(Icons.Default.Check, contentDescription = "Save name")
        }
    } else {
        IconButton(onClick = { state.startEditing(currentName ?: "") }) {
            Icon(Icons.Default.Edit, contentDescription = renameContentDescription)
        }
        extraActions()
    }
}
