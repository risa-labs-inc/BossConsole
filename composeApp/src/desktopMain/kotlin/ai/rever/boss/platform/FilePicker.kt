package ai.rever.boss.platform

import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

private val filePickerLogger = BossLogger.forComponent("FilePicker")

/**
 * Ceiling on a picked file's size.
 *
 * A 100k-entry export is roughly 10 MB, so this is comfortably above anything
 * real while keeping the read — and the plaintext String it decodes into —
 * small enough not to stall the UI or balloon the heap.
 */
private const val MAX_PICKED_FILE_BYTES = 16L * 1024 * 1024

@Composable
actual fun rememberFilePicker(
    onFileSelected: (path: String?, content: String?, tooLarge: Boolean) -> Unit,
    fileExtensions: List<String>,
    title: String,
): FilePicker =
    remember {
        DesktopFilePicker(onFileSelected, fileExtensions, title)
    }

class DesktopFilePicker(
    private val onFileSelected: (path: String?, content: String?, tooLarge: Boolean) -> Unit,
    private val fileExtensions: List<String>,
    private val title: String = "Select File",
) : FilePicker {
    override fun pickFile() {
        try {
            val fileDialog =
                FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                    // Set file filter for JSON files
                    if (fileExtensions.isNotEmpty()) {
                        setFilenameFilter { _, name ->
                            fileExtensions.any { name.endsWith(".$it", ignoreCase = true) }
                        }
                    }
                    isVisible = true
                }

            val selectedFile = fileDialog.file
            val selectedDir = fileDialog.directory

            if (selectedFile != null && selectedDir != null) {
                val file = File(selectedDir, selectedFile)

                // Bounded read: this runs on the caller's thread (the EDT for a
                // dialog), and an accidentally-picked multi-gigabyte file would
                // otherwise freeze the UI on its way to an OutOfMemoryError.
                if (file.length() > MAX_PICKED_FILE_BYTES) {
                    filePickerLogger.warn(
                        LogCategory.FILE,
                        "Picked file is too large to read - reporting no selection",
                        mapOf("bytes" to file.length()),
                    )
                    onFileSelected(null, null, true)
                    return
                }

                onFileSelected(file.absolutePath, file.readText(), false)
            } else {
                onFileSelected(null, null, false)
            }
        } catch (e: Exception) {
            filePickerLogger.warn(LogCategory.FILE, "Failed to read picked file - reporting no selection", error = e)
            onFileSelected(null, null, false)
        }
    }
}

/**
 * Desktop implementation of pickSaveFile using AWT FileDialog.
 * Runs synchronously on the EDT (Event Dispatch Thread) as required by JxBrowser callbacks.
 */
actual fun pickSaveFile(
    suggestedFileName: String,
    initialDirectory: String?,
    allowedExtensions: List<String>,
): String? {
    // Sanitize the suggested file name for security
    val sanitizedFileName = FileNameSanitizer.sanitize(suggestedFileName)

    var result: String? = null

    try {
        // Must run on EDT to avoid AWT threading issues
        SwingUtilities.invokeAndWait {
            val fileDialog =
                FileDialog(null as Frame?, "Save File", FileDialog.SAVE).apply {
                    // Set suggested file name
                    file = sanitizedFileName

                    // Set initial directory if provided
                    initialDirectory?.let { directory = it }

                    // Set file filter if extensions specified
                    if (allowedExtensions.isNotEmpty()) {
                        setFilenameFilter { _, name ->
                            allowedExtensions.any { ext ->
                                name.endsWith(".$ext", ignoreCase = true)
                            } || allowedExtensions.contains("*")
                        }
                    }

                    isVisible = true
                }

            val selectedFile = fileDialog.file
            val selectedDir = fileDialog.directory

            if (selectedFile != null && selectedDir != null) {
                result = File(selectedDir, selectedFile).absolutePath
            }
        }
    } catch (e: Exception) {
        filePickerLogger.warn(LogCategory.FILE, "Error showing save file dialog", error = e)
        result = null
    }

    return result
}

/**
 * Desktop implementation of confirmExecutableDownload using a Swing confirm dialog.
 * Runs synchronously on the EDT, same threading requirement as [pickSaveFile].
 */
actual fun confirmExecutableDownload(fileName: String): Boolean =
    confirmExecutableDownloadOnEdt {
        // A background download still needs an owner that the operator can raise.
        // Without any usable BOSS window, refuse instead of creating an orphan modal.
        val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val owner =
            pickDialogOwner(activeWindow, WindowFocusManager.candidateWindowsForDialogOwner())
                ?: return@confirmExecutableDownloadOnEdt JOptionPane.CLOSED_OPTION
        // An owner is not visibility: a modal JDialog blocks input to its owner but does not by
        // itself raise BOSS above other applications, so a background download could still show
        // this prompt somewhere the operator never sees while the JxBrowser callback thread waits
        // behind it. Already on the EDT here (confirmExecutableDownloadOnEdt's own Runnable), so
        // this can call AWT directly rather than needing invokeLater the way a cross-thread caller
        // (WindowFocusManager.focusWindow) does.
        owner.toFront()
        owner.requestFocus()
        JOptionPane.showOptionDialog(
            owner,
            "\"$fileName\" may be executable. Only download and run it if you trust its source.",
            "Confirm download",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            ExecutableDownloadOption.entries.toTypedArray(),
            ExecutableDownloadOption.CANCEL,
        )
    }

/**
 * Resolution policy behind [confirmExecutableDownload]'s dialog owner, kept pure so the ordering
 * can be asserted without live AWT windows (the same reason
 * [ai.rever.boss.utils.resolveActionableWindowIdFrom] is split out next to it).
 *
 * Prefers [activeWindow] - the window actually holding OS focus - over every entry in
 * [candidates], which is [WindowFocusManager.candidateWindowsForDialogOwner]'s ordered list in
 * production. Each candidate is tried in order rather than only the first: a disposed-but-not-yet-
 * unregistered window must not refuse a download while a second, genuinely usable window is open.
 * Returns null - refuse rather than orphan a modal - only when nothing offered is displayable.
 */
internal fun pickDialogOwner(
    activeWindow: Window?,
    candidates: List<Window>,
): Window? = (listOfNotNull(activeWindow) + candidates).firstOrNull { it.isDisplayable }

private enum class ExecutableDownloadOption(
    private val label: String,
) {
    DOWNLOAD("Download"),
    CANCEL("Cancel"),
    ;

    override fun toString(): String = label
}

/** The dialog is injectable so consent, failure and EDT dispatch can be tested without a window. */
internal fun confirmExecutableDownloadOnEdt(showDialog: () -> Int): Boolean {
    var proceed = false
    val prompt =
        Runnable {
            // Custom Swing options return their index. Map through the same entries that
            // populated the dialog, so changing their order cannot turn Cancel into consent.
            proceed = ExecutableDownloadOption.entries.getOrNull(showDialog()) == ExecutableDownloadOption.DOWNLOAD
        }
    return try {
        if (SwingUtilities.isEventDispatchThread()) {
            prompt.run()
        } else {
            SwingUtilities.invokeAndWait(prompt)
        }
        proceed
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        filePickerLogger.warn(LogCategory.FILE, "Executable download warning interrupted", error = e)
        false
    } catch (e: Exception) {
        filePickerLogger.warn(LogCategory.FILE, "Error showing executable download warning", error = e)
        false
    }
}
