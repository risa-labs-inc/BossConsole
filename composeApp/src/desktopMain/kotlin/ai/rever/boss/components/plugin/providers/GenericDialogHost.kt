package ai.rever.boss.components.plugin.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import ai.rever.boss.plugin.api.DialogButton
import ai.rever.boss.plugin.api.DialogChoiceItem

/**
 * Host composable for generic dialogs.
 *
 * Add this to your top-level UI to enable plugin dialogs.
 * It observes the GenericDialogProviderImpl state and renders appropriate dialogs.
 */
@Composable
fun GenericDialogHost() {
    val provider = GenericDialogProviderImpl.getInstance()
    val dialogRequest by provider.currentDialog.collectAsState()

    when (val request = dialogRequest) {
        is DialogRequest.TextInput -> TextInputDialog(request)
        is DialogRequest.Confirmation -> ConfirmationDialog(request)
        is DialogRequest.SingleChoice -> SingleChoiceDialog(request)
        is DialogRequest.MultiChoice -> MultiChoiceDialog(request)
        is DialogRequest.Alert -> AlertDialogWrapper(request)
        is DialogRequest.ThreeButton -> ThreeButtonDialog(request)
        is DialogRequest.Progress -> ProgressDialog(request)
        null -> { /* No dialog to show */ }
    }
}

@Composable
private fun TextInputDialog(request: DialogRequest.TextInput) {
    var text by remember { mutableStateOf(request.initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = { request.result.complete(null) },
        title = { Text(request.title) },
        text = {
            Column {
                if (request.message != null) {
                    Text(request.message)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { newValue ->
                        text = newValue
                        error = request.validation?.invoke(newValue)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    label = { Text(request.placeholder) },
                    isError = error != null,
                    singleLine = true
                )
                error?.let { errorText ->
                    Text(
                        text = errorText,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validationError = request.validation?.invoke(text)
                    if (validationError == null) {
                        request.result.complete(text)
                    } else {
                        error = validationError
                    }
                },
                enabled = error == null
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = { request.result.complete(null) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ConfirmationDialog(request: DialogRequest.Confirmation) {
    AlertDialog(
        onDismissRequest = { request.result.complete(false) },
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            Button(
                onClick = { request.result.complete(true) },
                colors = if (request.isDestructive) {
                    ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(request.confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = { request.result.complete(false) }) {
                Text(request.cancelText)
            }
        }
    )
}

@Composable
private fun SingleChoiceDialog(request: DialogRequest.SingleChoice) {
    var selectedIndex by remember { mutableStateOf(request.selectedIndex) }

    AlertDialog(
        onDismissRequest = { request.result.complete(null) },
        title = { Text(request.title) },
        text = {
            Column {
                if (request.message != null) {
                    Text(request.message)
                    Spacer(Modifier.height(12.dp))
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    itemsIndexed(request.choices) { index, choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(choice.label, style = MaterialTheme.typography.body1)
                                choice.description?.let { desc ->
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedIndex >= 0 && selectedIndex < request.choices.size) {
                        request.result.complete(request.choices[selectedIndex])
                    } else {
                        request.result.complete(null)
                    }
                },
                enabled = selectedIndex >= 0
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = { request.result.complete(null) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MultiChoiceDialog(request: DialogRequest.MultiChoice) {
    val selectedItems = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        selectedItems.addAll(request.choices.filter { it.isSelected }.map { it.id })
    }

    AlertDialog(
        onDismissRequest = { request.result.complete(null) },
        title = { Text(request.title) },
        text = {
            Column {
                if (request.message != null) {
                    Text(request.message)
                    Spacer(Modifier.height(12.dp))
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    itemsIndexed(request.choices) { _, choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedItems.contains(choice.id)) {
                                        selectedItems.remove(choice.id)
                                    } else {
                                        selectedItems.add(choice.id)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedItems.contains(choice.id),
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedItems.add(choice.id)
                                    } else {
                                        selectedItems.remove(choice.id)
                                    }
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(choice.label, style = MaterialTheme.typography.body1)
                                choice.description?.let { desc ->
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = request.choices.map { choice ->
                        DialogChoiceItem(
                            id = choice.id,
                            label = choice.label,
                            description = choice.description,
                            isSelected = selectedItems.contains(choice.id)
                        )
                    }
                    request.result.complete(result)
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = { request.result.complete(null) }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AlertDialogWrapper(request: DialogRequest.Alert) {
    AlertDialog(
        onDismissRequest = { request.result.complete(Unit) },
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            Button(onClick = { request.result.complete(Unit) }) {
                Text(request.buttonText)
            }
        }
    )
}

@Composable
private fun ThreeButtonDialog(request: DialogRequest.ThreeButton) {
    AlertDialog(
        onDismissRequest = { request.result.complete(DialogButton.CANCELLED) },
        title = { Text(request.title) },
        text = { Text(request.message) },
        buttons = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = { request.result.complete(DialogButton.NEUTRAL) }) {
                    Text(request.neutralText)
                }
                OutlinedButton(onClick = { request.result.complete(DialogButton.NEGATIVE) }) {
                    Text(request.negativeText)
                }
                Button(onClick = { request.result.complete(DialogButton.POSITIVE) }) {
                    Text(request.positiveText)
                }
            }
        }
    )
}

@Composable
private fun ProgressDialog(request: DialogRequest.Progress) {
    val progress by request.handle.progress.collectAsState()
    val message by request.handle.message.collectAsState()

    AlertDialog(
        onDismissRequest = {
            if (request.cancellable) {
                request.handle.cancel()
                request.handle.dismiss()
            }
        },
        title = { Text(request.title) },
        text = {
            Column {
                Text(message.ifEmpty { request.message })
                Spacer(Modifier.height(16.dp))
                if (request.isIndeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (request.cancellable) {
                TextButton(onClick = {
                    request.handle.cancel()
                    request.handle.dismiss()
                }) {
                    Text("Cancel")
                }
            }
        }
    )
}
