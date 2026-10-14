package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Native source identity and lifecycle remain platform specific; frames stay latest-only. */
internal interface AppNativeWindowStream : AutoCloseable {
    fun latest(): AppRawWindowFrame?
}

internal interface AppWindowStreamPlatform {
    val monitorsSession: Boolean get() = false

    fun available(): Boolean

    fun watchSession(onUnavailable: () -> Unit): AutoCloseable = error("Platform does not provide session monitoring")

    @Suppress("LongParameterList") // Explicit source identity, output bounds and native format contract.
    fun open(
        nativeHandle: Long,
        width: Int,
        height: Int,
        frameRate: () -> Int,
        preferredFormat: String,
        sourceGeometry: WindowCaptureGeometry? = null,
        diagnostics: AppCaptureDiagnostics? = null,
    ): AppNativeWindowStream
}

internal object MacWindowStreamPlatform : AppWindowStreamPlatform {
    override fun available(): Boolean = MacAppWindowStream.available()

    @Suppress("LongParameterList") // Mirrors the explicit platform capture contract.
    override fun open(
        nativeHandle: Long,
        width: Int,
        height: Int,
        frameRate: () -> Int,
        preferredFormat: String,
        sourceGeometry: WindowCaptureGeometry?,
        diagnostics: AppCaptureDiagnostics?,
    ): AppNativeWindowStream {
        val stream =
            MacAppWindowStream.open(
                nativeHandle,
                width,
                height,
                frameRate,
                preferredFormat,
                diagnostics,
                expectedWindowNumber = sourceGeometry?.nativeWindowNumber,
                expectedParentHandle = sourceGeometry?.nativeParentHandle,
            )
        return object : AppNativeWindowStream {
            override fun latest(): AppRawWindowFrame? = stream.latest()

            override fun close() = stream.close()
        }
    }
}

/** Native helpers independently monitor session authority, including while pixels are paused. */
internal class HelperWindowStreamPlatform(
    private val helper: Path,
) : AppWindowStreamPlatform {
    override val monitorsSession: Boolean = true

    override fun watchSession(onUnavailable: () -> Unit): AutoCloseable {
        val live =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val process =
            ProcessBuilder(helper.toString(), "--watch", ProcessHandle.current().pid().toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process.outputStream.close()
        Thread({
            process.waitFor()
            if (live.compareAndSet(true, false)) onUnavailable()
        }, "boss-native-session-watch").apply {
            isDaemon = true
            start()
        }
        return AutoCloseable {
            live.set(false)
            process.destroyForcibly()
        }
    }

    override fun available(): Boolean =
        runCatching {
            if (!Files.isRegularFile(helper) || !Files.isExecutable(helper)) return@runCatching false
            val probe =
                ProcessBuilder(helper.toString(), "--probe", ProcessHandle.current().pid().toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0
            } finally {
                probe.destroyForcibly()
            }
        }.getOrDefault(false)

    @Suppress("LongParameterList") // Mirrors the explicit platform capture contract.
    override fun open(
        nativeHandle: Long,
        width: Int,
        height: Int,
        frameRate: () -> Int,
        preferredFormat: String,
        sourceGeometry: WindowCaptureGeometry?,
        diagnostics: AppCaptureDiagnostics?,
    ): AppNativeWindowStream =
        HelperAppWindowStream(
            helper,
            nativeHandle,
            width,
            height,
            frameRate,
            sourceGeometry.takeIf { SystemUtils.isLinux },
            diagnostics,
        )
}

internal fun appWindowStreamPlatform(): AppWindowStreamPlatform? =
    when {
        SystemUtils.isMacOS -> {
            MacWindowStreamPlatform
        }

        SystemUtils.isLinux || SystemUtils.isWindows -> {
            // Packaged binaries only. Never execute a helper from PATH, the working
            // directory, a remote setting, or the contents of a shared project.
            System.getProperty("compose.application.resources.dir")?.let {
                val executable = if (SystemUtils.isWindows) "boss-app-capture.exe" else "boss-app-capture"
                HelperWindowStreamPlatform(Path.of(it, "app-capture", executable))
            }
        }

        else -> {
            null
        }
    }
