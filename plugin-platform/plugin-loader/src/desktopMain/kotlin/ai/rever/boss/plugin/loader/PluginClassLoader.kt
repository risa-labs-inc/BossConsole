package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
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
    UNLOADED,
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
 * Once the loader leaves [ClassLoaderState.ACTIVE] the child-first miss stops
 * delegating to the parent: a plugin class requested after teardown must fail
 * loudly rather than silently resolve to the host's copy. Only FIRST-TIME loads
 * are refused — the JVM records this loader as the initiating loader for every
 * name it has already resolved, host classes included, so `findLoadedClass`
 * keeps answering those after close and orderly teardown is unaffected. See
 * [loadClassChildFirst].
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
    private val sharedPackages: Set<String> = defaultSharedPackages,
) : URLClassLoader(urls, parent) {
    companion object {
        init {
            // Register during class initialization so getClassLoadingLock is per name.
            registerAsParallelCapable()
        }

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
         * First sighting of a refused class name gets a WARN carrying the
         * straggler's stack; repeats drop to DEBUG so a retry loop cannot bury
         * the shutdown log. Lives on the companion so the instance stays under
         * detekt's per-class function budget.
         */
        private fun logRefusal(
            firstSighting: Boolean,
            pluginId: String,
            className: String,
            state: ClassLoaderState,
            refusal: ClassNotFoundException,
        ) {
            // Built per branch, not up front: the repeat path is the hot one and
            // its DEBUG call is usually disabled.
            if (firstSighting) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Refused to resolve a plugin class against the host after unload",
                    mapOf("pluginId" to pluginId, "className" to className, "state" to state.name),
                    refusal,
                )
            } else {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Refused a repeat request for an already-refused plugin class",
                    mapOf("pluginId" to pluginId, "className" to className, "state" to state.name),
                )
            }
        }

        /**
         * Default packages that are always shared with the host.
         * These include Kotlin stdlib, coroutines, and BOSS plugin API.
         */
        val defaultSharedPackages =
            setOf(
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
                "ai.rever.boss.plugin.scrollbar.",
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
        val cls =
            try {
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
     * Class names already refused by [loadClassChildFirst] after unload. The
     * straggler this catches is by construction a thread that did not get the
     * memo — a Ktor accept loop, a reconnecting coroutine — so it asks for the
     * same name over and over. Logging is therefore deduped to one WARN with a
     * stack per name; the refusal itself is never deduped.
     */
    private val refusedClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
            logger.debug(
                LogCategory.SYSTEM,
                "Classloader marked for unloading",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )
        }
    }

    /**
     * Mark this classloader as fully unloaded.
     */
    fun markUnloaded() {
        _state.set(ClassLoaderState.UNLOADED)
        logger.debug(
            LogCategory.SYSTEM,
            "Classloader marked as unloaded",
            mapOf(
                "pluginId" to pluginId,
            ),
        )
    }

    /**
     * Load a class with the hybrid loading strategy.
     *
     * For shared packages, delegates to parent first (standard behavior).
     * For plugin packages, tries plugin JAR first (child-first).
     */
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            // Keep the lookup and definition under the same lock: concurrent first
            // loads must not both miss here and define the same plugin class twice.
            // Check if already loaded
            val loadedClass = findLoadedClass(name)
            if (loadedClass != null) {
                return@synchronized loadedClass
            }

            // Check state. Loading is still allowed while the loader winds down —
            // orderly teardown needs it: classes this loader already defined (the
            // early return above), shared host classes (parent-first, below), and
            // the plugin's own jar until close() shuts the jar. What is NOT allowed
            // any more is delegating a class the plugin jar cannot supply to the
            // host — see [loadClassChildFirst].
            //
            // DEBUG, not WARN: this fires on every post-ACTIVE attempt including the
            // legitimate ones above, and it is not deduped, so at WARN a retry loop
            // would bury the shutdown log with the benign message and hide the one
            // that matters. The refusal in loadClassChildFirst is the WARN.
            if (isUnloading) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Attempt to load class from unloading classloader",
                    mapOf(
                        "pluginId" to pluginId,
                        "className" to name,
                        "state" to state.name,
                    ),
                )
            }

            // Check if this is a shared package (parent-first)
            val isSharedPackage = sharedPackages.any { name.startsWith(it) }

            if (isSharedPackage) {
                // Parent-first loading for shared packages
                super.loadClass(name, resolve)
            } else {
                // Child-first loading for plugin classes
                loadClassChildFirst(name, resolve)
            }
        }

    /**
     * Load a class with child-first strategy.
     *
     * While the loader is ACTIVE a miss in the plugin jar delegates to the
     * parent — that is the normal path for every host-provided class. Once the
     * loader is unloading or closed the same delegation becomes destructive, so
     * it is refused instead; see the comment in the catch block.
     *
     * The two post-ACTIVE states are refused for different reasons, and only one
     * of them is load-bearing for the LinkageError this exists to prevent:
     * - [ClassLoaderState.UNLOADED] — CORRECTNESS. The jar is shut, `findClass`
     *   misses on every name including ones the plugin owns, so delegating
     *   splices the host's class graph into the plugin's.
     * - [ClassLoaderState.UNLOAD_IN_PROGRESS] — POLICY. The jar is still open
     *   here, so a miss is a genuine miss and delegating could not corrupt
     *   anything. It is refused anyway as fail-fast on a lifecycle bug: the
     *   plugin's own `dispose()` has already returned by the time this state is
     *   set (see `DynamicPluginLoader.unloadPlugin`), so a first-time load in
     *   this window is a straggler that would be refused a moment later anyway
     *   once `close()` lands. One rule beats two.
     */
    private fun loadClassChildFirst(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        // Try to find in plugin JAR first
        try {
            val clazz = findClass(name)
            if (resolve) {
                resolveClass(clazz)
            }
            return clazz
        } catch (notInPluginJar: ClassNotFoundException) {
            // One read: _state can move UNLOAD_IN_PROGRESS -> UNLOADED under us,
            // and the message must not disagree with the structured field.
            val stateAtRefusal = state
            if (stateAtRefusal == ClassLoaderState.ACTIVE) {
                // Fall back to parent. Deliberately unlogged: this is the
                // expected delegation path for every host-provided class a
                // plugin touches, so logging here would flood at class-load
                // time on a hot path.
                return parent.loadClass(name)
            }

            // A closed URLClassLoader answers findClass() with
            // ClassNotFoundException for EVERY name, including the ones its own
            // jar carries. Delegating here would therefore hand the plugin the
            // HOST's copy of a class the plugin owns, splicing two class graphs
            // together. The JVM does not complain at that moment — it complains
            // later and elsewhere, as a loader constraint LinkageError naming
            // two unrelated classes (this is how a terminal-tab hot-reload
            // produced an io/ktor/util/AttributeKey constraint violation between
            // CIOApplicationEngine and HttpRequestLifecycleKt, both of which the
            // plugin jar actually contained).
            //
            // Refuse at the request instead, where the caller is still on the
            // stack. ClassNotFoundException specifically: loadClass already
            // declares it, every caller — including the JVM's own resolution
            // machinery, which turns it into a precise NoClassDefFoundError at
            // the resolution site — already handles it. An unchecked exception
            // or a raw Error thrown from a classloader runs on whatever
            // straggler thread made the late request (a Ktor worker, a
            // coroutine dispatcher, an AWT handler) and can tear that thread
            // down mid-teardown.
            val refusal =
                ClassNotFoundException(
                    "Plugin classloader for '$pluginId' is $stateAtRefusal; refusing to resolve " +
                        "'$name' against the host classloader. Something still referenced the " +
                        "plugin after it was unloaded - that reference is the bug.",
                    notInPluginJar,
                )
            // WARN, not ERROR: refusing is the correct outcome and teardown
            // continues. The throwable is attached so the first entry for a name
            // carries the straggler's stack; repeats drop to DEBUG so a retry
            // loop cannot bury the shutdown log. Best effort — logging must
            // never replace the refusal, which is the thing that has to
            // propagate.
            try {
                logRefusal(refusedClassNames.add(name), pluginId, name, stateAtRefusal, refusal)
            } catch (_: Throwable) {
                // Logging can itself fail during shutdown; the refusal stands.
            }
            throw refusal
        }
    }

    /**
     * Get a resource with child-first strategy for plugin resources.
     */
    override fun getResource(name: String): URL? {
        // For shared packages, use parent-first
        val isSharedResource =
            sharedPackages.any {
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
        val isSharedResource =
            sharedPackages.any {
                name.startsWith(it.replace('.', '/'))
            }
        if (isSharedResource) {
            return super.getResources(name)
        }
        val own = java.util.Collections.list(findResources(name))
        val fromParents =
            java.util.Collections
                .list(parent.getResources(name))
                .filterNot { it in own }
        return java.util.Collections.enumeration(own + fromParents)
    }

    /**
     * Close this classloader and release resources.
     */
    override fun close() {
        markUnloaded()
        logger.info(
            LogCategory.SYSTEM,
            "Closing plugin classloader",
            mapOf(
                "pluginId" to pluginId,
            ),
        )
        super.close()
    }

    override fun toString(): String = "PluginClassLoader(pluginId=$pluginId, state=$state, urls=${getURLs().size})"
}
