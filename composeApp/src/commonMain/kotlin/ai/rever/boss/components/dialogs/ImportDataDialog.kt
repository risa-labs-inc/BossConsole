package ai.rever.boss.components.dialogs

import ai.rever.boss.platform.rememberFilePicker
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.auth.AuthStateManager
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.services.importer.ImportFileReader
import ai.rever.boss.services.importer.ImportPreview
import ai.rever.boss.services.importer.ImportResult
import ai.rever.boss.services.importer.ImportService
import ai.rever.boss.services.importer.browser.BrowserImportService
import ai.rever.boss.services.importer.browser.DetectedBrowser
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the import flow currently is. */
private sealed interface ImportStage {
    data object ChooseSource : ImportStage

    data class Failed(
        val message: String,
    ) : ImportStage

    data class Review(
        val preview: ImportPreview,
        /** Why part of the source could not be read, when applicable. */
        val note: String? = null,
    ) : ImportStage

    data class Running(
        val done: Int,
        val total: Int,
    ) : ImportStage

    data class Finished(
        val passwords: ImportResult?,
        val bookmarks: ImportResult?,
        val cancelled: Boolean = false,
    ) : ImportStage
}

/**
 * Imports passwords and bookmarks from a browser export.
 *
 * Passwords need a signed-in user (they go into the encrypted vault) and
 * bookmarks need the bookmarks plugin. Either half can be unavailable without
 * blocking the other, so the dialog reports what it can and cannot do rather
 * than refusing to open.
 */
@Composable
fun ImportDataDialog(onDismiss: () -> Unit) {
    var stage by remember { mutableStateOf<ImportStage>(ImportStage.ChooseSource) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    // What has landed so far, so cancelling can still report it.
    val partial = remember { PartialProgress() }
    val scope = rememberCoroutineScope()

    // Collected, not read: signing in while the dialog is open should re-enable
    // the password half rather than leave it disabled until reopened.
    val currentUser by AuthStateManager.currentUser.collectAsState()
    val canImportPasswords = currentUser != null
    val bookmarkProvider = remember { BookmarkAPIAccess.getProvider() }

    var browsers by remember { mutableStateOf<List<DetectedBrowser>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }

    // Scan on open. Counts only — nothing is decrypted here, so this cannot
    // trigger a keychain prompt before the user has chosen anything.
    LaunchedEffect(Unit) {
        browsers = runCatching { BrowserImportService.scan() }.getOrDefault(emptyList())
        scanning = false
    }

    val picker = rememberImportPicker(scope) { stage = it }

    // Dropping the parsed data as the dialog closes keeps plaintext credentials
    // from outliving the flow that needed them.
    fun close() {
        runningJob?.cancel()
        stage = ImportStage.ChooseSource
        onDismiss()
    }

    // Closing mid-import would leave half the entries written, so the scrim and
    // Escape are inert while it runs; Cancel inside the progress stage is the
    // way out.
    DialogShell(dismissable = stage !is ImportStage.Running, onDismiss = ::close) {
        StageBody(
            stage = stage,
            canImportPasswords = canImportPasswords,
            bookmarkProvider = bookmarkProvider,
            browsers = browsers,
            scanning = scanning,
            onPickBrowser = { detected ->
                stage = ImportStage.Running(0, 0)
                runningJob =
                    scope.launch {
                        stage = readBrowser(detected, canImportPasswords)
                    }
            },
            onChooseFile = { picker.pickFile() },
            onRestart = { stage = ImportStage.ChooseSource },
            onCancel = ::close,
            onStart = { preview ->
                stage = ImportStage.Running(0, preview.plannedTotal(canImportPasswords, bookmarkProvider != null))
                runningJob =
                    scope.launch {
                        runImport(
                            preview = preview,
                            canImportPasswords = canImportPasswords,
                            bookmarkProvider = bookmarkProvider,
                            partial = partial,
                            // Ignore a late write. Cancellation isn't
                            // instantaneous: the loop's trailing onProgress can
                            // land after Cancel set Finished, flipping the
                            // dialog back to a full progress bar with no job
                            // running — and the scrim is inert while Running,
                            // so the only way out would be pressing Cancel
                            // again.
                            onStage = { next -> if (stage is ImportStage.Running) stage = next },
                        )
                    }
            },
            onCancelRunning = {
                // Rows already written are permanent, so report what actually
                // landed rather than claiming nothing happened.
                runningJob?.cancel()
                stage = ImportStage.Finished(partial.passwords, partial.bookmarks, cancelled = true)
            },
        )
    }

    // Nothing to import means nothing to hold on to.
    LaunchedEffect(stage) {
        if (stage is ImportStage.ChooseSource) runningJob = null
    }
}

/** Card scaffold shared by every stage of the import dialog. */
@Composable
private fun DialogShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BossDialog(onDismissRequest = { if (dismissable) onDismiss() }) {
        Card(
            modifier = Modifier.width(DIALOG_WIDTH).padding(BossTheme.space.lg),
            elevation = BossTheme.elevation.popover,
            shape = BossTheme.radius.dialogShape,
            backgroundColor = BossTheme.colors.raised,
        ) {
            Column(modifier = Modifier.padding(BossTheme.space.xl)) {
                Text(
                    "Import Passwords & Bookmarks",
                    style = BossTheme.type.title,
                    color = BossTheme.colors.textPrimary,
                )
                Spacer(Modifier.height(BossTheme.space.lg))
                content()
            }
        }
    }
}

/**
 * File picker wired to the importer's sniffing, reporting the resulting stage.
 *
 * Parses the text the picker already read rather than opening the file a second
 * time — for a password CSV that content is plaintext in memory.
 */
@Composable
private fun rememberImportPicker(
    scope: CoroutineScope,
    onStage: (ImportStage) -> Unit,
) = rememberFilePicker(
    onFileSelected = { path, content, tooLarge ->
        when {
            // A distinct outcome, so an oversized file doesn't look identical to
            // "user cancelled" — which made the button appear to do nothing.
            tooLarge -> {
                onStage(ImportStage.Failed("That file is too large to import."))
            }

            path != null && content != null -> {
                onStage(ImportStage.Running(0, 0))
                scope.launch {
                    // Parsing a large CSV allocates one object per row; doing it
                    // in the picker callback froze the window.
                    val next =
                        withContext(Dispatchers.IO) {
                            ImportFileReader.parseContent(path, content).fold(
                                onSuccess = { ImportStage.Review(it) },
                                onFailure = {
                                    ImportStage.Failed(it.message ?: "That file could not be read.")
                                },
                            )
                        }
                    onStage(next)
                }
            }

            else -> {
                Unit
            }
        }
    },
    fileExtensions = listOf("csv", "html", "htm"),
    title = "Choose a passwords or bookmarks export",
)

/**
 * Pull everything importable out of a detected browser.
 *
 * On macOS this is where the keychain prompt appears, which is why it runs only
 * after the user picks a browser rather than during the initial scan.
 */
private suspend fun readBrowser(
    detected: DetectedBrowser,
    canImportPasswords: Boolean,
): ImportStage {
    val result = BrowserImportService.read(detected.profile, canImportPasswords)
    return if (result.preview.hasAnything) {
        ImportStage.Review(result.preview, note = result.note)
    } else {
        ImportStage.Failed(
            result.note ?: "Nothing importable was found in ${detected.profile.displayName}.",
        )
    }
}

/**
 * Items the import will actually attempt.
 *
 * Counts only the halves that will run — including a skipped half would leave
 * the bar stuck short of the end on a mixed file.
 */
private fun ImportPreview.plannedTotal(
    canImportPasswords: Boolean,
    bookmarksAvailable: Boolean,
): Int = (if (canImportPasswords) passwords.size else 0) + (if (bookmarksAvailable) bookmarks.size else 0)

/**
 * Run both halves of an import, reporting progress as a single combined count.
 *
 * A half is skipped when it has nothing to do or is unavailable; the other half
 * still runs. Kept out of the composable so the dialog is a state machine and
 * nothing else.
 */
private suspend fun runImport(
    preview: ImportPreview,
    canImportPasswords: Boolean,
    bookmarkProvider: BookmarkDataProvider?,
    partial: PartialProgress,
    onStage: (ImportStage) -> Unit,
) {
    val total = preview.plannedTotal(canImportPasswords, bookmarkProvider != null)

    val passwordResult =
        if (canImportPasswords && preview.passwords.isNotEmpty()) {
            ImportService.importPasswords(preview.passwords) { done, _, soFar ->
                // Recorded as it goes: cancellation discards the return value.
                partial.passwords = soFar
                onStage(ImportStage.Running(done, total))
            }
        } else {
            null
        }
    partial.passwords = passwordResult

    // Bookmarks continue the same progress bar rather than restarting it.
    val offset = if (passwordResult != null) preview.passwords.size else 0

    val bookmarkResult =
        if (preview.bookmarks.isNotEmpty() && bookmarkProvider != null) {
            ImportService.importBookmarks(preview.bookmarks, bookmarkProvider) { done, _, soFar ->
                partial.bookmarks = soFar
                onStage(ImportStage.Running(offset + done, total))
            }
        } else {
            null
        }
    partial.bookmarks = bookmarkResult

    onStage(ImportStage.Finished(passwordResult, bookmarkResult))
}

/** Renders whichever stage the flow is currently in. */
@Composable
private fun StageBody(
    stage: ImportStage,
    canImportPasswords: Boolean,
    bookmarkProvider: BookmarkDataProvider?,
    browsers: List<DetectedBrowser>,
    scanning: Boolean,
    onPickBrowser: (DetectedBrowser) -> Unit,
    onChooseFile: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onStart: (ImportPreview) -> Unit,
    onCancelRunning: () -> Unit,
) {
    when (stage) {
        is ImportStage.ChooseSource -> {
            ChooseSourceContent(
                browsers = browsers,
                scanning = scanning,
                onPickBrowser = onPickBrowser,
                onChoose = onChooseFile,
                onCancel = onCancel,
            )
        }

        is ImportStage.Failed -> {
            FailedContent(message = stage.message, onRetry = onRestart, onCancel = onCancel)
        }

        is ImportStage.Review -> {
            ReviewContent(
                preview = stage.preview,
                canImportPasswords = canImportPasswords,
                bookmarksAvailable = bookmarkProvider != null,
                // Only warn when the slow path would actually be noticeable.
                slowBookmarkPath =
                    bookmarkProvider != null &&
                        !ImportService.supportsBulkBookmarkInsert(bookmarkProvider) &&
                        stage.preview.bookmarks.size > ImportService.FALLBACK_WARNING_THRESHOLD,
                sourceNote = stage.note,
                onCancel = onCancel,
                onStart = { onStart(stage.preview) },
            )
        }

        is ImportStage.Running -> {
            RunningContent(done = stage.done, total = stage.total, onCancel = onCancelRunning)
        }

        is ImportStage.Finished -> {
            FinishedContent(
                passwords = stage.passwords,
                bookmarks = stage.bookmarks,
                cancelled = stage.cancelled,
                onClose = onCancel,
            )
        }
    }
}

/** Wide enough for a browser row plus its explanatory note on one line. */
private val DIALOG_WIDTH = 560.dp

/**
 * Results captured as each half finishes.
 *
 * Cancelling stops the coroutine before [runImport] can return, but whatever
 * was already written stays written — so the dialog reads its outcome from
 * here rather than claiming nothing happened.
 */
private class PartialProgress {
    // Written from Dispatchers.IO by the progress callbacks, read from the main
    // thread by Cancel. Without @Volatile the cancel path can read a stale value
    // and under-report what was written — the exact failure this class exists to
    // prevent.
    @Volatile
    var passwords: ImportResult? = null

    @Volatile
    var bookmarks: ImportResult? = null
}
