package ai.rever.boss.plugin.browser

import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window

/**
 * The one place an AWT file panel is created, so every caller gets an owner and the right mode.
 *
 * Two things go wrong with `FileDialog(null as Frame?, ...)`, and they are different failures:
 *
 * **No owner.** The panel has no window to be modal to and no window to come forward over, so it
 * can open behind the Compose window. The click that opened it then looks like it did nothing.
 * Taking the owner from the focus manager rather than from a stored reference matters, because the
 * active window is a `Dialog` whenever a BOSS modal is up, which is the case the null owner
 * misbehaves in most.
 *
 * **The directory flag is process-wide.** `apple.awt.fileDialogForDirectories` is read by the
 * native peer when the panel is created, and a modal `FileDialog` runs a nested event loop on the
 * EDT that keeps dispatching other `invokeLater` blocks. So a file panel really can be created
 * inside a folder panel's loop, and if it does not state its own mode it comes up as a directory
 * chooser. [showModal] states the mode immediately before showing and unwinds in reverse, clearing
 * the property when it was absent rather than writing "false" back.
 *
 * `NativeFileDialogs` already did both of these for the browser's own panels, and recorded that
 * every other `FileDialog` in the tree should be routed through the same helper. This is that
 * helper, extracted so there is one copy rather than a comment asking for one.
 */
internal fun ownedFileDialog(
    title: String,
    mode: Int,
): FileDialog {
    val dialog =
        when (val active = activeDialogOwner()) {
            is Frame -> FileDialog(active, title, mode)
            is Dialog -> FileDialog(active, title, mode)
            else -> FileDialog(null as Frame?, title, mode)
        }
    return dialog.apply { isAlwaysOnTop = true }
}

/**
 * The window a native panel should belong to, or null when nothing is focused.
 *
 * Also usable as a Swing parent `Component`, which is what `JFileChooser` wants on the platforms
 * that do not use `FileDialog`.
 */
internal fun activeDialogOwner(): Window? = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

/**
 * Show the panel with [MAC_DIRECTORY_MODE] stated, then always give the native peer back.
 *
 * The property is process-wide and read at creation time, so stating it here rather than trusting
 * the ambient value is what stops a panel nested inside another one inheriting the wrong mode. The
 * restore unwinds in reverse order, and an absent property is cleared rather than set to "false",
 * so an absent property stays absent.
 */
internal fun FileDialog.showModal(directories: Boolean) {
    try {
        withDirectoryMode(directories) { isVisible = true }
    } finally {
        dispose()
    }
}

/**
 * Run [body] with [MAC_DIRECTORY_MODE] set, restoring exactly what was there before.
 *
 * Separate from [showModal] because showing a panel needs a display and this does not, so the part
 * that is easy to get wrong is the part a test can reach. Three things it has to get right, each of
 * which has a test: an absent property is CLEARED rather than written back as "false", so absent
 * stays absent; a present one is restored verbatim; and nesting unwinds in reverse, which is the
 * case that exists at all only because a modal panel keeps dispatching on the EDT.
 */
internal fun <T> withDirectoryMode(
    directories: Boolean,
    body: () -> T,
): T {
    val previous = System.getProperty(MAC_DIRECTORY_MODE)
    System.setProperty(MAC_DIRECTORY_MODE, directories.toString())
    return try {
        body()
    } finally {
        if (previous == null) {
            System.clearProperty(MAC_DIRECTORY_MODE)
        } else {
            System.setProperty(MAC_DIRECTORY_MODE, previous)
        }
    }
}

internal const val MAC_DIRECTORY_MODE = "apple.awt.fileDialogForDirectories"
