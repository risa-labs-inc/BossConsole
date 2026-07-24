package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference

/**
 * State of the plugin classloader.
 *
 * Follows IntelliJ IDEA's pattern for tracking classloader lifecycle.
 */
enum class ClassLoaderState {
    /**
     * Classloader is active and can load classes.
     */
    ACTIVE,

    /**
     * Classloader is being unloaded.
     */
    UNLOAD_IN_PROGRESS,

    /**
     * Classloader has been unloaded and should not be used.
     */
    UNLOADED
}

/**
 * Custom classloader for isolated plugin loading.
 *
 * This classloader implements a hybrid parent-first/child-first loading strategy:
 * - Shared packages (from plugin manifest) use parent-first loading
 * - Plugin-specific classes use child-first (plugin JAR first) loading
 *
 * This ensures plugins get their own dependencies while sharing common APIs
 * with the host application.
 *
 * @param pluginId The ID of the plugin this classloader serves
 * @param urls URLs to the plugin JAR and its dependencies
 * @param parent Parent classloader (usually the application classloader)
 * @param sharedPackages Packages that should use parent-first loading
 */
class PluginClassLoader(
    val pluginId: String,
    urls: Array<URL>,
    parent: ClassLoader,
    private val sharedPackages: Set<String> = defaultSharedPackages
) : URLClassLoader(urls, parent) {

    companion object {
        private val logger = BossLogger.forComponent("PluginClassLoader")

        /**
         * Weak process-wide registry of every constructed plugin classloader,
         * across all windows' managers (managers are per-window; crash
         * attribution needs the whole process). Weak so unloaded loaders drop
         * off with GC; unloading-but-not-yet-collected loaders intentionally
         * remain visible — a crash caused by a just-unloaded plugin's lingering
         * class should still attribute to that plugin.
         */
        private val allInstances: MutableSet<PluginClassLoader> =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        /**
         * Find the plugin whose classloader DEFINED [className], if any.
         * Used by the crash handler to attribute an uncaught exception's stack
         * frames to a plugin. Only classes the plugin loader itself defined
         * match — shared parent-first classes resolve to the host and return
         * null here.
         */
        fun findPluginForClass(className: String): String? {
            val snapshot = synchronized(allInstances) { allInstances.toList() }
            return snapshot.firstOrNull { it.definedClassNamed(className) }?.pluginId
        }

        /**
         * Default packages that are always shared with the host.
         * These include Kotlin stdlib, coroutines, and BOSS plugin API.
         */
        val defaultSharedPackages = setOf(
            // Kotlin stdlib
            "kotlin.",
            "kotlinx.coroutines.",
            "kotlinx.serialization.",

            // BOSS Plugin API
            "ai.rever.boss.plugin.api.",

            // BOSS Browser Service API
            "ai.rever.boss.plugin.browser.",

            // BOSS Plugin type modules (must be from host for Compose stability)
            "ai.rever.boss.plugin.bookmark.",
            "ai.rever.boss.plugin.workspace.",

            // Compose (shared UI framework)
            "androidx.compose.",

            // Decompose (shared navigation)
            "com.arkivanov.decompose.",
            "com.arkivanov.essenty.",

            // Java stdlib (always from parent)
            "java.",
            "javax.",
            "sun.",
            "com.sun.",

            // Logging
            "org.slf4j.",
            "ai.rever.boss.plugin.logging.",

            // BOSS Plugin UI components
            "ai.rever.boss.plugin.ui.",
            "ai.rever.boss.plugin.scrollbar."
        )
    }

    init {
        synchronized(allInstances) { allInstances.add(this) }
    }

    /**
     * Whether this loader defined the class named [name] (i.e. the class came
     * from the plugin JAR, not a shared parent-first package). Reads the JVM's
     * loaded-class table only — never triggers a load, so it is safe during
     * crash handling and on closed loaders.
     */
    internal fun definedClassNamed(name: String): Boolean {
        val cls = try {
            findLoadedClass(name)
        } catch (_: Throwable) {
            // Closed/unloading loaders can throw Errors; attribution is best-effort.
            null
        }
        return cls != null && cls.classLoader === this
    }

    /**
     * Current state of this classloader.
     */
    private val _state = AtomicReference(ClassLoaderState.ACTIVE)
    val state: ClassLoaderState get() = _state.get()

    /**
     * Timestamp when the classloader was created.
     */
    val createdAt: Long = System.currentTimeMillis()

    /**
     * Whether this classloader has been marked for unloading.
     */
    val isUnloading: Boolean get() = state != ClassLoaderState.ACTIVE

    /**
     * Check if this classloader is still active.
     */
    fun isActive(): Boolean = state == ClassLoaderState.ACTIVE

    /**
     * Mark this classloader as being unloaded.
     *
     * After calling this, no new classes should be loaded from this classloader.
     */
    fun markUnloading() {
        if (_state.compareAndSet(ClassLoaderState.ACTIVE, ClassLoaderState.UNLOAD_IN_PROGRESS)) {
            logger.debug(LogCategory.SYSTEM, "Classloader marked for unloading", mapOf(
                "pluginId" to pluginId
            ))
        }
    }

    /**
     * Mark this classloader as fully unloaded.
     */
    fun markUnloaded() {
        _state.set(ClassLoaderState.UNLOADED)
        logger.debug(LogCategory.SYSTEM, "Classloader marked as unloaded", mapOf(
            "pluginId" to pluginId
        ))
    }

    /**
     * Load a class with the hybrid loading strategy.
     *
     * For shared packages, delegates to parent first (standard behavior).
     * For plugin packages, tries plugin JAR first (child-first).
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Check if already loaded
        val loadedClass = findLoadedClass(name)
        if (loadedClass != null) {
            return loadedClass
        }

        // Check state
        if (isUnloading) {
            logger.warn(LogCategory.SYSTEM, "Attempt to load class from unloading classloader", mapOf(
                "pluginId" to pluginId,
                "className" to name,
                "state" to state.name
            ))
            // Still allow loading during unload process for cleanup
        }

        // Check if this is a shared package (parent-first)
        val isSharedPackage = sharedPackages.any { name.startsWith(it) }

        return if (isSharedPackage) {
            // Parent-first loading for shared packages
            super.loadClass(name, resolve)
        } else {
            // Child-first loading for plugin classes
            loadClassChildFirst(name, resolve)
        }
    }

    /**
     * Load a class with child-first strategy.
     */
    private fun loadClassChildFirst(name: String, resolve: Boolean): Class<*> {
        // Try to find in plugin JAR first
        try {
            val clazz = findClass(name)
            if (resolve) {
                resolveClass(clazz)
            }
            return clazz
        } catch (ignored: ClassNotFoundException) {
            // Fall back to parent. Deliberately unlogged: this is the expected
            // delegation path for every host-provided class a plugin touches, so
            // logging here would flood at class-load time on a hot path.
            return parent.loadClass(name)
        }
    }

    /**
     * Get a resource with child-first strategy for plugin resources.
     */
    override fun getResource(name: String): URL? {
        // For shared packages, use parent-first
        val isSharedResource = sharedPackages.any {
            name.startsWith(it.replace('.', '/'))
        }

        return if (isSharedResource) {
            super.getResource(name)
        } else {
            // Child-first for plugin resources
            findResource(name) ?: parent.getResource(name)
        }
    }

    /**
     * Enumerate resources mirroring [getResource]'s strategy: shared paths
     * parent-first, everything else child-first. URLClassLoader's inherited
     * plural enumeration is always parent-first, and with the ApiClassLoader
     * in the parent chain (whose jar carries its own
     * META-INF/boss-plugin/plugin.json and jar manifest) that would surface
     * the api jar's copy of non-shared resources ahead of the plugin's own.
     */
    override fun getResources(name: String): java.util.Enumeration<URL> {
        val isSharedResource = sharedPackages.any {
            name.startsWith(it.replace('.', '/'))
        }
        if (isSharedResource) {
            return super.getResources(name)
        }
        val own = java.util.Collections.list(findResources(name))
        val fromParents = java.util.Collections.list(parent.getResources(name))
            .filterNot { it in own }
        return java.util.Collections.enumeration(own + fromParents)
    }

    /**
     * Close this classloader and release resources.
     */
    override fun close() {
        markUnloaded()
        logger.info(LogCategory.SYSTEM, "Closing plugin classloader", mapOf(
            "pluginId" to pluginId
        ))
        super.close()
    }

    override fun toString(): String {
        return "PluginClassLoader(pluginId=$pluginId, state=$state, urls=${getURLs().size})"
    }
}
