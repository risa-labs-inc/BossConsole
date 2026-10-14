package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Window
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/** Local, explicit consent binds a share identity to this exact live window object. */
internal data class AppCaptureTarget(
    val windowId: String,
    val generation: String,
    val awtWindow: Window,
)

internal data class AppCapturedFrame(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val geometryRevision: Long,
    val surfaces: AppSurfaceSnapshot? = null,
)

internal data class AppCaptureCapability(
    val supported: Boolean,
    val reason: String? = null,
    val backgroundWindows: Boolean = false,
    val ownedDialogs: Boolean = false,
)

internal interface AppWindowCapture {
    fun capability(target: AppCaptureTarget): AppCaptureCapability

    fun start(
        target: AppCaptureTarget,
        onFrame: (AppCapturedFrame) -> Unit,
        onStopped: (String) -> Unit,
        shouldCapture: () -> Boolean = { true },
    ): AutoCloseable
}

/**
 * Exact ScreenCaptureKit capture of the selected window and verified Compose-owned dialogs.
 * Background/occluded windows are supported. At most one native request runs at a time;
 * there is no display/title fallback. Unknown native surfaces and unsupported platforms fail closed.
 */
internal class ExactAppWindowCapture(
    private val backend: ExactWindowFrameSource = MacAppWindowFrameSource(),
    private val isMac: Boolean = SystemUtils.isMacOS,
    private val sessionMonitor: AppCaptureSessionMonitor = DesktopAppCaptureSessionMonitor(),
    private val failureObserver: (Throwable) -> Unit = {},
) : AppWindowCapture {
    override fun capability(target: AppCaptureTarget): AppCaptureCapability =
        when {
            !isMac -> {
                AppCaptureCapability(
                    false,
                    "Exact application-window capture is currently available only on macOS 14 or newer.",
                )
            }

            target.awtWindow !is ComposeWindow -> {
                AppCaptureCapability(false, "This window has no supported native identity.")
            }

            !backend.available() -> {
                AppCaptureCapability(
                    false,
                    "ScreenCaptureKit capture is unavailable or Screen Recording permission is missing.",
                )
            }

            !sessionMonitor.supported() -> {
                AppCaptureCapability(false, "OS session lock and sleep monitoring is unavailable.")
            }

            else -> {
                AppCaptureCapability(true, backgroundWindows = true, ownedDialogs = true)
            }
        }

    // Native/JNA failures, including linkage errors, must terminate publication rather than escape its worker.
    // Keep resource ownership and all terminal transitions in one auditable scope.
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    override fun start(
        target: AppCaptureTarget,
        onFrame: (AppCapturedFrame) -> Unit,
        onStopped: (String) -> Unit,
        shouldCapture: () -> Boolean,
    ): AutoCloseable {
        UUID.fromString(target.windowId)
        UUID.fromString(target.generation)
        check(capability(target).supported) { capability(target).reason ?: "Window capture unsupported" }
        val closed = AtomicBoolean(false)
        val executor =
            Executors.newSingleThreadScheduledExecutor {
                Thread(it, "boss-app-window-capture").apply {
                    isDaemon =
                        true
                }
            }
        val resources = OwnedAppResources()
        resources.own(AutoCloseable { executor.shutdownNow() })
        try {
            resources.own(
                sessionMonitor.watch {
                    if (closed.compareAndSet(false, true)) {
                        resources.close()
                        onStopped("Sharing stopped because the OS session locked, switched, or went to sleep.")
                    }
                },
            )
        } catch (failure: Throwable) {
            resources.close()
            throw failure
        }
        val quality = AppCaptureQuality()
        var previousSize: AppCaptureFrameSize? = null
        var previous: AppSurfaceSnapshot? = null
        var revision = 0L
        var captureFailures = 0
        var retryAt = 0L
        // One native request at a time. Schedule from this tick, without accumulating missed frames.
        lateinit var tick: Runnable
        tick =
            Runnable {
                val started = System.nanoTime()
                try {
                    if (closed.get() || started < retryAt || !shouldCapture()) return@Runnable
                    val geometry = onEdt { captureSurfaceSnapshot(target.awtWindow) }
                    val size = appCaptureFrameSize(geometry.width, geometry.height, quality.maxDimension)
                    if (geometry != previous || size != previousSize) {
                        revision++
                        previous = geometry
                        previousSize = size
                    }
                    val frame = captureSurfaces(geometry, backend, quality.maxDimension)
                    check(
                        frame.png.size <= 16 * 1024 * 1024 && frame.width == size.width &&
                            frame.height == size.height,
                    )
                    // Closing/replacing a session while native capture is pending cannot emit late pixels.
                    val stillExact = onEdt { captureSurfaceSnapshot(target.awtWindow) == geometry }
                    if (!stillExact) return@Runnable
                    captureFailures = 0
                    retryAt = 0L
                    if (!closed.get() &&
                        shouldCapture()
                    ) {
                        onFrame(AppCapturedFrame(frame.png, frame.width, frame.height, revision, geometry))
                        quality.recordCapture(System.nanoTime() - started)
                    }
                } catch (failure: Throwable) {
                    if (closed.get()) return@Runnable
                    // A dialog closing or a resize during capture invalidates this frame, not the share.
                    val changed = runCatching { onEdt { captureSurfaceSnapshot(target.awtWindow) } }.getOrNull()
                    if (changed != null && changed != previous && !closed.get()) return@Runnable
                    // Screenshot capture can transiently fail while macOS changes surfaces.
                    // Retry only a still-owned exact snapshot with current capture permission.
                    val stillOwned = changed != null && changed == previous
                    val retryable = captureFailures < 3 && isTransientCaptureFailure(failure)
                    if (stillOwned && retryable && runCatching { backend.available() }.getOrDefault(false)) {
                        captureFailures++
                        retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250L * captureFailures)
                        return@Runnable
                    }
                    runCatching { failureObserver(failure) }
                    if (closed.compareAndSet(false, true)) {
                        resources.close()
                        onStopped(captureFailureStatus(failure))
                    }
                    // Reporting must never prevent the stop callback, even before app logging is initialized.
                    runCatching {
                        BossLogger
                            .forComponent("AppWindowCapture")
                            .warn(LogCategory.BROWSER, "Exact application-window capture failed", error = failure)
                    }
                } finally {
                    if (!closed.get()) {
                        val remaining = (quality.frameIntervalNanos - (System.nanoTime() - started)).coerceAtLeast(0)
                        try {
                            executor.schedule(tick, remaining, TimeUnit.NANOSECONDS)
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            // The capture owner closed between the check and scheduling.
                        }
                    }
                }
            }
        executor.execute(tick)
        return AutoCloseable {
            closed.set(true)
            resources.close()
        }
    }
}

internal data class WindowCaptureGeometry(
    val nativeHandle: Long,
    val width: Int,
    val height: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val x: Int,
    val y: Int,
    val insets: AppCaptureInsets = AppCaptureInsets(),
    val nativeWindowNumber: Long? = null,
    val nativeParentHandle: Long? = null,
)

/** Immutable native-pixel insets captured on the EDT with the owning window geometry. */
internal data class AppCaptureInsets(
    val left: Int = 0,
    val right: Int = 0,
    val top: Int = 0,
    val bottom: Int = 0,
)

internal data class NativeWindowFrame(
    val png: ByteArray,
    val width: Int,
    val height: Int,
)

internal interface ExactWindowFrameSource {
    fun available(): Boolean

    fun capture(
        nativeHandle: Long,
        processId: Long,
        width: Int,
        height: Int,
    ): NativeWindowFrame
}

internal fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
}

/** Exact identity only: duplicate IDs, reused foreign-process IDs and absent windows fail closed. */
internal fun exactCaptureWindowIndex(
    windows: List<Pair<Long, Long>>,
    windowId: Long,
    processId: Long,
): Int? =
    windows
        .withIndex()
        .filter { it.value.first == windowId }
        .singleOrNull()
        ?.takeIf { it.value.second == processId }
        ?.index

internal fun isTransientCaptureFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.take(8).any {
        it is AppNativeGeometryChangedException ||
            (
                it is NativeAppCaptureException &&
                    it.domain == "com.apple.ScreenCaptureKit.SCStreamErrorDomain" && it.code == -3811L
            )
    }

internal fun captureFailureStatus(failure: Throwable): String {
    val denied =
        generateSequence(failure) { it.cause }.take(8).any {
            it is NativeAppCaptureException &&
                it.domain == "com.apple.ScreenCaptureKit.SCStreamErrorDomain" && it.code == -3801L
        }
    return when {
        denied -> {
            "macOS denied Screen Recording. Enable access for this app in System Settings, then restart BossConsole."
        }

        failure is java.util.concurrent.TimeoutException -> {
            "macOS window capture timed out. Check Screen Recording permission, then restart sharing."
        }

        else -> {
            "Capture stopped: the window is unavailable, hidden, or capture permission was lost."
        }
    }
}
