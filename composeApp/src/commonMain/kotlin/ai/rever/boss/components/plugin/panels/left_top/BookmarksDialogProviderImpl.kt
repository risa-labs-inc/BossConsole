package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.components.dialogs.BookmarkDialog
import ai.rever.boss.components.dialogs.CollectionSelectionDialog
import ai.rever.boss.components.dialogs.CollectionSelectionMode
import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.dialogs.NewCollectionDialog
import ai.rever.boss.components.dialogs.NewWorkspaceDialog
import ai.rever.boss.components.dialogs.RemoveBookmarkConfirmationDialog
import ai.rever.boss.components.dialogs.RenameDialog
import ai.rever.boss.components.dialogs.WorkspaceSelectionDialog
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.api.BookmarksDialogProvider
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.runtime.Composable

/**
 * Implementation of BookmarksDialogProvider that wraps composeApp's dialog implementations.
 *
 * This bridges the plugin's dialog interface to the existing composeApp dialogs,
 * allowing the bookmarks plugin to use the application's native dialog styling.
 */
object BookmarksDialogProviderImpl : BookmarksDialogProvider {

    @Composable
    override fun NewCollectionDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
        NewCollectionDialog(
            onDismiss = onDismiss,
            onCreate = onCreate
        )
    }

    @Composable
    override fun NewWorkspaceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
        NewWorkspaceDialog(
            onDismiss = onDismiss,
            onCreate = onCreate
        )
    }

    @Composable
    override fun ConfirmationDialog(
        title: String,
        message: String,
        confirmText: String,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit
    ) {
        ConfirmationDialog(
            title = title,
            message = message,
            icon = Icons.Outlined.DeleteSweep,
            iconTint = BossTheme.colors.alert,
            confirmText = confirmText,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }

    @Composable
    override fun RemoveBookmarkConfirmationDialog(
        bookmarkTitle: String,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit
    ) {
        RemoveBookmarkConfirmationDialog(
            bookmarkTitle = bookmarkTitle,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }

    @Composable
    override fun CollectionSelectionDialog(
        title: String,
        collections: List<BookmarkCollection>,
        excludeCollectionId: String,
        isMoveMode: Boolean,
        onDismiss: () -> Unit,
        onConfirm: (List<String>) -> Unit
    ) {
        CollectionSelectionDialog(
            title = title,
            collections = collections,
            excludeCollectionId = excludeCollectionId,
            mode = if (isMoveMode) CollectionSelectionMode.MOVE else CollectionSelectionMode.COPY,
            onDismiss = onDismiss,
            onConfirm = { selectedSet -> onConfirm(selectedSet.toList()) }
        )
    }

    @Composable
    override fun WorkspaceSelectionDialog(
        title: String,
        workspaces: List<LayoutWorkspace>,
        preselectedWorkspaces: Map<String, String?>,
        onDismiss: () -> Unit,
        onConfirm: (Map<String, String?>) -> Unit
    ) {
        WorkspaceSelectionDialog(
            title = title,
            workspaces = workspaces,
            preselectedWorkspaces = preselectedWorkspaces,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
    }

    @Composable
    override fun RenameDialog(
        title: String,
        currentName: String,
        label: String,
        onDismiss: () -> Unit,
        onRename: (String) -> Unit
    ) {
        RenameDialog(
            title = title,
            currentName = currentName,
            label = label,
            onDismiss = onDismiss,
            onRename = onRename
        )
    }

    @Composable
    override fun BookmarkDialog(
        tabTitle: String,
        collections: List<BookmarkCollection>,
        workspaces: List<LayoutWorkspace>,
        onDismiss: () -> Unit,
        onConfirm: (List<String>, Map<String, String?>) -> Unit
    ) {
        BookmarkDialog(
            tabTitle = tabTitle,
            collections = collections,
            workspaces = workspaces,
            onDismiss = onDismiss,
            onConfirm = { selectedSet, workspaceMap ->
                onConfirm(selectedSet.toList(), workspaceMap)
            }
        )
    }
}
