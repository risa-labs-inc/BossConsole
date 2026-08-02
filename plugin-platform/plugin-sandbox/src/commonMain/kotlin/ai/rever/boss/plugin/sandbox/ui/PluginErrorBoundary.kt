package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.PluginErrorClassifier
import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.SandboxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/**
 * Composition local for providing an error handler to child composables.
 *
 * Plugins can use this to report errors that should be tracked by the sandbox
 * but don't necessarily crash the component.
 */
val LocalPluginErrorHandler = compositionLocalOf<(Throwable) -> Unit> { { } }

/**
 * Tracks which plugins have crashed during composition.
 *
 * When a plugin crashes during composition (e.g., NoSuchMethodError), the SubcomposeLayout
 * tree is left corrupted and can't recompose. This registry is read at a level ABOVE the
 * corrupted tree (in BossMainPanelContent's key()) to force a complete teardown and rebuild,
 * allowing PluginErrorBoundary to show the fallback UI in a fresh composition.
 */
object PluginCrashRegistry {
    private val logger = BossLogger.forComponent("PluginCrashRegistry")

    /**
     * Stores crash information including the error and whether it's a binary incompatibility.
     */
    data class CrashInfo(
        val error: Throwable,
        val isBinaryIncompatibility: Boolean,
    )

    /** Thread-safe tracking store for crashed plugins. Used by watchdog and non-UI queries. */
    private val _crashedPluginsMap = ConcurrentHashMap<String, CrashInfo>()

    /** Compose-observable state, only mutated on EDT via invokeLater. */
    private val _crashedPluginsState = mutableStateOf(mapOf<String, CrashInfo>())

    /** @Composable to establish snapshot read dependency for recomposition. */
    val crashedPlugins: Map<String, CrashInfo>
        @Composable get() = _crashedPluginsState.value

    /**
     * Thread-safe mapping of pluginId → (tabId, closeAction).
     * The closeAction directly calls removeTabById() on the owning BossTabsComponent,
     * avoiding dependency on SplitViewStateRegistry (which may not be populated during
     * the first composition frame when crashes typically occur).
     */
    private val activeTabMappings = ConcurrentHashMap<String, Pair<String, () -> Unit>>()

    /**
     * Notification callback invoked (on EDT via invokeLater) after a crashed tab is closed.
     * Registered by the composeApp module to show a status message.
     * Parameters: (pluginId, error).
     */
    @Volatile
    var onCrashNotify: ((pluginId: String, error: Throwable) -> Unit)? = null

    /**
     * Register the active tab for a plugin with a direct close callback.
     * Called from BossMainPanelContent during composition (via remember{}),
     * BEFORE PluginErrorBoundary renders content. The closeAction captures
     * a direct reference to the owning BossTabsComponent.removeTabById(),
     * so it works even before SplitViewStateRegistry is populated.
     */
    fun registerActiveTab(
        pluginId: String,
        tabId: String,
        closeAction: () -> Unit,
    ) {
        activeTabMappings[pluginId] = tabId to closeAction
    }

    /** Unregister the active tab mapping for a plugin's tab (called on dispose). */
    fun unregisterActiveTab(
        pluginId: String,
        tabId: String,
    ) {
        // Only clear the mapping if it still points at this tab — a newer tab of the
        // same plugin may have replaced it before this (older) tab's dispose runs.
        activeTabMappings.computeIfPresent(pluginId) { _, current ->
            if (current.first == tabId) null else current
        }
    }

    /**
     * Record a crash for a plugin. Defers to SwingUtilities.invokeLater because this
     * is called from the uncaught exception handler during an active render pass.
     *
     * Instead of trying to recompose inside the corrupted SubcomposeLayout, this
     * closes the crashed tab via Decompose navigation (which lives outside Compose)
     * and shows a status message notification.
     */
    fun recordCrash(
        pluginId: String,
        error: Throwable,
    ) {
        // Store crash info in thread-safe map (for watchdog / non-UI tracking).
        // This runs on the crashing thread — ConcurrentHashMap is safe here.
        _crashedPluginsMap[pluginId] =
            CrashInfo(
                error = error,
                isBinaryIncompatibility = PluginErrorClassifier.isBinaryIncompatibility(error),
            )

        val mapping = activeTabMappings[pluginId]

        if (mapping != null) {
            val (tabId, closeAction) = mapping
            // Close the crashed tab via Decompose navigation on the EDT.
            // closeAction directly calls BossTabsComponent.removeTabById(),
            // bypassing the corrupted SubcomposeLayout entirely.
            javax.swing.SwingUtilities.invokeLater {
                logger.info(
                    LogCategory.UI,
                    "Closing crashed plugin tab",
                    mapOf(
                        "pluginId" to pluginId,
                        "tabId" to tabId,
                    ),
                )
                try {
                    closeAction()
                } catch (e: Throwable) {
                    logger.warn(
                        LogCategory.UI,
                        "closeAction threw during crash cleanup",
                        mapOf(
                            "pluginId" to pluginId,
                        ),
                        e,
                    )
                }
                _crashedPluginsMap.remove(pluginId)
                _crashedPluginsState.value = _crashedPluginsMap.toMap()
                onCrashNotify?.invoke(pluginId, error)
            }
        } else {
            logger.warn(
                LogCategory.UI,
                "Cannot close crashed tab — no active tab registered",
                mapOf(
                    "pluginId" to pluginId,
                ),
            )
            // Sync Compose state on EDT, then force repaint as fallback
            javax.swing.SwingUtilities.invokeLater {
                _crashedPluginsState.value = _crashedPluginsMap.toMap()
                java.awt.Window
                    .getWindows()
                    .forEach { it.repaint() }
            }
        }
    }

    /**
     * Record a crash *without* closing anything.
     *
     * [recordCrash] closes the plugin's tab when one is registered, which is right
     * for a crash known to belong to that plugin. It is badly wrong for a render
     * fault attributed only by "this panel happened to be on screen": quarantining
     * suspects that way closed the user's terminal and browser tabs — destroying
     * live sessions — because an unrelated plugin had a duplicate LazyColumn key.
     *
     * This flips the observable crash state only, so the panel swaps to its error
     * fallback and stops rendering plugin content, which is what actually stops
     * the exception recurring, while the tab and its contents stay put.
     *
     * @param notify whether to fire [onCrashNotify]. Pass false when the caller
     *   shows its own message. [PluginRenderRecovery] does: its quarantine toast
     *   names the plugin *and* says how to bring it back, and because this hop
     *   goes through `invokeLater` while recovery messages on the EDT directly,
     *   the generic "Plugin X crashed" landed second and overwrote it —
     *   StatusMessageManager holds one message. The tailored wording was lost
     *   exactly when it mattered.
     */
    fun recordRenderFault(
        pluginId: String,
        error: Throwable,
        notify: Boolean = true,
    ) {
        _crashedPluginsMap[pluginId] =
            CrashInfo(
                error = error,
                isBinaryIncompatibility = PluginErrorClassifier.isBinaryIncompatibility(error),
            )
        javax.swing.SwingUtilities.invokeLater {
            _crashedPluginsState.value = _crashedPluginsMap.toMap()
            if (notify) onCrashNotify?.invoke(pluginId, error)
        }
    }

    /** Clear crash state for a plugin (e.g., after restart or tab close). Safe to call from EDT. */
    fun clearCrash(pluginId: String) {
        _crashedPluginsMap.remove(pluginId)
        _crashedPluginsState.value = _crashedPluginsMap.toMap()
    }

    /** Check if a plugin has crashed (non-composable, thread-safe). */
    fun hasCrashed(pluginId: String): Boolean = _crashedPluginsMap.containsKey(pluginId)

    /**
     * Thread-safe set of plugins disabled due to binary incompatibility.
     * Persists across crash clear/tab close cycles so the DISABLED state fallback
     * can show the "incompatible" variant.
     */
    private val _incompatiblePlugins = ConcurrentHashMap.newKeySet<String>()

    /** Mark a plugin as incompatible (called from sandbox when binary incompatibility is detected). */
    fun markIncompatible(pluginId: String) {
        _incompatiblePlugins.add(pluginId)
    }

    /** Check if a plugin was disabled due to binary incompatibility. */
    fun isIncompatible(pluginId: String): Boolean = _incompatiblePlugins.contains(pluginId)

    /** Clear incompatible state (e.g., after plugin update). */
    fun clearIncompatible(pluginId: String) {
        _incompatiblePlugins.remove(pluginId)
    }
}

/**
 * Composition local for providing the plugin sandbox to child composables.
 */
val LocalPluginSandbox = compositionLocalOf<PluginSandbox?> { null }

/**
 * Error boundary composable that wraps plugin content and handles errors.
 *
 * When an error occurs within the content:
 * 1. The error is recorded in the sandbox's health metrics
 * 2. A fallback UI is shown with a "Restart Plugin" button
 * 3. The sandbox watchdog may trigger an automatic restart
 *
 * ## Error Handling Layers
 *
 * **Layer 1 - Explicit reporting (this boundary)**: Catches errors explicitly reported via
 * [LocalPluginErrorHandler]. Plugins should use `LocalPluginErrorHandler.current.invoke(error)`
 * in their event handlers, callbacks, and try-catch blocks.
 *
 * **Layer 2 - Coroutine exceptions (sandbox)**: The sandbox's [CoroutineExceptionHandler] catches
 * uncaught exceptions in coroutines launched via `sandboxScope`. These are recorded in health
 * metrics and may trigger watchdog restart.
 *
 * **Layer 3 - Composition crash interception (desktop)**: On desktop platforms, a
 * [PluginCrashInterceptor] hooks into the global uncaught exception handler to catch errors
 * thrown during composition (e.g., `NoSuchMethodError` from binary incompatibility). These
 * errors are attributed to the plugin by inspecting the stack trace and set the error state
 * in this boundary, showing the fallback UI instead of crashing the app.
 *
 * ## Mitigation Strategies for Plugin Authors
 *
 * To minimize composition crashes, plugins should:
 * - Use `derivedStateOf` for complex computed values that might throw
 * - Wrap risky operations in `remember { runCatching { ... } }` and handle failures gracefully
 * - Validate data before composition (e.g., null checks, bounds validation)
 * - Use `LaunchedEffect` for operations that might fail, with proper error handling
 * - Prefer loading states over throwing when data is unavailable
 *
 * Example:
 * ```kotlin
 * @Composable
 * fun SafePluginContent() {
 *     val result = remember { runCatching { riskyComputation() } }
 *     result.fold(
 *         onSuccess = { data -> DataDisplay(data) },
 *         onFailure = { LocalPluginErrorHandler.current(it) }
 *     )
 * }
 * ```
 *
 * @param pluginId The ID of the plugin for display purposes
 * @param sandbox The sandbox managing this plugin
 * @param onRestart Callback when the user clicks "Restart Plugin"
 * @param content The plugin content to render
 */
@Composable
fun PluginErrorBoundary(
    pluginId: String,
    sandbox: PluginSandbox,
    onRestart: () -> Unit,
    content: @Composable () -> Unit,
) {
    val logger = remember { BossLogger.forComponent("PluginErrorBoundary") }

    var error by remember { mutableStateOf<Throwable?>(null) }

    // Register crash interceptor eagerly during composition via remember{} so it's
    // active BEFORE content() is invoked. DisposableEffect fires too late — only after
    // the first successful composition — but crashes like NoSuchMethodError happen
    // DURING the first composition of content(). Uses expect/actual: on desktop, this
    // hooks into Thread.UncaughtExceptionHandler; on other platforms, this is a no-op.
    val crashRegistration =
        remember(pluginId) {
            registerCrashInterceptor(pluginId) { e ->
                logger.error(
                    LogCategory.UI,
                    "Composition crash intercepted for plugin",
                    mapOf(
                        "pluginId" to pluginId,
                        "errorType" to e.javaClass.simpleName,
                    ),
                    e,
                )
                sandbox.recordError(e)
                // Record in the crash registry so the parent (BossMainPanelContent) can
                // force a full subtree rebuild via key() change. This is necessary because
                // after a composition crash, the SubcomposeLayout tree is corrupted and
                // can't recompose in-place — we need the parent to tear it down entirely.
                PluginCrashRegistry.recordCrash(pluginId, e)
            }
        }
    DisposableEffect(pluginId) {
        onDispose {
            crashRegistration?.invoke()
        }
    }

    // Check sandbox state — if DISABLED (e.g. binary incompatibility), show error
    // immediately without attempting to render content. This prevents repeated crashes
    // when a plugin is already known to be broken.
    val sandboxState by sandbox.state.collectAsState()

    // Check both local error state and the crash registry.
    // The registry is populated by the crash interceptor for composition crashes
    // that corrupt the SubcomposeLayout tree. When the parent rebuilds the tree
    // (via key() change), this boundary is recreated fresh and reads from the registry.
    val registryCrash = PluginCrashRegistry.crashedPlugins[pluginId]
    val localError = error
    val activeError =
        when {
            sandboxState == SandboxState.DISABLED -> {
                // Sandbox was disabled (e.g. binary incompatibility) — synthesize an error
                // if none is already recorded, so the fallback UI always shows.
                localError ?: registryCrash?.error
                    ?: RuntimeException("Plugin is disabled")
            }

            else -> {
                localError ?: registryCrash?.error
            }
        }
    // Three distinct sources: persisted flag (survives crash clear), transient crash info,
    // and locally-caught errors that bypassed the registry.
    val isIncompatible =
        PluginCrashRegistry.isIncompatible(pluginId) ||
            registryCrash?.isBinaryIncompatibility == true ||
            (localError != null && PluginErrorClassifier.isBinaryIncompatibility(localError))

    if (activeError != null) {
        PluginErrorFallback(
            pluginId = pluginId,
            error = activeError,
            isIncompatible = isIncompatible,
            onRestart = {
                logger.info(
                    LogCategory.UI,
                    "User requested plugin restart",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                )
                error = null
                PluginCrashRegistry.clearCrash(pluginId)
                onRestart()
            },
            onDismiss = {
                logger.debug(
                    LogCategory.UI,
                    "User dismissed error",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                )
                error = null
                PluginCrashRegistry.clearCrash(pluginId)
            },
        )
    } else {
        // Note: Heartbeats are recorded automatically by the sandbox's heartbeat job
        // (InProcessPluginSandbox.startHeartbeatJob), so we don't need to record them here.

        CompositionLocalProvider(
            LocalPluginErrorHandler provides { e ->
                logger.error(
                    LogCategory.UI,
                    "Error reported from plugin",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                    e,
                )
                sandbox.recordError(e)
                error = e
            },
            LocalPluginSandbox provides sandbox,
        ) {
            // Tells PluginRenderRecovery this plugin is on screen. When a render
            // exception arrives that nobody can attribute from the stack — a
            // direct remeasure of a node inside this subtree, which bypasses the
            // boundary below — "which plugin panels are mounted" is the only
            // attribution left.
            DisposableEffect(pluginId) {
                val unregister = PluginRenderRecovery.registerMounted(pluginId)
                onDispose { unregister() }
            }

            // Rebuilt when recovery bumps the generation: a subcomposition left
            // inconsistent by a half-finished measure has to be discarded, not
            // recomposed in place — the same reason SidePanel keys on crash state.
            key(PluginRenderRecovery.generation) {
                // Composition crashes are caught by PluginCrashInterceptor, which
                // can find the plugin on the stack. Measure, placement and draw run
                // after the plugin's composables have returned, so nothing of the
                // plugin is on the stack there and attribution fails — the window
                // handler then treats it as a host crash and disposes the window,
                // taking the app with it. PluginRenderBoundary makes those phases
                // attributable by position, for the passes that flow through it.
                PluginRenderBoundary(
                    pluginId = pluginId,
                    onRenderCrash = { e ->
                        // FIRST, deliberately. report() wraps this callback in a
                        // try/catch that only logs, and `reported` is already
                        // latched — so if anything below threw, `error` would never
                        // be set and the panel would stay blank forever with nothing
                        // further logged. That is the exact failure this line exists
                        // to prevent, so it cannot run last. Safe to write here
                        // because PluginRenderBoundary hands this callback to the
                        // EDT rather than calling it mid-render.
                        error = e
                        sandbox.recordError(e)
                        // recordRenderFault, not recordCrash. recordCrash closes the
                        // plugin's registered tab, and activeTabMappings is keyed by
                        // plugin id alone — so a render fault in a plugin's *side
                        // panel* would close that plugin's unrelated main tab and
                        // whatever was live in it.
                        //
                        // The registry write is still needed and is not merely a
                        // duplicate of `error = e` above: if the parent tears this
                        // boundary down, the remembered local state goes with it and
                        // only the registry survives to show the fallback on the
                        // rebuild.
                        //
                        // Be clear about the blast radius, because it is wider than
                        // this panel: registryCrash is keyed by plugin id, so every
                        // surface of this plugin flips to the fallback, and unlike
                        // recordCrash — which removed its own entry after closing the
                        // tab — nothing clears this until the user hits Restart or
                        // Dismiss. A transient fault in one surface therefore degrades
                        // all of that plugin's surfaces for the session. That is the
                        // deliberate trade against destroying a live tab.
                        PluginCrashRegistry.recordRenderFault(pluginId, e)
                    },
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Wraps a composable with error handling via [LocalPluginErrorHandler].
 *
 * **Important Limitation**: Compose doesn't have built-in try-catch for composables.
 * This primarily helps with errors that are explicitly reported via the error handler
 * (e.g., in event handlers, callbacks, or coroutines). Unhandled exceptions during
 * composition may still crash the component.
 *
 * Usage in plugins:
 * ```kotlin
 * val errorHandler = LocalPluginErrorHandler.current
 * Button(onClick = {
 *     try {
 *         riskyOperation()
 *     } catch (e: Exception) {
 *         errorHandler(e)  // Reports error to sandbox
 *     }
 * })
 * ```
 *
 * @param pluginId The ID of the plugin for logging
 * @param sandbox The sandbox managing this plugin
 * @param fallback Composable to show when an error occurs
 * @param content The plugin content to render
 */
@Composable
fun SafePluginContent(
    pluginId: String,
    sandbox: PluginSandbox,
    fallback: @Composable (Throwable) -> Unit,
    content: @Composable () -> Unit,
) {
    val logger = remember { BossLogger.forComponent("SafePluginContent") }

    var error by remember { mutableStateOf<Throwable?>(null) }

    if (error != null) {
        fallback(error!!)
    } else {
        // Note: Heartbeats are recorded automatically by the sandbox's heartbeat job
        // (InProcessPluginSandbox.startHeartbeatJob), so we don't need to record them here.

        CompositionLocalProvider(
            LocalPluginErrorHandler provides { e ->
                logger.error(
                    LogCategory.UI,
                    "Error in plugin content",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                    e,
                )
                sandbox.recordError(e)
                error = e
            },
            LocalPluginSandbox provides sandbox,
        ) {
            content()
        }
    }
}
