package ai.rever.boss.components.settings.keymap

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.utils.SystemUtils

/**
 * Dialog for capturing keyboard shortcuts.
 * Displays a modal that captures the next key combination pressed by the user.
 */
@Composable
fun KeyCaptureDialog(
    actionId: String,
    actionDescription: String,
    context: ShortcutContext,
    category: String,
    currentBinding: KeyBinding?,
    onKeyCaptured: (KeyBinding) -> Unit,
    onDismiss: () -> Unit
) {
    var capturedKey by remember { mutableStateOf<Key?>(null) }
    var capturedModifiers by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasCapture by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(500.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Capture Keyboard Shortcut",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossTheme.colors.textPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BossTheme.colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action info card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(BossTheme.colors.ink)
                        .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = actionDescription,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Context: ${context.displayName}",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current binding display
                if (currentBinding != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BossTheme.colors.ink)
                            .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Current shortcut",
                            fontSize = 13.sp,
                            color = BossTheme.colors.textSecondary
                        )
                        KeyDisplay(currentBinding.displayString())
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Capture area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BossTheme.colors.ink)
                        .border(
                            width = 2.dp,
                            color = if (hasCapture) BossTheme.colors.signal else BossTheme.colors.line,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                capturedKey = event.key
                                val mods = mutableListOf<String>()
                                val isMacOS = SystemUtils.isMacOS
                                if (isMacOS) {
                                    if (event.isMetaPressed) mods.add("Cmd")
                                    if (event.isCtrlPressed) mods.add("Ctrl")
                                } else {
                                    if (event.isCtrlPressed) mods.add("Cmd")
                                    if (event.isMetaPressed) mods.add("Ctrl")
                                }
                                if (event.isShiftPressed) mods.add("Shift")
                                if (event.isAltPressed) mods.add("Alt")
                                capturedModifiers = mods
                                hasCapture = true
                                true
                            } else {
                                false
                            }
                        }
                        .focusable(),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasCapture && capturedKey != null) {
                        val displayStr = buildDisplayString(capturedKey!!, capturedModifiers)
                        KeyDisplay(displayStr, large = true)
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Press any key combination...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = BossTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The dialog is focused and ready to capture",
                                fontSize = 12.sp,
                                color = BossTheme.colors.textSecondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = BossTheme.colors.textSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (hasCapture && capturedKey != null) {
                                val binding = KeyBinding(
                                    actionId = actionId,
                                    key = capturedKey!!.keyCode.toString(),
                                    modifiers = capturedModifiers,
                                    context = context,
                                    category = category,
                                    description = actionDescription,
                                    enabled = true
                                )
                                onKeyCaptured(binding)
                            }
                        },
                        enabled = hasCapture && capturedKey != null,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            disabledBackgroundColor = BossTheme.colors.line
                        )
                    ) {
                        Text("Apply", color = BossTheme.colors.textPrimary)
                    }
                }
            }
        }
    }
}

/**
 * Displays a keyboard shortcut with styled keycap badges.
 */
@Composable
private fun KeyDisplay(shortcutText: String, large: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BossTheme.colors.signal.copy(alpha = 0.2f))
            .padding(horizontal = if (large) 16.dp else 8.dp, vertical = if (large) 8.dp else 4.dp)
    ) {
        Text(
            text = shortcutText,
            fontSize = if (large) 24.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.signal
        )
    }
}

/**
 * Builds a display string for captured keys.
 */
private fun buildDisplayString(key: Key, modifiers: List<String>): String {
    val isMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)

    val modifierStrings = modifiers.map { modifier ->
        when (modifier.lowercase()) {
            "cmd", "meta" -> if (isMac) "⌘" else "Ctrl"
            "ctrl", "control" -> if (isMac) "⌃" else "Ctrl"
            "shift" -> if (isMac) "⇧" else "Shift"
            "alt", "option" -> if (isMac) "⌥" else "Alt"
            else -> modifier
        }
    }

    val keyString = formatKeyDisplay(key.keyCode.toString())

    return (modifierStrings + keyString).joinToString(if (isMac) "" else "+")
}

/**
 * Formats the key name for display.
 */
private fun formatKeyDisplay(keyName: String): String {
    return when (keyName.lowercase()) {
        "space", "spacebar" -> "Space"
        "arrowleft", "directionleft" -> "←"
        "arrowright", "directionright" -> "→"
        "arrowup", "directionup" -> "↑"
        "arrowdown", "directiondown" -> "↓"
        "enter", "return" -> "↩"
        "backspace" -> "⌫"
        "delete" -> "⌦"
        "escape", "esc" -> "Esc"
        "tab" -> "Tab"
        else -> keyName.uppercase()
    }
}
