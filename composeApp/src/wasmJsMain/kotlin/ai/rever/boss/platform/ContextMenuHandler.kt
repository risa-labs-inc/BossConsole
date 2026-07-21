package ai.rever.boss.platform

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.IntOffset
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlinx.browser.document
import org.w3c.dom.get

/**
 * Web/WasmJS implementation of ContextMenuHandler.
 * Uses right-click for desktop browsers and long press for touch devices.
 * Also prevents the browser's native context menu from appearing.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual class ContextMenuHandler actual constructor() {
    
    // Initialize to prevent browser's default context menu
    init {
        try {
            // Get the root div containing the Compose app
            val rootElement = document.getElementById("compose-root") as? HTMLElement
            if (rootElement != null) {
                // Add event listener to prevent default context menu
                rootElement.addEventListener("contextmenu", { event ->
                    event.preventDefault()
                })
            }
        } catch (e: Throwable) {
            // Ignore errors in case we're not in a browser environment
            // Error logging removed due to WasmJS interop limitations
        }
    }
    
    /**
     * Web implementation uses right-click as primary activation method,
     * with long press as fallback for touch devices.
     */
    actual fun Modifier.applyContextMenuBehavior(
        showMenu: Boolean,
        setShowMenu: (Boolean) -> Unit,
        setMenuPosition: (IntOffset) -> Unit
    ): Modifier = composed {
        // Handle long press for touch devices
        pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { offset ->
                    setMenuPosition(IntOffset(offset.x.toInt(), offset.y.toInt()))
                    setShowMenu(true)
                },
                onTap = { 
                    if (showMenu) {
                        setShowMenu(false)
                    }
                }
            )
        }.pointerInput(Unit) {
            // For desktop browsers, detect right-click
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    
                    // Check for right-click events
                    // In browsers, secondary button is typically right-click
                    if (event.type == PointerEventType.Press && 
                        !event.buttons.isPrimaryPressed) {
                        
                        // Get position from the first change
                        val change = event.changes.firstOrNull()
                        if (change != null) {
                            setMenuPosition(IntOffset(
                                change.position.x.toInt(),
                                change.position.y.toInt()
                            ))
                            setShowMenu(true)
                            
                            // Prevent browser's default context menu
                            change.consume()
                        }
                    }
                    
                    // Handle click to dismiss menu
                    if (showMenu && event.type == PointerEventType.Press && 
                        event.buttons.isPrimaryPressed) {
                        setShowMenu(false)
                    }
                }
            }
        }
    }
    
    actual fun getInstructionText(): String = 
        "Right-click or long press anywhere to see the context menu"
} 
