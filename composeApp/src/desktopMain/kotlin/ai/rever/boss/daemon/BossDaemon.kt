package ai.rever.boss.daemon

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.utils.AppVersion
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Dispatched before UI initialization, using BOSS's own packaged launcher/runtime. */
object BossDaemon {
    const val ARG = "--boss-daemon"
    private val logger = BossLogger.forComponent("BossDaemon")

    fun dispatch(args: Array<String>): Boolean {
        if (args.firstOrNull() != ARG) return false
        require(args.size == 2) { "Expected BOSS daemon data directory" }
        System.setProperty("apple.awt.UIElement", "true")
        System.setProperty("boss.dev.mode", (File(args[1]).parentFile?.name == ".boss_debug").toString())
        BossDaemonProcess.detach()
        run(File(args[1]).absoluteFile)
        return true
    }

    internal fun run(directory: File) {
        ownerDirectory(directory)
        val lockFile = File(directory, "instance.lock").also(::ownerFile)
        RandomAccessFile(lockFile, "rw").use { file ->
            val lock = runCatching { file.channel.tryLock() }.getOrNull() ?: return
            lock.use { serve(directory) }
        }
    }

    private fun serve(directory: File) {
        val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val endpointFile = File(directory, "endpoint.json").also(::ownerFile)
        val registry = DaemonServiceRegistry(directory)
        System.setProperty("boss.api.version", registry.apiVersion.orEmpty())
        System.setProperty("boss.api.jar", registry.apiJarPath.orEmpty())
        System.setProperty("boss.app.version", AppVersion.currentVersionString())
        val threads =
            ThreadPoolExecutor(
                16,
                16,
                30,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(64),
                { r -> Thread(r, "boss-daemon-request").apply { isDaemon = true } },
            )
        val stopped = CountDownLatch(1)
        val hook =
            Thread {
                server.close()
                stopped.await()
            }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            registry.restore()
            val endpoint = DaemonEndpoint(server.localPort, secret)
            endpointFile.writeText(daemonJson.encodeToString(DaemonEndpoint.serializer(), endpoint))
            BossDaemonTray.install(registry::count) { server.close() }
            logger.info(LogCategory.SYSTEM, "BOSS daemon started")
            acceptRequests(server, threads, secret, registry)
        } finally {
            server.close()
            threads.shutdown()
            // Do not unload workers ahead of a request still executing plugin code.
            while (!threads.awaitTermination(1, TimeUnit.SECONDS)) Unit
            try {
                registry.close()
            } finally {
                try {
                    BossDaemonTray.remove()
                } finally {
                    endpointFile.delete()
                    stopped.countDown()
                    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
                }
            }
        }
    }

    private fun acceptRequests(
        server: ServerSocket,
        threads: ThreadPoolExecutor,
        secret: String,
        registry: DaemonServiceRegistry,
    ) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            try {
                threads.execute { handleSocket(socket, secret, registry) }
            } catch (_: RejectedExecutionException) {
                socket.close()
            }
        }
    }

    private fun handleSocket(
        socket: Socket,
        secret: String,
        registry: DaemonServiceRegistry,
    ) = socket.use {
        socket.soTimeout = 5000
        val result = handleRequest(socket, secret, registry)
        runCatching {
            val bytes = daemonJson.encodeToString(DaemonResponse.serializer(), result).toByteArray()
            require(bytes.size <= MAX_MESSAGE_BYTES)
            socket.getOutputStream().apply {
                write(bytes)
                write(10)
                flush()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Plugin linkage errors must not kill the RPC handler pool.
    private fun handleRequest(socket: Socket, secret: String, registry: DaemonServiceRegistry): DaemonResponse =
        try {
            val request = daemonJson.decodeFromString(DaemonRequest.serializer(), readMessage(socket.getInputStream()))
            check(MessageDigest.isEqual(secret.toByteArray(), request.secret.toByteArray())) {
                "Unauthorized daemon request"
            }
            when (request.operation) {
                "ping" -> {
                    DaemonResponse()
                }

                "stopPlugin" -> {
                    registry.stopPlugin(request.pluginId)
                    DaemonResponse()
                }

                else -> {
                    registry.dispatch(request)
                }
            }
        } catch (failure: Throwable) {
            // Exception messages and request payloads can contain credentials.
            logger.warn(LogCategory.SYSTEM, "BOSS daemon request failed", mapOf("type" to failure.javaClass.simpleName))
            DaemonResponse(error = failure.javaClass.simpleName)
        }
}
