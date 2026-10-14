package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.browser.activeDialogOwner
import ai.rever.boss.plugin.browser.ownedFileDialog
import ai.rever.boss.plugin.browser.showModal
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.FileDialog
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private val logger = BossLogger.forComponent("FilePickerProvider")

/**
 * Desktop implementation of FilePickerProvider factory.
 */
actual fun createFilePickerProvider(): FilePickerProvider? = DesktopFilePickerProvider()

/**
 * Extensions as this file's filters mean them: bare, with no leading dot.
 *
 * The API takes `List<String>` and says nothing about the spelling, so plugins pass both `csv` and
 * `.csv`. Every consumer here then appends its own dot: the macOS filter tests `name.endsWith(".$ext")`
 * and Swing's [FileNameExtensionFilter] takes bare extensions and adds the dot itself. A caller who
 * wrote `.csv` was therefore matched against `..csv`, which no file is named, so the filter hid
 * every valid choice instead of narrowing to it.
 *
 * Normalising on the way in rather than declaring a bare-extension contract: the contract is not
 * checkable at the plugin boundary, and the failure it produces is a dialog that silently shows
 * nothing, which reads as "there are no matching files" rather than as a bug.
 *
 * Blank entries are dropped. A list of nothing but blanks yields an empty list, which every caller
 * below treats as "no filter" rather than as a filter that matches nothing.
 */
internal fun normalizedExtensions(filters: List<String>?): List<String> =
    filters
        .orEmpty()
        .map { it.trim().removePrefix(".").trim() }
        .filter { it.isNotEmpty() }

/**
 * Whether [fileName] carries one of [extensions], which is the macOS filter's whole rule.
 *
 * An empty list accepts everything: the callers only install a filter when there is one to install,
 * and this keeps the two in step if that ever changes.
 */
internal fun matchesAnyExtension(
    fileName: String,
    extensions: List<String>,
): Boolean = extensions.isEmpty() || extensions.any { fileName.endsWith(".$it", ignoreCase = true) }

/**
 * Wraps [onResult] so it runs at most once.
 *
 * `onResult` is the only channel this API has, and it was being called inside the same `try` that
 * catches picker failures. So a plugin whose callback threw got its result and then a `null`
 * immediately after, which is indistinguishable from the user cancelling: one pick, two answers,
 * the second one wrong.
 *
 * With this the two throws are told apart by when they happen. A throw from the picker itself
 * arrives before delivery, so the catch still reports the cancel. A throw from the plugin's own
 * callback arrives after it, so the catch logs and delivers nothing further.
 *
 * Not synchronised: every caller runs inside `SwingUtilities.invokeLater`, so both the delivery and
 * the catch that might re-deliver are on the EDT.
 */
internal fun deliverOnce(onResult: (String?) -> Unit): (String?) -> Unit {
    var delivered = false
    return { value ->
        if (!delivered) {
            // Set before invoking, so a throw from onResult cannot be retried by the catch below.
            delivered = true
            onResult(value)
        }
    }
}

/**
 * Desktop file picker provider using AWT/Swing dialogs.
 *
 * Every panel here is owned by the window the user is looking at and states its own directory
 * mode, through [ownedFileDialog] and [showModal]. Both matter for a panel a PLUGIN opens:
 *
 *  - An ownerless panel has no window to be modal to and none to come forward over, so it can open
 *    behind the Compose window. `onResult(null)` then arrives looking exactly like a cancel, and
 *    the plugin reports that the user changed their mind.
 *  - `apple.awt.fileDialogForDirectories` is process-wide and read when the peer is created, and a
 *    modal panel runs a nested event loop that keeps dispatching. A plugin picker opened while the
 *    browser's folder panel is up therefore came up as a DIRECTORY chooser, which
 *    `NativeFileDialogs` already recorded as a live consequence of this file not stating its mode.
 */
private class DesktopFilePickerProvider : FilePickerProvider {
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    // The spread is over a plugin's declared filter list, a handful of entries at most, and
    // FileNameExtensionFilter is Java varargs with no collection overload to prefer. Same
    // reasoning, and the same suppression, as NativeFileDialogs.onOpenFiles.
    @Suppress("SpreadOperator")
    override fun pickFile(
        title: String?,
        filters: List<String>?,
        onResult: (String?) -> Unit,
    ) {
        val deliver = deliverOnce(onResult)
        val extensions = normalizedExtensions(filters)
        SwingUtilities.invokeLater {
            try {
                if (isMacOS) {
                    val dialog = ownedFileDialog(title ?: "Open File", FileDialog.LOAD)
                    if (extensions.isNotEmpty()) {
                        dialog.setFilenameFilter { _, name -> matchesAnyExtension(name, extensions) }
                    }
                    dialog.showModal(directories = false)
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        deliver("$dir$file")
                    } else {
                        deliver(null)
                    }
                } else {
                    val chooser =
                        JFileChooser().apply {
                            dialogTitle = title ?: "Open File"
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            currentDirectory = File(System.getProperty("user.home"))
                            if (extensions.isNotEmpty()) {
                                isAcceptAllFileFilterUsed = false
                                addChoosableFileFilter(
                                    FileNameExtensionFilter(
                                        extensions.joinToString(", ") { "*.$it" },
                                        *extensions.toTypedArray(),
                                    ),
                                )
                            }
                        }
                    val result = chooser.showOpenDialog(activeDialogOwner())
                    if (result == JFileChooser.APPROVE_OPTION) {
                        deliver(chooser.selectedFile?.absolutePath)
                    } else {
                        deliver(null)
                    }
                }
            } catch (e: Exception) {
                // onResult(null) is the only channel this API has, and the plugin cannot tell it
                // apart from a cancel. The log is therefore the only place the difference exists,
                // so it is an error rather than a warning and says so explicitly.
                logger.error(
                    LogCategory.SYSTEM,
                    "File picker failed; the plugin will see this as a user cancel",
                    error = e,
                )
                deliver(null)
            }
        }
    }

    @Suppress("SpreadOperator")
    override fun pickSaveFile(
        suggestedFileName: String?,
        filters: List<String>?,
        onResult: (String?) -> Unit,
    ) {
        val deliver = deliverOnce(onResult)
        val extensions = normalizedExtensions(filters)
        SwingUtilities.invokeLater {
            try {
                if (isMacOS) {
                    val dialog = ownedFileDialog("Save File", FileDialog.SAVE)
                    if (suggestedFileName != null) {
                        dialog.file = suggestedFileName
                    }
                    // A SAVE panel's filter narrows what is LISTED. It does not constrain what the
                    // user types, so the returned path may still carry any extension or none.
                    if (extensions.isNotEmpty()) {
                        dialog.setFilenameFilter { _, name -> matchesAnyExtension(name, extensions) }
                    }
                    dialog.showModal(directories = false)
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        deliver("$dir$file")
                    } else {
                        deliver(null)
                    }
                } else {
                    val chooser =
                        JFileChooser().apply {
                            dialogTitle = "Save File"
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            currentDirectory = File(System.getProperty("user.home"))
                            if (suggestedFileName != null) {
                                selectedFile = File(suggestedFileName)
                            }
                            if (extensions.isNotEmpty()) {
                                isAcceptAllFileFilterUsed = false
                                addChoosableFileFilter(
                                    FileNameExtensionFilter(
                                        extensions.joinToString(", ") { "*.$it" },
                                        *extensions.toTypedArray(),
                                    ),
                                )
                            }
                        }
                    val result = chooser.showSaveDialog(activeDialogOwner())
                    if (result == JFileChooser.APPROVE_OPTION) {
                        deliver(chooser.selectedFile?.absolutePath)
                    } else {
                        deliver(null)
                    }
                }
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Save file picker failed; the plugin will see this as a user cancel",
                    error = e,
                )
                deliver(null)
            }
        }
    }
}
