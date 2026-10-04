package ai.rever.boss.sharing

import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.awt.Desktop
import java.awt.Window
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class AppCaptureUnavailableException(
    message: String,
) : IllegalStateException(message)

internal data class SharedAppWindow(
    val id: String,
    val title: String,
)

internal data class AppSharingState(
    val windows: List<SharedAppWindow> = emptyList(),
    val sessions: List<JsonObject> = emptyList(),
    val status: String = "Application sharing is stopped.",
    val activeWindowId: String? = null,
    val activeWindowIds: Set<String> = emptySet(),
    val selectedWindowIds: Set<String> = emptySet(),
    val viewers: Int = 0,
    val statusWindowId: String? = null,
    val controller: Boolean = false,
    val busy: Boolean = false,
    val relayEnabled: Boolean = true,
    val automaticSharingEnabled: Boolean = true,
    val preferences: JsonObject? = null,
)

/** Host-owned lifecycle; no plugin classloader, terminal login or terminal relay wire dependency. */
// One coordinator owns account transitions, window lifetimes and their immediate Stop boundary.
@Suppress("TooManyFunctions")
internal object AppSharingService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backend = AppSharingBackend()
    private val preferenceStore by lazy { AppSharingPreferenceStore() }
    private val capture by lazy { AppContinuousWindowCapture() }
    private val instanceId = UUID.randomUUID().toString()
    private val windows = ConcurrentHashMap<String, Window>()
    private val serial = AtomicLong()
    private val lifecycle = Any()
    private val automaticStart = AppSharingAutoStart()
    private val preferenceMutex = Mutex()
    private val _state = MutableStateFlow(AppSharingState())
    val state: StateFlow<AppSharingState> = _state.asStateFlow()

    @Volatile private var assets: AppSharingAssets? = null

    @Volatile private var active: Active? = null

    @Volatile private var startJob: Job? = null

    private class Active(
        val owner: String,
        val sessionId: String,
        val generation: String,
        val peerId: String,
        val windowIds: Set<String>,
        val input: AppInputDispatcher,
    ) {
        val windowId: String = windowIds.first()
        val resources = OwnedAppResources()

        init {
            resources.own(input)
        }

        @Volatile var controller: String? = null

        @Volatile var controlAvailable: Boolean = true

        @Volatile var demandedWindows: Set<String> = emptySet()
    }

    init {
        scope.launch {
            combine(AuthService.currentUser, AuthService.authState) { user, auth ->
                user?.id?.takeIf { auth is AuthService.AuthState.Authenticated }
            }.distinctUntilChanged().collect { owner ->
                automaticStart.accountChanged(owner)
                stopActive(null, "Application sharing is stopped.")
                closeAssetServer()
                _state.update { old -> old.copy(sessions = emptyList(), preferences = null) }
                if (owner != null) scheduleAutomaticSharing()
            }
        }
    }

    fun registerWindow(
        id: String,
        window: Window,
        title: String,
    ) {
        windows[id] = window
        _state.update {
            it.copy(windows = it.windows.filterNot { entry -> entry.id == id } + SharedAppWindow(id, title))
        }
        scheduleAutomaticSharing()
    }

    fun unregisterWindow(id: String) {
        windows.remove(id)
        if (id in _state.value.activeWindowIds) {
            stopActive(null, "Application sharing is stopped.")
            automaticStart.windowClosed()
        }
        _state.update {
            it.copy(
                windows = it.windows.filterNot { entry -> entry.id == id },
                selectedWindowIds = it.selectedWindowIds - id,
            )
        }
        scheduleAutomaticSharing()
    }

    private fun scheduleAutomaticSharing() =
        scope.launch {
            // Let startup restore the main windows before choosing the initial publication.
            delay(500)
            val local = preferenceMutex.withLock { preferenceStore.load() }
            updateLocalPreferences(local)
            val owner = runCatching { owner() }.getOrNull()
            val visible =
                onEdt {
                    windows.entries
                        .filter { it.value.isShowing }
                        .map { it.key }
                        .sorted()
                }
            synchronized(lifecycle) {
                val state = _state.value
                val plan = automaticStart.claim(owner, visible, local, state.busy || state.activeWindowIds.isNotEmpty())
                if (plan != null && automaticStart.isCurrent(plan)) startWindows(plan.windows, plan.owner)
            }
        }

    private fun updateLocalPreferences(local: AppSharingLocalPreferences) {
        _state.update {
            it.copy(relayEnabled = local.relayEnabled, automaticSharingEnabled = local.automaticSharingEnabled)
        }
    }

    fun selectWindow(
        id: String,
        selected: Boolean,
    ) {
        if (!windows.containsKey(id)) return
        _state.update {
            it.copy(selectedWindowIds = if (selected) it.selectedWindowIds + id else it.selectedWindowIds - id)
        }
    }

    /** Local native menu/toolbar only: remote input must never activate or expand capture. */
    fun startSelectedWindows() {
        automaticStart.pause()
        startWindows(_state.value.selectedWindowIds.toList())
    }

    private fun owner(): String =
        AuthService.currentUser.value
            ?.id
            ?.takeIf { AuthService.authState.value is AuthService.AuthState.Authenticated }
            ?: throw AppSharingException("sign_in_required")

    fun refresh() =
        scope.launch {
            runCatching {
                val local = preferenceMutex.withLock { preferenceStore.load() }
                updateLocalPreferences(local)
                val owner = owner()
                val snapshot = loadAppSharingAccountSnapshot(backend, owner)
                check(owner() == owner)
                _state.update {
                    it.copy(
                        preferences = snapshot.preferences,
                        sessions = snapshot.sessions,
                    )
                }
            }.onFailure(::report)
        }

    fun setRelayEnabled(enabled: Boolean) = changeLocalPreferences { it.copy(relayEnabled = enabled) }

    fun setAutomaticSharingEnabled(enabled: Boolean) =
        changeLocalPreferences {
            it.copy(automaticSharingEnabled = enabled)
        }

    private fun changeLocalPreferences(update: (AppSharingLocalPreferences) -> AppSharingLocalPreferences) =
        scope.launch {
            runCatching {
                val local = preferenceMutex.withLock { update(preferenceStore.load()).also(preferenceStore::save) }
                updateLocalPreferences(local)
                if (!local.relayEnabled || !local.automaticSharingEnabled) {
                    stop()
                } else {
                    automaticStart.resume()
                    scheduleAutomaticSharing()
                }
            }.onFailure(::report)
        }

    fun setAccountPreference(
        name: String,
        enabled: Boolean,
    ) = scope.launch {
        runCatching {
            val current = _state.value.preferences ?: return@runCatching
            val owner = owner()
            val result = setAppSharingAccountPreference(backend, owner, current, name, enabled)
            check(owner() == owner)
            _state.update { it.copy(preferences = result) }
            if (!enabled) {
                if (name == "auto_admit") stop() else takeBackControl()
            }
        }.onFailure {
            report(it)
            refresh()
        }
    }

    // Local native menu entry. Registration/capture/browser startup retire partial resources on failure.
    fun start(windowId: String) {
        automaticStart.pause()
        startWindows(listOf(windowId))
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startWindows(
        windowIds: List<String>,
        expectedOwner: String? = null,
    ) {
        if (windowIds.isEmpty() || windowIds.size > 16) return
        stopActive(null, "Application sharing is stopped.")
        val attempt = beginStart(windowIds)
        val pendingStart =
            scope.launch(start = CoroutineStart.LAZY) {
                var registered: Active? = null
                try {
                    delay(250) // The native menu must close before the selected window can be captured.
                    if (expectedOwner != null) check(owner() == expectedOwner)
                    val generation = UUID.randomUUID().toString()
                    val targets =
                        windowIds.map { id ->
                            AppCaptureTarget(id, generation, windows[id] ?: error("A selected window has closed."))
                        }
                    val publication = preparePublication(targets)
                    val host = publication.host
                    registered = host
                    ensureActive()
                    synchronized(lifecycle) {
                        check(serial.get() == attempt && targets.all { windows[it.windowId] === it.awtWindow })
                        active = host
                    }
                    targets.forEach { target ->
                        startMedia(host, target, publication.identity.hostConfig(host.peerId, target.windowId))
                    }
                    val heartbeat = heartbeat(host)
                    host.resources.own(AutoCloseable { heartbeat.cancel() })
                    val demand = pollDemand(host)
                    host.resources.own(AutoCloseable { demand.cancel() })
                    updateHostState(host) { it.copy(busy = false, status = "Connecting encrypted media...") }
                } catch (cancelled: CancellationException) {
                    abandonStart(attempt, registered)
                    throw cancelled
                } catch (error: Exception) {
                    abandonStart(attempt, registered)
                    synchronized(lifecycle) { if (serial.get() == attempt) report(error) }
                }
            }
        synchronized(lifecycle) {
            if (serial.get() == attempt) startJob = pendingStart else pendingStart.cancel()
        }
        pendingStart.start()
    }

    private fun beginStart(windowIds: List<String>): Long =
        synchronized(lifecycle) {
            val attempt = serial.incrementAndGet()
            _state.update {
                it.copy(
                    busy = true,
                    activeWindowId = windowIds.first(),
                    activeWindowIds = windowIds.toSet(),
                    selectedWindowIds = windowIds.toSet(),
                    statusWindowId = windowIds.first(),
                    status = "Starting application sharing...",
                )
            }
            attempt
        }

    private data class PreparedPublication(
        val host: Active,
        val identity: AppPublicationIdentity,
    )

    private suspend fun preparePublication(targets: List<AppCaptureTarget>): PreparedPublication {
        val owner = owner()
        val preferences = preferenceMutex.withLock { preferenceStore.load().also(preferenceStore::save) }
        if (!preferences.relayEnabled) throw AppSharingException("relay_disabled")
        targets.forEach { target ->
            val capability = onEdt { capture.capability(target) }
            if (!capability.supported) {
                throw AppCaptureUnavailableException(capability.reason ?: "Window capture is unavailable.")
            }
        }
        val target = targets.first()
        val identity = AppPublicationIdentity(target)
        val title =
            _state.value.windows
                .firstOrNull { it.id == target.windowId }
                ?.title ?: "BossConsole"
        val sharedWindows =
            targets.map { selected ->
                _state.value.windows.first { it.id == selected.windowId }
            }
        val registration = identity.registration(preferences.deviceId, instanceId, title, sharedWindows)
        val result = backend.call(owner, appSharingRequest("register", registration))
        val host =
            Active(
                owner,
                identity.sessionId,
                target.generation,
                result.getValue("host_peer_id").jsonPrimitive.content,
                targets.map { it.windowId }.toSet(),
                AppInputDispatcher(identity.sessionId, target.generation),
            )
        return PreparedPublication(host, identity)
    }

    private fun startMedia(
        host: Active,
        target: AppCaptureTarget,
        config: JsonObject,
    ) {
        val owner = host.owner
        val window = target.awtWindow
        val windowId = target.windowId
        val generation = target.generation
        val peer = AppSharingPeerScope(host.sessionId, generation, host.peerId, true, windowId)
        val page = assetServer().open(appSharingHostConfig(config), true) { backend.call(owner, peer.bind(it)) }
        check(host.resources.own(AutoCloseable { page.close() }))
        val sink =
            AwtAppInputSink(window, requireForeground = false, onCursor = page.rawFrames::cursor).apply {
                pauseCapture()
            }
        val media =
            AppSharingMediaHost(page, { handleMediaState(host, windowId, it) }, { input ->
                host.controller?.let { controller ->
                    if (active === host) host.input.dispatchJson(controller, input.toString())
                }
            })
        check(host.resources.own(media))
        media.start()
        val captureHandle =
            capture.start(target, { frame ->
                if (active === host) {
                    updateCapturedInput(host.input, target, sink, frame)
                    media.frame(frame)
                }
            }, { reason ->
                stopActive(
                    host,
                    reason,
                )
            }, { windowId in host.demandedWindows }, media::captureFrameRate, media::capturePixelFormat)
        check(host.resources.own(captureHandle))
    }

    private fun heartbeat(host: Active) =
        scope.launch {
            while (isActive && active === host) {
                delay(25_000)
                try {
                    recoverAppSharingRequest {
                        backend.call(host.owner, scoped(host, "heartbeat"))
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (_: AppSharingException) {
                    stopActive(host, "Sharing stopped because the account service could not be reached.")
                } catch (_: java.io.IOException) {
                    stopActive(host, "Sharing stopped because the account service could not be reached.")
                }
            }
        }

    private fun pollDemand(host: Active) =
        scope.launch {
            var recovering = false
            while (isActive && active === host) {
                try {
                    val result =
                        recoverAppSharingRequest(onInterrupted = {
                            if (active === host) {
                                recovering = true
                                host.demandedWindows = emptySet()
                                host.input.suspend()
                                updateHostState(host) { it.copy(status = "Connection interrupted. Reconnecting…") }
                            }
                        }) {
                            backend.call(host.owner, scoped(host, "mediaDemand"))
                        }
                    ensureActive()
                    if (active !== host) return@launch
                    val counts =
                        result.getValue("windows").jsonArray.associate { value ->
                            val window = value.jsonObject
                            window.getValue("window_id").jsonPrimitive.content to
                                window.getValue("viewers").jsonPrimitive.int
                        }
                    val demanded = counts.filter { it.value > 0 && it.key in host.windowIds }.keys
                    val removed = host.demandedWindows - demanded
                    host.demandedWindows = demanded
                    removed.forEach { host.input.remove(it) }
                    updateHostState(host) {
                        it.copy(
                            viewers = counts.values.sum(),
                            status = if (recovering) "Sharing this BossConsole window." else it.status,
                        )
                    }
                    recovering = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    stopActive(host, "Sharing stopped because viewing permissions could not be verified.")
                    return@launch
                }
                delay(1000)
            }
        }

    private fun abandonStart(
        attempt: Long,
        registered: Active?,
    ) {
        registered?.let(::cleanup)
        synchronized(lifecycle) {
            if (serial.get() == attempt) {
                if (active === registered) active = null
                _state.update { it.copy(busy = false, activeWindowId = null, activeWindowIds = emptySet()) }
            }
        }
    }

    private fun handleMediaState(
        host: Active,
        windowId: String,
        message: JsonObject,
    ) {
        if (active !== host) return
        when (message["state"]?.jsonPrimitive?.contentOrNull) {
            "control-suspended" -> {
                if (windowId == host.windowId) {
                    host.controller = null
                    host.input.suspend()
                    updateHostState(host) { it.copy(controller = false) }
                }
            }

            "authority-suspended", "disconnected" -> {
                updateHostState(host) { it.copy(status = "Connection interrupted. Reconnecting…") }
            }

            "lease" -> {
                if (windowId != host.windowId) return
                val lease = message["lease"] as? JsonObject
                if (lease == null) {
                    host.controller = null
                    host.input.revoke()
                } else {
                    val parsed =
                        runCatching {
                            AppControlLease(
                                lease.getValue("leaseId").jsonPrimitive.content,
                                lease.getValue("peerId").jsonPrimitive.content,
                                host.generation,
                                lease.getValue("expiresAt").jsonPrimitive.long,
                            )
                        }.getOrNull()
                    host.controller = parsed?.takeIf { host.input.installLease(it) }?.peerId
                    if (host.controller == null) host.input.revoke()
                }
                updateHostState(host) { it.copy(controller = host.controller != null) }
            }

            "control-unavailable" -> {
                host.controlAvailable = false
                takeBackControl(host)
                updateHostState(host) {
                    it.copy(status = "Sharing this BossConsole window. Remote control is unavailable.")
                }
            }

            "publishing", "connected", "live", "sharing", "authority-restored" -> {
                updateHostState(host) {
                    it.copy(
                        busy = false,
                        status =
                            if (host.controlAvailable) {
                                "Sharing this BossConsole window."
                            } else {
                                "Sharing this BossConsole window. Remote control is unavailable."
                            },
                    )
                }
            }

            "error", "failed", "stopped" -> {
                stopActive(host, appMediaFailureStatus(message["reason"]?.jsonPrimitive?.contentOrNull))
            }
        }
    }

    fun takeBackControl() {
        val host = active ?: return
        takeBackControl(host)
    }

    private fun takeBackControl(host: Active) {
        host.controller = null
        host.input.revoke()
        updateHostState(host) { it.copy(controller = false) }
        scope.launch { runCatching { backend.call(host.owner, scoped(host, "controlRelease")) } }
    }

    fun stop() {
        automaticStart.pause()
        stopActive(null, "Application sharing is stopped.")
    }

    fun dismissStatus() {
        _state.update { it.copy(statusWindowId = null) }
    }

    private fun updateHostState(
        host: Active,
        transform: (AppSharingState) -> AppSharingState,
    ) {
        synchronized(lifecycle) {
            if (active === host) _state.update(transform)
        }
    }

    private fun stopActive(
        expected: Active?,
        status: String,
    ) {
        val previous =
            synchronized(lifecycle) {
                if (expected != null && active !== expected) return
                serial.incrementAndGet()
                startJob?.cancel()
                startJob = null
                _state.update {
                    it.stopped(status, expected?.windowId)
                }
                active.also { active = null }
            }
        if (previous != null) cleanup(previous)
    }

    private fun cleanup(host: Active) {
        host.controller = null
        runCatching { host.input.close() }
        host.resources.close()
        scope.launch { runCatching { backend.call(host.owner, scoped(host, "stop")) } }
    }

    private fun scoped(
        host: Active,
        action: String,
    ) = appSharingHostRequest(action, host.sessionId, host.generation, host.peerId)

    @Synchronized private fun assetServer(): AppSharingAssets = assets ?: AppSharingAssets().also { assets = it }

    @Synchronized private fun closeAssetServer() {
        assets?.close()
        assets = null
    }

    fun openViewer(
        descriptor: JsonObject,
        externalBrowser: Boolean,
        windowId: String? = null,
    ) = scope.launch {
        runCatching {
            val owner = owner()
            val sessionId = descriptor.getValue("session_id").jsonPrimitive.content
            val generation = descriptor.getValue("generation").jsonPrimitive.content
            val device = preferenceMutex.withLock { preferenceStore.load().also(preferenceStore::save).deviceId }
            val role =
                if (_state.value.preferences
                        ?.get("auto_control")
                        ?.jsonPrimitive
                        ?.booleanOrNull != false
                ) {
                    "control"
                } else {
                    "view"
                }
            val preferredAdmission =
                buildJsonObject {
                    put("session_id", sessionId)
                    put("generation", generation)
                    put("device_id", device)
                    put("role", role)
                }
            val (admission, ticket) = admitViewer(owner, preferredAdmission)
            val consumed =
                backend.call(
                    owner,
                    appSharingRequest(
                        "consume",
                        buildJsonObject {
                            admission.forEach { (key, value) -> put(key, value) }
                            put("ticket", ticket.getValue("ticket"))
                        },
                    ),
                )
            val peerId = consumed.getValue("peer_id").jsonPrimitive.content
            val config = appSharingViewerConfig(descriptor, consumed, role, windowId)
            val peer =
                AppSharingPeerScope(
                    sessionId,
                    generation,
                    peerId,
                    false,
                    config.getValue("windowId").jsonPrimitive.content,
                )
            val page = assetServer().open(config, false) { backend.call(owner, peer.bind(it)) }
            withContext(Dispatchers.Main) {
                if (owner() != owner) {
                    page.close()
                    throw AppSharingException("account_changed")
                }
                if (externalBrowser) {
                    Desktop.getDesktop().browse(URI(page.url))
                } else {
                    DeepLinkHandler.processDeepLink("boss://url?url=" + URLEncoder.encode(page.url, "UTF-8"))
                }
            }
        }.onFailure(::report)
    }

    private suspend fun admitViewer(
        owner: String,
        preferred: JsonObject,
    ): Pair<JsonObject, JsonObject> =
        try {
            preferred to backend.call(owner, appSharingRequest("admit", preferred))
        } catch (error: AppSharingException) {
            val requestedControl = preferred["role"]?.jsonPrimitive?.content == "control"
            val canWatchInstead = error.reason == "approval_required" && requestedControl
            if (!canWatchInstead) throw error
            val viewOnly =
                buildJsonObject {
                    preferred.forEach { (key, value) -> put(key, value) }
                    put("role", "view")
                }
            viewOnly to backend.call(owner, appSharingRequest("admit", viewOnly))
        }

    private fun report(error: Throwable) {
        if (error is CancellationException) return
        BossLogger.forComponent("AppSharingService").warn(
            LogCategory.SYSTEM,
            "Application sharing operation failed",
            data =
                mapOf(
                    "errorType" to error.javaClass.simpleName,
                    "reason" to (
                        (error as? AppSharingException)?.reason
                            ?: (error as? AppCaptureUnavailableException)?.message ?: "unexpected_failure"
                    ),
                ),
        )
        _state.update { it.copy(status = appSharingFailureStatus(error)) }
    }
}

/** Retire visible authority while preserving the user's local policy and window selection. */
internal fun AppSharingState.stopped(
    status: String,
    windowId: String?,
): AppSharingState =
    copy(
        busy = false,
        activeWindowId = null,
        activeWindowIds = emptySet(),
        viewers = 0,
        statusWindowId = windowId,
        controller = false,
        status = status,
    )

/** Retain recovery authority while pixels are paused, and publish fresh coordinates together on the EDT. */
private fun updateCapturedInput(
    input: AppInputDispatcher,
    target: AppCaptureTarget,
    sink: AwtAppInputSink,
    frame: AppRawCapturedFrame?,
) = onEdt {
    if (frame == null) {
        sink.pauseCapture()
    } else {
        sink.updateSurfaces(frame.surfaces)
        input.register(AppInputTarget(target.windowId, target.generation, frame.geometryRevision, sink))
    }
}
