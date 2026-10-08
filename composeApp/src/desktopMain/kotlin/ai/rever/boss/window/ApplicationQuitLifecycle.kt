package ai.rever.boss.window

import ai.rever.boss.utils.CleanupRunner
import ai.rever.boss.utils.logging.LogCategory
import java.awt.desktop.QuitResponse

/** Retains the native quit request until Compose has disposed all window compositions. */
internal class ApplicationQuitLifecycle {
    private val lock = Any()
    private val responses = mutableListOf<QuitResponse>()
    private var closeRequested = false
    private var cleanupComplete = false
    private val cleanup = CleanupRunner("ApplicationQuitLifecycle", category = LogCategory.SYSTEM)

    fun requestQuit(
        response: QuitResponse,
        closeApplication: () -> Unit,
    ) {
        retainResponse(response)
        val shouldClose =
            synchronized(lock) {
                if (cleanupComplete || closeRequested) {
                    false
                } else {
                    closeRequested = true
                    true
                }
            }
        if (shouldClose) closeApplication()
    }

    /** Safe on the native event thread; Compose closure is requested separately on the EDT. */
    fun retainResponse(response: QuitResponse) {
        val completeNow =
            synchronized(lock) {
                if (responses.any { it === response }) return
                responses += response
                cleanupComplete
            }
        if (completeNow) completeResponse(response)
    }

    /** A failing plugin must not prevent the remaining windows or the app from closing. */
    fun closeApplication(
        windowIds: List<String>,
        prepareWindow: (String) -> Unit,
        exitApplication: () -> Unit,
    ) {
        try {
            windowIds.forEach { windowId ->
                val context = mapOf("windowId" to windowId)
                val windowCleanup =
                    CleanupRunner("ApplicationQuitLifecycle", context, category = LogCategory.SYSTEM)
                windowCleanup.run("prepare window for Quit") {
                    prepareWindow(windowId)
                }
            }
        } finally {
            exitApplication()
        }
    }

    fun completeQuit() {
        val pending =
            synchronized(lock) {
                if (cleanupComplete) return
                cleanupComplete = true
                responses.toList()
            }
        pending.forEach(::completeResponse)
    }

    private fun completeResponse(response: QuitResponse) {
        cleanup.run("complete native Quit response") { response.performQuit() }
    }
}
