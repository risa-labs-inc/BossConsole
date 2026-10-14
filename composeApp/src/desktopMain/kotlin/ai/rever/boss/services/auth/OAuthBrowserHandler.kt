package ai.rever.boss.services.auth

import ai.rever.boss.utils.DefaultBrowserManager
import ai.rever.boss.utils.DefaultHandlerState
import ai.rever.boss.utils.MacOSDefaultBrowserHandler

internal actual suspend fun externalSignInBrowserRequired(): Boolean {
    val handler =
        if (System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
        ) {
            // OAuth uses HTTPS. The aggregate browser role also includes HTTP and HTML files,
            // which can belong to another app even when HTTPS still points back to BOSS.
            MacOSDefaultBrowserHandler.browserHandlerStates().getOrNull()?.get("https")
        } else {
            DefaultBrowserManager.browserHandlerState().getOrNull()
        }
    return requiresExternalSignInBrowser(handler)
}

/** Unknown ownership must not send an OAuth URL back into BOSS. */
internal fun requiresExternalSignInBrowser(handler: DefaultHandlerState?): Boolean =
    handler !is DefaultHandlerState.Other || handler.bundleId.isNullOrBlank()
