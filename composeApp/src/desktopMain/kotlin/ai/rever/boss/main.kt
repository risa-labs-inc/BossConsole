package ai.rever.boss

import BossTheme
import ai.rever.boss.cli.CLICommandHandler
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.ChromiumDownloadContent
import ai.rever.boss.components.settings.search.SettingsSearchIndex
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.config.ChromiumFlagKeys
import ai.rever.boss.config.ChromiumFlagsSettingsManager
import ai.rever.boss.config.ConfigLoader
import ai.rever.boss.config.ResourceModeConfig
import ai.rever.boss.crash.CrashHandler
import ai.rever.boss.crash.RENDER_RECOVERY_TOAST_MILLIS
import ai.rever.boss.crash.RenderCrashPolicy
import ai.rever.boss.crash.RenderRecoveryToaster
import ai.rever.boss.crash.WindowExceptionRoute
import ai.rever.boss.crash.decideWindowExceptionRoute
import ai.rever.boss.crash.displayPluginId
import ai.rever.boss.crash.hasFatalCause
import ai.rever.boss.crash.hostPluginIdResolver
import ai.rever.boss.crash.noteRecoveryOutcome
import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.performance.MemoryPressureWatchdog
import ai.rever.boss.performance.PerformanceMonitor
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import ai.rever.boss.plugin.sandbox.ui.installCrashInterceptor
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.services.passkey.PasskeyPlatformInit
import ai.rever.boss.startup.ChromiumBootstrap
import ai.rever.boss.startup.CliBootstrap
import ai.rever.boss.startup.CliDispatchResult
import ai.rever.boss.startup.OverlaySetup
import ai.rever.boss.startup.PlatformSetup
import ai.rever.boss.startup.ShutdownSequence
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.updater.AppUpdateRealtimeService
import ai.rever.boss.updater.UpdateCoordinator
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.AWTKeyboardInterceptor
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindow
import ai.rever.boss.window.BossWindowIcon
import ai.rever.boss.window.DefaultWindowIcon
import ai.rever.boss.window.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Window
import javax.swing.JPopupMenu
import kotlin.system.exitProcess

private val logger by lazy { BossLogger.forComponent("Main") }

/**
 * Decides the render-recovery toast and rate-limits it. EDT-confined: the window
 * exception handler is the only caller. See [RenderRecoveryToaster] for why this
 * is not just a `!=` against the last message.
 */
private val renderRecoveryToaster = RenderRecoveryToaster()

/**
 * A fault we can pin on a plugin that has no boundary to hand it to.
 *
 * The gap this closes: [PluginCrashInterceptor.attributeToPlugin] only answers
 * for plugins with a *mounted* error boundary, so a plugin with no UI on screen
 * was unattributable — and an unattributable `StackOverflowError` escalated to
 * ending the app.
 */
private fun quarantineBlamedPlugin(
    pluginId: String,
    throwable: Throwable,
) {
    logger.error(
        LogCategory.UI,
        "Render exception blamed on a plugin with no error boundary - quarantining it, window kept alive",
        mapOf(
            "pluginId" to pluginId,
            "errorType" to throwable.javaClass.simpleName,
        ),
        throwable,
    )
    // Written to disk rather than raised as a dialog: the session survives, and a
    // crash we recovered from has no business interrupting the user. Same
    // reasoning as containRenderFault.
    ai.rever.boss.crash.CrashHandler
        .recordContained(throwable)
    if (pluginId.isNotBlank()) {
        // notify = true: this is the only message the user will get, and unlike a
        // contained render fault there is a named plugin to put in it.
        ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
            .recordCrash(pluginId, throwable)
    }
}

/**
 * Keep the window, recover the plugin panels, and tell the user.
 *
 * Extracted from the handler rather than inlined: the block was long enough to
 * push `exceptionHandler` past the length limit, and the ordering here matters
 * enough to read on its own.
 */
private fun containRenderFault(
    throwable: Throwable,
    policy: RenderCrashPolicy,
) {
    logger.error(
        LogCategory.UI,
        "Unattributed render exception - contained, window kept alive",
        mapOf(
            "errorType" to throwable.javaClass.simpleName,
            "recentFailures" to policy.recentFailureCount().toString(),
        ),
        throwable,
    )
    // Reported, but not through CrashHandler.handleCrash: a fault the render path
    // has already contained and recovered from must not interrupt the user to ask
    // about it. (That dialog was also terminal on every exit; a plugin-attributed
    // crash now recovers instead, but a contained fault still has no business
    // opening it.)
    // recordContained writes the report to disk instead, so a host-side render bug
    // stays visible rather than costing one log line and a toast.
    ai.rever.boss.crash.CrashHandler
        .recordContained(throwable)
    // Keeping the window alive is not enough on its own: a repaint over a subtree
    // that still reproduces the fault leaves a broken window and no explanation.
    val outcome = PluginRenderRecovery.onUnattributedRenderException(throwable)
    // Shared with the seam test so both exercise the same pairing — see
    // noteRecoveryOutcome.
    val madeProgress = noteRecoveryOutcome(policy, outcome)

    // Telling the user and un-counting the fault are separate decisions; every
    // attempt to derive one from the other has regressed the other. The toaster
    // owns this one, and is tested — see RenderRecoveryToaster.
    renderRecoveryToaster.toastFor(outcome, now = System.nanoTime() / 1_000_000)?.let { message ->
        StatusMessageManager.showMessage(message, durationMs = RENDER_RECOVERY_TOAST_MILLIS)
    }
    // The repaint stays on progress only: it is a full sweep of every window, and
    // during a storm it arguably feeds the fault it is responding to. Nothing to
    // repaint for a verdict that changed nothing.
    if (madeProgress) {
        Window.getWindows().forEach { it.repaint() }
    }
}

/**
 * Scope for fire-and-forget startup work (PSI warm-up, update-Realtime start).
 * Deliberately process-lifetime — main() has no teardown point; long-lived
 * services manage their own scopes and are disposed via the shutdown hook.
 * SupervisorJob so one failed warm-up doesn't cancel the others.
 */
private val startupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun main(args: Array<String>) {
    // -------------------------------------------------------------------------
    // Phase 1: Headless CLI & credential helper dispatch (before AWT / logging)
    // -------------------------------------------------------------------------
    when (val earlyResult = CliBootstrap.dispatchHeadless(args)) {
        is CliDispatchResult.Exit -> exitProcess(earlyResult.code)
        CliDispatchResult.Continue -> Unit
    }

    val startupBeganMs = System.currentTimeMillis()

    // -------------------------------------------------------------------------
    // Phase 2: Logging initialization
    // -------------------------------------------------------------------------
    BossLogger.configureFromEnvironment()
    BossLogger.initialize() // Register shutdown hook for log flushing

    // -------------------------------------------------------------------------
    // Phase 3: Platform setup, pre-AWT properties & settings warm-up
    // -------------------------------------------------------------------------
    PlatformSetup.applyMacAppearanceFromTheme()

    // Serve credential brokers to plugins
    ai.rever.boss.services.llm.BrokeredCredentialAccess.initialize(
        ai.rever.boss.llm.BrokeredCredentialProviderImpl,
    )

    // Plugin load remedy access resolver
    ai.rever.boss.components.plugin.PluginLoadRemedyAccess.initialize(
        ai.rever.boss.components.plugin.DesktopPluginLoadRemedyResolver,
    )

    // Warm settings singletons on IO thread
    startupScope.launch(Dispatchers.IO) {
        ai.rever.boss.components.workspaces.WorkspaceSettingsManager.currentSettings
        ai.rever.boss.focusmode.FocusModeSettingsManager.currentSettings
    }

    // Set WM_CLASS for Linux desktop integration (must be before any AWT init)
    PlatformSetup.setLinuxWMClass()

    // Set up proper temp directories for native libraries
    PlatformSetup.setupNativeLibraryPaths()

    // Publish browser configuration flags as system properties
    ChromiumFlagsSettingsManager.applyToSystemProperties()
    ai.rever.boss.config.SwipeNavSettingsManager
        .publish()
    ai.rever.boss.config.AutoPipSettingsManager
        .publish()

    // Compose UI rendering backend override (Skiko)
    ConfigLoader
        .getConfig("BOSS_SKIKO_RENDER_API")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { requested ->
            val known = ChromiumFlagKeys.SKIKO_RENDER_APIS
            val normalized = requested.uppercase()
            if (normalized in known) {
                System.setProperty("skiko.renderApi", normalized)
            } else {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Ignoring unrecognized BOSS_SKIKO_RENDER_API - letting Skiko auto-detect",
                    mapOf("value" to requested, "known" to known.joinToString("|")),
                )
            }
        }

    // Disable lightweight popups for HARDWARE_ACCELERATED rendering mode
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)

    // Uninstall protocol hook (Windows)
    when (val unregResult = CliBootstrap.handleProtocolUnregistration(args)) {
        is CliDispatchResult.Exit -> exitProcess(unregResult.code)
        CliDispatchResult.Continue -> Unit
    }

    // -------------------------------------------------------------------------
    // Phase 4: Crash handlers & single instance check
    // -------------------------------------------------------------------------
    CrashHandler.install()
    installCrashInterceptor()
    PluginExecutionBoundary.installPluginIdResolver(hostPluginIdResolver())

    PluginCrashRegistry.onCrashNotify = { pluginId, error ->
        val errorMsg =
            (error.message ?: error.javaClass.simpleName)
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .take(60)
        StatusMessageManager.showMessage(
            "Plugin '${displayPluginId(pluginId)}' crashed: $errorMsg",
            durationMs = 8000,
        )
    }

    logger.info(LogCategory.SYSTEM, "BOSS starting up")

    // Single-instance check: ensure only one BOSS instance runs
    if (!SingleInstanceManager.acquireLock()) {
        logger.info(LogCategory.SYSTEM, "Another BOSS instance is already running")
        val forwarded = CliBootstrap.forwardToExistingInstance(args)
        exitProcess(if (forwarded) 0 else 1)
    }

    // A forwarding launch must never bind the kernel socket or spawn a second service cohort.
    // Saved KERNEL mode applies to later OS file/link launches too.
    // Initialize microkernel infrastructure (no-op in MONOLITH mode)
    val kernelBootstrap: Any? =
        try {
            val bossMode =
                ConfigLoader.getConfig("BOSS_MODE")
            if (bossMode.equals("KERNEL", ignoreCase = true)) {
                val cls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
                val modeClass = Class.forName("ai.rever.boss.process.ProcessMode")
                val kernelMode = modeClass.enumConstants.first { it.toString() == "KERNEL" }
                val instance = cls.getConstructor(modeClass).newInstance(kernelMode)
                cls.getMethod("initialize").invoke(instance)
                instance
            } else {
                null
            }
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }

    // -------------------------------------------------------------------------
    // Phase 5: Shutdown hook registration
    // -------------------------------------------------------------------------
    ShutdownSequence.register { kernelBootstrap }
    logger.info(LogCategory.SYSTEM, "Successfully acquired single-instance lock")

    // -------------------------------------------------------------------------
    // Phase 6: Overlays, window nets & Chromium engine preparation
    // -------------------------------------------------------------------------
    // After headless exits and rendering properties: installing the AWT listener creates the
    // toolkit, which reads those properties once. Before any application window can open.
    DefaultWindowIcon.install()

    startupScope.launch(Dispatchers.IO) {
        DefaultWorkingDirectory.ensureDefaultDirectory()
    }

    OverlaySetup.configure()

    val (chromiumNeedsDownload, engineLabel) = ChromiumBootstrap.prepare()

    // -------------------------------------------------------------------------
    // Phase 7: Post-lock CLI, keyboard interceptor, services & plugins
    // -------------------------------------------------------------------------
    CliBootstrap.dispatchPostLock(args)

    AWTKeyboardInterceptor.install()
    // macOS already read the theme before AWT; other platforms still need this initialization.
    AppThemeSettingsManager.ensureInitialized()
    PasskeyPlatformInit.initialize()
    SettingsSearchIndex.registerWithGlobalSearch()
    PluginStoreSetup.initialize()

    startupScope.launch {
        AppUpdateRealtimeService.instance.apply {
            onReleaseChanged = {
                val updateCoordinator = UpdateCoordinator.instance
                updateCoordinator.checkForUpdatesInBackground()
                updateCoordinator.versionListManager.fetchVersions(forceRefresh = true)
            }
            start()
        }
    }

    ai.rever.boss.components.plugin.DefaultPlugin.Companion.loadPersistedPluginsInternal = { manager ->
        PluginStoreSetup.loadPersistedPlugins(manager)
    }

    GlobalLogCapture.start()
    ResourceModeConfig.publishToPlugins()

    if (ResourceModeConfig.mode.backgroundSamplingEnabled) {
        PerformanceMonitor.start()
    } else {
        logger.info(
            LogCategory.SYSTEM,
            "Performance sampling disabled by the resource mode",
            mapOf("mode" to ResourceModeConfig.mode.name),
        )
    }

    MemoryPressureWatchdog.start(startupScope)

    logger.debug(
        LogCategory.SYSTEM,
        "Environment info",
        mapOf(
            "cwd" to System.getProperty("user.dir"),
            "javaVersion" to System.getProperty("java.version"),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        ),
    )

    // Create initial window BEFORE application{} to prevent auto-recreation
    if (!chromiumNeedsDownload) {
        WindowManager.createNewWindow()
    }

    logger.info(
        LogCategory.SYSTEM,
        "Pre-UI startup complete",
        mapOf(
            "elapsedMs" to (System.currentTimeMillis() - startupBeganMs).toString(),
        ),
    )

    // -------------------------------------------------------------------------
    // No PSI or ProjectIndexer lifecycle here: indexing user.dir on a Finder launch can walk
    // the entire disk. Project indexing belongs to the editor plugin's project lifecycle.
    // Phase 8: Compose Application Entry & Window Loop
    // -------------------------------------------------------------------------
    application {
        // Provide a custom WindowExceptionHandlerFactory that intercepts plugin crashes
        // during composition. Compose's default factory shows an error dialog and disposes
        // the window, which bypasses our UncaughtExceptionHandler-based interceptor.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val defaultExceptionHandlerFactory = LocalWindowExceptionHandlerFactory.current

        // Shared across windows on purpose: a corrupted scene tends to throw from
        // whichever window repaints next, and the question being asked is "is this
        // app still rendering?", not "is this window still rendering?".
        val renderCrashPolicy =
            remember {
                ai.rever.boss.crash
                    .RenderCrashPolicy()
            }

        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val pluginAwareExceptionHandlerFactory =
            remember(defaultExceptionHandlerFactory) {
                object : WindowExceptionHandlerFactory {
                    override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler {
                        val defaultHandler = defaultExceptionHandlerFactory.exceptionHandler(window)
                        return WindowExceptionHandler { throwable ->
                            val pluginId =
                                PluginCrashInterceptor.attributeToPlugin(throwable)
                            // Not computed under an OOM. Blame walks the stack and
                            // may call into the plugin manager, which allocates —
                            // and a fatal heap is escalated regardless, so the
                            // answer could not change the route anyway.
                            val blamedPluginId =
                                if (pluginId != null || throwable.hasFatalCause()) {
                                    null
                                } else {
                                    PluginCrashInterceptor.blameFor(throwable)
                                }
                            when (decideWindowExceptionRoute(throwable, pluginId, renderCrashPolicy, blamedPluginId)) {
                                WindowExceptionRoute.PluginHandled -> {
                                    logger.warn(
                                        LogCategory.SYSTEM,
                                        "Compose exception intercepted for plugin",
                                        mapOf(
                                            "pluginId" to pluginId.orEmpty(),
                                            "errorType" to throwable.javaClass.simpleName,
                                        ),
                                    )
                                    PluginCrashInterceptor.tryHandle(pluginId.orEmpty(), throwable)
                                }

                                WindowExceptionRoute.QuarantinePlugin -> {
                                    quarantineBlamedPlugin(blamedPluginId.orEmpty(), throwable)
                                }

                                WindowExceptionRoute.Contain -> {
                                    containRenderFault(throwable, renderCrashPolicy)
                                }

                                WindowExceptionRoute.Escalate -> {
                                    logger.error(
                                        LogCategory.UI,
                                        "Render exception is not containable - escalating to the default handler",
                                        mapOf(
                                            "errorType" to throwable.javaClass.simpleName,
                                            "recentFailures" to renderCrashPolicy.recentFailureCount().toString(),
                                        ),
                                        throwable,
                                    )
                                    defaultHandler.onException(throwable)
                                }
                            }
                        }
                    }
                }
            }
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides pluginAwareExceptionHandlerFactory,
        ) {
            // State for Chromium download
            var isDownloadingChromium by remember { mutableStateOf(chromiumNeedsDownload) }
            var downloadProgress by remember {
                mutableStateOf(ChromiumAutoDownloader.DownloadProgress(0, 0))
            }

            // Show Chromium download dialog if needed
            if (isDownloadingChromium) {
                val downloadWindowState =
                    rememberWindowState(
                        position = WindowPosition.Aligned(Alignment.Center),
                        width = 500.dp,
                        height = 220.dp,
                    )

                // The error state adds a failure message plus Retry/Exit buttons; grow the
                // window so they aren't clipped by the fixed 220dp height.
                LaunchedEffect(downloadProgress.error != null) {
                    downloadWindowState.size =
                        DpSize(
                            500.dp,
                            if (downloadProgress.error != null) 360.dp else 220.dp,
                        )
                }

                Window(
                    onCloseRequest = { exitApplication() },
                    state = downloadWindowState,
                    title = "BOSS - Setup",
                    resizable = false,
                    // This is the one window that opens before any main window exists, so it can
                    // inherit an icon from nothing - and it is the first thing a new user sees.
                    icon = BossWindowIcon.painter,
                ) {
                    ApplyBossWindowIcon(window)

                    // Start download when dialog opens
                    LaunchedEffect(Unit) {
                        ChromiumAutoDownloader.downloadChromium { progress ->
                            downloadProgress = progress
                            if (progress.isComplete) {
                                // Download complete - create window and proceed
                                WindowManager.createNewWindow()
                                // The pre-warm was skipped at startup because the engine
                                // was missing; now that it is installed, warm it so the
                                // first tab does not pay the full boot.
                                //
                                // force, because it was skipped for a SECOND reason this
                                // comment did not know about: the unforced gate wants an
                                // existing browser profile, and a machine that has just
                                // downloaded its engine has never had one. So this call
                                // silently did nothing, on the one launch it was written for.
                                runCatching {
                                    ai.rever.boss.plugin.browser.FluckEngine
                                        .prewarmInBackground(force = true)
                                }
                                isDownloadingChromium = false
                            }
                        }
                    }

                    BossTheme {
                        Box(
                            modifier =
                                androidx.compose.ui.Modifier
                                    .fillMaxSize()
                                    .background(BossThemeController.current.colors.panel),
                        ) {
                            ChromiumDownloadContent(
                                progress = downloadProgress.progressFraction,
                                downloadedMB = downloadProgress.downloadedMB,
                                totalMB = downloadProgress.totalMB,
                                // Name the version being fetched. This dialog blocks
                                // the whole app for a several-hundred-MB download, and
                                // which engine it is turns out to be the first thing
                                // anyone asks when it appears unexpectedly — an engine
                                // mismatch is exactly what triggers it.
                                status =
                                    ai.rever.boss.components.dialogs.engineDownloadStatus(
                                        engineLabel = engineLabel,
                                        isExtracting = downloadProgress.isExtracting,
                                        totalBytes = downloadProgress.totalBytes,
                                    ),
                                error = downloadProgress.error,
                                onCancel = { exitApplication() },
                                onRetry = {
                                    // Reset progress and retry
                                    downloadProgress = ChromiumAutoDownloader.DownloadProgress(0, 0)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        ChromiumAutoDownloader.downloadChromium { progress ->
                                            downloadProgress = progress
                                            if (progress.isComplete) {
                                                WindowManager.createNewWindow()
                                                // Forced for the same reason as the first-attempt
                                                // path above: a freshly downloaded engine has no
                                                // browser profile yet, which the unforced gate reads
                                                // as "this machine does not use the browser".
                                                runCatching {
                                                    ai.rever.boss.plugin.browser.FluckEngine
                                                        .prewarmInBackground(force = true)
                                                }
                                                isDownloadingChromium = false
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Initialize CLI handler once app is running (only after Chromium is ready)
            if (!isDownloadingChromium) {
                LaunchedEffect(Unit) {
                    CLICommandHandler.getInstance().initialize(
                        windowManager = WindowManager,
                        getSplitViewState = {
                            // Workspace loading now handled via WorkspaceManager from BossApp
                            // No need to expose SplitViewState to CLI handler
                            null
                        },
                    )
                }

                // Render each window with stable identity via key()
                // This prevents re-composition of existing windows when new windows are added
                //
                // IMPORTANT: No auto-creation logic here!
                // When all windows close, app stays running (standard macOS behavior)
                // User can create new windows via UI elements (+ button, File menu, etc.)
                WindowManager.windows.forEach { windowState ->
                    key(windowState.id) {
                        BossWindow(
                            windowState = windowState,
                            onCloseRequest = {
                                // Exit fullscreen/maximized BEFORE disposing browsers to prevent
                                // SIGABRT crash in JxBrowser's getWindowHandle during macOS
                                // fullscreen exit transition. requestToggleFullScreen() is async
                                // (macOS Spaces animation takes ~300-500ms), so we add a brief
                                // delay to let the transition start before disposing browsers.
                                //
                                // Blocking the UI thread here is acceptable: the app is closing
                                // and the window is about to be destroyed anyway.
                                val awtWindow =
                                    ai.rever.boss.utils.WindowFocusManager
                                        .getWindow(windowState.id)
                                var needsTransitionWait = false
                                if (awtWindow is java.awt.Frame) {
                                    if (awtWindow.extendedState != java.awt.Frame.NORMAL) {
                                        logger.debug(
                                            LogCategory.UI,
                                            "Exiting maximized state before window close",
                                            mapOf(
                                                "windowId" to windowState.id,
                                                "extendedState" to awtWindow.extendedState.toString(),
                                            ),
                                        )
                                        awtWindow.extendedState = java.awt.Frame.NORMAL
                                        needsTransitionWait = true
                                    }
                                    // macOS native fullscreen uses Spaces, not AWT exclusive mode.
                                    // requestToggleFullScreen is a TOGGLE — calling it when not
                                    // fullscreen will ENTER fullscreen. We must detect whether the
                                    // window is actually in native fullscreen before calling it.
                                    // Detection: in native fullscreen, the window bounds match the
                                    // full screen size (not the visible/usable area).
                                    val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
                                    if (isMacOS) {
                                        val screenBounds =
                                            awtWindow.graphicsConfiguration
                                                ?.device
                                                ?.defaultConfiguration
                                                ?.bounds
                                        val windowBounds = awtWindow.bounds
                                        val isNativeFullscreen =
                                            screenBounds != null &&
                                                windowBounds.width >= screenBounds.width &&
                                                windowBounds.height >= screenBounds.height
                                        if (isNativeFullscreen) {
                                            try {
                                                logger.debug(
                                                    LogCategory.UI,
                                                    "Requesting macOS fullscreen exit before window close",
                                                    mapOf(
                                                        "windowId" to windowState.id,
                                                    ),
                                                )
                                                val appClass = Class.forName("com.apple.eawt.Application")
                                                val app = appClass.getMethod("getApplication").invoke(null)
                                                appClass
                                                    .getMethod("requestToggleFullScreen", java.awt.Window::class.java)
                                                    .invoke(app, awtWindow)
                                                needsTransitionWait = true
                                            } catch (e: Exception) {
                                                logger.debug(
                                                    LogCategory.UI,
                                                    "macOS fullscreen exit not available",
                                                    mapOf(
                                                        "errorType" to e.javaClass.simpleName,
                                                        "reason" to (e.message ?: "unknown"),
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    // Wait for fullscreen/maximize transition to start before
                                    // disposing browsers. Both state changes are async on macOS.
                                    // Using runBlocking{delay()} per THREADING.md guidelines;
                                    // blocking is acceptable here since the window is closing.
                                    if (needsTransitionWait) {
                                        kotlinx.coroutines.runBlocking {
                                            kotlinx.coroutines.delay(150)
                                        }
                                    }
                                }

                                // CRITICAL: Dispose all browsers BEFORE window close begins
                                // This prevents JxBrowser OffScreenWidget crash when it tries to
                                // access the window handle during Compose disposal
                                // Must happen HERE, not in BossApp.onDispose, because:
                                // - onCloseRequest runs BEFORE Compose disposal
                                // - BossApp.onDispose runs DURING Compose disposal (too late!)
                                ai.rever.boss.components.window_panel.SplitViewStateRegistry
                                    .getState(windowState.id)
                                    ?.disposeAllBrowsersBlocking()

                                // Clean up runner terminal state to prevent memory leaks (Issue #498)
                                ai.rever.boss.run.RunnerTerminalService
                                    .cleanupWindow(windowState.id)
                                ai.rever.boss.services.terminal.TerminalAPIAccess
                                    .removeAllForWindow(windowState.id)

                                WindowManager.closeWindow(windowState.id)
                                ai.rever.boss.utils.WindowFocusManager
                                    .unregisterWindow(windowState.id)
                                // Don't call exitApplication - keep app running (macOS style)
                                // When window count reaches 0, app stays in Dock
                                // User can quit via Cmd+Q or right-click Dock → Quit
                            },
                        )
                    }
                }
            }
        } // CompositionLocalProvider
    }
}
