package ai.rever.boss.window

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.desktop.QuitResponse

/** Retains the native quit request until Compose has disposed all window compositions. */
internal class ApplicationQuitLifecycle {
    private val logger = BossLogger.forComponent("ApplicationQuitLifecycle")

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
    @Suppress("TooGenericExceptionCaught")
    fun closeApplication(
        windowIds: List<String>,
        prepareWindow: (String) -> Unit,
        exitApplication: () -> Unit,
    ) {
        try {
            windowIds.forEach { windowId ->
                try {
                    prepareWindow(windowId)
                } catch (t: Throwable) {
                    logger.warn(
                        LogCategory.UI,
                        "Window cleanup failed during Quit (continuing)",
                        mapOf("windowId" to windowId),
                        t,
                    )
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
