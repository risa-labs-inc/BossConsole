package ai.rever.boss.daemon

import ai.rever.boss.plugin.api.DaemonService
import ai.rever.boss.plugin.api.DaemonServiceContext
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Services retain their own classloader until fully stopped. UI unload is unrelated. */
internal class DaemonServiceRegistry(
    private val directory: File,
) {
    private class Worker(
        val service: DaemonService,
        val scope: CoroutineScope,
        val loader: PluginClassLoader,
        val endpoints: Map<String, String>,
        val startupFailure: Throwable? = null,
    )

    private val storage = DaemonServiceStorage(directory)
    private val apiLoader = storage.snapshotApiLoader(javaClass.classLoader)
    val apiVersion: String? get() = apiLoader.apiVersion
    val apiJarPath: String? get() = apiLoader.apiJarPath
    private val workers = ConcurrentHashMap<Pair<String, String>, Worker>()
    private val locks = ConcurrentHashMap<Pair<String, String>, Any>()
    private val admission = ReentrantReadWriteLock(true)

    @Volatile private var closed = false

    fun dispatch(request: DaemonRequest): DaemonResponse =
        admission.read {
            require(request.pluginId.matches(Regex("[A-Za-z0-9_.-]{1,180}"))) { "Invalid plugin ID" }
            require(request.serviceId.length in 1..512) { "Invalid service ID" }
            val key = request.pluginId to request.serviceId
            synchronized(locks.computeIfAbsent(key) { Any() }) {
                runBlocking {
                    when (request.operation) {
                        "connect" -> {
                            connect(key, request)
                        }

                        "request" -> {
                            invoke(key, request)
                        }

                        "stop" -> {
                            stop(key, removeRegistration = true)
                            DaemonResponse()
                        }

                        else -> {
                            error("Unknown daemon operation")
                        }
                    }
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // Drain partially initialized plugin code, including linkage failures.
    private suspend fun connect(key: Pair<String, String>, request: DaemonRequest): DaemonResponse {
        check(!closed) { "Daemon is stopping" }
        workers[key]?.let {
            check(it.startupFailure == null) { "Previous startup failed; stop it before retrying" }
            return DaemonResponse(endpoints = it.endpoints)
        }
        val snapshot = storage.snapshotArtifact(File(request.jar), request.sha256)
        val loader = PluginClassLoader(request.pluginId, arrayOf(snapshot.toURI().toURL()), apiLoader)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + WorkerClassLoader(loader))
        var service: DaemonService? = null
        try {
            service =
                withContext(WorkerClassLoader(loader)) {
                    val type = loader.loadClass(request.entryPoint)
                    require(type.classLoader === loader && DaemonService::class.java.isAssignableFrom(type)) {
                        "Worker entry point must belong to the plugin artifact and implement DaemonService"
                    }
                    type.getDeclaredConstructor().newInstance() as DaemonService
                }
            val context = serviceContext(key, scope)
            val instanceId = UUID.randomUUID().toString()
            val endpoints =
                withContext(WorkerClassLoader(loader)) {
                    service.start(context, request.configuration).toMap() + ("boss.service.instanceId" to instanceId)
                }
            val response = DaemonResponse(endpoints = endpoints)
            val metadataSize = daemonJson.encodeToString(DaemonResponse.serializer(), response).toByteArray().size
            require(metadataSize <= MAX_MESSAGE_BYTES) { "Worker metadata exceeds daemon message limit" }
            storage.saveRegistration(key, request.copy(secret = "", jar = snapshot.absolutePath))
            workers[key] = Worker(service, scope, loader, endpoints)
            return response
        } catch (failure: Throwable) {
            cleanupFailedStart(key, service, scope, loader, failure)
            throw failure
        }
    }

    // Plugin code can throw linkage errors as well as exceptions. Cleanup must still drain it.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanupFailedStart(
        key: Pair<String, String>,
        service: DaemonService?,
        scope: CoroutineScope,
        loader: PluginClassLoader,
        failure: Throwable,
    ) {
        try {
            try {
                withContext(WorkerClassLoader(loader)) { service?.stop() }
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
            loader.close()
        } catch (cleanup: Throwable) {
            service?.let { workers[key] = Worker(it, scope, loader, emptyMap(), failure) }
            failure.addSuppressed(cleanup)
        }
    }

    private fun serviceContext(
        key: Pair<String, String>,
        workerScope: CoroutineScope,
    ): DaemonServiceContext =
        object : DaemonServiceContext {
            override val scope = workerScope
            override val dataDirectory =
                ownerDirectory(File(directory, "services/${sha256(key.toString().toByteArray())}")).absolutePath
        }

    private suspend fun invoke(
        key: Pair<String, String>,
        request: DaemonRequest,
    ): DaemonResponse {
        val worker = checkNotNull(workers[key]) { "Service is not running" }
        check(worker.startupFailure == null) { "Service startup failed" }
        val payload =
            withContext(WorkerClassLoader(worker.loader)) {
                worker.service.request(request.method, request.payload)
            }
        return DaemonResponse(payload = payload)
    }

    private suspend fun stop(
        key: Pair<String, String>,
        removeRegistration: Boolean,
    ) {
        workers[key]?.let { worker ->
            try {
                withContext(WorkerClassLoader(worker.loader)) { worker.service.stop() }
            } finally {
                worker.scope.coroutineContext[Job]?.cancelAndJoin()
            }
            worker.loader.close()
            workers.remove(key)
        }
        val descriptor = storage.descriptorFile(key)
        if (removeRegistration && descriptor.exists()) {
            check(descriptor.delete()) { "Could not remove service registration" }
        }
    }

    fun restore() {
        storage.registrationFiles().forEach { file ->
            runCatching {
                dispatch(daemonJson.decodeFromString(DaemonRequest.serializer(), file.readText()))
            }.onFailure {
                BossLogger.forComponent("BossDaemon").warn(
                    LogCategory.SYSTEM,
                    "Background service restore failed",
                    mapOf("registration" to file.name, "type" to it.javaClass.simpleName),
                )
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Stop every worker; a failed drain must retain its registration.
    fun stopPlugin(pluginId: String) =
        admission.write {
            val keys = workers.keys.filter { it.first == pluginId }.toMutableSet()
            storage.registrationFiles().forEach { file ->
                val request = daemonJson.decodeFromString(DaemonRequest.serializer(), file.readText())
                if (request.pluginId == pluginId) keys.add(request.pluginId to request.serviceId)
            }
            var failure: Throwable? = null
            keys.forEach { key ->
                try {
                    runBlocking { stop(key, removeRegistration = true) }
                } catch (
                    t: Throwable,
                ) {
                    if (failure == null) failure = t else failure!!.addSuppressed(t)
                }
            }
            failure?.let { throw it }
        }

    @Suppress("TooGenericExceptionCaught") // Attempt all workers, retaining loaders of any failed drain.
    fun close() =
        admission.write {
            closed = true
            var failure: Throwable? = null
            workers.keys.toList().forEach { key ->
                try {
                    runBlocking { stop(key, removeRegistration = false) }
                } catch (
                    t: Throwable,
                ) {
                    if (failure == null) failure = t else failure!!.addSuppressed(t)
                }
            }
            failure?.let { throw it }
            apiLoader.close()
        }

    fun count(): Int = workers.size
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Keep ServiceLoader and resource discovery in the plugin's loader across suspensions. */
private class WorkerClassLoader(
    private val loader: ClassLoader,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<ClassLoader> {
    companion object Key : CoroutineContext.Key<WorkerClassLoader>

    override fun updateThreadContext(context: CoroutineContext): ClassLoader =
        Thread.currentThread().contextClassLoader.also { Thread.currentThread().contextClassLoader = loader }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: ClassLoader,
    ) {
        Thread.currentThread().contextClassLoader = oldState
    }
}
