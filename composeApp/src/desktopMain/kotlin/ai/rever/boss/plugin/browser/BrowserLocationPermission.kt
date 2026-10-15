package ai.rever.boss.plugin.browser

import ai.rever.boss.config.ConfigLoader
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.browser.event.FrameDeleted
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.navigation.event.NavigationStarted
import com.teamdev.jxbrowser.permission.callback.RequestPermissionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Dialog
import java.awt.Window
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JOptionPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** A Google Geolocation API key is optional. Never log its value or include it in the permission UI. */
internal object BrowserLocationProvider {
    val apiKey: String? by lazy {
        ConfigLoader.getConfig("BOSS_BROWSER_GOOGLE_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Display the requesting security origin, never a path, user-info, or page-supplied HTML. */
internal fun locationPermissionOrigin(url: String?): String? =
    runCatching {
        val uri = URI(url ?: return null)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawUserInfo != null || uri.port !in -1..65535) return null
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        if (scheme != "https" && !(scheme == "http" && loopback)) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port =
            uri.port
                .takeIf { it != -1 && it != defaultPort }
                ?.let { ":$it" }
                .orEmpty()
        "$scheme://$host$port"
    }.getOrNull()

/** One native answer even when a click, timeout, navigation, and tab disposal race. */
internal class LocationPermissionRequest(
    private val answer: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var completed = false
    private val cleanup = mutableListOf<() -> Unit>()

    val isCompleted: Boolean
        get() = synchronized(lock) { completed }

    fun onFinished(release: () -> Unit) {
        val alreadyCompleted =
            synchronized(lock) {
                if (completed) {
                    true
                } else {
                    cleanup.add(release)
                    false
                }
            }
        if (alreadyCompleted) release()
    }

    fun finish(allow: Boolean) {
        val releases =
            synchronized(lock) {
                if (completed) return
                completed = true
                cleanup.toList().also { cleanup.clear() }
            }
        try {
            answer(allow)
        } finally {
            releases.forEach { runCatching { it() } }
        }
    }
}

/**
 * Geolocation consent belongs to the requesting browser's window, including a popped-out browser.
 * BOSS does not persist site grants. Unmanaged/headless browsers fail closed rather than prompt in
 * an unrelated active window. Other browser permission types retain their existing policy.
 */
internal object BrowserLocationPermission {
    private val logger = BossLogger.forComponent("BrowserLocationPermission")

    private data class Registration(
        val owner: () -> Window?,
        val isCurrentOrigin: suspend (Frame, String) -> Boolean,
    )

    private val owners = ConcurrentHashMap<Browser, Registration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap<Browser, LocationPermissionRequest>()
    private const val TIMEOUT_MS = 60_000

    fun register(
        browser: Browser,
        owner: () -> Window?,
        isCurrentOrigin: suspend (Frame, String) -> Boolean,
    ) {
        owners[browser] = Registration(owner, isCurrentOrigin)
        if (browser.isClosed) unregister(browser)
    }

    fun unregister(browser: Browser) {
        owners.remove(browser)
        pending.remove(browser)?.finish(false)
    }

    // Native callbacks and owner suppliers may fail during teardown; every failure denies.
    @Suppress("TooGenericExceptionCaught")
    fun request(
        params: RequestPermissionCallback.Params,
        action: RequestPermissionCallback.Action,
    ) {
        val request =
            LocationPermissionRequest { allow ->
                runCatching { if (allow) action.grant() else action.deny() }
                    .onFailure { logger.debug(LogCategory.BROWSER, "Location request is no longer available") }
            }
        try {
            val origin = locationPermissionOrigin(params.url())
            val frame = params.frame()
            val browser = frame.browser()
            if (origin == null || !owners.containsKey(browser) || pending.putIfAbsent(browser, request) != null) {
                request.finish(false)
                return
            }
            request.onFinished { pending.remove(browser, request) }
            bindLifetime(browser, frame, request)
            val timeout =
                scope.launch {
                    delay(TIMEOUT_MS.toLong())
                    request.finish(false)
                }
            request.onFinished { timeout.cancel() }
            scope.launch {
                if (isCurrentOrigin(browser, frame, origin)) {
                    SwingUtilities.invokeLater { showPrompt(browser, frame, origin, request) }
                } else {
                    request.finish(false)
                }
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Could not request location permission",
                mapOf("error" to e.javaClass.name),
            )
            request.finish(false)
        }
    }

    private fun bindLifetime(
        browser: Browser,
        frame: Frame,
        request: LocationPermissionRequest,
    ) {
        val closed = browser.on(BrowserClosed::class.java) { request.finish(false) }
        request.onFinished { closed.unsubscribe() }
        val deleted = browser.on(FrameDeleted::class.java) { if (it.frame() == frame) request.finish(false) }
        request.onFinished { deleted.unsubscribe() }
        // Conservatively cancel on any frame's document navigation. A frame may change origin
        // while a top-level page remains unchanged, so checking only browser.url() is insufficient.
        val navigated =
            browser.navigation().on(NavigationStarted::class.java) { event ->
                if (!event.isSameDocument) request.finish(false)
            }
        request.onFinished { navigated.unsubscribe() }
        if (browser.isClosed) request.finish(false)
    }

    private suspend fun isCurrentOrigin(
        browser: Browser,
        frame: Frame,
        origin: String,
    ): Boolean = runCatching { owners[browser]?.isCurrentOrigin?.invoke(frame, origin) == true }.getOrDefault(false)

    @Suppress("TooGenericExceptionCaught")
    private fun showPrompt(
        browser: Browser,
        frame: Frame,
        origin: String,
        request: LocationPermissionRequest,
    ) {
        if (request.isCompleted) return
        try {
            val owner = owners[browser]?.owner?.invoke()?.takeIf { it.isShowing }
            if (owner == null) {
                request.finish(false)
                return
            }
            showOwnedPrompt(owner, origin, BrowserLocationProvider.apiKey != null, request) {
                scope.launch { request.finish(isCurrentOrigin(browser, frame, origin)) }
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Could not display location permission",
                mapOf("error" to e.javaClass.name),
            )
            request.finish(false)
        }
    }

    private fun showOwnedPrompt(
        owner: Window,
        origin: String,
        providerConfigured: Boolean,
        request: LocationPermissionRequest,
        allow: () -> Unit,
    ) {
        val message =
            if (providerConfigured) {
                "$origin wants to access your location.\n\n" +
                    "If you allow access, BOSS will use Google's location service to estimate your location " +
                    "and share it with this site. This may send network information, such as your IP address " +
                    "and nearby Wi-Fi access points, to Google."
            } else {
                "$origin requested your location.\n\n" +
                    "Location sharing is unavailable because this BOSS installation has no location service " +
                    "configured. Ask your administrator to configure the Google Geolocation API, then restart BOSS. " +
                    "You can enter your location on the website manually if it offers that option."
            }
        val text =
            JTextArea(message, 0, 48).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
            }
        val options = if (providerConfigured) arrayOf("Allow location", "Block") else arrayOf("Close")
        val pane =
            JOptionPane(text, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options.last())
        val dialog = pane.createDialog(owner, if (providerConfigured) "Share location?" else "Location unavailable")
        request.onFinished {
            SwingUtilities.invokeLater {
                dialog.dispose()
            }
        }
        try {
            dialog.modalityType = Dialog.ModalityType.DOCUMENT_MODAL
            if (!request.isCompleted) dialog.isVisible = true
            if (providerConfigured && pane.value == "Allow location" && !request.isCompleted) {
                allow()
            } else {
                request.finish(false)
            }
        } finally {
            dialog.dispose()
        }
    }
}
