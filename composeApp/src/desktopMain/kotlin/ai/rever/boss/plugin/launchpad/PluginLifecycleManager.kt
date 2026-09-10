package ai.rever.boss.plugin.launchpad

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

/**
 * Manages classloader isolation, lifecycle teardown, and live hot-reloading of BossPlugin instances.
 */
@Suppress("ExplicitGarbageCollectionCall", "TooGenericExceptionCaught")
class PluginLifecycleManager(
    private val hostContext: Any? = null,
) {
    private var currentClassLoader: URLClassLoader? = null
    private var currentInstance: BossPlugin? = null
    private var pluginScope: CoroutineScope? = null

    @Synchronized
    fun reload(
        jarPath: Path,
        pluginId: String,
    ): Result<Unit> {
        // 1. Invoke onStop() contract synchronously and idempotently
        try {
            currentInstance?.onStop()
        } catch (t: Throwable) {
            System.err.println("Warning: Exception thrown during onStop() for plugin $pluginId: ${t.message}")
        }

        // 2. Cancel and clear active plugin coroutine scope
        pluginScope?.cancel()
        pluginScope = null

        // 3. Sever classloader references and close open JAR handles to prevent Windows file locks
        currentInstance = null
        try {
            currentClassLoader?.close()
        } catch (_: Exception) {
        }
        currentClassLoader = null
        System.gc()

        // 4. Instantiate isolated classloader and launch fresh instance
        return try {
            val newClassLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), this::class.java.classLoader)
            val manifest = PluginValidator.readManifestFromJar(jarPath.toFile())
            val pluginClass = Class.forName(manifest.entrypointClass, true, newClassLoader)
            val newInstance = pluginClass.getDeclaredConstructor().newInstance() as BossPlugin

            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            newInstance.onStart(PluginContext(newScope, hostContext))

            currentClassLoader = newClassLoader
            currentInstance = newInstance
            pluginScope = newScope
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    @Synchronized
    fun shutdown() {
        try {
            currentInstance?.onStop()
        } catch (_: Throwable) {
        }
        pluginScope?.cancel()
        pluginScope = null
        currentInstance = null
        try {
            currentClassLoader?.close()
        } catch (_: Exception) {
        }
        currentClassLoader = null
        System.gc()
    }

    companion object {
        const val DEFAULT_MAX_VERSIONS_TO_KEEP = 3

        fun pruneStagingHistory(
            pluginDevDir: Path,
            maxVersionsToKeep: Int = DEFAULT_MAX_VERSIONS_TO_KEEP,
        ) {
            if (!Files.isDirectory(pluginDevDir)) return

            runCatching {
                val versionDirs =
                    Files.list(pluginDevDir).use { stream ->
                        stream
                            .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("v") }
                            .sorted(Comparator.comparingLong<Path>(::extractDirTimestamp).reversed())
                            .toList()
                    }

                if (versionDirs.size > maxVersionsToKeep) {
                    versionDirs.drop(maxVersionsToKeep).forEach { oldDir ->
                        runCatching {
                            Files.walk(oldDir).use { walkStream ->
                                walkStream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                            }
                        }
                    }
                }
            }
        }

        private fun extractDirTimestamp(dir: Path): Long =
            dir
                .fileName
                .toString()
                .removePrefix("v")
                .toLongOrNull()
                ?: runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(0L)

        fun findActiveDevJar(
            pluginId: String,
            devRoot: Path,
        ): Path? {
            val pluginDir = devRoot.resolve(pluginId)
            if (!Files.isDirectory(pluginDir)) return null
            return runCatching {
                Files.list(pluginDir).use { stream ->
                    stream
                        .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("v") }
                        .sorted(Comparator.comparingLong<Path>(::extractDirTimestamp).reversed())
                        .findFirst()
                        .map { latestDir ->
                            Files.list(latestDir).use { files ->
                                files.filter { it.toString().endsWith(".jar") }.findFirst().orElse(null)
                            }
                        }.orElse(null)
                }
            }.getOrNull()
        }

        fun findAllActiveDevJars(devRoot: Path): List<Path> {
            if (!Files.isDirectory(devRoot)) return emptyList()
            return runCatching {
                Files.list(devRoot).use { stream ->
                    stream
                        .filter { Files.isDirectory(it) }
                        .map { pluginDir ->
                            findActiveDevJar(pluginDir.fileName.toString(), devRoot)
                        }.filter { it != null }
                        .map { it!! }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }
    }
}
