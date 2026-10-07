package ai.rever.boss.sharing

import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** Respect the component's traversal policy; Compose's internal focus keeps its normal key path. */
@Suppress("ReturnCount") // Each guard preserves a distinct component/policy boundary.
internal fun appTraversalTarget(target: Component, event: AppInputEvent.Key): Component? {
    if (!isTraversalKey(event) || !target.focusTraversalKeysEnabled) return null
    val direction =
        if (event.shift) KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS else KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS
    val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, if (event.shift) InputEvent.SHIFT_DOWN_MASK else 0)
    if (stroke !in target.getFocusTraversalKeys(direction)) return null
    val root = target.focusCycleRootAncestor ?: return null
    val policy = root.focusTraversalPolicy ?: return null
    val next = if (event.shift) policy.getComponentBefore(root, target) else policy.getComponentAfter(root, target)
    if (next === target) return null
    return next?.takeIf { it.isShowing && it.isEnabled && it.isFocusable }
}

private fun isTraversalKey(event: AppInputEvent.Key): Boolean {
    val modified = event.alt || event.ctrl || event.meta
    return event.code == "Tab" && !modified
}
