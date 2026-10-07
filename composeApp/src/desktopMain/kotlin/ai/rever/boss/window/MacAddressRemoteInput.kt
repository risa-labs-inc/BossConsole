package ai.rever.boss.window

import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The EDT waits only for bounded, control-specific AppKit work; a late callback has no authority. */
internal fun scopedNativeToolbarCall(
    validUntilMillis: Long,
    action: () -> Boolean,
): Boolean {
    val live = AtomicBoolean(true)
    val done = CompletableFuture<Boolean>()
    val deadline = minOf(validUntilMillis, System.currentTimeMillis() + 150)
    MacToolbarRuntime.dispatch {
        if (live.get() && System.currentTimeMillis() < deadline) {
            runCatching(action).fold(done::complete, done::completeExceptionally)
        } else {
            done.complete(false)
        }
    }
    return try {
        done.get(150, TimeUnit.MILLISECONDS)
    } catch (_: java.util.concurrent.TimeoutException) {
        false
    } finally {
        live.set(false)
    }
}

/** Direct NSTextView operations never enter NSApplication's menu or global shortcut routing. */
@Suppress("ReturnCount") // Explicit rejection precedes every native editing operation.
internal fun editNativeAddress(
    editing: MacAddressEditing,
    event: AppInputEvent.Key,
): Boolean {
    if (editing.closed || event.action != "down") return false
    val editor = pointer(editing.view, "currentEditor") ?: return false
    val command = nativeAddressCommand(event)
    if (command != null) {
        if (editing.command(command, event.shift)) return true
        if (!MacToolbarRuntime.supports(editor, command)) return false
        send(editor, command, null)
        revealNativeAddressCaret(editor)
        return true
    }
    if (listOf("Shift", "Control", "Alt", "Meta").any { event.code.startsWith(it) }) {
        return true
    }
    val modified = event.ctrl || event.meta || event.alt
    val printable = event.key.codePointCount(0, event.key.length) == 1 && event.key.none { it.isISOControl() }
    if (modified || !printable) {
        return false
    }
    send(editor, "insertText:replacementRange:", string(event.key), MacTextRange(-1L, 0L))
    revealNativeAddressCaret(editor)
    return true
}

// These tables translate individual keys; branches do not combine authority or ownership decisions.
@Suppress("CyclomaticComplexMethod")
private fun nativeAddressShortcut(event: AppInputEvent.Key): String? =
    when (event.code) {
        "KeyA", "KeyL" -> {
            "selectAll:"
        }

        "KeyC" -> {
            "copy:"
        }

        "KeyX" -> {
            "cut:"
        }

        "KeyV" -> {
            "paste:"
        }

        "KeyZ" -> {
            if (event.shift) "redo:" else "undo:"
        }

        "ArrowLeft", "Home" -> {
            if (event.shift) "moveToBeginningOfLineAndModifySelection:" else "moveToBeginningOfLine:"
        }

        "ArrowRight", "End" -> {
            if (event.shift) "moveToEndOfLineAndModifySelection:" else "moveToEndOfLine:"
        }

        "Backspace" -> {
            "deleteToBeginningOfLine:"
        }

        else -> {
            null
        }
    }

@Suppress("CyclomaticComplexMethod")
private fun nativeAddressCommand(event: AppInputEvent.Key): String? {
    if (event.meta || event.ctrl) return nativeAddressShortcut(event)
    return when (event.code) {
        "Enter", "NumpadEnter" -> {
            "insertNewline:"
        }

        "Backspace" -> {
            if (event.alt) "deleteWordBackward:" else "deleteBackward:"
        }

        "Delete" -> {
            if (event.alt) "deleteWordForward:" else "deleteForward:"
        }

        "ArrowLeft" -> {
            horizontalAddressCommand(event, "Left")
        }

        "ArrowRight" -> {
            horizontalAddressCommand(event, "Right")
        }

        "ArrowUp" -> {
            "moveUp:"
        }

        "ArrowDown" -> {
            "moveDown:"
        }

        "Home" -> {
            if (event.shift) "moveToBeginningOfLineAndModifySelection:" else "moveToBeginningOfLine:"
        }

        "End" -> {
            if (event.shift) "moveToEndOfLineAndModifySelection:" else "moveToEndOfLine:"
        }

        "Tab" -> {
            if (event.shift) "insertBacktab:" else "insertTab:"
        }

        "Escape" -> {
            "cancelOperation:"
        }

        else -> {
            null
        }
    }
}

private fun horizontalAddressCommand(
    event: AppInputEvent.Key,
    direction: String,
): String =
    when {
        event.alt && event.shift -> "moveWord${direction}AndModifySelection:"
        event.shift -> "move${direction}AndModifySelection:"
        event.alt -> "moveWord$direction:"
        else -> "move$direction:"
    }
