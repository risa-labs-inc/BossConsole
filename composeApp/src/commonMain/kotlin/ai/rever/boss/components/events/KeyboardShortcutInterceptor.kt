package ai.rever.boss.components.events

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.ShortcutContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/**
 * Set of modifier-only keys that should not trigger shortcut matching.
 * These keys don't have a standalone action and are only used in combination.
 */
private val MODIFIER_ONLY_KEYS = setOf(
    Key.CapsLock, Key.ShiftLeft, Key.ShiftRight,
    Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight, Key.NumLock, Key.ScrollLock
)

/**
 * Creates a modifier that intercepts keyboard events and routes matched shortcuts
 * to KeyboardEventBus, preventing the wrapped component from receiving them.
 *
 * Use this to wrap components that consume all keyboard input (like terminals, browsers)
 * to ensure global/workspace shortcuts still work.
 *
 * @param windowId The current window ID for event routing
 * @param source The event source identifier (e.g., COMPONENT_TERMINAL, COMPONENT_BROWSER)
 * @param context The shortcut context for matching (e.g., TERMINAL, BROWSER)
 * @return Modifier that intercepts matched shortcuts
 */
@Composable
fun Modifier.interceptKeyboardShortcuts(
    windowId: String,
    source: KeyEventSource,
    context: ShortcutContext
): Modifier {
    val settings by KeymapSettingsManager.currentSettings.collectAsState()
    val matcher = remember(settings) { KeymapMatcher(settings) }
    val coroutineScope = rememberCoroutineScope()

    return this.onPreviewKeyEvent { keyEvent ->
        // Only handle key down events
        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

        // Skip modifier-only keys
        if (keyEvent.key in MODIFIER_ONLY_KEYS) return@onPreviewKeyEvent false

        // Check if this key combo matches any shortcut
        val binding = matcher.match(keyEvent, context)

        if (binding != null) {
            // Emit to KeyboardEventBus for action execution
            coroutineScope.launch {
                KeyboardEventBus.emit(
                    KeyboardEvent(
                        keyEvent = keyEvent,
                        source = source,
                        context = context,
                        sourceWindowId = windowId
                    )
                )
            }
            true // Consume the event - don't let wrapped component handle it
        } else {
            false // Let wrapped component handle regular input
        }
    }
}

/**
 * Composable wrapper that intercepts keyboard shortcuts before they reach child content.
 * Convenience wrapper around [interceptKeyboardShortcuts] modifier.
 *
 * @param windowId The current window ID for event routing
 * @param source The event source identifier
 * @param context The shortcut context for matching
 * @param content The content to wrap
 */
@Composable
fun KeyboardShortcutInterceptor(
    windowId: String,
    source: KeyEventSource,
    context: ShortcutContext,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .interceptKeyboardShortcuts(windowId, source, context)
    ) {
        content()
    }
}
