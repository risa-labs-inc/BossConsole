package ai.rever.boss.components.dialogs

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkLibraryProvider
import ai.rever.boss.plugin.bookmark.BookmarkLibraryState
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/** One save/edit flow shared by tab menus and the sidebar. Saving never opens the target. */
@Composable
internal fun BookmarkEditorDialog(
    provider: BookmarkLibraryProvider,
    config: TabConfig,
    existing: Bookmark? = null,
    preferFavorite: Boolean? = null,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit = {},
) {
    val library by provider.state.collectAsState()
    val scope = rememberCoroutineScope()
    val form =
        remember(provider, config, existing?.id, preferFavorite) {
            BookmarkEditorState(provider, config, existing, preferFavorite, scope)
        }
    val creatingBookmark = existing == null && form.bookmarkId == null
    val addingFavorite = creatingBookmark && preferFavorite == true
    LaunchedEffect(library.ready) { form.initializeLoadedLibrary() }
    val save: (Boolean) -> Unit = { copy ->
        form.save(copy) {
            form.bookmarkId?.let(onSaved)
            StatusMessageManager.showMessage(if (form.favorite) "Saved to Favorites" else "Bookmark saved")
            onDismiss()
        }
    }
    BossDialog(
        onDismissRequest = { if (!form.busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = BossTheme.colors.panel,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 620.dp),
        ) {
            Column(
                Modifier.padding(20.dp).onPreviewKeyEvent {
                    val submitKey = it.key == Key.Enter && it.type == KeyEventType.KeyUp
                    val editingTarget = form.inputFocused && !form.creatingCollection
                    if (submitKey && editingTarget) {
                        save(false)
                        true
                    } else {
                        false
                    }
                },
            ) {
                Text(form.dialogTitle, style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (creatingBookmark) {
                        BookmarkQuickFields(form, config.type, library)
                    } else {
                        BookmarkTargetFields(form, config.type)
                        BookmarkCollectionFields(form, library)
                    }
                    BookmarkEditorMessages(form, library, save)
                }
                Spacer(Modifier.height(12.dp))
                BookmarkEditorActions(form, addingFavorite, save, onDismiss)
            }
        }
    }
}

@Composable
private fun BookmarkEditorActions(
    form: BookmarkEditorState,
    addingFavorite: Boolean,
    save: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(enabled = !form.busy, onClick = onDismiss) { Text("Cancel") }
        Button(enabled = form.canSave, onClick = { save(false) }) {
            Text(
                if (form.busy) {
                    "Saving…"
                } else if (addingFavorite) {
                    "Add"
                } else {
                    "Save"
                },
            )
        }
    }
}

@Composable
private fun BookmarkQuickFields(
    form: BookmarkEditorState,
    type: String,
    library: BookmarkLibraryState,
) {
    var expanded by remember { mutableStateOf(false) }
    BookmarkNameField(form)
    if (!expanded) {
        Text(
            bookmarkTypeDescription(type) + " · " + form.target.ifBlank { "Default startup folder" },
            style = MaterialTheme.typography.caption.copy(lineHeight = 18.sp),
            color = BossTheme.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (type == "terminal" && form.command.isNotBlank()) {
            Text(
                "Startup command: " + form.command,
                style = MaterialTheme.typography.caption.copy(lineHeight = 18.sp),
                color = BossTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    TextButton(
        enabled = !form.busy,
        onClick = { expanded = !expanded },
        colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
    ) {
        Text(if (expanded) "Hide options" else "More options")
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
        )
    }
    if (expanded) {
        BookmarkTargetFields(form, type, includeName = false)
        BookmarkCollectionFields(form, library, showFavorite = false)
    }
}

@Composable
private fun BookmarkNameField(form: BookmarkEditorState) {
    OutlinedTextField(
        form.name,
        { form.name = it },
        label = { Text("Name") },
        singleLine = true,
        enabled = !form.busy,
        modifier = Modifier.fillMaxWidth().onFocusChanged { form.inputFocused = it.isFocused },
    )
}

@Composable
private fun BookmarkTargetFields(
    form: BookmarkEditorState,
    type: String,
    includeName: Boolean = true,
) {
    if (includeName) BookmarkNameField(form)
    OutlinedTextField(
        form.target,
        { form.target = it },
        label = { Text(bookmarkTargetLabel(type)) },
        singleLine = true,
        enabled = !form.busy,
        modifier = Modifier.fillMaxWidth().onFocusChanged { form.inputFocused = it.isFocused },
    )
    if (type == "terminal") {
        OutlinedTextField(
            form.command,
            { form.command = it },
            label = { Text("Startup command (optional)") },
            singleLine = true,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth().onFocusChanged { form.inputFocused = it.isFocused },
        )
        Text(
            "Opens a new terminal. Running processes and terminal history are not saved.",
            style = MaterialTheme.typography.caption.copy(lineHeight = 18.sp),
            color = BossTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun BookmarkCollectionFields(
    form: BookmarkEditorState,
    library: BookmarkLibraryState,
    showFavorite: Boolean = true,
) {
    BookmarkCollectionPicker(
        collections =
            library.collections.map {
                it.id to if (it.id in library.unfiledCollectionIds) "No folder" else it.name
            },
        selectedId = form.collectionId,
        enabled = !form.busy,
        onSelect = { form.collectionId = it },
        onCreate = { form.creatingCollection = true },
    )
    if (form.creatingCollection) {
        OutlinedTextField(
            form.collectionName,
            { form.collectionName = it },
            label = { Text("New folder name") },
            singleLine = true,
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = !form.busy && form.collectionName.isNotBlank(), onClick = form::createCollection) {
            Text("Create folder")
        }
        TextButton(enabled = !form.busy, onClick = { form.creatingCollection = false }) {
            Text("Cancel new folder")
        }
    }
    if (showFavorite) {
        Row(
            modifier =
                Modifier.fillMaxWidth().toggleable(
                    value = form.favorite,
                    enabled = !form.busy,
                    role = Role.Checkbox,
                    onValueChange = { form.favorite = it },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(form.favorite, onCheckedChange = null, enabled = !form.busy)
            Text("Show in Favorites")
        }
        Text(
            "Favorites appear in the sidebar. All saved items remain in Bookmarks.",
            style = MaterialTheme.typography.caption.copy(lineHeight = 18.sp),
            color = BossTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun BookmarkEditorMessages(
    form: BookmarkEditorState,
    library: BookmarkLibraryState,
    save: (Boolean) -> Unit,
) {
    (form.error ?: form.problem ?: library.error)?.let { Text(it, color = MaterialTheme.colors.error) }
    if (!library.ready || form.error != null || library.error != null) {
        TextButton(enabled = !form.busy, onClick = form::reload) { Text("Reload library") }
    }
    if (form.duplicateId != null) {
        TextButton(enabled = !form.busy, onClick = form::editDuplicate) { Text("Edit existing bookmark") }
        TextButton(enabled = form.canSave, onClick = { save(true) }) { Text("Save a separate copy") }
    }
}

private fun bookmarkTypeDescription(type: String): String =
    when (type) {
        "browser" -> "Website bookmark"
        "terminal" -> "Terminal shortcut"
        "editor" -> "File bookmark"
        "jupyter" -> "Notebook bookmark"
        "diff" -> "Working-tree diff bookmark"
        "composer" -> "Composer session bookmark"
        else -> "Saved shortcut"
    }

private fun bookmarkTargetLabel(type: String): String =
    when (type) {
        "browser" -> "Website address"
        "terminal" -> "Startup folder (optional)"
        "diff" -> "File path within saved project"
        "composer" -> "Session ID"
        else -> "File path"
    }
