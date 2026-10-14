package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class DevReloadResponseTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun useTempRuntimeDir() {
        SingleInstanceManager.runtimeDirOverride = tempDir.resolve("run").toFile()
    }

    @AfterEach
    fun releaseChannel() {
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
    }

    @Test
    fun `reload response warns when restoring the previous build also failed`() {
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            throw IllegalStateException("new build failed").apply {
                addSuppressed(IllegalStateException("old build could not be restored"))
            }
        }
        assertTrue(SingleInstanceManager.acquireLock())
        val result = SingleInstanceManager.reloadDevPlugin("diagnostic-plugin")
        kotlin.test.assertIs<ReloadResult.Failed>(result)
        assertTrue(result.reason.contains("Rollback failed"), result.reason)
        assertTrue(result.reason.contains("new build failed"), result.reason)
    }

    @Test
    fun `only an exact plugin reload acknowledgment confirms success`() {
        for (response in listOf("OK", "RELOAD_OK wrong-plugin", "RELOAD_OKevil", "RELOAD_OK test-plugin")) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val runtime = tempDir.resolve("run")
                Files.createDirectories(runtime)
                val descriptor =
                    InstanceDescriptor(SingleInstanceTransport.TCP, server.localPort.toString(), "a".repeat(64))
                Files.writeString(runtime.resolve("single-instance"), descriptor.encode())
                val reply =
                    CompletableFuture.runAsync {
                        server.accept().use { socket ->
                            socket.getInputStream().bufferedReader().readLine()
                            socket.getOutputStream().write((response + "\n").toByteArray())
                        }
                    }
                val result = SingleInstanceManager.reloadDevPlugin("test-plugin", timeoutMs = 1000)
                reply.get(5, TimeUnit.SECONDS)
                if (response == "RELOAD_OK test-plugin") {
                    kotlin.test.assertIs<ReloadResult.Success>(result)
                } else {
                    kotlin.test.assertIs<ReloadResult.Failed>(result)
                }
            }
        }
    }
}
