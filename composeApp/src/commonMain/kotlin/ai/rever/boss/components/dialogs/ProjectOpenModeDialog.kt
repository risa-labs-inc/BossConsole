package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.Project
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Asks where a project should open: in the Space on screen, in a new Space, or in a new window.
 *
 * A Space carries its own project, so this is the whole question - there is no second prompt for a
 * layout afterwards unless "New Space" is picked, which is what asks for one. "This Space" is the
 * filled button because it changes the least: the layout stays and the project moves in.
 *
 * @param onOpenInThisSpace Keep the layout on screen and give it this project
 * @param onOpenInNewSpace Pick a Space to open the project in
 * @param onOpenInNewWindow Open a new window with this project
 */
@Composable
fun ProjectOpenModeDialog(
    project: Project,
    onDismiss: () -> Unit,
    onOpenInThisSpace: (Project) -> Unit,
    onOpenInNewSpace: (Project) -> Unit,
    onOpenInNewWindow: (Project) -> Unit,
) {
    BossDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(520.dp)
                    .wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = BossTheme.colors.panel,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                ProjectHeading(project)

                Spacer(modifier = Modifier.height(24.dp))

                // Question
                Text(
                    text = "Where would you like to open this project?",
                    fontSize = 14.sp,
                    color = BossTheme.colors.textSecondary,
                )

                Spacer(modifier = Modifier.height(16.dp))

                OpenModeButtons(
                    project = project,
                    onDismiss = onDismiss,
                    onOpenInThisSpace = onOpenInThisSpace,
                    onOpenInNewSpace = onOpenInNewSpace,
                    onOpenInNewWindow = onOpenInNewWindow,
                )
            }
        }
    }
}

/** Which project this is about: a title, then its name and where it lives. */
@Composable
private fun ProjectHeading(project: Project) {
    // Icon and title
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = "Open Project",
            tint = BossTheme.colors.signalText,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Open Project",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Project name
    Text(
        text = project.name,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = BossTheme.colors.textPrimary,
    )
    Text(
        text = project.path,
        fontSize = 12.sp,
        color = BossTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** The three answers, in the order they change things: least first. */
@Composable
private fun OpenModeButtons(
    project: Project,
    onDismiss: () -> Unit,
    onOpenInThisSpace: (Project) -> Unit,
    onOpenInNewSpace: (Project) -> Unit,
    onOpenInNewWindow: (Project) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenModeButton(
            text = "This Space",
            icon = Icons.Outlined.Tab,
            primary = true,
            onClick = {
                onOpenInThisSpace(project)
                onDismiss()
            },
        )
        OpenModeButton(
            text = "New Space",
            icon = Icons.Outlined.AddBox,
            onClick = {
                onOpenInNewSpace(project)
                onDismiss()
            },
        )
        OpenModeButton(
            text = "New Window",
            icon = Icons.AutoMirrored.Outlined.OpenInNew,
            onClick = {
                onOpenInNewWindow(project)
                onDismiss()
            },
        )
    }
}

/** One of the three answers. Filled for [primary], outlined otherwise. */
@Composable
private fun RowScope.OpenModeButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val modifier = Modifier.weight(1f).height(40.dp)
    val shape = RoundedCornerShape(6.dp)
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val content: @Composable RowScope.() -> Unit = {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = Color.White,
                ),
            shape = shape,
            contentPadding = padding,
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BossTheme.colors.textPrimary),
            shape = shape,
            contentPadding = padding,
            content = content,
        )
    }
}
