package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.window.Project
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.project.ProjectCreationService
import ai.rever.boss.project.ValidationResult
import ai.rever.boss.project.templates.ProjectTemplate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

// Dashboard-style accent color
private val AccentBlue get() = BossThemeController.current.colors.signal
private val HoverBackground get() = BossThemeController.current.colors.raised
private val SuccessGreen get() = BossThemeController.current.colors.ok
private val ErrorRed get() = BossThemeController.current.colors.alert

/**
 * Multi-step wizard dialog for creating new projects from templates.
 * Styled to match the Dashboard theme with hover animations and blue accents.
 *
 * @param onDismiss Callback when the dialog is dismissed
 * @param onProjectCreated Callback when a project is successfully created
 */
@Composable
fun NewProjectWizardDialog(
    onDismiss: () -> Unit,
    onProjectCreated: (Project) -> Unit
) {
    var wizardStep by remember { mutableStateOf<WizardStep>(WizardStep.TemplateSelection) }

    Dialog(
        onDismissRequest = {
            // Only allow dismiss if not creating
            if (wizardStep !is WizardStep.Creating) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnClickOutside = wizardStep !is WizardStep.Creating,
            dismissOnBackPress = wizardStep !is WizardStep.Creating,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .width(700.dp)
                .heightIn(min = 500.dp, max = 650.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BossTheme.colors.panel)
        ) {
            when (val step = wizardStep) {
                is WizardStep.TemplateSelection -> TemplateSelectionStep(
                    onDismiss = onDismiss,
                    onTemplateSelected = { template ->
                        wizardStep = WizardStep.Configuration(template)
                    }
                )

                is WizardStep.Configuration -> ConfigurationStep(
                    template = step.template,
                    onBack = { wizardStep = WizardStep.TemplateSelection },
                    onCreate = { name, path ->
                        wizardStep = WizardStep.Creating(name, path, step.template)
                    }
                )

                is WizardStep.Creating -> CreatingStep(
                    name = step.name,
                    path = step.path,
                    template = step.template,
                    onSuccess = { project ->
                        wizardStep = WizardStep.Success(project)
                    },
                    onError = { message ->
                        wizardStep = WizardStep.Error(message)
                    }
                )

                is WizardStep.Success -> SuccessStep(
                    project = step.project,
                    onOpenProject = {
                        onProjectCreated(step.project)
                        onDismiss()
                    }
                )

                is WizardStep.Error -> ErrorStep(
                    message = step.message,
                    onRetry = { wizardStep = WizardStep.TemplateSelection },
                    onClose = onDismiss
                )
            }
        }
    }
}

/**
 * Step 1: Select a project template
 */
@Composable
private fun TemplateSelectionStep(
    onDismiss: () -> Unit,
    onTemplateSelected: (ProjectTemplate) -> Unit
) {
    var selectedTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        // Header with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "New Project",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.textPrimary
                )
                Text(
                    text = "Select a project template to get started",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close",
                    tint = BossTheme.colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Template grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(ProjectTemplate.all) { template ->
                TemplateCard(
                    template = template,
                    isSelected = selectedTemplate == template,
                    onClick = { selectedTemplate = template }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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
                Text("Cancel")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = { selectedTemplate?.let { onTemplateSelected(it) } },
                enabled = selectedTemplate != null,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentBlue,
                    contentColor = BossTheme.colors.onSignal,
                    disabledBackgroundColor = BossTheme.colors.line,
                    disabledContentColor = BossTheme.colors.textSecondary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Text("Next", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Template selection card with Dashboard-style hover effects
 */
@Composable
private fun TemplateCard(
    template: ProjectTemplate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.6f)
    )

    val backgroundColor = when {
        isSelected -> AccentBlue.copy(alpha = 0.15f)
        isHovered -> HoverBackground
        else -> BossTheme.colors.raised
    }

    val borderColor = when {
        isSelected -> AccentBlue
        isHovered -> BossTheme.colors.line.copy(alpha = 0.8f)
        else -> Color.Transparent
    }

    val iconColor = when {
        isSelected -> AccentBlue
        isHovered -> BossTheme.colors.textPrimary
        else -> BossTheme.colors.textSecondary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(
                if (isSelected || isHovered) {
                    Modifier.background(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            )
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(1.dp)
    ) {
        // Border effect
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        color = AccentBlue.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp)
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = template.icon,
                contentDescription = template.name,
                modifier = Modifier.size(32.dp),
                tint = iconColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = template.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected || isHovered) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = template.description,
                fontSize = 12.sp,
                color = BossTheme.colors.textSecondary.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Step 2: Configure project name and location
 */
@Composable
private fun ConfigurationStep(
    template: ProjectTemplate,
    onBack: () -> Unit,
    onCreate: (name: String, path: String) -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var projectLocation by remember { mutableStateOf(ProjectCreationService.getDefaultProjectsDirectory()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Directory picker
    val directoryPicker = rememberDirectoryPicker { path ->
        path?.let { projectLocation = it }
    }

    // Debounce validation to avoid excessive I/O on every keystroke (300ms delay)
    @OptIn(FlowPreview::class)
    LaunchedEffect(Unit) {
        snapshotFlow { projectName to projectLocation }
            .debounce(300L)
            .collectLatest { (name, location) ->
                validationError = if (name.isNotBlank()) {
                    when (val result = ProjectCreationService.validateProjectLocation(location, name)) {
                        is ValidationResult.Valid -> null
                        is ValidationResult.Invalid -> result.reason
                    }
                } else {
                    null
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = BossTheme.colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "New ${template.name} Project",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.textPrimary
                )
                Text(
                    text = template.description,
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Project name input
        Text(
            text = "Project Name",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = projectName,
            onValueChange = { projectName = it },
            placeholder = { Text("my-project", color = BossTheme.colors.textSecondary.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = BossTheme.colors.textPrimary,
                cursorColor = AccentBlue,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = BossTheme.colors.line,
                errorBorderColor = ErrorRed,
                backgroundColor = BossTheme.colors.raised
            ),
            singleLine = true,
            isError = validationError != null && projectName.isNotBlank(),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Project location
        Text(
            text = "Location",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = projectLocation,
                onValueChange = { projectLocation = it },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = BossTheme.colors.textPrimary,
                    cursorColor = AccentBlue,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = BossTheme.colors.line,
                    backgroundColor = BossTheme.colors.raised
                ),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = { directoryPicker.pickDirectory() },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.raised,
                    contentColor = BossTheme.colors.textPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Browse",
                    tint = BossTheme.colors.textSecondary
                )
            }
        }

        // Show full project path with overflow handling for long paths
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Project will be created at: $projectLocation/$projectName",
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Show validation error (using safe access instead of !!)
        validationError?.takeIf { projectName.isNotBlank() }?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                fontSize = 12.sp,
                color = ErrorRed
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = BossTheme.colors.textSecondary
                )
            ) {
                Text("Back")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = { onCreate(projectName.trim(), projectLocation) },
                enabled = projectName.isNotBlank() && validationError == null,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentBlue,
                    contentColor = BossTheme.colors.onSignal,
                    disabledBackgroundColor = BossTheme.colors.line,
                    disabledContentColor = BossTheme.colors.textSecondary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Text("Create Project", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Step 3: Creating project with progress
 */
@Composable
private fun CreatingStep(
    name: String,
    path: String,
    template: ProjectTemplate,
    onSuccess: (Project) -> Unit,
    onError: (String) -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("Initializing...") }

    // Trigger project creation on first composition
    // LaunchedEffect already provides a coroutine scope, no need for rememberCoroutineScope
    LaunchedEffect(Unit) {
        val result = ProjectCreationService.createProject(
            name = name,
            parentDirectory = path,
            template = template,
            onProgress = { p, msg ->
                progress = p
                statusMessage = msg
            }
        )

        result.fold(
            onSuccess = { project -> onSuccess(project) },
            onFailure = { error -> onError(error.message ?: "Unknown error occurred") }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(72.dp),
            color = AccentBlue,
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Creating Project",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = statusMessage,
            fontSize = 14.sp,
            color = BossTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = AccentBlue,
            backgroundColor = BossTheme.colors.raised
        )
    }
}

/**
 * Step 4: Project created successfully
 */
@Composable
private fun SuccessStep(
    project: Project,
    onOpenProject: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = Modifier.size(72.dp),
            tint = SuccessGreen
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Project Created!",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = project.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = project.path,
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = onOpenProject,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AccentBlue,
                contentColor = BossTheme.colors.onSignal
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(44.dp)
        ) {
            Text("Open Project", fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Step 5: Error occurred
 */
@Composable
private fun ErrorStep(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            modifier = Modifier.size(72.dp),
            tint = ErrorRed
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Project Creation Failed",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(8.dp))
                .background(BossTheme.colors.raised)
                .padding(16.dp)
        ) {
            Text(
                text = message,
                fontSize = 13.sp,
                color = BossTheme.colors.textPrimary.copy(alpha = 0.9f),
                lineHeight = 20.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(0.6f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onClose,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = BossTheme.colors.textSecondary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text("Close")
            }

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = AccentBlue,
                    contentColor = BossTheme.colors.onSignal
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text("Try Again", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Wizard step states
 */
private sealed class WizardStep {
    data object TemplateSelection : WizardStep()
    data class Configuration(val template: ProjectTemplate) : WizardStep()
    data class Creating(val name: String, val path: String, val template: ProjectTemplate) : WizardStep()
    data class Success(val project: Project) : WizardStep()
    data class Error(val message: String) : WizardStep()
}
