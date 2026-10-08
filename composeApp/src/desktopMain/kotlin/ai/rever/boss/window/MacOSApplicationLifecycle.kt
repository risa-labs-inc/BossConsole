package ai.rever.boss.window

import ai.rever.boss.utils.CleanupRunner
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.awaitCancellation
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.QuitResponse
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** Called only by the macOS host, outside all window compositions. */
@Composable
internal fun MacOSApplicationLifecycle(
    onReopen: () -> Unit,
    onNewWindow: () -> Unit,
    retainQuitResponse: (QuitResponse) -> Unit,
    onQuit: (QuitResponse) -> Unit,
) {
    val currentReopen by rememberUpdatedState(onReopen)
    val currentNewWindow by rememberUpdatedState(onNewWindow)
    val currentQuit by rememberUpdatedState(onQuit)
    val currentRetainQuitResponse by rememberUpdatedState(retainQuitResponse)

    // Compose ends application{} when no windows or active effects remain. Keep
    // this effect suspended until explicit Quit disposes the application composition.
    LaunchedEffect(Unit) { awaitCancellation() }

    DisposableEffect(Unit) {
        val desktop = Desktop.getDesktop()
        var disposed = false
        val reopenListener =
            AppReopenedListener {
                SwingUtilities.invokeLater {
                    if (!disposed) currentReopen()
                }
            }
        desktop.addAppEventListener(reopenListener)
        val quitDispatcher =
            MacOSQuitDispatcher(
                retainResponse = { currentRetainQuitResponse(it) },
                onQuit = { currentQuit(it) },
                dispatchToUi = { task -> SwingUtilities.invokeLater(task) },
            )
        desktop.setQuitHandler { _, response ->
            // Keep the native request pending during Compose cleanup. cancelQuit()
            // would also veto a pending macOS logout, restart, or shutdown.
            quitDispatcher.requestQuit(response)
        }

        // Window MenuBar compositions disappear with their windows. Supply New
        // Window (Cmd+N) while there are no frames; AppKit retains the app/Quit menu.
        val supportsDefaultMenu = desktop.isSupported(Desktop.Action.APP_MENU_BAR)
        if (supportsDefaultMenu) {
            val newWindow =
                JMenuItem("New Window").apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.META_DOWN_MASK)
                    addActionListener { if (!disposed) currentNewWindow() }
                }
            desktop.setDefaultMenuBar(
                JMenuBar().apply {
                    add(JMenu("File").apply { add(newWindow) })
                },
            )
        }

        onDispose {
            disposed = true
            val cleanup = CleanupRunner("MacOSApplicationLifecycle", category = LogCategory.SYSTEM)
            quitDispatcher.dispose()
            cleanup.run("remove reopen listener") { desktop.removeAppEventListener(reopenListener) }
            cleanup.run("remove quit handler") { desktop.setQuitHandler(null) }
            if (supportsDefaultMenu) cleanup.run("remove default menu") { desktop.setDefaultMenuBar(null) }
        }
    }
}

/** Retain native requests before posting to the EDT, including requests racing effect disposal. */
internal class MacOSQuitDispatcher(
    private val retainResponse: (QuitResponse) -> Unit,
    private val onQuit: (QuitResponse) -> Unit,
    private val dispatchToUi: (() -> Unit) -> Unit,
) {
    private val disposed = AtomicBoolean(false)

    fun requestQuit(response: QuitResponse) {
        retainResponse(response)
        if (!disposed.get()) {
            dispatchToUi { if (!disposed.get()) onQuit(response) }
        }
    }

    fun dispose() {
        disposed.set(true)
    }
}
