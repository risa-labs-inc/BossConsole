package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkError
import BossDarkTextPrimary
import BossDarkTextSecondary
import ai.rever.boss.git.GitFileStatus
import ai.rever.boss.git.GitFileStatusType
import ai.rever.boss.git.GitOperationResult
import ai.rever.boss.plugin.git.GitOperationResult.Success as GitSuccess
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError
import ai.rever.boss.git.GitService
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowGitState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * Commit Dialog for staging files and creating commits.
 *
 * Features:
 * - File list with checkboxes for staging/unstaging
 * - Commit message editor
 * - Amend previous commit option
 * - Sign-off option
 *
 * @param onDismiss Called when dialog is dismissed
 * @param onCommitSuccess Called when commit is successful with the commit message
 */
@Composable
fun CommitDialog(
    onDismiss: () -> Unit,
    onCommitSuccess: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val windowId = LocalWindowId.current
    // Use window-specific git state for independent per-window git UI
    val windowGitState = LocalWindowGitState.current
    val fileStatus by windowGitState?.fileStatus?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val isLoading by windowGitState?.isLoading?.collectAsState() ?: remember { mutableStateOf(false) }

    var commitMessage by remember { mutableStateOf("") }
    var amendCommit by remember { mutableStateOf(false) }
    var signOff by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }

    // Load file status and last commit message on open - using window-specific state
    LaunchedEffect(windowGitState) {
        GitService.getStatusForWindow(windowGitState)
    }

    // Load last commit message when amend is checked
    LaunchedEffect(amendCommit) {
        if (amendCommit) {
            val lastMessage = GitService.getLastCommitMessage()
            if (lastMessage != null && commitMessage.isEmpty()) {
                commitMessage = lastMessage
            }
        }
    }

    // Request focus on message field
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
    }

    val stagedFiles = fileStatus.filter { it.isStaged }
    val unstagedFiles = fileStatus.filter { it.isUnstaged || it.indexStatus == GitFileStatusType.UNTRACKED }
    val hasChangesToCommit = stagedFiles.isNotEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .heightIn(max = 700.dp),
            shape = RoundedCornerShape(8.dp),
            color = BossDarkBackground,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (amendCommit) "Amend Commit" else "Commit Changes",
                        color = BossDarkTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Close",
                            tint = BossDarkTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Commit message input
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = { commitMessage = it },
                    label = { Text("Commit message", color = BossDarkTextSecondary, fontSize = 12.sp) },
                    placeholder = { Text("Enter commit message...", color = BossDarkTextSecondary.copy(alpha = 0.5f)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = BossDarkTextPrimary,
                        cursorColor = BossDarkAccent,
                        focusedBorderColor = BossDarkAccent,
                        unfocusedBorderColor = BossDarkBorder,
                        backgroundColor = BossDarkBackground
                    ),
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Options row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Amend checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { amendCommit = !amendCommit }
                    ) {
                        Checkbox(
                            checked = amendCommit,
                            onCheckedChange = { amendCommit = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = BossDarkAccent,
                                uncheckedColor = BossDarkBorder
                            )
                        )
                        Text(
                            text = "Amend previous commit",
                            color = BossDarkTextPrimary,
                            fontSize = 12.sp
                        )
                    }

                    // Sign-off checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { signOff = !signOff }
                    ) {
                        Checkbox(
                            checked = signOff,
                            onCheckedChange = { signOff = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = BossDarkAccent,
                                uncheckedColor = BossDarkBorder
                            )
                        )
                        Text(
                            text = "Add sign-off",
                            color = BossDarkTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // File sections
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, BossDarkBorder, RoundedCornerShape(4.dp))
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Staged files section
                        if (stagedFiles.isNotEmpty()) {
                            item {
                                CommitFileSectionHeader(
                                    title = "Staged Changes",
                                    count = stagedFiles.size,
                                    actionText = "Unstage All",
                                    onAction = {
                                        scope.launch { GitService.unstageAll(windowId = windowId) }
                                    }
                                )
                            }
                            items(stagedFiles, key = { "staged-${it.path}" }) { file ->
                                CommitFileRow(
                                    file = file,
                                    isStaged = true,
                                    onToggle = {
                                        scope.launch { GitService.unstage(file.path, windowId = windowId) }
                                    }
                                )
                            }
                        }

                        // Unstaged files section
                        if (unstagedFiles.isNotEmpty()) {
                            item {
                                CommitFileSectionHeader(
                                    title = "Changes",
                                    count = unstagedFiles.size,
                                    actionText = "Stage All",
                                    onAction = {
                                        scope.launch { GitService.stageAll(windowId = windowId) }
                                    }
                                )
                            }
                            items(unstagedFiles, key = { "unstaged-${it.path}" }) { file ->
                                CommitFileRow(
                                    file = file,
                                    isStaged = false,
                                    onToggle = {
                                        scope.launch { GitService.stage(file.path, windowId = windowId) }
                                    }
                                )
                            }
                        }

                        // Empty state
                        if (stagedFiles.isEmpty() && unstagedFiles.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No changes to commit",
                                        color = BossDarkTextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Error message
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = BossDarkError,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Staged count indicator
                    if (stagedFiles.isNotEmpty()) {
                        Text(
                            text = "${stagedFiles.size} file${if (stagedFiles.size > 1) "s" else ""} staged",
                            color = BossDarkTextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = BossDarkTextSecondary)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                val finalMessage = if (signOff) {
                                    "$commitMessage\n\nSigned-off-by: ${System.getProperty("user.name")}"
                                } else {
                                    commitMessage
                                }

                                val result = GitService.commit(finalMessage, amend = amendCommit, windowId = windowId)
                                when (result) {
                                    is GitSuccess -> {
                                        onCommitSuccess(commitMessage)
                                        onDismiss()
                                    }
                                    is GitError -> {
                                        errorMessage = result.message
                                    }
                                }
                            }
                        },
                        enabled = commitMessage.isNotBlank() && (hasChangesToCommit || amendCommit) && !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent,
                            disabledBackgroundColor = BossDarkBorder
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossDarkTextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (amendCommit) "Amend" else "Commit",
                                color = BossDarkTextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitFileSectionHeader(
    title: String,
    count: Int,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossDarkBackground.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = BossDarkTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "($count)",
                color = BossDarkTextSecondary.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
        TextButton(
            onClick = onAction,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.height(24.dp)
        ) {
            Text(
                text = actionText,
                color = BossDarkAccent,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun CommitFileRow(
    file: GitFileStatus,
    isStaged: Boolean,
    onToggle: () -> Unit
) {
    val statusType = if (isStaged) file.indexStatus else file.workTreeStatus
    val statusChar = when (statusType) {
        GitFileStatusType.MODIFIED -> "M"
        GitFileStatusType.ADDED -> "A"
        GitFileStatusType.DELETED -> "D"
        GitFileStatusType.RENAMED -> "R"
        GitFileStatusType.COPIED -> "C"
        GitFileStatusType.UNTRACKED -> "?"
        else -> " "
    }
    val statusColor = when (statusType) {
        GitFileStatusType.MODIFIED -> Color(0xFF6B9BFA)
        GitFileStatusType.ADDED -> Color(0xFF73C991)
        GitFileStatusType.DELETED -> Color(0xFFF28B82)
        GitFileStatusType.RENAMED, GitFileStatusType.COPIED -> Color(0xFFFDD663)
        GitFileStatusType.UNTRACKED -> Color(0xFF9AA0A6)
        else -> BossDarkTextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isStaged,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = BossDarkAccent,
                uncheckedColor = BossDarkBorder
            ),
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = statusChar,
            color = statusColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(16.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = file.path,
            color = BossDarkTextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
