package ai.rever.boss.daemon

import ai.rever.boss.plugin.api.DaemonServiceConnection
import ai.rever.boss.plugin.api.DaemonServiceProvider
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Created only by the host's verified plugin-registration path; callers cannot nominate another JAR. */
internal class BossDaemonClient(
    private val pluginId: String,
    private val pluginJar: File,
    private val directory: File = File(BossDirectories.rootDir, "daemon"),
) : DaemonServiceProvider {
    private val artifactHash = sha256(pluginJar)
    private val generation = synchronized(lockFor(pluginId)) { generationFor(pluginId).get() }

    private fun checkAdmission() {
        check(generation == generationFor(pluginId).get()) { "Plugin daemon access was revoked" }
    }

    override suspend fun connect(
        serviceId: String,
        entryPoint: String,
        configuration: Map<String, String>,
    ): DaemonServiceConnection =
        withContext(Dispatchers.IO) {
            synchronized(lockFor(pluginId)) {
                checkAdmission()
                val endpoint = ensureConnected(directory)
                val response =
                    daemonRequest(
                        endpoint,
                        DaemonRequest(
                            "",
                            "connect",
                            pluginId,
                            serviceId,
                            pluginJar.absolutePath,
                            artifactHash,
                            entryPoint,
                            configuration,
                        ),
                    )
                object : DaemonServiceConnection {
                    override val endpoints: Map<String, String>
                        get() = response.endpoints

                    override suspend fun request(
                        method: String,
                        payload: String,
                    ): String =
                        withContext(Dispatchers.IO) {
                            synchronized(lockFor(pluginId)) {
                                checkAdmission()
                                daemonRequest(
                                    endpoint,
                                    DaemonRequest(
                                        "",
                                        "request",
                                        pluginId,
                                        serviceId,
                                        method = method,
                                        payload = payload,
                                    ),
                                ).payload
                            }
                        }

                    override suspend fun stop(): Unit =
                        withContext(Dispatchers.IO) {
                            synchronized(lockFor(pluginId)) {
                                checkAdmission()
                                daemonRequest(endpoint, DaemonRequest("", "stop", pluginId, serviceId))
                                Unit
                            }
                        }
                }
            }
        }

    companion object {
        private val spawnLock = Any()
        private val pluginLocks = ConcurrentHashMap<String, Any>()
        private val generations = ConcurrentHashMap<String, AtomicLong>()

        private fun lockFor(id: String) = pluginLocks.computeIfAbsent(id) { Any() }

        private fun generationFor(id: String) = generations.computeIfAbsent(id) { AtomicLong() }

        private fun readEndpoint(directory: File): DaemonEndpoint? =
            runCatching {
                daemonJson.decodeFromString(DaemonEndpoint.serializer(), File(directory, "endpoint.json").readText())
            }.getOrNull()

        private fun live(directory: File): DaemonEndpoint? =
            readEndpoint(directory)?.takeIf {
                runCatching { daemonRequest(it, DaemonRequest("", "ping")) }.isSuccess
            }

        suspend fun stopPlugin(pluginId: String): Unit =
            withContext(Dispatchers.IO) {
                synchronized(lockFor(pluginId)) {
                    generationFor(pluginId).incrementAndGet()
                    val directory = File(BossDirectories.rootDir, "daemon")
                    val endpoint = live(directory)
                    if (endpoint != null) {
                        daemonRequest(endpoint, DaemonRequest("", "stopPlugin", pluginId))
                    } else {
                        // A stopped daemon must not resurrect removed/disabled workers on next login.
                        if (!directory.exists()) return@synchronized
                        // A failed ping is not evidence that a live process has stopped.
                        val instanceFile = File(directory, "instance.lock").also(::ownerFile)
                        RandomAccessFile(instanceFile, "rw").use { raf ->
                            val lock = runCatching { raf.channel.tryLock() }.getOrNull()
                            check(lock != null) { "BOSS daemon is running but unreachable; workers were not stopped" }
                            lock.use {
                                DaemonServiceStorage(directory).registrationFiles().forEach { file ->
                                    val descriptor =
                                        daemonJson.decodeFromString(DaemonRequest.serializer(), file.readText())
                                    if (descriptor.pluginId == pluginId) {
                                        check(file.delete()) { "Could not remove background registration" }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        private fun ensureConnected(directory: File): DaemonEndpoint =
            synchronized(spawnLock) {
                ownerDirectory(directory)
                BossDaemonLogin.install(directory)
                live(directory)?.let { return it }
                val file = File(directory, "spawn.lock").also(::ownerFile)
                RandomAccessFile(file, "rw").use { raf ->
                    raf.channel.lock().use {
                        live(directory)?.let { return it }
                        check(
                            readEndpoint(directory)?.protocol.let { it == null || it == DAEMON_PROTOCOL },
                        ) { "BOSS daemon update requires an explicit restart" }
                        BossDaemonLauncher.spawn(directory)
                        val deadline = System.nanoTime() + 15_000_000_000L
                        while (System.nanoTime() < deadline) {
                            live(directory)?.let { return it }
                            Thread.sleep(50)
                        }
                        error("BOSS background process did not become ready")
                    }
                }
            }
    }
}
