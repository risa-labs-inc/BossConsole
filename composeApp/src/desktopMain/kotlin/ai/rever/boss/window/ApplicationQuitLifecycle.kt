package ai.rever.boss.window

import ai.rever.boss.utils.CleanupRunner
import java.awt.desktop.QuitResponse

/** Retains the native quit request until Compose has disposed all window compositions. */
internal class ApplicationQuitLifecycle {
    @Volatile
    private var pendingResponse: QuitResponse? = null

    fun requestQuit(
        response: QuitResponse,
        closeApplication: () -> Unit,
    ) {
        pendingResponse = response
        closeApplication()
    }

    /** A failing plugin must not prevent the remaining windows or the app from closing. */
    fun closeApplication(
        windowIds: List<String>,
        prepareWindow: (String) -> Unit,
        exitApplication: () -> Unit,
    ) {
        try {
            windowIds.forEach { windowId ->
                val cleanup = CleanupRunner("ApplicationQuitLifecycle", mapOf("windowId" to windowId))
                cleanup.run("prepare window for Quit") {
                    prepareWindow(windowId)
                }
            }
        } finally {
            exitApplication()
        }
    }

    fun completeQuit() {
        val response = pendingResponse
        pendingResponse = null
        response?.performQuit()
    }
}
