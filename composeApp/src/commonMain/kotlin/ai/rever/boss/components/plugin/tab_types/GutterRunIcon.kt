package ai.rever.boss.components.plugin.tab_types

import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.run.DetectedMainFunction
import ai.rever.boss.run.Language
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Play
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp

/**
 * Run icon displayed in the code editor gutter next to main functions.
 * Clicking the icon runs the detected main function.
 * Right-clicking shows a context menu with additional options.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GutterRunIcon(
    detected: DetectedMainFunction,
    onRun: (DetectedMainFunction) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showContextMenu by remember { mutableStateOf(false) }

    val iconColor = if (isHovered) {
        Color(0xFF6BBF78) // Brighter green when hovered
    } else {
        Color(0xFF59A869) // IntelliJ's run icon green
    }

    Icon(
        imageVector = FeatherIcons.Play,
        contentDescription = "Run ${detected.functionName}",
        tint = iconColor,
        modifier = modifier
            .size(20.dp)
            .hoverable(interactionSource)
            .clickable { onRun(detected) }
            .onPointerEvent(PointerEventType.Press) { event ->
                // Right-click to show context menu
                if (event.button == PointerButton.Secondary) {
                    showContextMenu = true
                }
            }
    )

    // Context menu for additional options
    if (showContextMenu) {
        GutterRunContextMenu(
            detected = detected,
            onDismissRequest = { showContextMenu = false },
            onRun = { onRun(detected) },
            onRunWithArgs = { /* Future: Show dialog to enter arguments */ }
        )
    }
}

/**
 * Context menu shown when right-clicking the run icon.
 */
@Composable
private fun GutterRunContextMenu(
    detected: DetectedMainFunction,
    onDismissRequest: () -> Unit,
    onRun: () -> Unit,
    onRunWithArgs: () -> Unit
) {
    val menuItems = buildList {
        add(ContextMenuItem(
            text = "Run '${detected.toShortName()}'",
            icon = Icons.Outlined.PlayArrow,
            onClick = {
                onRun()
                onDismissRequest()
            }
        ))

        // Future: Debug option
        // add(ContextMenuItem(
        //     text = "Debug '${detected.toShortName()}'",
        //     icon = Icons.Outlined.BugReport,
        //     onClick = { onDismissRequest() }
        // ))

        add(ContextMenuItem(isDivider = true))

        add(ContextMenuItem(
            text = "Run with Arguments...",
            icon = null,
            onClick = {
                onRunWithArgs()
                onDismissRequest()
            }
        ))
    }

    ContextMenu(
        items = menuItems,
        onDismissRequest = onDismissRequest
    )
}

/**
 * Spacer used in place of the run icon when a line doesn't have a main function.
 * Keeps the gutter width consistent.
 */
@Composable
fun GutterRunIconSpacer(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(20.dp))
}
