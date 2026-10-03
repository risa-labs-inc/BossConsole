package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.MacToolbarRuntime
import ai.rever.boss.window.scopedNativeToolbarCall
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import java.awt.Frame
import java.awt.Window
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal data class AppRawCapturedFrame(
    val pixels: AppRawWindowFrame,
    val geometryRevision: Long,
    val surfaces: AppSurfaceSnapshot,
)

/** Latest-only raw streams. Geometry stays in original logical coordinates for input routing. */
internal class AppContinuousWindowCapture(
    private val sessionMonitor: AppCaptureSessionMonitor = DesktopAppCaptureSessionMonitor(),
    private val platform: AppWindowStreamPlatform? = appWindowStreamPlatform(),
    private val diagnostics: AppCaptureDiagnostics? = null,
) {
    fun capability(target: AppCaptureTarget): AppCaptureCapability =
        when {
            platform == null || target.awtWindow !is ComposeWindow -> {
                AppCaptureCapability(false, "An exact-window capture adapter is unavailable for this desktop session.")
            }

            !platform.available() -> {
                AppCaptureCapability(
                    false,
                    "Continuous capture is unavailable. Check capture permission and OS session support.",
                )
            }

            !platform.monitorsSession && !sessionMonitor.supported() -> {
                AppCaptureCapability(false, "OS session lock and sleep monitoring is unavailable.")
            }

            else -> {
                AppCaptureCapability(true, backgroundWindows = true, ownedDialogs = true)
            }
        }

    // Stream lifetimes, geometry fences and terminal transitions remain in one auditable owner scope.
    // Classify only after the common minimize/ownership checks; separate catches would duplicate those fences.
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "TooGenericExceptionCaught",
        "LongParameterList",
        "InstanceOfCheckForException",
        "NestedBlockDepth", // Optional aggregate probes remain inside the existing publication fence.
    )
    fun start(
        target: AppCaptureTarget,
        onFrame: (AppRawCapturedFrame?) -> Unit,
        onStopped: (String) -> Unit,
        shouldCapture: () -> Boolean,
        frameRate: () -> Int = { 30 },
        pixelFormat: () -> String = { "BGRA" },
    ): AutoCloseable {
        UUID.fromString(target.windowId)
        UUID.fromString(target.generation)
        check(capability(target).supported)
        val closed = AtomicBoolean()
        val executor =
            Executors.newSingleThreadScheduledExecutor {
                Thread(it, "boss-app-continuous-capture").apply { isDaemon = true }
            }
        val nativePlatform = checkNotNull(platform)
        val streams = mutableListOf<AppNativeWindowStream>()
        val resources = OwnedAppResources()
        resources.own(
            AutoCloseable {
                closed.set(true)
                executor.shutdownNow()
                onFrame(null)
                val retired = synchronized(streams) { streams.toList().also { streams.clear() } }
                retired.forEach { runCatching { it.close() } }
            },
        )

        fun stop(reason: String) {
            if (closed.compareAndSet(false, true)) {
                resources.close()
                onStopped(reason)
            }
        }
        try {
            val unavailable = { stop("Sharing stopped because the OS session locked, switched, or went to sleep.") }
            resources.own(
                if (nativePlatform.monitorsSession) {
                    nativePlatform.watchSession(unavailable)
                } else {
                    sessionMonitor.watch(unavailable)
                },
            )
        } catch (failure: Throwable) {
            resources.close()
            throw failure
        }
        var geometry: AppSurfaceSnapshot? = null
        var preferredFormat: String? = null
        var revision = 0L
        var failures = 0
        var retryAt = 0L
        var lastPixels: List<AppRawWindowFrame> = emptyList()
        var lastWorkerStart = 0L
        var lastWorkerEnd = 0L
        executor.scheduleWithFixedDelay({
            val began = if (diagnostics == null) 0L else System.nanoTime()
            diagnostics?.let {
                it.add(AppCaptureDiagnostics.Counter.WORKER_PASSES)
                if (lastWorkerStart != 0L) it.time(AppCaptureDiagnostics.Timing.WORKER_PERIOD, began - lastWorkerStart)
                if (lastWorkerEnd != 0L) it.time(AppCaptureDiagnostics.Timing.WORKER_GAP, began - lastWorkerEnd)
                lastWorkerStart = began
            }
            try {
                if (closed.get() || System.nanoTime() < retryAt) return@scheduleWithFixedDelay
                val minimized =
                    measuredCaptureEdt(
                        diagnostics,
                        AppCaptureDiagnostics.Timing.MINIMIZE_QUEUE,
                        AppCaptureDiagnostics.Timing.MINIMIZE_WORK,
                    ) {
                        appCaptureTemporarilyMinimized(target.awtWindow)
                    }
                if (!shouldCapture() || minimized) {
                    diagnostics?.add(AppCaptureDiagnostics.Counter.WORKER_PAUSED)
                    synchronized(streams) {
                        streams.forEach { it.close() }
                        streams.clear()
                    }
                    if (geometry != null) {
                        geometry = null
                        lastPixels = emptyList()
                        onFrame(null)
                    }
                    return@scheduleWithFixedDelay
                }
                val current =
                    measuredCaptureEdt(
                        diagnostics,
                        AppCaptureDiagnostics.Timing.GEOMETRY_QUEUE,
                        AppCaptureDiagnostics.Timing.GEOMETRY_WORK,
                    ) {
                        captureSurfaceSnapshot(target.awtWindow)
                    }
                val currentFormat = if (current.surfaces.size == 1) pixelFormat() else "BGRA"
                if (current != geometry || currentFormat != preferredFormat) {
                    onFrame(null)
                    val retired = synchronized(streams) { streams.toList().also { streams.clear() } }
                    retired.forEach { it.close() }
                    run {
                        val size = appCaptureFrameSize(current.width, current.height, 1920)
                        for (surface in current.surfaces) {
                            if (closed.get() || !shouldCapture()) return@scheduleWithFixedDelay
                            val surfaceSize =
                                appCaptureSurfaceFrameSize(surface.geometry, current.logicalWidth, size.width)
                            val next =
                                nativePlatform.open(
                                    surface.geometry.nativeHandle,
                                    surfaceSize.width,
                                    surfaceSize.height,
                                    frameRate,
                                    currentFormat,
                                    surface.geometry,
                                    diagnostics,
                                )
                            val admitted =
                                synchronized(streams) {
                                    if (closed.get()) {
                                        false
                                    } else {
                                        streams.add(next)
                                        true
                                    }
                                }
                            if (!admitted) {
                                next.close()
                                return@scheduleWithFixedDelay
                            }
                        }
                    }
                    geometry = current
                    preferredFormat = currentFormat
                    revision++
                    lastPixels = emptyList()
                }
                val pixels = synchronized(streams) { streams.map { it.latest() } }
                if (pixels.any { it == null }) {
                    diagnostics?.add(AppCaptureDiagnostics.Counter.WORKER_NO_PIXELS)
                    return@scheduleWithFixedDelay
                }
                val complete = pixels.filterNotNull()
                val unchanged = complete.indices.all { complete[it] === lastPixels.getOrNull(it) }
                if (complete.size != current.surfaces.size || unchanged) {
                    diagnostics?.add(AppCaptureDiagnostics.Counter.WORKER_UNCHANGED)
                    return@scheduleWithFixedDelay
                }
                if (closed.get() || !shouldCapture() || measuredCaptureEdt(
                        diagnostics,
                        AppCaptureDiagnostics.Timing.FINAL_GEOMETRY_QUEUE,
                        AppCaptureDiagnostics.Timing.FINAL_GEOMETRY_WORK,
                    ) {
                        captureSurfaceSnapshot(target.awtWindow)
                    } != current
                ) {
                    diagnostics?.add(AppCaptureDiagnostics.Counter.WORKER_GEOMETRY_REJECTED)
                    return@scheduleWithFixedDelay
                }
                failures = 0
                retryAt = 0L
                val frame =
                    measuredCapture(diagnostics, AppCaptureDiagnostics.Timing.COMPOSE) {
                        composeRawWindowFrames(current, complete)
                    }
                lastPixels = complete
                if (!closed.get() && shouldCapture()) {
                    diagnostics?.let { probe ->
                        probe.add(AppCaptureDiagnostics.Counter.WORKER_PUBLISHED)
                        complete.mapNotNull { it.diagnosticStamp?.readyAtNanos }.minOrNull()?.let { ready ->
                            probe.time(AppCaptureDiagnostics.Timing.DELIVERY_OLDEST_AGE, System.nanoTime() - ready)
                        }
                    }
                    onFrame(AppRawCapturedFrame(frame, revision, current))
                }
            } catch (failure: Throwable) {
                diagnostics?.add(AppCaptureDiagnostics.Counter.WORKER_FAILURES)
                // Native capture can report its window becoming unavailable in the
                // same iteration that the user minimizes it. That is a pixel pause,
                // not revocation; preserve the publication for explicit restoration.
                val minimized =
                    runCatching { onEdt { appCaptureTemporarilyMinimized(target.awtWindow, nativeCheck = true) } }
                        .getOrDefault(false)
                if (minimized && !closed.get()) {
                    val retired = synchronized(streams) { streams.toList().also { streams.clear() } }
                    retired.forEach { runCatching { it.close() } }
                    geometry = null
                    lastPixels = emptyList()
                    onFrame(null)
                    return@scheduleWithFixedDelay
                }
                val changed = runCatching { onEdt { captureSurfaceSnapshot(target.awtWindow) } }.getOrNull()
                // A generic helper/session/protocol failure is terminal even if
                // an AWT resize happens concurrently. Only typed native geometry
                // changes may enter the bounded transient retry path.
                val changedDuringCapture =
                    failure !is AppNativeCaptureStoppedException &&
                        changed != null && geometry != null && changed != geometry
                val transient =
                    changed != null && failures < 3 && isTransientCaptureFailure(failure) &&
                        runCatching { nativePlatform.available() }.getOrDefault(false)
                if (changedDuringCapture || transient) {
                    if (transient) {
                        failures++
                        retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250L * failures)
                    }
                    // A dialog/resize changed during startup. Retire partial streams before retrying.
                    val retired = synchronized(streams) { streams.toList().also { streams.clear() } }
                    retired.forEach { runCatching { it.close() } }
                    geometry = null
                    onFrame(null)
                } else if (!closed.get()) {
                    stop(captureFailureStatus(failure))
                }
            } finally {
                diagnostics?.let {
                    lastWorkerEnd = System.nanoTime()
                    it.time(AppCaptureDiagnostics.Timing.WORKER_WORK, lastWorkerEnd - began)
                }
            }
        }, 0, 8, TimeUnit.MILLISECONDS)
        return AutoCloseable {
            closed.set(true)
            resources.close()
        }
    }
}

/** Minimize pauses pixels only; a destroyed/hidden window is terminal, never a paused share. */
@Suppress("ReturnCount") // Visibility, platform and exact native identity each fence the OS query.
internal fun appCaptureTemporarilyMinimized(
    window: Window,
    nativeCheck: Boolean = false,
): Boolean {
    check(window.isDisplayable && window.isVisible) { "Shared window is no longer available" }
    if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) return true
    if (!nativeCheck) return false
    val handle = (window as? ComposeWindow)?.windowHandle ?: return false
    if (handle == 0L) return false
    if (SystemUtils.isWindows) {
        return runCatching {
            WindowsAppCaptureState.minimized(handle) && window.isDisplayable && window.isVisible &&
                window.windowHandle == handle
        }.getOrDefault(false)
    }
    if (!SystemUtils.isMacOS) return false
    return runCatching {
        scopedNativeToolbarCall(System.currentTimeMillis() + 150) {
            val native = Pointer(handle)
            MacToolbarRuntime.isLiveWindow(native) && MacToolbarRuntime.number(native, "isMiniaturized") != 0L
        }
    }.getOrDefault(false)
}

/** Uniform native scaling and premultiplied source-over preserve owned overlays without image encoding. */
internal fun composeRawWindowFrames(
    snapshot: AppSurfaceSnapshot,
    frames: List<AppRawWindowFrame>,
): AppRawWindowFrame {
    require(frames.size == snapshot.surfaces.size && frames.isNotEmpty())
    if (frames.size == 1) return frames.single()
    val size = appCaptureFrameSize(snapshot.width, snapshot.height, 1920)
    val scale = size.width.toDouble() / snapshot.logicalWidth
    val bytes = ByteArray(size.width * size.height * 4)
    // Video has no alpha: uncovered space and transparent window corners resolve to black.
    for (alpha in 3 until bytes.size step 4) bytes[alpha] = 255.toByte()
    snapshot.surfaces.zip(frames).forEach { (surface, frame) ->
        val x = ((surface.geometry.x - snapshot.x) * scale).roundToInt()
        val y = ((surface.geometry.y - snapshot.y) * scale).roundToInt()
        blendPremultipliedBgra(frame, bytes, size.width, x, y)
    }
    return AppRawWindowFrame(bytes, size.width, size.height)
}
