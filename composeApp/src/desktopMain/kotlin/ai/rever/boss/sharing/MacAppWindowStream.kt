package ai.rever.boss.sharing

import ai.rever.boss.platform.MacOSScreenCapture
import ai.rever.boss.window.MacToolbarRuntime
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Tightly packed BGRA or limited-range BT.709 NV12. No image codec or Base64 is involved. */
internal data class AppRawWindowFrame(
    val bgra: ByteArray,
    val width: Int,
    val height: Int,
    val format: String = "BGRA",
    val diagnosticStamp: AppCaptureFrameStamp? = null,
)

/** One continuous desktop-independent SCStream for an exact NSWindow belonging to this process. */
internal class MacAppWindowStream private constructor(
    private val stream: Pointer,
    private val output: Pointer,
    private val state: StreamOutputState,
    private val configuration: Pointer,
) : AutoCloseable {
    private var configuredRate = state.frameRate().coerceIn(30, 60)

    @Synchronized
    fun latest(): AppRawWindowFrame? {
        if (state.closed.get()) return null
        state.failure.get()?.let { throw it }
        val requestedRate = state.frameRate().coerceIn(30, 60)
        if (requestedRate != configuredRate) {
            // Rate changes stay within the existing exact-window filter. Native pacing preserves
            // the final changed frame when the source becomes idle, unlike dropping callbacks.
            runCatching {
                val next = checkNotNull(MacToolbarRuntime.pointer(configuration, "copy"))
                try {
                    val interval = StreamFrameInterval().apply { timescale = requestedRate }
                    MacToolbarRuntime.send(next, "setMinimumFrameInterval:", interval)
                    StreamErrorBlock
                        .call { MacToolbarRuntime.send(stream, "updateConfiguration:completionHandler:", next, it) }
                        .get(5, TimeUnit.SECONDS)
                } finally {
                    MacToolbarRuntime.send(next, "release")
                }
            }
            // Optional tuning failure keeps the existing stream and avoids a busy retry loop.
            configuredRate = requestedRate
        }
        val frame = state.latest.get()
        state.streamDiagnostics?.consume(frame?.diagnosticStamp, System.nanoTime())
        return frame
    }

    @Synchronized
    override fun close() {
        if (!state.closed.compareAndSet(false, true)) return
        state.latest.set(null)
        // Native callbacks can be in flight. Their static IMP remains alive; the state is fenced now.
        StreamErrorBlock
            .call(teardown = true) { block ->
                MacToolbarRuntime.send(stream, "stopCaptureWithCompletionHandler:", block)
            }.whenComplete { _, _ ->
                StreamOutputRuntime.remove(output)
                MacToolbarRuntime.send(stream, "release")
                MacToolbarRuntime.send(output, "release")
                MacToolbarRuntime.send(configuration, "release")
            }
    }

    companion object {
        private val framework by lazy {
            NativeLibrary.getInstance("/System/Library/Frameworks/ScreenCaptureKit.framework/ScreenCaptureKit")
        }

        fun available(): Boolean {
            framework
            // Preserve the existing macOS 14+ capability boundary; no screenshot API is invoked.
            return MacToolbarRuntime.clazz("SCStream") != null &&
                MacToolbarRuntime.clazz("SCScreenshotManager") != null && MacOSScreenCapture.hasPermission()
        }

        private fun configurePixelFormat(
            configuration: Pointer,
            format: String,
        ) {
            val nativeFormat = if (format == "NV12") 0x34323076 else 0x42475241
            MacToolbarRuntime.send(configuration, "setPixelFormat:", nativeFormat)
            if (format != "NV12") return
            val graphics = NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
            val matrix = graphics.getGlobalVariableAddress("kCGDisplayStreamYCbCrMatrix_ITU_R_709_2").getPointer(0)
            val colorSpace = graphics.getGlobalVariableAddress("kCGColorSpaceITUR_709").getPointer(0)
            MacToolbarRuntime.send(configuration, "setColorMatrix:", matrix)
            MacToolbarRuntime.send(configuration, "setColorSpaceName:", colorSpace)
        }

        private fun compatiblePixelFormat(
            preferred: String,
            width: Int,
            height: Int,
        ): String = if (preferred == "NV12" && width % 2 == 0 && height % 2 == 0) "NV12" else "BGRA"

        private fun nativeCaptureWindowId(
            nativeHandle: Long,
            expectedWindowNumber: Long?,
            expectedParentHandle: Long?,
        ): Long {
            val window = Pointer(nativeHandle)
            check(MacToolbarRuntime.isLiveWindow(window)) { "Native window is no longer owned" }
            if (expectedParentHandle != null) {
                val parent = Pointer(expectedParentHandle)
                check(MacToolbarRuntime.isLiveWindow(parent)) { "Native toolbar parent retired" }
                check(MacToolbarRuntime.pointer(window, "parentWindow") == parent) { "Native toolbar owner changed" }
            }
            return MacToolbarRuntime.number(window, "windowNumber").also {
                check(it > 0 && (expectedWindowNumber == null || it == expectedWindowNumber)) {
                    "Native capture identity changed"
                }
            }
        }

        // All native references have one owner and are released on every unsuccessful startup.
        @Suppress("LongMethod", "TooGenericExceptionCaught", "LongParameterList")
        fun open(
            nativeHandle: Long,
            width: Int,
            height: Int,
            frameRate: () -> Int = { 30 },
            pixelFormat: String = "BGRA",
            diagnostics: AppCaptureDiagnostics? = null,
            expectedWindowNumber: Long? = null,
            expectedParentHandle: Long? = null,
        ): MacAppWindowStream {
            check(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 4_194_304)
            require(pixelFormat == "BGRA" || pixelFormat == "NV12")
            val format = compatiblePixelFormat(pixelFormat, width, height)
            check(available()) { "Continuous window capture unavailable" }
            val identity = CompletableFuture<Long>()
            MacToolbarRuntime.dispatch {
                runCatching {
                    nativeCaptureWindowId(nativeHandle, expectedWindowNumber, expectedParentHandle)
                }.fold(identity::complete, identity::completeExceptionally)
            }
            val windowId = identity.get(5, TimeUnit.SECONDS)
            val contentRequest =
                CompletionBlock.call { block ->
                    MacToolbarRuntime.send(
                        MacToolbarRuntime.clazz("SCShareableContent"),
                        "getShareableContentExcludingDesktopWindows:onScreenWindowsOnly:completionHandler:",
                        1.toByte(),
                        0.toByte(),
                        block,
                    )
                }
            val content =
                try {
                    contentRequest.get(10, TimeUnit.SECONDS) ?: error("Missing capture content")
                } catch (failure: Throwable) {
                    contentRequest.whenComplete { value, _ -> MacToolbarRuntime.send(value, "release") }
                    throw failure
                }
            var filter: Pointer? = null
            var configuration: Pointer? = null
            var output: Pointer? = null
            var stream: Pointer? = null
            try {
                val list = MacToolbarRuntime.pointer(content, "windows")
                val count = MacToolbarRuntime.number(list, "count")
                check(count in 0..4096)
                val candidates = (0 until count).map { MacToolbarRuntime.pointer(list, "objectAtIndex:", it) }
                val identities =
                    candidates.map {
                        MacToolbarRuntime.number(it, "windowID") to
                            MacToolbarRuntime.number(MacToolbarRuntime.pointer(it, "owningApplication"), "processID")
                    }
                val index = checkNotNull(exactCaptureWindowIndex(identities, windowId, ProcessHandle.current().pid()))
                filter =
                    checkNotNull(
                        MacToolbarRuntime.pointer(
                            MacToolbarRuntime.pointer(MacToolbarRuntime.clazz("SCContentFilter"), "alloc"),
                            "initWithDesktopIndependentWindow:",
                            candidates[index],
                        ),
                    )
                configuration =
                    checkNotNull(MacToolbarRuntime.pointer(MacToolbarRuntime.clazz("SCStreamConfiguration"), "new"))
                MacToolbarRuntime.send(configuration, "setWidth:", width.toLong())
                MacToolbarRuntime.send(configuration, "setHeight:", height.toLong())
                configurePixelFormat(configuration, format)
                MacToolbarRuntime.send(configuration, "setShowsCursor:", 0.toByte())
                MacToolbarRuntime.send(configuration, "setQueueDepth:", 3L)
                MacToolbarRuntime.send(
                    configuration,
                    "setMinimumFrameInterval:",
                    StreamFrameInterval().apply { timescale = frameRate().coerceIn(30, 60) },
                )
                if (MacToolbarRuntime.supports(configuration, "setIncludeChildWindows:")) {
                    MacToolbarRuntime.send(configuration, "setIncludeChildWindows:", 0.toByte())
                }
                if (MacToolbarRuntime.supports(configuration, "setIgnoreShadowsSingleWindow:")) {
                    MacToolbarRuntime.send(configuration, "setIgnoreShadowsSingleWindow:", 1.toByte())
                }
                val state = StreamOutputState(width, height, frameRate, format, diagnostics)
                output = StreamOutputRuntime.create(state)
                stream =
                    checkNotNull(
                        MacToolbarRuntime.pointer(
                            MacToolbarRuntime.pointer(MacToolbarRuntime.clazz("SCStream"), "alloc"),
                            "initWithFilter:configuration:delegate:",
                            filter,
                            configuration,
                            output,
                        ),
                    )
                Memory(8).use { error ->
                    error.clear()
                    check(
                        MacToolbarRuntime.number(
                            stream,
                            "addStreamOutput:type:sampleHandlerQueue:error:",
                            output,
                            0L,
                            StreamOutputRuntime.queue,
                            error,
                        ) != 0L,
                    ) { "Native stream output rejected" }
                }
                val selectedStream = stream
                StreamErrorBlock
                    .call { MacToolbarRuntime.send(selectedStream, "startCaptureWithCompletionHandler:", it) }
                    .get(10, TimeUnit.SECONDS)
                val result = MacAppWindowStream(selectedStream, output, state, configuration)
                configuration = null
                stream = null
                output = null
                return result
            } finally {
                stream?.let { MacToolbarRuntime.send(it, "stopCaptureWithCompletionHandler:", null) }
                output?.let(StreamOutputRuntime::remove)
                MacToolbarRuntime.send(stream, "release")
                MacToolbarRuntime.send(output, "release")
                MacToolbarRuntime.send(configuration, "release")
                MacToolbarRuntime.send(filter, "release")
                MacToolbarRuntime.send(content, "release")
            }
        }
    }
}

@Structure.FieldOrder("value", "timescale", "flags", "epoch")
internal class StreamFrameInterval :
    Structure(),
    Structure.ByValue {
    @JvmField var value: Long = 1

    @JvmField var timescale: Int = 60

    @JvmField var flags: Int = 1

    // kCMTimeFlags_Valid
    @JvmField var epoch: Long = 0
}

private class StreamOutputState(
    val width: Int,
    val height: Int,
    val frameRate: () -> Int,
    val format: String,
    val diagnostics: AppCaptureDiagnostics?,
) {
    val streamDiagnostics = diagnostics?.stream(mac = true)
    val latest = AtomicReference<AppRawWindowFrame?>()
    val failure = AtomicReference<Throwable?>()
    val closed = AtomicBoolean()
}

/** Static native IMPs outlive every stream. Only the latest complete pixel buffer is copied. */
private object StreamOutputRuntime {
    private val coreMedia = NativeLibrary.getInstance("/System/Library/Frameworks/CoreMedia.framework/CoreMedia")
    private val coreVideo = NativeLibrary.getInstance("/System/Library/Frameworks/CoreVideo.framework/CoreVideo")
    private val statusKey =
        NativeLibrary
            .getInstance("/System/Library/Frameworks/ScreenCaptureKit.framework/ScreenCaptureKit")
            .getGlobalVariableAddress("SCStreamFrameInfoStatus")
            .getPointer(0)
    private val system = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    val queue: Pointer =
        checkNotNull(
            system.getFunction("dispatch_queue_create").invokePointer(arrayOf("boss-app-window-stream", null)),
        )
    private val states = ConcurrentHashMap<Pointer, StreamOutputState>()

    private fun interface Output : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            stream: Pointer,
            sample: Pointer,
            type: Long,
        )
    }

    private fun interface Stopped : Callback {
        fun invoke(
            self: Pointer,
            selector: Pointer,
            stream: Pointer,
            error: Pointer,
        )
    }

    // Native callback exceptions must be fenced before returning to Objective-C.
    @Suppress("TooGenericExceptionCaught")
    private val outputCallback =
        Output { self, _, _, sample, type ->
            val state = states[self]
            if (type == 0L && state != null && !state.closed.get()) {
                try {
                    state.diagnostics?.add(AppCaptureDiagnostics.Counter.NATIVE_CALLBACKS)
                    readFrame(sample, state)?.let { frame ->
                        state.diagnostics?.add(AppCaptureDiagnostics.Counter.NATIVE_COPIED)
                        val observed =
                            state.streamDiagnostics?.let {
                                frame.copy(diagnosticStamp = it.stamp(System.nanoTime()))
                            } ?: frame
                        if (!state.closed.get()) {
                            state.latest.set(observed)
                            state.diagnostics?.add(AppCaptureDiagnostics.Counter.NATIVE_PUBLISHED)
                        }
                    }
                } catch (failure: Throwable) {
                    state.failure.compareAndSet(null, failure)
                }
            }
        }
    private val stoppedCallback =
        Stopped { self, _, _, error ->
            val failure =
                NativeAppCaptureException(
                    "com.apple.ScreenCaptureKit.SCStreamErrorDomain",
                    MacToolbarRuntime.number(error, "code"),
                )
            states[self]?.failure?.compareAndSet(
                null,
                failure,
            )
        }
    private val outputClass: Pointer by lazy {
        val objc = MacToolbarRuntime.objc
        val name = "BossExactWindowStreamOutput"
        val allocation = objc.getFunction("objc_allocateClassPair")
        val cls = checkNotNull(allocation.invokePointer(arrayOf(MacToolbarRuntime.clazz("NSObject"), name, 0L)))

        fun add(
            selector: String,
            callback: Callback,
            signature: String,
        ) {
            check(
                objc.getFunction("class_addMethod").invokeInt(
                    arrayOf(
                        cls,
                        MacToolbarRuntime.selector(selector),
                        CallbackReference.getFunctionPointer(callback),
                        signature,
                    ),
                ) != 0,
            )
        }
        add("stream:didOutputSampleBuffer:ofType:", outputCallback, "v@:@@q")
        add("stream:didStopWithError:", stoppedCallback, "v@:@@")
        for (nameOfProtocol in listOf("SCStreamOutput", "SCStreamDelegate")) {
            val protocol = objc.getFunction("objc_getProtocol").invokePointer(arrayOf(nameOfProtocol))
            check(protocol != null)
            check(objc.getFunction("class_addProtocol").invokeInt(arrayOf(cls, protocol)) != 0)
        }
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(cls))
        cls
    }

    fun create(state: StreamOutputState): Pointer =
        checkNotNull(MacToolbarRuntime.pointer(outputClass, "new"))
            .also { states[it] = state }

    fun remove(output: Pointer) {
        states.remove(output)
    }

    // Reject incomplete samples before touching their untrusted native pixel storage.
    @Suppress("ReturnCount")
    private fun readFrame(
        sample: Pointer,
        state: StreamOutputState,
    ): AppRawWindowFrame? {
        if (coreMedia.getFunction("CMSampleBufferIsValid").invokeInt(arrayOf(sample)) == 0) return null
        val attachments =
            coreMedia
                .getFunction("CMSampleBufferGetSampleAttachmentsArray")
                .invokePointer(arrayOf(sample, 0.toByte())) ?: return null
        if (MacToolbarRuntime.number(attachments, "count") == 0L) return null
        val info = MacToolbarRuntime.pointer(attachments, "objectAtIndex:", 0L)
        val status = MacToolbarRuntime.pointer(info, "objectForKey:", statusKey) ?: return null
        if (MacToolbarRuntime.number(status, "integerValue") != 0L) return null // SCFrameStatusComplete
        state.diagnostics?.add(AppCaptureDiagnostics.Counter.NATIVE_COMPLETE)
        val pixels = coreMedia.getFunction("CMSampleBufferGetImageBuffer").invokePointer(arrayOf(sample)) ?: return null
        val width = coreVideo.getFunction("CVPixelBufferGetWidth").invokeLong(arrayOf(pixels)).toInt()
        val height = coreVideo.getFunction("CVPixelBufferGetHeight").invokeLong(arrayOf(pixels)).toInt()
        if (width != state.width || height != state.height) return null
        val expectedFormat = if (state.format == "NV12") 0x34323076 else 0x42475241
        check(coreVideo.getFunction("CVPixelBufferGetPixelFormatType").invokeInt(arrayOf(pixels)) == expectedFormat)
        check(coreVideo.getFunction("CVPixelBufferLockBaseAddress").invokeInt(arrayOf(pixels, 1L)) == 0)
        return try {
            measuredCapture(state.diagnostics, AppCaptureDiagnostics.Timing.NATIVE_COPY) {
                if (state.format == "NV12") {
                    readNv12Frame(pixels, width, height)
                } else {
                    readBgraFrame(pixels, width, height)
                }
            }
        } finally {
            coreVideo.getFunction("CVPixelBufferUnlockBaseAddress").invokeInt(arrayOf(pixels, 1L))
        }
    }

    private fun readBgraFrame(
        pixels: Pointer,
        width: Int,
        height: Int,
    ): AppRawWindowFrame {
        val base = checkNotNull(coreVideo.getFunction("CVPixelBufferGetBaseAddress").invokePointer(arrayOf(pixels)))
        val stride = coreVideo.getFunction("CVPixelBufferGetBytesPerRow").invokeLong(arrayOf(pixels))
        val bytes = ByteArray(width * height * 4)
        copyPixelRows(base, stride, bytes, 0, width * 4, height)
        return AppRawWindowFrame(bytes, width, height)
    }

    private fun readNv12Frame(
        pixels: Pointer,
        width: Int,
        height: Int,
    ): AppRawWindowFrame {
        check(coreVideo.getFunction("CVPixelBufferGetPlaneCount").invokeLong(arrayOf(pixels)) == 2L)
        val bytes = ByteArray(width * height * 3 / 2)
        for (plane in 0L..1L) {
            val planeWidth = coreVideo.getFunction("CVPixelBufferGetWidthOfPlane").invokeLong(arrayOf(pixels, plane))
            val planeHeight = coreVideo.getFunction("CVPixelBufferGetHeightOfPlane").invokeLong(arrayOf(pixels, plane))
            val rows = if (plane == 0L) height else height / 2
            check(planeWidth == (if (plane == 0L) width else width / 2).toLong() && planeHeight == rows.toLong())
            val base =
                checkNotNull(
                    coreVideo.getFunction("CVPixelBufferGetBaseAddressOfPlane").invokePointer(arrayOf(pixels, plane)),
                )
            val stride = coreVideo.getFunction("CVPixelBufferGetBytesPerRowOfPlane").invokeLong(arrayOf(pixels, plane))
            copyPixelRows(base, stride, bytes, if (plane == 0L) 0 else width * height, width, rows)
        }
        return AppRawWindowFrame(bytes, width, height, "NV12")
    }

    @Suppress("LongParameterList") // Native plane address, stride, destination, and dimensions are independent bounds.
    private fun copyPixelRows(
        base: Pointer,
        stride: Long,
        bytes: ByteArray,
        offset: Int,
        rowBytes: Int,
        rows: Int,
    ) {
        check(stride in rowBytes.toLong()..65536L)
        if (stride == rowBytes.toLong()) {
            base.read(0, bytes, offset, rowBytes * rows)
        } else {
            for (row in 0 until rows) base.read(row * stride, bytes, offset + row * rowBytes, rowBytes)
        }
    }
}

/** Completion for SCStream's one-argument NSError block; distinct from the screenshot block ABI. */
@Suppress("TooGenericExceptionCaught") // No exception may unwind through the native completion ABI.
private object StreamErrorBlock {
    private val library = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val descriptor =
        Memory(16).apply {
            setLong(0, 0)
            setLong(8, 32)
        }
    private val pending = ConcurrentHashMap.newKeySet<Callback>()

    private fun interface Completion : Callback {
        fun invoke(
            block: Pointer?,
            error: Pointer?,
        )
    }

    fun call(
        teardown: Boolean = false,
        invoke: (Pointer) -> Unit,
    ): CompletableFuture<Unit> {
        // Startup backpressure must never prevent stopping an already-owned native stream.
        // Each admitted stream can add only one teardown, even if the OS callback is delayed.
        check(teardown || pending.size < 64) { "Native stream callback budget exhausted" }
        val future = CompletableFuture<Unit>()
        lateinit var callback: Completion
        callback =
            Completion { _, error ->
                try {
                    if (error == null) {
                        future.complete(Unit)
                    } else {
                        val code = MacToolbarRuntime.number(error, "code")
                        val domain = "com.apple.ScreenCaptureKit.SCStreamErrorDomain"
                        future.completeExceptionally(NativeAppCaptureException(domain, code))
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
