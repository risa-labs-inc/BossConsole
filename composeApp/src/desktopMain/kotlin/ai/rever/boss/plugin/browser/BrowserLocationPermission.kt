package ai.rever.boss.plugin.browser

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        if (scheme != "https" && !(scheme == "http" && isLocationLoopback(host))) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port =
            uri.port
                .takeIf { it != -1 && it != defaultPort }
                ?.let { ":$it" }
                .orEmpty()
        "$scheme://$host$port"
    }.getOrNull()

private fun isLocationLoopback(host: String): Boolean {
    val ipv4 = host.split('.')
    return host == "localhost" || host.endsWith(".localhost") || host == "[::1]" ||
        (ipv4.size == 4 && ipv4.first() == "127" && ipv4.all { it.toIntOrNull() in 0..255 })
}

/** One native answer even when a click, timeout, navigation, and tab disposal race. */
internal class LocationPermissionRequest(
    answer: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var decision: Boolean? = null
    private val answers = mutableListOf(answer)
    private val cleanup = mutableListOf<() -> Unit>()

    private companion object {
        const val MAX_ANSWERS = 64
    }

    val isCompleted: Boolean
        get() = synchronized(lock) { decision != null }

    val isAllowed: Boolean
        get() = synchronized(lock) { decision == true }

    fun onFinished(release: () -> Unit) {
        val alreadyCompleted =
            synchronized(lock) {
                if (decision != null) {
                    true
                } else {
                    cleanup.add(release)
                    false
                }
            }
        if (alreadyCompleted) release()
    }

    /** Coalesced native requests receive the same decision, including races with completion. */
    fun join(answer: (Boolean) -> Unit) {
        val resolved =
            synchronized(lock) {
                when {
                    decision != null -> {
                        decision
                    }

                    answers.size >= MAX_ANSWERS -> {
                        false
                    }

                    else -> {
                        answers.add(answer)
                        null
                    }
                }
            }
        if (resolved != null) answer(resolved)
    }

    fun finish(allow: Boolean) {
        val (callbacks, releases) =
            synchronized(lock) {
                if (decision != null) return
                decision = allow
                (answers.toList() to cleanup.toList()).also {
                    answers.clear()
                    cleanup.clear()
                }
            }
        try {
            callbacks.forEach { it(allow) }
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
internal data class LocationPermissionOrigins(
    val requesting: String,
    val embedding: String,
)

internal typealias LocationPermissionPresenter =
    (Window?, LocationPermissionOrigins, LocationPermissionRequest, () -> Unit) -> Unit

internal object BrowserLocationPermission : LocationPermissionController()

/** Injectable presentation and timing let tests exercise the native grant path without a display. */
internal open class LocationPermissionController(
    private val providerConfigured: () -> Boolean = { BrowserLocationProvider.apiKey != null },
    private val presentPrompt: LocationPermissionPresenter? = null,
    private val timeoutMillis: Long = 60_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val showUnavailable: () -> Unit = {
        StatusMessageManager.showMessage(
            "Location sharing is unavailable. Configure BOSS_BROWSER_GOOGLE_API_KEY before starting BOSS.",
            durationMs = 10_000,
        )
    },
) {
    private val logger = BossLogger.forComponent("BrowserLocationPermission")

    private data class Registration(
        val owner: () -> Window?,
        val isCurrentOrigin: suspend (Frame, LocationPermissionOrigins) -> Boolean,
        val deniedAt: AtomicReference<Long?> = AtomicReference(null),
    )

    private val owners = ConcurrentHashMap<Browser, Registration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Pending(
        val frame: Frame,
        val origins: LocationPermissionOrigins,
        val request: LocationPermissionRequest,
    )

    private val pending = ConcurrentHashMap<Browser, Pending>()
    private val unavailableShown = AtomicBoolean(false)
    private val denialCooldownNanos = 30_000_000_000L

    fun register(
        browser: Browser,
        owner: () -> Window?,
        isCurrentOrigin: suspend (Frame, LocationPermissionOrigins) -> Boolean,
    ) {
        owners[browser] = Registration(owner, isCurrentOrigin)
        if (browser.isClosed) unregister(browser)
    }

    fun unregister(browser: Browser) {
        owners.remove(browser)
        pending.remove(browser)?.request?.finish(false)
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
            val embedding = locationPermissionOrigin(browser.url())
            val registration = owners[browser]
            if (origin == null || embedding == null || registration == null) {
                request.finish(false)
                return
            }
            if (!providerConfigured()) {
                if (unavailableShown.compareAndSet(false, true)) showUnavailable()
                request.finish(false)
                return
            }
            preparePrompt(browser, frame, LocationPermissionOrigins(origin, embedding), registration, request)
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Could not request location permission",
                mapOf("error" to e.javaClass.name),
            )
            request.finish(false)
        }
    }

    private fun preparePrompt(
        browser: Browser,
        frame: Frame,
        origins: LocationPermissionOrigins,
        registration: Registration,
        request: LocationPermissionRequest,
    ) {
        val deniedAt = registration.deniedAt.get()
        if (deniedAt != null && nanoTime() - deniedAt < denialCooldownNanos) {
            request.finish(false)
            return
        }
        val entry = Pending(frame, origins, request)
        val existing = pending.putIfAbsent(browser, entry)
        if (existing != null) {
            if (existing.frame == frame && existing.origins == origins) {
                existing.request.join(request::finish)
            } else {
                logger.debug(LogCategory.BROWSER, "Another frame already has a pending location prompt")
                request.finish(false)
            }
            return
        }
        request.onFinished {
            // Browser-wide backoff prevents a page rotating iframe origins to evade it.
            if (!request.isAllowed) registration.deniedAt.set(nanoTime())
            pending.remove(browser, entry)
        }
        bindLifetime(browser, frame, request)
        val timeout =
            scope.launch {
                delay(timeoutMillis)
                request.finish(false)
            }
        request.onFinished { timeout.cancel() }
        scope.launch {
            if (isCurrentOrigin(browser, frame, origins)) {
                SwingUtilities.invokeLater { showPrompt(browser, frame, origins, request) }
            } else {
                request.finish(false)
            }
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
        origins: LocationPermissionOrigins,
    ): Boolean = runCatching { owners[browser]?.isCurrentOrigin?.invoke(frame, origins) == true }.getOrDefault(false)

    @Suppress("TooGenericExceptionCaught")
    private fun showPrompt(
        browser: Browser,
        frame: Frame,
        origins: LocationPermissionOrigins,
        request: LocationPermissionRequest,
    ) {
        if (request.isCompleted) return
        try {
            val registration = owners[browser]
            if (registration == null) {
                request.finish(false)
                return
            }
            val owner = registration.owner()
            val allow = {
                scope.launch { request.finish(isCurrentOrigin(browser, frame, origins)) }
                Unit
            }
            if (presentPrompt != null) {
                presentPrompt.invoke(owner, origins, request, allow)
            } else if (owner?.isShowing == true) {
                showOwnedPrompt(owner, origins, request, allow)
            } else {
                request.finish(false)
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
        origins: LocationPermissionOrigins,
        request: LocationPermissionRequest,
        allow: () -> Unit,
    ) {
        val identity =
            if (origins.requesting == origins.embedding) {
                "${origins.requesting} wants to access your location."
            } else {
                "${origins.embedding} embeds ${origins.requesting}, which wants to access your location."
            }
        val message =
            "$identity\n\n" +
                "If you allow access, BOSS will use Google's location service to estimate your location " +
                "and share it with the requesting site. This may send network information, such as your IP address " +
                "and nearby Wi-Fi access points, to Google."
        val text =
            JTextArea(message, 0, 48).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
            }
        val options = arrayOf("Allow location", "Block")
        val pane =
            JOptionPane(text, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, options, options.last())
        val dialog = pane.createDialog(owner, "Share location?")
        request.onFinished {
            SwingUtilities.invokeLater {
                dialog.dispose()
            }
        }
        try {
            dialog.modalityType = Dialog.ModalityType.DOCUMENT_MODAL
            if (!request.isCompleted) dialog.isVisible = true
            if (pane.value == "Allow location" && !request.isCompleted) {
                allow()
            } else {
                request.finish(false)
            }
        } finally {
            dialog.dispose()
        }
    }
}
