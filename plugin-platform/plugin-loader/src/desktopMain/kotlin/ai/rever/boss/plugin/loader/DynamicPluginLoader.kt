package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.LoadedPlugin
import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginManifestConstants
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.Version
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for loading and unloading plugins dynamically.
 */
interface DynamicPluginLoader {
    /**
     * Load a plugin from a JAR file.
     *
     * @param jarPath Path to the plugin JAR
     * @return Result containing the loaded plugin or an error
     */
    suspend fun loadPlugin(jarPath: String): Result<LoadedPlugin>

    /**
     * Unload a plugin.
     *
     * @param pluginId The ID of the plugin to unload
     * @param waitForGC Whether to wait and verify classloader garbage collection
     * @param force Bypass the canUnload=false protection. Used by controlled,
     *   restorative operations (plugin reload/upgrade, the API-layer hot swap)
     *   that unload-then-reload — without it, system plugins keep classloaders
     *   parented to a superseded (closed) ApiClassLoader after a swap.
     * @return Result indicating success or failure
     */
    suspend fun unloadPlugin(
        pluginId: String,
        waitForGC: Boolean = false,
        force: Boolean = false,
    ): Result<Unit>

    /**
     * Get a loaded plugin by ID.
     *
     * @param pluginId The plugin ID
     * @return The loaded plugin, or null if not found
     */
    fun getPlugin(pluginId: String): LoadedPlugin?

    /**
     * Get all loaded plugins.
     */
    fun getLoadedPlugins(): List<LoadedPlugin>

    /**
     * Check if a plugin is loaded.
     *
     * @param pluginId The plugin ID
     * @return True if the plugin is loaded
     */
    fun isLoaded(pluginId: String): Boolean
}

/**
 * Default implementation of [DynamicPluginLoader].
 *
 * This implementation uses isolated classloaders per plugin and
 * follows IntelliJ IDEA patterns for dynamic plugin management.
 */
class DynamicPluginLoaderImpl(
    private val classLoaderManager: PluginClassLoaderManager = PluginClassLoaderManager(),
    /**
     * Verifier for load-time store-signature checks. Defaults to the pinned
     * store public key; injectable for tests. Immutable after construction.
     */
    private val signatureVerifier: PluginSignatureVerifier = PluginSignatureVerifier(PluginStoreTrust.TRUSTED_KEYS),
) : DynamicPluginLoader {
    private val logger = BossLogger.forComponent("DynamicPluginLoader")

    /**
     * Loaded plugins by ID.
     */
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()

    /**
     * Current BOSS application version. Must be set before loading plugins
     * that have minBossVersion requirements.
     */
    var currentBossVersion: String? = null

    /**
     * Version of the runtime API layer (the newest installed boss-plugin-api
     * jar). Falls back to the PROCESS-WIDE ApiClassLoader's version so a
     * loader whose own [initializeApiLayer] never ran (per-window managers
     * after the first window) still enforces the minApiVersion gate. The
     * setter remains for tests and explicit overrides.
     */
    var currentApiVersion: String? = null
        get() = field ?: classLoaderManager.getApiClassLoader()?.apiVersion

    /**
     * Resolve and install the shared API layer from [pluginDir]: the
     * resulting [ApiClassLoader] parents all subsequently created plugin
     * classloaders, and its version is compared against manifest
     * minApiVersion. Call after the plugin dir is reconciled and before any
     * plugin loads.
     */
    fun initializeApiLayer(pluginDir: java.io.File): ApiClassLoader {
        // Deliberately does NOT set the currentApiVersion override field: the
        // getter falls back to the shared loader, so a later hot-swap (from
        // any manager) is reflected here without a stale per-instance copy.
        return classLoaderManager.initializeApiLayer(pluginDir)
    }

    /**
     * Hot-swap the process-wide API layer (see
     * [PluginClassLoaderManager.swapApiLayer]). Caller must have closed all
     * plugin classloaders first.
     */
    fun swapApiLayer(pluginDir: java.io.File): ApiClassLoader {
        currentApiVersion = null // drop any stale override; getter follows the shared loader
        return classLoaderManager.swapApiLayer(pluginDir)
    }

    /**
     * Test-only seam invoked after the staged JAR passes signature
     * verification and before its classloader is created — the window the
     * verify-then-load TOCTOU used to expose. Lets a regression test swap the
     * bytes at the original path (or at the staged copy) mid-load and assert
     * the swap cannot reach execution. Never set outside tests.
     */
    internal var verifyToLoadHook: ((jarPath: String, stagedJar: File) -> Unit)? = null

    // JAR reading, bytecode validation, classloading, and instantiation are
    // heavy; callers typically run on Dispatchers.Main, so keep it all on IO.
    override suspend fun loadPlugin(jarPath: String): Result<LoadedPlugin> =
        withContext(Dispatchers.IO) {
            logger.info(
                LogCategory.SYSTEM,
                "Loading plugin from JAR",
                mapOf(
                    "jarPath" to jarPath,
                ),
            )

            // Stage the JAR into a private copy before anything else reads it:
            // the manifest, the signature digest anchor, binary validation and
            // the classloader all then operate on one snapshot, and the
            // original path is never re-opened. Closes the verify-then-load gap
            // where a local writer could swap bytes at jarPath between the
            // opens (identity of A, digest of B, execution of C). See
            // [StagedPluginJar].
            val staged =
                try {
                    StagedPluginJar.stage(File(jarPath))
                } catch (e: PluginLoadException) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to load plugin",
                        mapOf(
                            "jarPath" to jarPath,
                            "error" to (e.message ?: "unknown"),
                        ),
                        e,
                    )
                    return@withContext Result.failure(e)
                } catch (e: Exception) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Unexpected error loading plugin",
                        mapOf(
                            "jarPath" to jarPath,
                        ),
                        e,
                    )
                    return@withContext Result.failure(
                        PluginLoadException(
                            "Unexpected error loading plugin: ${e.message}",
                            cause = e,
                        ),
                    )
                }

            // The classloader takes ownership of the staged copy when it is
            // created (it deletes the copy in close()); every exit before that
            // must delete it here.
            var stagedOwnedByLoader = false
            try {
                // Read and validate manifest — from the staged copy, so the
                // identity checked below is the identity that will execute.
                val manifest = PluginManifestReader.readFromJar(staged.file.absolutePath)
                val pluginId = manifest.pluginId

                // Check if already loaded — refuse the duplicate before any
                // further verification work on the staged copy.
                if (loadedPlugins.containsKey(pluginId)) {
                    return@withContext Result.failure(
                        PluginLoadException(
                            "${PluginLoadException.ALREADY_LOADED_PREFIX}: $pluginId",
                            pluginId,
                        ),
                    )
                }

                // Verify the store signature (sidecar) before loading. This is the
                // choke point every install path funnels through, so it covers
                // Toolbox installs/updates, the first-run wizard, and restored
                // plugins alike. The digest it anchors is the staged copy's —
                // the bytes that will execute — not whatever sits at jarPath now.
                verifySignatureOrThrow(jarPath, manifest, staged.sha256)?.let { return@withContext Result.failure(it) }

                // Test-only seam at the old verify→load window (see
                // [verifyToLoadHook]); a no-op in production.
                verifyToLoadHook?.invoke(jarPath, staged.file)

                // Check API version compatibility
                if (!isApiVersionCompatible(manifest.apiVersion)) {
                    return@withContext Result.failure(
                        PluginApiVersionException(
                            "Plugin requires API version ${manifest.apiVersion}, but current version is ${PluginManifestConstants.CURRENT_API_VERSION}",
                            pluginId,
                            manifest.apiVersion,
                            PluginManifestConstants.CURRENT_API_VERSION,
                        ),
                    )
                }

                // Check minimum BOSS version compatibility
                val minBossVersion = manifest.minBossVersion
                if (!minBossVersion.isNullOrBlank()) {
                    val currentVersion = currentBossVersion
                    if (currentVersion == null) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Skipping minBossVersion validation - currentBossVersion not set",
                            mapOf(
                                "pluginId" to pluginId,
                                "requiredVersion" to minBossVersion,
                            ),
                        )
                    } else if (!isBossVersionCompatible(minBossVersion, currentVersion)) {
                        return@withContext Result.failure(
                            PluginBossVersionException(
                                "Plugin requires BOSS version $minBossVersion or later, but current version is $currentVersion",
                                pluginId,
                                minBossVersion,
                                currentVersion,
                            ),
                        )
                    }
                }

                // Check minimum API-layer version compatibility (the runtime
                // boss-plugin-api jar; same fail-open semantics as minBossVersion)
                val minApiVersion = manifest.minApiVersion
                if (minApiVersion.isNotBlank()) {
                    val installedApiVersion = currentApiVersion
                    if (installedApiVersion == null) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Skipping minApiVersion validation - currentApiVersion not set",
                            mapOf(
                                "pluginId" to pluginId,
                                "requiredVersion" to minApiVersion,
                            ),
                        )
                    } else if (!isBossVersionCompatible(minApiVersion, installedApiVersion)) {
                        return@withContext Result.failure(
                            PluginApiLevelException(
                                "Plugin requires boss-plugin-api $minApiVersion or later, but installed API layer is $installedApiVersion",
                                pluginId,
                                minApiVersion,
                                installedApiVersion,
                            ),
                        )
                    }
                }

                // Create classloader over the staged copy — the verified
                // bytes, not a re-open of jarPath. Ownership of the copy
                // transfers to the loader (deleted on close).
                val classLoader =
                    classLoaderManager
                        .createClassLoader(manifest, jarPath, stagedJar = staged)
                        .also { stagedOwnedByLoader = true }

                // Binary compatibility check — against the same staged bytes.
                val validation = BinaryCompatibilityValidator.validate(classLoader, staged.file.absolutePath)
                if (!validation.isCompatible) {
                    classLoaderManager.closeClassLoader(pluginId, classLoader)
                    return@withContext Result.failure(
                        PluginBinaryIncompatibilityException(
                            "Plugin '$pluginId' has binary incompatibilities: ${validation.errors.first()}",
                            pluginId,
                            manifest,
                        ),
                    )
                }

                // Load main class
                val pluginClass =
                    try {
                        classLoader.loadClass(manifest.mainClass)
                    } catch (e: ClassNotFoundException) {
                        classLoaderManager.closeClassLoader(pluginId, classLoader)
                        return@withContext Result.failure(
                            PluginClassException(
                                "Plugin main class not found: ${manifest.mainClass}",
                                pluginId,
                                manifest.mainClass,
                                e,
                            ),
                        )
                    } catch (e: LinkageError) {
                        // The JVM reports "these bytes will not link into this host" as an Error,
                        // not an Exception, so it matches neither the clause above nor the
                        // Exception clause that closes this function - it left by being thrown.
                        // The commonest instance is the commonest packaging mistake: a plugin
                        // built on a newer JDK arrives as UnsupportedClassVersionError.
                        // LinkageError, deliberately not Error: OutOfMemoryError and
                        // StackOverflowError are the host's problem and must keep propagating.
                        classLoaderManager.closeClassLoader(pluginId, classLoader)
                        return@withContext Result.failure(
                            PluginClassException(
                                "Plugin main class could not be linked: ${e.javaClass.simpleName}",
                                pluginId,
                                manifest.mainClass,
                                e,
                            ),
                        )
                    }

                // Verify it implements Plugin interface
                if (!Plugin::class.java.isAssignableFrom(pluginClass)) {
                    classLoaderManager.closeClassLoader(pluginId, classLoader)
                    return@withContext Result.failure(
                        PluginClassException(
                            "Main class does not implement Plugin interface: ${manifest.mainClass}",
                            pluginId,
                            manifest.mainClass,
                        ),
                    )
                }

                // Instantiate plugin
                val pluginInstance =
                    try {
                        // Try to get singleton instance (Kotlin object) first
                        val instanceField =
                            try {
                                pluginClass.getDeclaredField("INSTANCE")
                            } catch (e: NoSuchFieldException) {
                                // Not a Kotlin object - fall through to constructor instantiation
                                logger.debug(
                                    LogCategory.SYSTEM,
                                    "Plugin class has no INSTANCE field - using constructor",
                                    mapOf("error" to e.toString()),
                                )
                                null
                            }

                        if (instanceField != null) {
                            instanceField.isAccessible = true
                            instanceField.get(null) as Plugin
                        } else {
                            // Try no-arg constructor
                            pluginClass.getDeclaredConstructor().newInstance() as Plugin
                        }
                    } catch (e: LinkageError) {
                        // Reading a Kotlin object's INSTANCE field runs its static initializer -
                        // the plugin's own code, on this thread, for the first time. When that
                        // throws, the JVM wraps it in ExceptionInInitializerError, which is a
                        // LinkageError and so is not caught below. Nothing earlier can pre-empt
                        // it either: BinaryCompatibilityValidator loads classes with
                        // initialize=false precisely so it does NOT run plugin code.
                        classLoaderManager.closeClassLoader(pluginId, classLoader)
                        return@withContext Result.failure(
                            PluginClassException(
                                "Plugin failed to initialize: ${e.javaClass.simpleName}",
                                pluginId,
                                manifest.mainClass,
                                e,
                            ),
                        )
                    } catch (e: Exception) {
                        classLoaderManager.closeClassLoader(pluginId, classLoader)
                        return@withContext Result.failure(
                            PluginClassException(
                                "Failed to instantiate plugin: ${e.message}",
                                pluginId,
                                manifest.mainClass,
                                e,
                            ),
                        )
                    }

                // Create loaded plugin record
                val loadedPlugin =
                    LoadedPlugin(
                        manifest = manifest,
                        instance = pluginInstance,
                        classLoader = classLoader,
                        jarPath = jarPath,
                        state = PluginState.LOADED,
                    )

                loadedPlugins[pluginId] = loadedPlugin

                logger.info(
                    LogCategory.SYSTEM,
                    "Plugin loaded successfully",
                    mapOf(
                        "pluginId" to pluginId,
                        "version" to manifest.version,
                        "mainClass" to manifest.mainClass,
                    ),
                )

                Result.success(loadedPlugin)
            } catch (e: PluginLoadException) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to load plugin",
                    mapOf(
                        "jarPath" to jarPath,
                        "error" to (e.message ?: "unknown"),
                    ),
                    e,
                )
                Result.failure(e)
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Unexpected error loading plugin",
                    mapOf(
                        "jarPath" to jarPath,
                    ),
                    e,
                )
                Result.failure(
                    PluginLoadException(
                        "Unexpected error loading plugin: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (e: LinkageError) {
                // A backstop for the linkage failures the two clauses above do not sit in front
                // of - a class resolved lazily somewhere in this body, for instance. Without it
                // this function can still leave by throwing, and its signature promises a Result:
                // every getOrElse and onFailure on the install and startup-scan paths is bypassed
                // when it does. Still LinkageError rather than Error, so a VirtualMachineError
                // continues to propagate rather than being filed as a failed plugin.
                logger.error(
                    LogCategory.SYSTEM,
                    "Plugin could not be linked",
                    mapOf(
                        "jarPath" to jarPath,
                        "error" to e.javaClass.simpleName,
                    ),
                    e,
                )
                Result.failure(
                    PluginLoadException(
                        "Plugin could not be linked: ${e.javaClass.simpleName}: ${e.message}",
                        cause = e,
                    ),
                )
            } finally {
                if (!stagedOwnedByLoader) staged.delete()
            }
        }

    override suspend fun unloadPlugin(
        pluginId: String,
        waitForGC: Boolean,
        force: Boolean,
    ): Result<Unit> {
        return try {
            logger.info(
                LogCategory.SYSTEM,
                "Unloading plugin",
                mapOf(
                    "pluginId" to pluginId,
                    "waitForGC" to waitForGC,
                    "force" to force,
                ),
            )

            val loadedPlugin =
                loadedPlugins[pluginId]
                    ?: return Result.failure(
                        PluginLoadException(
                            "Plugin not found: $pluginId",
                            pluginId,
                        ),
                    )

            // Check if the plugin can be unloaded (system plugins may be
            // protected); force bypasses for reload/upgrade/hot-swap flows.
            if (!loadedPlugin.manifest.canUnload && !force) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Cannot unload system plugin",
                    mapOf(
                        "pluginId" to pluginId,
                        "systemPlugin" to loadedPlugin.manifest.systemPlugin,
                    ),
                )
                return Result.failure(
                    PluginUnloadException(
                        "Cannot unload system plugin: $pluginId (canUnload=false)",
                        pluginId,
                        listOf("System plugin is protected from unloading"),
                    ),
                )
            }

            // Update state
            loadedPlugins[pluginId] = loadedPlugin.copy(state = PluginState.UNLOADING)

            // Dispose plugin instance.
            //
            // ORDER MATTERS: dispose() must run BEFORE prepareUnload() below.
            // Once the classloader leaves ACTIVE it refuses to resolve anything
            // new against the host (PluginClassLoader.loadClassChildFirst), so
            // moving the mark up would make every plugin's dispose() that
            // lazily touches a host class start throwing. PluginUnloadOrderingTest
            // pins this.
            @Suppress("TooGenericExceptionCaught")
            try {
                loadedPlugin.instance.dispose()
            } catch (e: Throwable) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Error disposing plugin",
                    mapOf(
                        "pluginId" to pluginId,
                        "error" to (e.message ?: e::class.simpleName ?: "unknown"),
                    ),
                    e,
                )
            }

            // Remove from loaded plugins
            loadedPlugins.remove(pluginId)

            // Prepare classloader for unload
            val classLoader = classLoaderManager.prepareUnload(pluginId)
            if (classLoader != null) {
                classLoaderManager.closeClassLoader(pluginId, classLoader)

                // Optionally wait for GC
                if (waitForGC) {
                    val gcRef = classLoaderManager.getUnloadingReference(pluginId)
                    if (gcRef != null) {
                        val gcResult = ClassLoaderGCWatcher.waitForGC(pluginId, gcRef)
                        if (!gcResult.isSuccess) {
                            logger.warn(
                                LogCategory.SYSTEM,
                                "Classloader may not have been garbage collected",
                                mapOf(
                                    "pluginId" to pluginId,
                                ),
                            )
                        }
                    }
                }
            }

            logger.info(
                LogCategory.SYSTEM,
                "Plugin unloaded successfully",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Error unloading plugin",
                mapOf(
                    "pluginId" to pluginId,
                ),
                e,
            )
            Result.failure(
                PluginUnloadException(
                    "Error unloading plugin: ${e.message}",
                    pluginId,
                    cause = e,
                ),
            )
        }
    }

    override fun getPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]

    override fun getLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.toList()

    override fun isLoaded(pluginId: String): Boolean = loadedPlugins.containsKey(pluginId)

    /**
     * Check if the plugin's API version is compatible with the current version.
     */
    private fun isApiVersionCompatible(requiredVersion: String): Boolean {
        val required = parseVersion(requiredVersion)
        val current = parseVersion(PluginManifestConstants.CURRENT_API_VERSION)

        // Major version must match, and current minor must be >= required
        return required.first == current.first && current.second >= required.second
    }

    /**
     * Parse a version string into (major, minor) pair.
     */
    private fun parseVersion(version: String): Pair<Int, Int> {
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major to minor
    }

    /**
     * Check if the current BOSS version meets the plugin's minimum version requirement.
     * Uses semantic versioning comparison with proper prerelease handling.
     *
     * Note: If version parsing fails, the plugin is allowed to load with a warning logged.
     * This "fail-open" approach prevents blocking plugins due to malformed version strings,
     * while still logging the issue for investigation.
     */
    private fun isBossVersionCompatible(
        requiredVersion: String,
        currentVersion: String,
    ): Boolean {
        val required = Version.parse(requiredVersion)
        if (required == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to parse required version, allowing plugin",
                mapOf(
                    "requiredVersion" to requiredVersion,
                ),
            )
            return true
        }

        val current = Version.parse(currentVersion)
        if (current == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to parse current version, allowing plugin",
                mapOf(
                    "currentVersion" to currentVersion,
                ),
            )
            return true
        }

        return current >= required
    }

    /**
     * Verify the store signature carried in the `<jar>.sig` sidecar against
     * the pinned key, binding the canonical anchor `pluginId|version|sha256`
     * to the JAR bytes and manifest identity.
     *
     * Returns a [PluginSignatureException] to fail the load, or null to
     * proceed. Policy mirrors the download path: a sidecar that is present but
     * does not verify always fails; a missing sidecar (dev side-loads, or
     * store versions from before signing) warns and proceeds during the
     * rollout, becoming a hard failure once [PluginSignatureEnforcement] is
     * enabled — except in dev mode, where a missing sidecar is always allowed
     * so locally built plugins keep loading, and except a [PluginBundledTrust]
     * exemption, which is permanent rather than rollout-scoped: a bundled JAR
     * has no store signature to eventually gain, so it stays exempt even once
     * enforcement is on.
     *
     * [jarSha256] must be the digest of the bytes that will execute — the
     * staged copy — so the anchored identity cannot drift from the loaded
     * artifact no matter how the file at [jarPath] changes after staging.
     */
    private fun verifySignatureOrThrow(
        jarPath: String,
        manifest: PluginManifest,
        jarSha256: String,
    ): PluginSignatureException? {
        val signature = PluginSignatureSidecar.read(jarPath)
        if (signature == null) {
            if (PluginBundledTrust.isTrusted(jarPath, jarSha256)) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Plugin has no store signature but is a trusted bundled artifact - exempt from enforcement",
                    mapOf(
                        "pluginId" to manifest.pluginId,
                        "version" to manifest.version,
                    ),
                )
                return null
            }
            val devMode = System.getProperty("boss.dev.mode")?.toBoolean() == true
            if (PluginSignatureEnforcement.enforceUnsigned && !devMode) {
                return PluginSignatureException(
                    "Plugin has no store signature and signature enforcement is enabled",
                    manifest.pluginId,
                )
            }
            logger.warn(
                LogCategory.SYSTEM,
                "Plugin has no store signature - allowing for now, will be rejected once signature enforcement is enabled",
                mapOf(
                    "pluginId" to manifest.pluginId,
                    "version" to manifest.version,
                ),
            )
            return null
        }

        // [jarSha256] is the digest of the staged copy, computed over the same
        // stream that wrote it — the load below executes those exact bytes, so
        // the anchor binds the artifact that will run even if jarPath's file
        // was swapped after staging (the old verify→load TOCTOU).
        val anchor = PluginStoreTrust.versionAnchor(manifest.pluginId, manifest.version, jarSha256)
        val result = signatureVerifier.verifySignedMessage(anchor, signature)
        if (!result.isVerified) {
            val reason = (result as? SignatureVerificationResult.Failed)?.reason ?: "unknown"
            // Fail closed and leave the artifact in place — don't auto-delete
            // from a shared plugin dir (could race the store's own management).
            // It will be re-rejected on every startup until removed/reinstalled;
            // say so, since the JAR looks installed but never loads.
            logger.error(
                LogCategory.SYSTEM,
                "Plugin signature verification failed - plugin will NOT load and will keep being rejected until it is reinstalled or removed",
                mapOf(
                    "pluginId" to manifest.pluginId,
                    "version" to manifest.version,
                    "jarPath" to jarPath,
                    "reason" to reason,
                ),
            )
            return PluginSignatureException("Plugin signature verification failed: $reason", manifest.pluginId)
        }
        logger.info(
            LogCategory.SYSTEM,
            "Plugin signature verified",
            mapOf(
                "pluginId" to manifest.pluginId,
                "version" to manifest.version,
            ),
        )
        return null
    }

    /**
     * Get the classloader manager for advanced operations.
     */
    fun getClassLoaderManager(): PluginClassLoaderManager = classLoaderManager

    /**
     * Load all bundled plugins from a directory.
     *
     * Bundled plugins are system plugins that ship with BossConsole.
     * They are loaded in priority order (lower loadPriority values load first).
     *
     * This helper does not establish provenance from the directory argument. Production startup
     * binds trusted copies through PluginStoreSetup; unsigned, unbound files still fail enforcement.
     *
     * @param bundledDir Directory containing bundled plugin JARs
     * @return List of successfully loaded plugins, sorted by load priority
     */
    suspend fun loadBundledPlugins(bundledDir: java.io.File): List<LoadedPlugin> {
        if (!bundledDir.exists() || !bundledDir.isDirectory) {
            logger.debug(
                LogCategory.SYSTEM,
                "Bundled plugins directory not found",
                mapOf(
                    "path" to bundledDir.absolutePath,
                ),
            )
            return emptyList()
        }

        val jarFiles =
            bundledDir.listFiles { file ->
                file.isFile && file.extension == "jar"
            } ?: emptyArray()

        if (jarFiles.isEmpty()) {
            logger.debug(
                LogCategory.SYSTEM,
                "No bundled plugins found",
                mapOf(
                    "path" to bundledDir.absolutePath,
                ),
            )
            return emptyList()
        }

        logger.info(
            LogCategory.SYSTEM,
            "Loading bundled plugins",
            mapOf(
                "count" to jarFiles.size,
                "path" to bundledDir.absolutePath,
            ),
        )

        // Load plugins and collect successful ones
        val loadedBundled = mutableListOf<LoadedPlugin>()
        for (jarFile in jarFiles) {
            try {
                val result = loadPlugin(jarFile.absolutePath)
                if (result.isSuccess) {
                    loadedBundled.add(result.getOrThrow())
                } else {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to load bundled plugin",
                        mapOf(
                            "file" to jarFile.name,
                            "error" to (result.exceptionOrNull()?.message ?: "unknown"),
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Exception loading bundled plugin",
                    mapOf(
                        "file" to jarFile.name,
                    ),
                    e,
                )
            }
        }

        // Sort by load priority (lower values first)
        return loadedBundled.sortedBy { it.manifest.loadPriority }
    }

    /**
     * Check if a plugin is a system/bundled plugin.
     *
     * @param pluginId The plugin ID to check
     * @return True if the plugin is a system plugin
     */
    fun isSystemPlugin(pluginId: String): Boolean = loadedPlugins[pluginId]?.manifest?.systemPlugin == true

    /**
     * Check if a plugin can be unloaded.
     *
     * @param pluginId The plugin ID to check
     * @return True if the plugin can be unloaded
     */
    fun canUnloadPlugin(pluginId: String): Boolean = loadedPlugins[pluginId]?.manifest?.canUnload != false

    /**
     * Dispose all loaded plugins and classloaders.
     */
    suspend fun disposeAll() {
        logger.info(
            LogCategory.SYSTEM,
            "Disposing all plugins",
            mapOf(
                "count" to loadedPlugins.size,
            ),
        )

        // Unload all plugins. force: disposeAll() closes every classloader
        // below regardless, so refusing canUnload=false system plugins here
        // would only skip their dispose() and log a misleading warning.
        for (pluginId in loadedPlugins.keys.toList()) {
            unloadPlugin(pluginId, waitForGC = false, force = true)
        }

        classLoaderManager.disposeAll()
    }
}
