package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import javax.swing.SwingUtilities

/** AppKit delegate events are delivered in order to the browser's Compose state on the EDT. */
internal class MacAddressEditing {
    var input: NativeTitleBarTextInput? = null
    var view: Pointer? = null
    var closed = false
    private var typed = ""
    private var revision = -1
    private var completing = false
    private var allowCompletion = true

    fun update(
        next: NativeTitleBarTextInput,
        changedBrowser: Boolean,
    ) {
        input = next
        val model = next.address
        val editor = pointer(view, "currentEditor")
        val reset = changedBrowser || revision != model?.revision
        if (editor == null || reset) {
            completing = false
            val text = model?.text ?: next.value
            send(view, "setStringValue:", string(text))
            typed = text
            if (editor != null && model != null) {
                send(
                    editor,
                    "setSelectedRange:",
                    MacTextRange(
                        model.selectionStart.toLong(),
                        (model.selectionEnd - model.selectionStart).coerceAtLeast(0).toLong(),
                    ),
                )
            }
        }
        revision = model?.revision ?: -1
        if (editor != null && model != null) applyCompletion(editor, model)
    }

    private fun applyCompletion(
        editor: Pointer,
        model: ai.rever.boss.plugin.browser.BrowserAddressBarState,
    ) {
        if (completing || !allowCompletion || number(editor, "hasMarkedText") != 0L) return
        val caret = selection(editor)
        val suffix =
            addressCompletion(typed, model.text, model.completion, caret.first, caret.second)
                ?: return
        send(editor, "setString:", string(suffix))
        val range = MacTextRange(typed.length.toLong(), (suffix.length - typed.length).toLong())
        send(editor, "setSelectedRange:", range)
        completing = true
    }

    fun notification(name: String) {
        val current = input ?: return
        when (name) {
            "end" -> {
                deliver { current.address?.onFocusLost?.invoke() }
            }

            "change" -> {
                val editor = pointer(view, "currentEditor") ?: return
                val text = pointer(pointer(editor, "string"), "UTF8String")?.getString(0).orEmpty()
                val selection = selection(editor)
                allowCompletion = text.length >= typed.length
                typed = text
                completing = false
                deliver { current.address?.onEdit?.invoke(text, selection.first, selection.second) }
            }
        }
    }

    fun command(
        selector: String,
        shift: Boolean,
        onSubmit: () -> Unit = ::submit,
    ): Boolean =
        when {
            closed || input == null -> {
                false
            }

            selector == "insertNewline:" -> {
                onSubmit()
                true
            }

            else -> {
                editingCommand(selector, shift)
            }
        }

    private fun editingCommand(
        selector: String,
        shift: Boolean,
    ): Boolean {
        val model = input?.address ?: return false
        val command =
            when (selector) {
                "moveDown:" -> "next"
                "moveUp:" -> "previous"
                "insertTab:" -> if (model.completion != null) "accept" else null
                "moveRight:" -> if (completingSelection()) "right" else null
                "cancelOperation:" -> "cancel"
                "deleteBackward:", "deleteForward:" -> if (shift && model.hasSelectedSuggestion) "delete" else null
                else -> null
            }
        if (command != null) deliver { model.onCommand(command) }
        return command != null
    }

    private fun completingSelection(): Boolean {
        val editor = pointer(view, "currentEditor") ?: return false
        val range = selection(editor)
        return completing && range.first == typed.length && range.second > range.first
    }

    fun submit() {
        val current = input ?: return
        if (typed.isBlank()) return
        send(pointer(view, "window"), "makeFirstResponder:", null)
        deliver {
            val model = current.address
            if (model != null) model.onCommand("submit") else current.onSubmit(typed)
            val handle =
                ai.rever.boss.plugin.browser.ActiveBrowserRegistry
                    .handleById(current.identity)
            (handle as? ai.rever.boss.plugin.browser.BrowserHandleImpl)?.focusPageAfterAddressCommit()
        }
    }

    private fun deliver(action: () -> Unit) {
        SwingUtilities.invokeLater { if (!closed) action() }
    }
}

private fun selection(editor: Pointer): Pair<Int, Int> =
    Memory(16).use { bytes ->
        val value = pointer(editor, "valueForKey:", string("selectedRange"))
        send(value, "getValue:size:", bytes, 16L)
        val start = bytes.getLong(0).toInt().coerceAtLeast(0)
        start to (start + bytes.getLong(8).toInt().coerceAtLeast(0))
    }

@Structure.FieldOrder("location", "length")
internal class MacTextRange(
    @JvmField var location: Long = 0,
    @JvmField var length: Long = 0,
) : Structure(),
    Structure.ByValue
