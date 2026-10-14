package ai.rever.boss.sharing

import java.io.DataInputStream
import java.io.EOFException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** An isolated native process, one bounded frame reader, and a latest-only publication slot. */
@Suppress("LongParameterList") // Native identity, geometry, tuning and optional probe have independent lifetimes.
internal class HelperAppWindowStream(
    helper: Path,
    nativeHandle: Long,
    width: Int,
    height: Int,
    private val frameRate: () -> Int,
    sourceGeometry: WindowCaptureGeometry? = null,
    private val diagnostics: AppCaptureDiagnostics? = null,
) : AppNativeWindowStream {
    private val streamDiagnostics = diagnostics?.stream(mac = false)
    private val closed = AtomicBoolean()
    private val frame = AtomicReference<AppRawWindowFrame?>()
    private val failure = AtomicReference<Throwable?>()
    private val started = System.nanoTime()
    private val process: Process
    private var requestedRate = frameRate()

    init {
        require(nativeHandle != 0L && width in 1..1920 && height in 1..1920)
        require(requestedRate == 30 || requestedRate == 60)
        process =
            ProcessBuilder(
                listOf(
                    helper.toString(),
                    ProcessHandle.current().pid().toString(),
                    java.lang.Long.toUnsignedString(nativeHandle),
                    width.toString(),
                    height.toString(),
                    requestedRate.toString(),
                ) + nativeCaptureGeometryArguments(nativeHandle, sourceGeometry),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        Thread({ readFrames(width, height) }, "boss-native-window-capture").apply {
            isDaemon = true
            start()
        }
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth") // One owner retires the process, stream, and frame slot.
    private fun readFrames(
        width: Int,
        height: Int,
    ) {
        try {
            DataInputStream(process.inputStream).use { input ->
                var sequence = 0L
                while (!closed.get()) {
                    val next =
                        measuredCapture(diagnostics, AppCaptureDiagnostics.Timing.HELPER_READ_WAIT) {
                            readAppNativeFrame(input, width, height, sequence)
                        }
                    sequence = next.first
                    if (!closed.get()) {
                        diagnostics?.add(AppCaptureDiagnostics.Counter.HELPER_FRAMES)
                        val observed =
                            streamDiagnostics?.let {
                                next.second.copy(diagnosticStamp = it.stamp(System.nanoTime()))
                            } ?: next.second
                        frame.set(observed)
                    }
                }
            }
        } catch (cause: AppNativeFrameBoundaryEnd) {
            if (!closed.get()) {
                frame.set(null)
                val exitCode =
                    runCatching {
                        if (process.waitFor(250, TimeUnit.MILLISECONDS)) process.exitValue() else null
                    }.getOrNull()
                failure.set(nativeCaptureFailure(exitCode, cause))
            }
        } catch (cause: Throwable) {
            if (!closed.get()) {
                frame.set(null)
                failure.set(nativeCaptureFailure(null, cause))
            }
        } finally {
            process.destroyForcibly()
        }
    }

    override fun latest(): AppRawWindowFrame? {
        failure.get()?.let { throw it }
        check(!closed.get()) { "Window capture is closed" }
        val rate = frameRate()
        require(rate == 30 || rate == 60)
        if (rate != requestedRate) {
            java.io.DataOutputStream(process.outputStream).apply {
                writeInt(rate)
                flush()
            }
            requestedRate = rate
        }
        val current = frame.get()
        streamDiagnostics?.consume(current?.diagnosticStamp, System.nanoTime())
        check(current != null || System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
            "Exact native capture did not produce a frame"
        }
        return current
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            frame.set(null)
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}

internal open class AppNativeCaptureStoppedException(
    cause: Throwable,
) : IllegalStateException("Exact native capture ended; window or session authority changed.", cause)

internal class AppNativeGeometryChangedException(
    cause: Throwable,
) : AppNativeCaptureStoppedException(cause)

internal class AppNativeFrameBoundaryEnd : EOFException("Native frame stream ended between frames")

internal fun nativeCaptureFailure(
    exitCode: Int?,
    cause: Throwable,
): AppNativeCaptureStoppedException =
    if (exitCode == 75 && cause is AppNativeFrameBoundaryEnd) {
        AppNativeGeometryChangedException(cause)
    } else {
        AppNativeCaptureStoppedException(cause)
    }

/** Geometry is trusted host state, bound to the same native source before it reaches the helper. */
internal fun nativeCaptureGeometryArguments(
    nativeHandle: Long,
    geometry: WindowCaptureGeometry?,
): List<String> {
    if (geometry == null) return emptyList()
    require(geometry.nativeHandle == nativeHandle && nativeHandle != 0L)
    require(geometry.width in 1..8192 && geometry.height in 1..8192)
    val insets = geometry.insets
    require(listOf(insets.left, insets.right, insets.top, insets.bottom).all { it in 0..8192 })
    require(insets.left + insets.right < geometry.width && insets.top + insets.bottom < geometry.height)
    return listOf(geometry.width, geometry.height, insets.left, insets.right, insets.top, insets.bottom)
        .map(Int::toString)
}

/**
 * BSC1 is opaque BGRA; BSC2 is premultiplied BGRA with alpha. Both retain the same bounded
 * header and sequence rules. Never interpret an old protocol's unused bytes as transparency.
 */
internal fun readAppNativeFrame(
    input: DataInputStream,
    expectedWidth: Int,
    expectedHeight: Int,
    previousSequence: Long,
): Pair<Long, AppRawWindowFrame> {
    require(expectedWidth in 1..1920 && expectedHeight in 1..1920)
    val first = input.read()
    if (first < 0) throw AppNativeFrameBoundaryEnd()
    check(first == 0x42 && input.readUnsignedByte() == 0x53 && input.readUnsignedByte() == 0x43) {
        "Invalid native capture protocol"
    }
    val version = input.readUnsignedByte()
    check(version == 0x31 || version == 0x32) { "Unsupported native capture protocol" }
    val width = input.readInt()
    val height = input.readInt()
    val size = input.readInt()
    val sequence = input.readLong()
    check(width == expectedWidth && height == expectedHeight) { "Native capture geometry changed" }
    check(size == width * height * 4 && width.toLong() * height <= 4_194_304) { "Invalid native frame size" }
    check(sequence > previousSequence) { "Stale native capture frame" }
    val pixels = input.readNBytes(size)
    if (pixels.size != size) throw EOFException("Incomplete native capture frame")
    if (version == 0x31) {
        check((3 until pixels.size step 4).all { pixels[it] == (-1).toByte() }) { "BSC1 frame must be opaque" }
    }
    return sequence to AppRawWindowFrame(pixels, width, height)
}
