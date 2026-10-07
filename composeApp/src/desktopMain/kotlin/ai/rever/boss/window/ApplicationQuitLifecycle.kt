package ai.rever.boss.window

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

    fun completeQuit() {
        val response = pendingResponse
        pendingResponse = null
        response?.performQuit()
    }
}
