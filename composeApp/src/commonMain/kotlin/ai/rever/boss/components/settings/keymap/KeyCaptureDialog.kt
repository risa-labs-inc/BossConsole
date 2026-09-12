package ai.rever.boss.components.settings.keymap

import ai.rever.boss.components.events.MODIFIER_ONLY_KEYS
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.model.recordedModifiers
import ai.rever.boss.keymap.model.storedKeyName
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.SystemUtils
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
    onDismiss: () -> Unit,
) {
    var capturedKey by remember { mutableStateOf<Key?>(null) }
    var capturedModifiers by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasCapture by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BossDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier =
                Modifier
                    .width(500.dp)
                    .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Capture Keyboard Shortcut",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossTheme.colors.textPrimary,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BossTheme.colors.textSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action info card
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BossTheme.colors.ink)
                            .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                ) {
                    Text(
                        text = actionDescription,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossTheme.colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Context: ${context.displayName}",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textSecondary,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current binding display
                if (currentBinding != null) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(BossTheme.colors.ink)
                                .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                                .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Current shortcut",
                            fontSize = 13.sp,
                            color = BossTheme.colors.textSecondary,
                        )
                        KeyDisplay(currentBinding.displayString())
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Capture area
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BossTheme.colors.ink)
                            .border(
                                width = 2.dp,
                                color = if (hasCapture) BossTheme.colors.signal else BossTheme.colors.line,
                                shape = RoundedCornerShape(8.dp),
                            ).focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && isShortcutCaptureKey(event.key)) {
                                    capturedKey = event.key
                                    // The shared rule, not a private swap. This dialog used to
                                    // record a Control press as "Cmd" off macOS and a Super press
                                    // as "Ctrl", which agreed with the old matcher and disagreed
                                    // with both the default preset and the string this dialog
                                    // shows the user one line below. See recordedModifiers.
                                    capturedModifiers =
                                        recordedModifiers(
                                            metaDown = event.isMetaPressed,
                                            controlDown = event.isCtrlPressed,
                                            shiftDown = event.isShiftPressed,
                                            altDown = event.isAltPressed,
                                            isMacOS = SystemUtils.isMacOS,
                                        )
                                    hasCapture = true
                                    true
                                } else {
                                    false
                                }
                            }.focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasCapture && capturedKey != null) {
                        // The preview renders the keystroke that Apply will persist, rather than
                        // formatting the captured Key on its own. It used to have a private copy
                        // of the formatter, which is how it came to display a raw keyCode.
                        val displayStr = KeyStroke(storedKeyName(capturedKey!!), capturedModifiers).displayString()
                        KeyDisplay(displayStr, large = true)
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Press any key combination...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = BossTheme.colors.textSecondary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The dialog is focused and ready to capture",
                                fontSize = 12.sp,
                                color = BossTheme.colors.textSecondary.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = BossTheme.colors.textSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (hasCapture && capturedKey != null) {
                                val binding =
                                    KeyBinding(
                                        actionId = actionId,
                                        // #329: this was `capturedKey!!.keyCode.toString()`, a
                                        // packed Long ("4294967333"). Both matchers compare
                                        // key NAMES, so every rebind made here saved, displayed
                                        // and then fired on neither path.
                                        key = storedKeyName(capturedKey!!),
                                        modifiers = capturedModifiers,
                                        context = context,
                                        category = category,
                                        description = actionDescription,
                                        enabled = true,
                                    )
                                onKeyCaptured(binding)
                            }
                        },
                        enabled = hasCapture && capturedKey != null,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.signal,
                                disabledBackgroundColor = BossTheme.colors.line,
                            ),
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
private fun KeyDisplay(
    shortcutText: String,
    large: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BossTheme.colors.signal.copy(alpha = 0.2f))
                .padding(horizontal = if (large) 16.dp else 8.dp, vertical = if (large) 8.dp else 4.dp),
    ) {
        Text(
            text = shortcutText,
            fontSize = if (large) 24.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.signalText,
        )
    }
}

/** A modifier alone cannot be dispatched by the AWT shortcut interceptor. */
internal fun isShortcutCaptureKey(key: Key): Boolean = key !in MODIFIER_ONLY_KEYS
