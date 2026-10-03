// Java exceptions and linkage errors must never unwind through an Objective-C callback.
@file:Suppress("TooGenericExceptionCaught")

package ai.rever.boss.sharing

import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.MacToolbarRuntime
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Uses macOS 14+ SCScreenshotManager, not deprecated CGWindowListCreateImage. CaptureSource in
 * JxBrowser 9.5.2 exposes no native window ID, so its title-based picker cannot implement this.
 * The selected SCWindow must match both the live Compose NSWindow's number and this process.
 */
internal class NativeAppCaptureException(
    val domain: String,
    val code: Long,
) : IllegalStateException("Native capture refused: $domain ($code)")

internal class MacAppWindowFrameSource : ExactWindowFrameSource {
    private val runtime: ScreenCaptureRuntime? by lazy {
        if (!SystemUtils.isMacOS) null else runCatching { ScreenCaptureRuntime() }.getOrNull()
    }

    override fun available(): Boolean = runtime?.supported == true && MacOSScreenCapture.hasPermission()

    override fun capture(
        nativeHandle: Long,
        processId: Long,
        width: Int,
        height: Int,
    ): NativeWindowFrame {
        check(available()) { "Capture unavailable" }
        val api = checkNotNull(runtime)
        val result = CompletableFuture<NativeWindowFrame>()
        val stage = AtomicReference("AppKit dispatch")
        MacToolbarRuntime.dispatch {
            try {
                val window = Pointer(nativeHandle)
                check(MacToolbarRuntime.isLiveWindow(window)) { "The native window is no longer owned by this app" }
                val number = MacToolbarRuntime.number(window, "windowNumber")
                check(number > 0)
                api.capture(number, processId, width, height, result, stage)
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        return try {
            result.get(15, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            throw TimeoutException("Native window capture timed out during ${stage.get()}").apply { initCause(timeout) }
        }
    }
}

private class ScreenCaptureRuntime {
    // Keep framework loaded before objc_getClass. No native symbols load on other platforms.
    @Suppress("unused")
    private val screenCaptureKit =
        NativeLibrary.getInstance(
            "/System/Library/Frameworks/ScreenCaptureKit.framework/ScreenCaptureKit",
        )
    private val coreFoundation =
        NativeLibrary.getInstance(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
        )
    private val imageIO = NativeLibrary.getInstance("/System/Library/Frameworks/ImageIO.framework/ImageIO")
    private val coreGraphics =
        NativeLibrary.getInstance(
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
        )
    val supported: Boolean = MacToolbarRuntime.clazz("SCScreenshotManager") != null

    // Keep retain/release ownership of both nested native completions visible in one scope.
    @Suppress("LongMethod", "LongParameterList")
    fun capture(
        windowId: Long,
        processId: Long,
        width: Int,
        height: Int,
        result: CompletableFuture<NativeWindowFrame>,
        stage: AtomicReference<String>,
    ) {
        check(supported)
        stage.set("shareable window lookup")
        CompletionBlock
            .call { block ->
                // Use complete SCWindow objects for screenshot filters. The current-process
                // enumeration API returns redacted content intended for consent-free discovery.
                MacToolbarRuntime.send(
                    MacToolbarRuntime.clazz("SCShareableContent"),
                    "getShareableContentExcludingDesktopWindows:onScreenWindowsOnly:completionHandler:",
                    1.toByte(),
                    // Background windows still match the exact native ID and owner PID below.
                    0.toByte(),
                    block,
                )
            }.whenComplete { content, error ->
                if (error != null ||
                    content == null
                ) {
                    result.completeExceptionally(error ?: IllegalStateException("Missing capture content"))
                    return@whenComplete
                }
                var filter: Pointer? = null
                var configuration: Pointer? = null
                try {
                    val list = MacToolbarRuntime.pointer(content, "windows")
                    val count = MacToolbarRuntime.number(list, "count")
                    check(count in 0..4096)
                    val windows = (0 until count).map { MacToolbarRuntime.pointer(list, "objectAtIndex:", it) }
                    val identities =
                        windows.map { candidate ->
                            MacToolbarRuntime.number(candidate, "windowID") to
                                MacToolbarRuntime.number(
                                    MacToolbarRuntime.pointer(candidate, "owningApplication"),
                                    "processID",
                                )
                        }
                    val index =
                        checkNotNull(
                            exactCaptureWindowIndex(identities, windowId, processId),
                        ) { "Exact source not present" }
                    filter =
                        MacToolbarRuntime.pointer(
                            MacToolbarRuntime.pointer(MacToolbarRuntime.clazz("SCContentFilter"), "alloc"),
                            "initWithDesktopIndependentWindow:",
                            windows[index],
                        )
                    checkNotNull(filter)
                    configuration = MacToolbarRuntime.pointer(MacToolbarRuntime.clazz("SCStreamConfiguration"), "new")
                    checkNotNull(configuration)
                    MacToolbarRuntime.send(configuration, "setWidth:", width.toLong())
                    MacToolbarRuntime.send(configuration, "setHeight:", height.toLong())
                    MacToolbarRuntime.send(configuration, "setShowsCursor:", 0.toByte())
                    if (MacToolbarRuntime.supports(configuration, "setIncludeChildWindows:")) {
                        MacToolbarRuntime.send(configuration, "setIncludeChildWindows:", 0.toByte())
                    }
                    if (MacToolbarRuntime.supports(configuration, "setIgnoreShadowsSingleWindow:")) {
                        MacToolbarRuntime.send(configuration, "setIgnoreShadowsSingleWindow:", 1.toByte())
                    }
                    stage.set("window screenshot")
                    val retainedFilter = filter
                    val retainedConfiguration = configuration
                    CompletionBlock
                        .call(retain = {
                            coreGraphics.getFunction("CGImageRetain").invokePointer(arrayOf(it))
                            Unit
                        }) { block ->
                            MacToolbarRuntime.send(
                                MacToolbarRuntime.clazz("SCScreenshotManager"),
                                "captureImageWithFilter:configuration:completionHandler:",
                                retainedFilter,
                                retainedConfiguration,
                                block,
                            )
                        }.whenComplete { image, captureError ->
                            try {
                                if (captureError != null ||
                                    image == null
                                ) {
                                    result.completeExceptionally(
                                        captureError ?: IllegalStateException("Empty window image"),
                                    )
                                } else {
                                    stage.set("PNG encoding")
                                    result.complete(encode(image, width, height))
                                }
                            } catch (failure: Throwable) {
                                result.completeExceptionally(failure)
                            } finally {
                                if (image != null) coreGraphics.getFunction("CGImageRelease").invokeVoid(arrayOf(image))
                                MacToolbarRuntime.send(retainedFilter, "release")
                                MacToolbarRuntime.send(retainedConfiguration, "release")
                            }
                        }
                    filter = null
                    configuration = null
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                } finally {
                    // The shareable-content completion block retained this NSObject for this consumer.
                    MacToolbarRuntime.send(content, "release")
                    MacToolbarRuntime.send(filter, "release")
                    MacToolbarRuntime.send(configuration, "release")
                }
            }
    }

    private fun encode(
        image: Pointer,
        width: Int,
        height: Int,
    ): NativeWindowFrame {
        check(coreGraphics.getFunction("CGImageGetWidth").invokeLong(arrayOf(image)) == width.toLong())
        check(coreGraphics.getFunction("CGImageGetHeight").invokeLong(arrayOf(image)) == height.toLong())
        val data = checkNotNull(coreFoundation.getFunction("CFDataCreateMutable").invokePointer(arrayOf(null, 0L)))
        val type =
            checkNotNull(
                coreFoundation
                    .getFunction(
                        "CFStringCreateWithCString",
                    ).invokePointer(arrayOf<Any?>(null, "public.png", 0x08000100)),
            )
        var destination: Pointer? = null
        try {
            destination =
                checkNotNull(
                    imageIO
                        .getFunction(
                            "CGImageDestinationCreateWithData",
                        ).invokePointer(arrayOf(data, type, 1L, null)),
                )
            imageIO.getFunction("CGImageDestinationAddImage").invokeVoid(arrayOf(destination, image, null))
            check(imageIO.getFunction("CGImageDestinationFinalize").invokeInt(arrayOf(destination)) != 0)
            val length = coreFoundation.getFunction("CFDataGetLength").invokeLong(arrayOf(data))
            check(length in 1..16 * 1024 * 1024)
            val bytes = checkNotNull(coreFoundation.getFunction("CFDataGetBytePtr").invokePointer(arrayOf(data)))
            return NativeWindowFrame(bytes.getByteArray(0, length.toInt()), width, height)
        } finally {
            listOfNotNull(
                destination,
                type,
                data,
            ).forEach { coreFoundation.getFunction("CFRelease").invokeVoid(arrayOf(it)) }
        }
    }
}

/**
 * Fixed Objective-C block ABI: two pointer arguments and no captured native fields. The static
 * descriptor outlives every copied block. _Block_copy owns native storage; pending keeps the JNA
 * callback alive until invocation. A hard cap stops a wedged OS API accumulating callbacks.
 */
internal object CompletionBlock {
    private val library = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val descriptor =
        Memory(16).apply {
            setLong(0, 0)
            setLong(8, 32)
        }
    private val pending = ConcurrentHashMap.newKeySet<NativeCompletion>()

    private fun interface NativeCompletion : Callback {
        fun invoke(
            block: Pointer?,
            value: Pointer?,
            error: Pointer?,
        )
    }

    private fun safeErrorDomain(domain: String?): String {
        val allowed = Regex("[a-zA-Z0-9_.-]{1,100}")
        return domain?.takeIf { it.matches(allowed) } ?: "unknown"
    }

    fun call(
        retain: (Pointer) -> Unit = { MacToolbarRuntime.send(it, "retain") },
        invoke: (Pointer) -> Unit,
    ): CompletableFuture<Pointer?> {
        check(pending.size < 32) { "Native capture callback budget exhausted" }
        val future = CompletableFuture<Pointer?>()
        lateinit var callback: NativeCompletion
        callback =
            NativeCompletion { _, value, error ->
                try {
                    if (error != null) {
                        val code = MacToolbarRuntime.number(error, "code")
                        val domain = MacToolbarRuntime.pointer(error, "domain")
                        val nativeDomain = MacToolbarRuntime.pointer(domain, "UTF8String")?.getString(0)
                        val safeDomain = safeErrorDomain(nativeDomain)
                        future.completeExceptionally(NativeAppCaptureException(safeDomain, code))
                    } else {
                        // Transfer a correctly typed retained reference to the completion consumer.
                        if (value != null) retain(value)
                        future.complete(value)
                    }
                } catch (failure: Throwable) {
                    future.completeExceptionally(failure)
                } finally {
                    pending.remove(callback)
                }
            }
        pending.add(callback)
        Memory(32).use { literal ->
            literal.setPointer(0, library.getGlobalVariableAddress("_NSConcreteStackBlock"))
            literal.setInt(8, 0)
            literal.setInt(12, 0)
            literal.setPointer(16, CallbackReference.getFunctionPointer(callback))
            literal.setPointer(24, descriptor)
            val copied = checkNotNull(library.getFunction("_Block_copy").invokePointer(arrayOf(literal)))
            try {
                invoke(copied)
            } catch (
                failure: Throwable,
            ) {
                pending.remove(callback)
                future.completeExceptionally(failure)
            } finally {
                library.getFunction("_Block_release").invokeVoid(arrayOf(copied))
            }
        }
        return future
    }
}
