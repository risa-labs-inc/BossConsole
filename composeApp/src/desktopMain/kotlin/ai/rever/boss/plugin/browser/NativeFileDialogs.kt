package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.callback.BrowserCallback
import com.teamdev.jxbrowser.browser.callback.OpenFileCallback
import com.teamdev.jxbrowser.browser.callback.OpenFilesCallback
import com.teamdev.jxbrowser.browser.callback.OpenFolderCallback
import com.teamdev.jxbrowser.browser.callback.SaveAsPdfCallback
import com.teamdev.jxbrowser.browser.callback.SaveFileCallback
import com.teamdev.jxbrowser.callback.Advisable
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Native macOS file panels for the page's own file dialogs.
 *
 * JxBrowser does not leave `<input type="file">` unanswered: both the Compose and the Swing
 * `BrowserView` install a set of *default* callbacks when they are created, and every one of
 * them shows a `javax.swing.JFileChooser`. On macOS that renders through `AquaFileChooserUI` -
 * a Swing re-creation of the pre-10.7 Open panel, with a "Where:" popup and a "File Format:"
 * combo - so uploading a file from BOSS looked a decade older than doing it from Safari or
 * Chrome, which both open the real `NSOpenPanel` (sidebar, column view, search, Recents).
 *
 * `java.awt.FileDialog` *is* that panel: its macOS peer (`sun.lwawt.macosx.CFileDialog`) is a
 * thin wrapper over `NSOpenPanel`/`NSSavePanel`. So the fix is not to restyle anything, it is
 * to answer these callbacks ourselves before JxBrowser installs its own.
 *
 * **Registration has to happen before the view is created.** `DefaultCallbacks.register()`
 * checks `advisable.get(type).isPresent()` and *skips* any callback already set, so setting
 * ours first wins and JxBrowser never installs the Swing chooser. [installOn] is therefore
 * called from browser construction, not from composition - and because the view's matching
 * `unregister()` only removes what it actually registered, ours survives a view being
 * detached and re-attached (tab switch, fullscreen, a popup adopted into a window).
 *
 * **macOS only, deliberately.** `FileDialog` is the wrong trade on the other two:
 * - **Windows**: `setFilenameFilter` is documented as a no-op there (the Win32 peer drives
 *   `GetOpenFileName` with filter *strings*, not a per-item Java callback), so an
 *   `accept=".png"` input would silently list every file; and `FileDialog` cannot select a
 *   directory at all, so `webkitdirectory` would hand Chromium a *file* path and quietly
 *   yield nothing. The `JFileChooser` default gets both right. A native Windows panel is
 *   worth having, but it needs the Win32 filter strings and a folder browser, not this.
 * - **Linux**: AWT resolves to `GtkFileDialogPeer` only when GTK loads, and otherwise falls
 *   back to `XFileDialogPeer`, a Motif-era dialog worse than the chooser it would replace.
 *
 * **What is not covered**: the auth browsers (`DesktopPasskeyBrowserView`,
 * `DesktopAuthBrandSite`) build their own `newBrowser()` and never call [installOn], so they
 * keep the Swing chooser. That is fine for a sign-in page with no upload field, and stated
 * here so the next reader does not have to infer it.
 */
object NativeFileDialogs {
    private val logger = BossLogger.forComponent("NativeFileDialogs")

    private val isMacOs =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    /** True when [installOn] does anything. See the class KDoc for why only macOS. */
    val isSupported: Boolean = isMacOs

    /**
     * Point [browser]'s page-driven file dialogs at the OS ones, where that is an improvement.
     *
     * Safe to call more than once for the same browser - `Advisable.set` replaces.
     */
    // Deliberately broad: a browser closed between creation and here throws rather than
    // no-ops, and losing the native panel is cosmetic. It must never fail browser creation.
    @Suppress("TooGenericExceptionCaught")
    fun installOn(browser: Advisable<BrowserCallback>) {
        if (!isSupported) return
        try {
            registerOn(browser)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not install native file dialogs", error = e)
        }
    }

    /**
     * The registration itself, with no platform gate, so a test can assert the full set on
     * every CI leg rather than sleeping through two of the three.
     *
     * Every dialog the page can raise has to be claimed here. Dropping one line silently
     * reinstates the Swing chooser for that dialog and nothing else in the tree notices,
     * which is what `NativeFileDialogsTest` exists to catch.
     */
    internal fun registerOn(target: Advisable<BrowserCallback>) {
        target.set(OpenFileCallback::class.java, OpenFileCallback(::onOpenFile))
        target.set(OpenFilesCallback::class.java, OpenFilesCallback(::onOpenFiles))
        target.set(OpenFolderCallback::class.java, OpenFolderCallback(::onOpenFolder))
        target.set(SaveFileCallback::class.java, SaveFileCallback(::onSaveFile))
        target.set(SaveAsPdfCallback::class.java, SaveAsPdfCallback(::onSaveAsPdf))
    }

    // ============================================================
    // CALLBACKS
    // ============================================================

    private fun onOpenFile(
        params: OpenFileCallback.Params,
        action: OpenFileCallback.Action,
    ) = answerOnce(
        pick = {
            showOpen(
                suggestedDirectory = params.suggestedDirectory(),
                extensions = params.acceptableExtensions(),
                acceptAll = params.acceptAll(),
                multiple = false,
            ).firstOrNull()
        },
        open = action::open,
        cancel = action::cancel,
    )

    // The spread is over a user's selection, so a handful of paths at most, and
    // Action.open is Java varargs with no collection overload to prefer.
    @Suppress("SpreadOperator")
    private fun onOpenFiles(
        params: OpenFilesCallback.Params,
        action: OpenFilesCallback.Action,
    ) = answerOnce(
        pick = {
            // OpenFilesCallback.Params carries no suggestedDirectory and no acceptAll,
            // unlike the single-file one. Extensions still narrow the panel.
            showOpen(
                suggestedDirectory = "",
                extensions = params.acceptableExtensions(),
                acceptAll = false,
                multiple = true,
            ).takeIf { it.isNotEmpty() }
        },
        open = { paths -> action.open(*paths.toTypedArray()) },
        cancel = action::cancel,
    )

    private fun onOpenFolder(
        params: OpenFolderCallback.Params,
        action: OpenFolderCallback.Action,
    ) = answerOnce(
        pick = { showOpenFolder(params.suggestedDirectory()) },
        open = action::open,
        cancel = action::cancel,
    )

    private fun onSaveFile(
        params: SaveFileCallback.Params,
        action: SaveFileCallback.Action,
    ) = answerOnce(
        pick = {
            showSave(
                suggestedFileName = params.suggestedFileName(),
                suggestedDirectory = params.suggestedDirectory(),
                extensions = params.acceptableExtensions(),
                acceptAll = params.acceptAll(),
            )
        },
        open = action::save,
        cancel = action::cancel,
    )

    private fun onSaveAsPdf(
        params: SaveAsPdfCallback.Params,
        action: SaveAsPdfCallback.Action,
    ) = answerOnce(
        pick = {
            showSave(
                suggestedFileName = params.suggestedFileName(),
                // The only Params here that hands back a Path rather than a String.
                suggestedDirectory = params.suggestedDirectory()?.toString().orEmpty(),
                extensions = listOf(PDF),
                acceptAll = false,
                // The panel's name field is editable, so the user can clear the extension
                // off a file Chromium is about to write PDF bytes into.
            )?.let { pathWithExtension(it, PDF) }
        },
        open = action::save,
        cancel = action::cancel,
    )

    // ============================================================
    // PLUMBING
    // ============================================================

    /**
     * Run [pick] on the EDT and answer the callback exactly once, with [open] or [cancel].
     *
     * These are async callbacks: JxBrowser hands over an action and returns, and the page's
     * file input stays pending until something answers. Dropping the answer wedges that input
     * for the life of the tab, so a throw inside the dialog still has to produce a cancel.
     * Answering *twice* is the other failure, hence the flag rather than two exit paths.
     */
    // Deliberately broad, both of them: the point is that no failure, whatever its type,
    // leaves the page waiting. Error still propagates, as elsewhere in this package.
    @Suppress("TooGenericExceptionCaught")
    internal fun <T : Any> answerOnce(
        pick: () -> T?,
        open: (T) -> Unit,
        cancel: () -> Unit,
    ) {
        val answered = AtomicBoolean(false)

        fun answer(picked: T?) {
            if (!answered.compareAndSet(false, true)) return
            try {
                if (picked != null) open(picked) else cancel()
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Could not answer a file dialog", error = e)
            }
        }

        try {
            SwingUtilities.invokeLater {
                val picked =
                    try {
                        pick()
                    } catch (t: Throwable) {
                        // Throwable, not Exception. The house rule elsewhere in this package is
                        // that Error propagates, and it still does - but an AWTError raised while
                        // creating the peer would otherwise escape this invokeLater body and leave
                        // the page's file input pending for the life of the tab, which is the
                        // exact failure this function exists to prevent. Answer, then rethrow, so
                        // both properties hold.
                        logger.warn(LogCategory.BROWSER, "Native file dialog failed", error = t)
                        answer(null)
                        throw t
                    }
                answer(picked)
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not show a native file dialog", error = e)
            answer(null)
        }
    }
}

private const val PDF = "pdf"

/**
 * Asks `NSOpenPanel` for a directory rather than a file. Process-wide; see [showModal].
 */
private fun showOpen(
    suggestedDirectory: String,
    extensions: List<String>,
    acceptAll: Boolean,
    multiple: Boolean,
): List<Path> {
    val dialog = newDialog("Open", FileDialog.LOAD, suggestedDirectory)
    dialog.isMultipleMode = multiple
    dialog.narrowTo(extensions, acceptAll)
    dialog.showModal(directories = false)
    // getFiles() is populated in both modes, not only multi-select: CFileDialog.Task.run
    // calls setFiles unconditionally, and getFile()/getDirectory() are just the
    // single-selection view of that same array. Empty means cancelled.
    return dialog.files.orEmpty().map { it.toPath() }
}

private fun showOpenFolder(suggestedDirectory: String): Path? {
    val dialog = newDialog("Open", FileDialog.LOAD, suggestedDirectory)
    dialog.showModal(directories = true)
    val name = dialog.file
    val directory = dialog.directory
    // In directory mode the chosen folder comes back split: directory is its parent, file
    // is its own name. Cancelling leaves both null.
    return when {
        directory == null -> null
        name == null -> File(directory).toPath()
        else -> File(directory, name).toPath()
    }
}

private fun showSave(
    suggestedFileName: String,
    suggestedDirectory: String,
    extensions: List<String>,
    acceptAll: Boolean,
): Path? {
    val dialog = newDialog("Save", FileDialog.SAVE, suggestedDirectory)
    if (suggestedFileName.isNotBlank()) dialog.file = safePrefill(suggestedFileName)
    dialog.narrowTo(extensions, acceptAll)
    dialog.showModal(directories = false)
    val name = dialog.file
    val directory = dialog.directory
    return if (name != null && directory != null) File(directory, name).toPath() else null
}

/**
 * An OS file panel, owned by the window the user is actually looking at.
 *
 * The owner matters: with a null one the native panel can open BEHIND the Compose window, so
 * the click that opened it looks like it did nothing. Same fix as `DirectoryPickerProviderImpl`,
 * except that one only handles a `Frame` owner - the active window is a `Dialog` whenever a
 * BOSS modal is up, which is the very case the null owner misbehaves in.
 */
private fun newDialog(
    title: String,
    mode: Int,
    suggestedDirectory: String,
): FileDialog =
    ownedFileDialog(title, mode).apply {
        val suggested = File(suggestedDirectory)
        if (suggestedDirectory.isNotBlank() && suggested.isDirectory) {
            directory = suggested.absolutePath
        }
    }

/**
 * Show the panel, then always give the native peer back.
 *
 * [MAC_DIRECTORY_MODE] is process-wide and read by the peer when the panel is created, and a
 * modal `FileDialog` runs a **nested event loop** on the EDT that keeps dispatching other
 * `invokeLater` blocks - so a second dialog really can be created inside this one's loop.
 * Every dialog here states its own mode immediately before showing and the restore unwinds in
 * reverse order; the property is cleared rather than written back as `"false"`, so an absent
 * property stays absent.
 *
 * **That invariant is one-directional.** It holds for these dialogs nested inside anything,
 * because they state their own mode. It does *not* hold in reverse: `DesktopFilePicker`,
 * `FilePickerProviderFactory` and `SettingsComponents` each create a `LOAD` dialog without
 * touching the flag, so one of those opened inside [showOpenFolder]'s nested loop would come
 * up as a directory chooser. Narrow in practice, since the panel is app-modal and it therefore
 * takes a non-UI-driven caller such as a plugin invoking `openFile` off a background thread.
 * The durable fix is to route every `FileDialog` in the tree through this helper, which is a
 * wider change than this one. `SAVE` dialogs are immune either way: the flag only affects
 * `NSOpenPanel`.
 */

/**
 * Narrow the panel to [extensions], unless the page said any file will do.
 *
 * The macOS peer asks this filter per URL and answers yes for anything that is not a regular
 * file, so directories stay navigable.
 */
private fun FileDialog.narrowTo(
    extensions: List<String>,
    acceptAll: Boolean,
) {
    if (acceptAll) return
    val suffixes = fileDialogSuffixes(extensions)
    if (suffixes.isEmpty()) return
    setFilenameFilter { _, name -> matchesSuffix(name, suffixes) }
}

/**
 * The page's suggested name, reduced to something safe to put in the panel's name field.
 *
 * Only the last path segment survives, so a suggestion of `../../x` cannot steer where the
 * panel opens, and control characters are dropped. Deliberately **not** `FileNameSanitizer`,
 * which the download handler uses: that maps everything outside a small ASCII set to `_`, and
 * this string is shown to the user and edited by them, so a page titled in Japanese would
 * pre-fill as `___.pdf`. Nothing here has to be filesystem-safe - the path returned is the one
 * the panel reports back after the user has accepted it.
 */
internal fun safePrefill(suggested: String): String {
    val name = File(suggested).name.filter { it.code >= 0x20 && it.code != DEL }
    // File("..").name is ".." - a legal thing to type, but accepting it hands back a
    // directory where the page is expecting a file.
    return if (name == "." || name == "..") "" else name
}

private const val DEL = 0x7F

/**
 * [path] with [extension] appended unless it already carries it.
 *
 * Not an extension function on `Path`: `withExtension` is generic enough that putting it in
 * module scope invites a surprising resolution somewhere else.
 *
 * **Known limitation**: the append happens after the panel has closed, so the overwrite prompt
 * the user answered was for the name they typed. Saving as `report` where `report.pdf` already
 * exists overwrites it unprompted. `FileDialog` exposes no allowed-file-types API to let
 * `NSSavePanel` append the extension itself, which is what would fix this properly.
 */
internal fun pathWithExtension(
    path: Path,
    extension: String,
): Path {
    val name = path.fileName?.toString().orEmpty()
    return if (name.endsWith(".$extension", ignoreCase = true)) path else path.resolveSibling("$name.$extension")
}

/**
 * The suffixes an `accept` attribute names, or nothing when it cannot be expressed as
 * suffixes at all. Normalised for [matchesSuffix]; a leading dot is common and would double
 * up in the `.ext` comparison.
 *
 * **A token that is not a suffix makes the whole filter unrepresentable, not just itself.**
 * An `accept` of `.png` plus an image MIME pattern admits every image, so filtering on the
 * surviving `png` would grey out `photo.jpg` - a file the page explicitly allows - and that is
 * worse than not filtering. The same all-or-nothing rule covers a pure MIME pattern, which
 * names no suffix at all. Empty means "no filter", so the panel shows everything rather than
 * nothing.
 */
internal fun fileDialogSuffixes(extensions: List<String>): List<String> {
    val tokens = extensions.mapNotNull { it.trim().removePrefix(".").takeIf(String::isNotEmpty) }
    val suffixes = tokens.filterNot { it.contains('/') || it.contains('*') }
    return if (suffixes.size == tokens.size) suffixes else emptyList()
}

/** Whether [name] carries one of [suffixes]. Case-insensitive: the disk is not. */
internal fun matchesSuffix(
    name: String,
    suffixes: List<String>,
): Boolean = suffixes.any { name.endsWith(".$it", ignoreCase = true) }
