package ai.rever.boss.components.dialogs

import ai.rever.boss.git.GitOperationResult
import ai.rever.boss.plugin.git.GitOperationResult.Success as GitSuccess
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError
import ai.rever.boss.git.GitService
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.project.ProjectCreationService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.File

private val logger = BossLogger.forComponent("CloneProject")

/**
 * Dialog for cloning Git repositories into the BossProject folder structure.
 * Follows the settings dialog design pattern with clean, consistent styling.
 *
 * Issue #550: Clone Project Feature
 *
 * @param onDismiss Callback when the dialog is dismissed
 * @param onProjectCloned Callback when a project is successfully cloned, receives the project path
 */
@Composable
fun CloneProjectDialog(
    onDismiss: () -> Unit,
    onProjectCloned: (String) -> Unit
) {
    var cloneStep by remember { mutableStateOf<CloneStep>(CloneStep.Configuration) }

    Dialog(
        onDismissRequest = {
            // Only allow dismiss if not cloning
            if (cloneStep !is CloneStep.Cloning) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnClickOutside = cloneStep !is CloneStep.Cloning,
            dismissOnBackPress = cloneStep !is CloneStep.Cloning,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp
        ) {
            when (val step = cloneStep) {
                is CloneStep.Configuration -> ConfigurationStep(
                    onDismiss = onDismiss,
                    onClone = { url, directory ->
                        cloneStep = CloneStep.Cloning(url, directory, "Initializing...")
                    }
                )

                is CloneStep.Cloning -> CloningStep(
                    repositoryUrl = step.repositoryUrl,
                    targetDirectory = step.targetDirectory,
                    progressMessage = step.progressMessage,
                    onProgress = { progress ->
                        cloneStep = CloneStep.Cloning(step.repositoryUrl, step.targetDirectory, progress)
                    },
                    onSuccess = { projectPath ->
                        cloneStep = CloneStep.Success(projectPath)
                    },
                    onError = { message ->
                        cloneStep = CloneStep.Error(message)
                    }
                )

                is CloneStep.Success -> SuccessStep(
                    projectPath = step.projectPath,
                    onOpenProject = {
                        onProjectCloned(step.projectPath)
                        onDismiss()
                    },
                    onClose = onDismiss
                )

                is CloneStep.Error -> ErrorStep(
                    message = step.message,
                    onRetry = { cloneStep = CloneStep.Configuration },
                    onClose = onDismiss
                )
            }
        }
    }
}

/**
 * Step 1: Configuration - Enter repository URL and select target directory
 */
@Composable
private fun ConfigurationStep(
    onDismiss: () -> Unit,
    onClone: (url: String, directory: String) -> Unit
) {
    var repositoryUrl by remember { mutableStateOf("") }
    var targetDirectory by remember { mutableStateOf(ProjectCreationService.getDefaultProjectsDirectory()) }
    var customDirectoryName by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var lastAutoFilledName by remember { mutableStateOf("") }
    var userManuallyEdited by remember { mutableStateOf(false) }

    // Directory picker
    val directoryPicker = rememberDirectoryPicker { path ->
        path?.let { targetDirectory = it }
    }

    // Validate URL
    val isValidUrl = remember(repositoryUrl) {
        val trimmedUrl = repositoryUrl.trim()
        when {
            trimmedUrl.isEmpty() -> {
                urlError = null
                false
            }
            trimmedUrl.startsWith("https://") ||
            trimmedUrl.startsWith("http://") ||
            trimmedUrl.startsWith("git@") ||
            trimmedUrl.startsWith("ssh://") -> {
                urlError = null
                true
            }
            else -> {
                urlError = "Invalid URL format. Use https://, git@, or ssh://"
                false
            }
        }
    }

    // Automatically derive directory name from URL (only when user hasn't manually edited)
    LaunchedEffect(repositoryUrl) {
        val trimmedUrl = repositoryUrl.trim()

        // Check if URL is valid and has actual content after the protocol
        val isValid = when {
            trimmedUrl.startsWith("https://") -> trimmedUrl.length > "https://".length
            trimmedUrl.startsWith("http://") -> trimmedUrl.length > "http://".length
            trimmedUrl.startsWith("git@") -> trimmedUrl.length > "git@".length
            trimmedUrl.startsWith("ssh://") -> trimmedUrl.length > "ssh://".length
            else -> false
        }

        // Reset manual edit flag if URL is empty (user starting fresh)
        if (trimmedUrl.isEmpty()) {
            userManuallyEdited = false
            lastAutoFilledName = ""
        }

        // Auto-fill if: valid URL AND user hasn't manually edited
        if (isValid && !userManuallyEdited) {
            val repoName = extractRepoName(trimmedUrl)
            if (repoName != "cloned-repo" && repoName != lastAutoFilledName) {
                customDirectoryName = repoName
                lastAutoFilledName = repoName
            }
        }
    }

    // Calculate final clone path
    val finalClonePath = remember(targetDirectory, customDirectoryName) {
        if (customDirectoryName.isNotBlank()) {
            "$targetDirectory${File.separator}$customDirectoryName"
        } else {
            targetDirectory
        }
    }

    // Check if directory already exists
    val directoryExists = remember(finalClonePath) {
        customDirectoryName.isNotBlank() && File(finalClonePath).exists()
    }

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
                text = "Clone Git Repository",
                color = BossTheme.colors.textPrimary,
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
                    tint = BossTheme.colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Repository URL input
        Text(
            text = "Repository URL",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        OutlinedTextField(
            value = repositoryUrl,
            onValueChange = { repositoryUrl = it },
            placeholder = {
                Text(
                    "https://github.com/username/repo.git",
                    color = BossTheme.colors.textSecondary.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = BossTheme.colors.textPrimary,
                cursorColor = BossTheme.colors.signal,
                focusedBorderColor = if (urlError != null) BossTheme.colors.alert else BossTheme.colors.signal,
                unfocusedBorderColor = if (urlError != null) BossTheme.colors.alert else BossTheme.colors.line,
                backgroundColor = BossTheme.colors.raised
            ),
            singleLine = true,
            isError = urlError != null,
            shape = RoundedCornerShape(4.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )

        // Show URL validation error
        urlError?.let { error ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error,
                fontSize = 12.sp,
                color = BossTheme.colors.alert
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Directory name input
        Text(
            text = "Directory Name",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        OutlinedTextField(
            value = customDirectoryName,
            onValueChange = { newValue ->
                customDirectoryName = newValue
                // Mark as manually edited if user changes it to something different than auto-filled
                if (newValue != lastAutoFilledName) {
                    userManuallyEdited = true
                }
            },
            placeholder = {
                Text(
                    "my-project",
                    color = BossTheme.colors.textSecondary.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = BossTheme.colors.textPrimary,
                cursorColor = BossTheme.colors.signal,
                focusedBorderColor = if (directoryExists) BossTheme.colors.alert else BossTheme.colors.signal,
                unfocusedBorderColor = if (directoryExists) BossTheme.colors.alert else BossTheme.colors.line,
                backgroundColor = BossTheme.colors.raised
            ),
            singleLine = true,
            isError = directoryExists,
            shape = RoundedCornerShape(4.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Parent directory location
        Text(
            text = "Parent Directory",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = targetDirectory,
                onValueChange = { targetDirectory = it },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = BossTheme.colors.textPrimary,
                    cursorColor = BossTheme.colors.signal,
                    focusedBorderColor = BossTheme.colors.signal,
                    unfocusedBorderColor = BossTheme.colors.line,
                    backgroundColor = BossTheme.colors.raised
                ),
                singleLine = true,
                shape = RoundedCornerShape(4.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { directoryPicker.pickDirectory() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Browse",
                    tint = BossTheme.colors.textSecondary
                )
            }
        }

        // Show full project path
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (directoryExists) BossTheme.colors.alert.copy(alpha = 0.1f) else BossTheme.colors.raised,
                    RoundedCornerShape(4.dp)
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = "Clone destination:",
                    fontSize = 12.sp,
                    color = BossTheme.colors.textSecondary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = finalClonePath,
                    fontSize = 13.sp,
                    color = if (directoryExists) BossTheme.colors.alert else BossTheme.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (directoryExists) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "⚠ Directory already exists",
                        fontSize = 12.sp,
                        color = BossTheme.colors.alert,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary
                )
            ) {
                Text("Cancel", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { onClone(repositoryUrl.trim(), finalClonePath) },
                enabled = isValidUrl && customDirectoryName.isNotBlank() && !directoryExists,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal,
                    disabledBackgroundColor = BossTheme.colors.line,
                    disabledContentColor = BossTheme.colors.textSecondary
                ),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("Clone", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Step 2: Cloning - Show progress while cloning
 */
@Composable
private fun CloningStep(
    repositoryUrl: String,
    targetDirectory: String,
    progressMessage: String,
    onProgress: (String) -> Unit,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    // Trigger clone operation on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            logger.info(
                LogCategory.GENERAL,
                "Starting clone operation",
                mapOf("url" to repositoryUrl, "target" to targetDirectory)
            )

            val result = GitService.cloneRepository(
                repositoryUrl = repositoryUrl,
                targetDirectory = targetDirectory,
                onProgress = onProgress
            )

            when (result) {
                is GitSuccess -> {
                    logger.info(LogCategory.GENERAL, "Clone completed successfully")
                    onSuccess(targetDirectory)
                }
                is GitError -> {
                    logger.error(LogCategory.GENERAL, "Clone failed: ${result.message}")
                    onError(result.message)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .heightIn(min = 200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = BossTheme.colors.signal,
            strokeWidth = 3.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Cloning Repository",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = progressMessage,
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "This may take a few moments...",
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary.copy(alpha = 0.6f)
        )
    }
}

/**
 * Step 3: Success - Repository cloned successfully
 */
@Composable
private fun SuccessStep(
    projectPath: String,
    onOpenProject: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .heightIn(min = 200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = Modifier.size(48.dp),
            tint = BossTheme.colors.ok
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Repository Cloned Successfully",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = projectPath,
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary
                )
            ) {
                Text("Close", fontSize = 13.sp)
            }

            Button(
                onClick = onOpenProject,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal
                ),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("Open Project", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Step 4: Error - Clone operation failed
 */
@Composable
private fun ErrorStep(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Error",
                    modifier = Modifier.size(20.dp),
                    tint = BossTheme.colors.alert
                )
                Text(
                    text = "Clone Failed",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.textPrimary
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = BossTheme.colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BossTheme.colors.raised, RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                color = BossTheme.colors.textPrimary.copy(alpha = 0.9f),
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary
                )
            ) {
                Text("Close", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal
                ),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("Try Again", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Wizard step sealed class
 */
private sealed class CloneStep {
    data object Configuration : CloneStep()
    data class Cloning(
        val repositoryUrl: String,
        val targetDirectory: String,
        val progressMessage: String
    ) : CloneStep()
    data class Success(val projectPath: String) : CloneStep()
    data class Error(val message: String) : CloneStep()
}

/**
 * Extract repository name from Git URL
 * Examples:
 *   https://github.com/user/repo -> repo
 *   https://github.com/user/repo.git -> repo
 *   git@github.com:user/repo.git -> repo
 */
private fun extractRepoName(url: String): String {
    return try {
        if (url.isBlank()) return "cloned-repo"

        val trimmed = url.trim()

        // Remove .git suffix if present
        val withoutGit = trimmed.removeSuffix(".git")

        // For SSH URLs (git@host:path), split by : first to get the path part
        val pathPart = if (withoutGit.startsWith("git@")) {
            withoutGit.substringAfter(':', withoutGit)
        } else {
            // For HTTP(S) URLs, remove protocol
            withoutGit
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("ssh://")
        }

        // Split by / and get the last non-empty part
        val parts = pathPart.split('/').filter { it.isNotBlank() }

        // Get the last part (repo name), but skip if it's just a domain
        val repoName = parts.lastOrNull()?.takeIf {
            it.isNotBlank() &&
            !it.contains('.') && // Skip domain names like github.com
            it != "git@" &&
            parts.size > 1 // Must have at least org/repo
        } ?: "cloned-repo"

        repoName
    } catch (e: Exception) {
        logger.debug(LogCategory.WORKSPACE, "Could not derive repo name from URL - using default", mapOf("error" to e.toString()))
        "cloned-repo"
    }
}
