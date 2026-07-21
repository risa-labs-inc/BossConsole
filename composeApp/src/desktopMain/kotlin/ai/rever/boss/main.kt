package ai.rever.boss

import ai.rever.boss.cli.createBossCLI
import ai.rever.boss.cli.CLICommandHandler
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.components.dialogs.ChromiumDownloadContent
import BossTheme
import BossDarkBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.services.passkey.PasskeyPlatformInit
import ai.rever.boss.window.AWTKeyboardInterceptor
import ai.rever.boss.window.WindowManager
import ai.rever.boss.window.BossWindow
import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.performance.PerformanceDataProviderImpl
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.github.ajalt.clikt.core.main
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.swing.JPopupMenu

private val logger = BossLogger.forComponent("Main")

/**
 * Scope for fire-and-forget startup work (PSI warm-up, update-Realtime start).
 * Deliberately process-lifetime — main() has no teardown point; long-lived
 * services manage their own scopes and are disposed via the shutdown hook.
 * SupervisorJob so one failed warm-up doesn't cancel the others.
 */
private val startupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

fun main(args: Array<String>) {
    val startupBeganMs = System.currentTimeMillis()

    // Set WM_CLASS for Linux desktop integration (must be before any AWT init)
    setLinuxWMClass()

    // Set up proper temp directories for native libraries
    setupNativeLibraryPaths()

    // Disable lightweight popups for HARDWARE_ACCELERATED rendering mode (#258)
    // This ensures Swing popup menus (context menus) appear above the browser view
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)

    // Initialize logging framework early
    BossLogger.configureFromEnvironment()
    BossLogger.initialize()  // Register shutdown hook for log flushing

    // Install crash handler after logger is ready
    ai.rever.boss.crash.CrashHandler.install()

    // Install plugin crash interceptor (chains after CrashHandler to catch plugin-specific crashes)
    ai.rever.boss.plugin.sandbox.ui.installCrashInterceptor()

    // Register notification callback for plugin crashes.
    // Tab closing is handled directly by PluginCrashRegistry via the closeAction
    // registered in BossMainPanelContent. This callback only shows the status message.
    ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry.onCrashNotify = { pluginId, error ->
        val errorMsg = error.message?.take(60) ?: error.javaClass.simpleName
        ai.rever.boss.components.bars.horizontal.StatusMessageManager.showMessage(
            "Plugin '$pluginId' crashed: $errorMsg",
            durationMs = 8000
        )
    }

    logger.info(LogCategory.SYSTEM, "BOSS starting up")

    // Initialize microkernel infrastructure (no-op in MONOLITH mode, which is default)
    // On Windows ARM64, boss-ipc/boss-process-manager modules are excluded (no protoc),
    // so KernelBootstrap may not be available — silently skip.
    val kernelBootstrap: Any? = try {
        val bossMode = System.getenv("BOSS_MODE")
            ?: ai.rever.boss.config.ConfigLoader.getConfig("BOSS_MODE")
        if (bossMode == "KERNEL") {
            val cls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
            val modeClass = Class.forName("ai.rever.boss.process.ProcessMode")
            val kernelMode = modeClass.enumConstants.first { it.toString() == "KERNEL" }
            val instance = cls.getConstructor(modeClass).newInstance(kernelMode)
            cls.getMethod("initialize").invoke(instance)
            instance
        } else null
    } catch (_: ClassNotFoundException) { null }
    catch (_: NoClassDefFoundError) { null }

    // Single-instance check: ensure only one BOSS instance runs
    // On Windows, this prevents multiple windows when clicking deep links
    if (!SingleInstanceManager.acquireLock()) {
        logger.info(LogCategory.SYSTEM, "Another BOSS instance is already running")

        // Check if we have a deep link or URL to send to the existing instance
        val deepLink = args.firstOrNull {
            it.startsWith("boss://") ||
            it.startsWith("http://") ||
            it.startsWith("https://")
        }

        if (deepLink != null) {
            logger.info(LogCategory.SYSTEM, "Sending URL to existing instance")

            // Try to send with retry logic (important for auth deep links during sign-in)
            // Note: runBlocking is acceptable here as this runs during pre-UI initialization,
            // before the Compose application starts. No UI thread exists yet to block.
            var success = false
            val maxRetries = 3
            for (attempt in 1..maxRetries) {
                if (SingleInstanceManager.sendToExistingInstance(deepLink)) {
                    logger.info(LogCategory.SYSTEM, "URL sent successfully", mapOf("attempt" to attempt))
                    success = true
                    break
                } else {
                    logger.warn(LogCategory.SYSTEM, "Failed to send URL", mapOf(
                        "attempt" to attempt,
                        "maxRetries" to maxRetries
                    ))
                    if (attempt < maxRetries) {
                        // Use coroutine delay instead of Thread.sleep to avoid blocking
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.delay(500)
                        }
                    }
                }
            }

            if (success) {
                exitProcess(0)
            } else {
                // IPC failed after retries - DO NOT create new window
                // This prevents duplicate windows during sign-in
                logger.error(LogCategory.SYSTEM, "Could not send URL to existing instance after retries", mapOf(
                    "maxRetries" to maxRetries
                ))
                exitProcess(1)
            }
        } else {
            logger.info(LogCategory.SYSTEM, "No URL to send - existing BOSS window should be visible")
            exitProcess(0)
        }
    }

    // Register shutdown hook to release the single-instance lock AND close browser engine
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            // Stop performance monitoring to cancel background coroutines
            ai.rever.boss.performance.PerformanceMonitor.stop()
        } catch (e: Exception) {
            // Can't use logger in shutdown hook reliably, use System.err
            System.err.println("Error stopping performance monitor: ${e.message}")
        }
        try {
            // Close browser engine first to release lock files
            val engine = ai.rever.boss.plugin.browser.FluckEngine.currentEngine
            if (engine != null && !engine.isClosed) {
                engine.close()
            }
        } catch (e: Exception) {
            System.err.println("Error closing browser engine: ${e.message}")
        }
        try {
            // Close HTTP client for high-quality favicon service
            ai.rever.boss.cache.HighQualityFaviconService.close()
        } catch (e: Exception) {
            System.err.println("Error closing favicon HTTP client: ${e.message}")
        }
        try {
            // Uninstall AWT keyboard interceptor
            AWTKeyboardInterceptor.uninstall()
        } catch (e: Exception) {
            System.err.println("Error uninstalling keyboard interceptor: ${e.message}")
        }
        try {
            // Stop app-update realtime subscription
            ai.rever.boss.updater.AppUpdateRealtimeService.instance.dispose()
        } catch (e: Exception) {
            System.err.println("Error stopping app update realtime: ${e.message}")
        }
        try {
            // Shutdown plugin store
            PluginStoreSetup.shutdown()
        } catch (e: Exception) {
            System.err.println("Error shutting down plugin store: ${e.message}")
        }
        try {
            // Shutdown BossLogger
            BossLogger.shutdown()
        } catch (e: Exception) {
            System.err.println("Error shutting down logger: ${e.message}")
        }
        try {
            // Shutdown microkernel infrastructure (child processes, IPC server)
            kernelBootstrap?.let { kb ->
                kb.javaClass.getMethod("shutdown").invoke(kb)
            }
        } catch (e: Exception) {
            System.err.println("Error shutting down kernel: ${e.message}")
        }
        SingleInstanceManager.release()
    })

    logger.info(LogCategory.SYSTEM, "Successfully acquired single-instance lock")

    // Proactively clean up stale JxBrowser lock files from previous sessions
    // This is especially important for debug mode where shutdown hooks may not run
    try {
        ai.rever.boss.plugin.browser.FluckEngine.proactiveCleanupOnStartup()
    } catch (e: Exception) {
        logger.warn(LogCategory.SYSTEM, "Proactive browser lock cleanup failed", error = e)
    }

    // Pre-warm the browser engine off the UI thread so the first browser tab
    // opens against an already-running Chromium instead of paying the full
    // engine boot inside its composition. Opt out with BOSS_BROWSER_PREWARM=false.
    try {
        ai.rever.boss.plugin.browser.FluckEngine.prewarmInBackground()
    } catch (e: Exception) {
        logger.warn(LogCategory.SYSTEM, "Browser engine pre-warm failed to start", error = e)
    }

    // Parse CLI arguments if provided
    if (args.isNotEmpty()) {
        try {
            // Check if args contain deep link protocols
            val hasDeepLink = args.any {
                it.startsWith("boss://") || it.startsWith("http://") || it.startsWith("https://")
            }

            // If it's a deep link, let DeepLinkHandler process it
            // Otherwise, treat as CLI command
            if (!hasDeepLink) {
                logger.debug(LogCategory.SYSTEM, "Processing CLI arguments", mapOf("args" to args.joinToString(" ")))
                createBossCLI().main(args)
                // Commands are queued, continue with app initialization
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "CLI error", error = e)
            // Don't exit - let the app start normally
            // CLI errors shouldn't prevent GUI from launching
        }
    }

    // Initialize deep link handler
    DeepLinkHandler

    // Process command line arguments for deep links (Windows)
    DeepLinkHandler.processCommandLineArgs(args)

    // Install AWT keyboard interceptor to capture shortcuts before BossTerm
    // This ensures Cmd+N, Cmd+W, etc. work even when terminal has focus
    AWTKeyboardInterceptor.install()
    
    // Apply the persisted app theme before any UI composes, so the app opens
    // in the user's chosen look (Operator / Daylight / Clean).
    ai.rever.boss.theme.AppThemeSettingsManager.ensureInitialized()

    // Initialize passkey service for desktop platforms
    PasskeyPlatformInit.initialize()

    // Initialize plugin store (remote repository, download cache, update manager)
    PluginStoreSetup.initialize()

    // Start app-update Realtime push (Supabase) so the app learns about new releases
    // instantly instead of polling; route events into the existing update manager.
    // Off the main thread: building the Supabase client is not needed for first paint.
    startupScope.launch {
        ai.rever.boss.updater.AppUpdateRealtimeService.instance.apply {
            onReleaseChanged = { ai.rever.boss.updater.UpdateManager.instance.checkForUpdates() }
            start()
        }
    }

    // Set up the persisted plugins loader for DefaultPlugin
    ai.rever.boss.components.plugin.DefaultPlugin.Companion.loadPersistedPluginsInternal = { manager ->
        PluginStoreSetup.loadPersistedPlugins(manager)
    }

    // Note: no PSI or ProjectIndexer lifecycle here. The PSI stack lives in
    // the editor-tab plugin's bundled BossEditor now — the plugin warms it up
    // on register and shuts it down on dispose. (Indexing user.dir at startup
    // was also actively harmful: for a packaged app launched from Finder,
    // user.dir is "/", so the indexer walked the entire disk at 100% CPU.)

    // Start global log capture from app startup
    GlobalLogCapture.start()

    // Start performance monitoring from app startup
    ai.rever.boss.performance.PerformanceMonitor.start()


    // Debug: Log environment info
    logger.debug(LogCategory.SYSTEM, "Environment info", mapOf(
        "cwd" to System.getProperty("user.dir"),
        "javaVersion" to System.getProperty("java.version"),
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
    ))

    // Log API key availability (without exposing values)
    val apiKeyStatus = mapOf(
        "ANTHROPIC_API_KEY" to (System.getenv("ANTHROPIC_API_KEY") != null),
        "OPENAI_API_KEY" to (System.getenv("OPENAI_API_KEY") != null),
        "TOGETHER_API_KEY" to (System.getenv("TOGETHER_API_KEY") != null),
        "CUSTOM_LLM_API_KEY" to (System.getenv("CUSTOM_LLM_API_KEY") != null)
    )
    logger.debug(LogCategory.SYSTEM, "API key availability", apiKeyStatus.mapValues { if (it.value) "set" else "not set" })

    // Apply any engine install staged from Settings before validating/creating the engine
    ChromiumAutoDownloader.promotePendingInstall()

    // Check if Chromium needs to be downloaded (for debug/dev builds)
    val chromiumNeedsDownload = !ChromiumAutoDownloader.isChromiumInstalled()
    if (chromiumNeedsDownload) {
        logger.info(LogCategory.SYSTEM, "BOSS-branded Chromium not found - will prompt for download")
    }

    // Create initial window BEFORE application{} to prevent auto-recreation
    // This runs once on startup, not during recomposition
    // Note: Window creation is deferred if Chromium download is needed
    if (!chromiumNeedsDownload) {
        WindowManager.createNewWindow()
    }

    logger.info(LogCategory.SYSTEM, "Pre-UI startup complete", mapOf(
        "elapsedMs" to (System.currentTimeMillis() - startupBeganMs).toString()
    ))

    application {
        // Provide a custom WindowExceptionHandlerFactory that intercepts plugin crashes
        // during composition. Compose's default factory shows an error dialog and disposes
        // the window, which bypasses our UncaughtExceptionHandler-based interceptor.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val defaultExceptionHandlerFactory = LocalWindowExceptionHandlerFactory.current
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val pluginAwareExceptionHandlerFactory = remember(defaultExceptionHandlerFactory) {
            object : WindowExceptionHandlerFactory {
                override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler {
                    val defaultHandler = defaultExceptionHandlerFactory.exceptionHandler(window)
                    return WindowExceptionHandler { throwable ->
                        val pluginId = ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor
                            .attributeToPlugin(throwable)
                        if (pluginId != null) {
                            // Plugin crash — let the interceptor handle it (closes tab,
                            // shows status message)
                            logger.warn(LogCategory.SYSTEM,
                                "Compose exception intercepted for plugin",
                                mapOf("pluginId" to pluginId,
                                    "errorType" to throwable.javaClass.simpleName))
                            ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor
                                .tryHandle(pluginId, throwable)
                        } else {
                            // Not a plugin crash — delegate to default (shows error dialog)
                            defaultHandler.onException(throwable)
                        }
                    }
                }
            }
        }
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides pluginAwareExceptionHandlerFactory
        ) {
        // State for Chromium download
        var isDownloadingChromium by remember { mutableStateOf(chromiumNeedsDownload) }
        var downloadProgress by remember {
            mutableStateOf(ChromiumAutoDownloader.DownloadProgress(0, 0))
        }

        // Show Chromium download dialog if needed
        if (isDownloadingChromium) {
            val downloadWindowState = rememberWindowState(
                position = WindowPosition.Aligned(Alignment.Center),
                width = 500.dp,
                height = 220.dp
            )

            // The error state adds a failure message plus Retry/Exit buttons; grow the
            // window so they aren't clipped by the fixed 220dp height.
            LaunchedEffect(downloadProgress.error != null) {
                downloadWindowState.size = DpSize(
                    500.dp,
                    if (downloadProgress.error != null) 360.dp else 220.dp
                )
            }

            Window(
                onCloseRequest = { exitApplication() },
                state = downloadWindowState,
                title = "BOSS - Setup",
                resizable = false
            ) {
                // Start download when dialog opens
                LaunchedEffect(Unit) {
                    ChromiumAutoDownloader.downloadChromium { progress ->
                        downloadProgress = progress
                        if (progress.isComplete) {
                            // Download complete - create window and proceed
                            WindowManager.createNewWindow()
                            isDownloadingChromium = false
                        }
                    }
                }

                BossTheme {
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(BossDarkBackground)
                    ) {
                        ChromiumDownloadContent(
                            progress = downloadProgress.progressFraction,
                            downloadedMB = downloadProgress.downloadedMB,
                            totalMB = downloadProgress.totalMB,
                            status = when {
                                downloadProgress.isExtracting -> "Extracting files..."
                                downloadProgress.totalBytes > 0 -> "Installing BOSS Browser Engine..."
                                else -> "Connecting to download server..."
                            },
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
                                            isDownloadingChromium = false
                                        }
                                    }
                                }
                            }
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
                    }
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
                            val awtWindow = ai.rever.boss.utils.WindowFocusManager.getWindow(windowState.id)
                            var needsTransitionWait = false
                            if (awtWindow is java.awt.Frame) {
                                if (awtWindow.extendedState != java.awt.Frame.NORMAL) {
                                    logger.debug(LogCategory.UI, "Exiting maximized state before window close", mapOf(
                                        "windowId" to windowState.id,
                                        "extendedState" to awtWindow.extendedState.toString()
                                    ))
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
                                    val screenBounds = awtWindow.graphicsConfiguration?.device?.defaultConfiguration?.bounds
                                    val windowBounds = awtWindow.bounds
                                    val isNativeFullscreen = screenBounds != null &&
                                        windowBounds.width >= screenBounds.width &&
                                        windowBounds.height >= screenBounds.height
                                    if (isNativeFullscreen) {
                                        try {
                                            logger.debug(LogCategory.UI, "Requesting macOS fullscreen exit before window close", mapOf(
                                                "windowId" to windowState.id
                                            ))
                                            val appClass = Class.forName("com.apple.eawt.Application")
                                            val app = appClass.getMethod("getApplication").invoke(null)
                                            appClass.getMethod("requestToggleFullScreen", java.awt.Window::class.java)
                                                .invoke(app, awtWindow)
                                            needsTransitionWait = true
                                        } catch (e: Exception) {
                                            logger.debug(LogCategory.UI, "macOS fullscreen exit not available", mapOf(
                                                "errorType" to e.javaClass.simpleName,
                                                "reason" to (e.message ?: "unknown")
                                            ))
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
                            ai.rever.boss.run.RunnerTerminalService.cleanupWindow(windowState.id)
                            ai.rever.boss.services.terminal.TerminalAPIAccess
                                .removeAllForWindow(windowState.id)

                            WindowManager.closeWindow(windowState.id)
                            ai.rever.boss.utils.WindowFocusManager.unregisterWindow(windowState.id)
                            // Don't call exitApplication - keep app running (macOS style)
                            // When window count reaches 0, app stays in Dock
                            // User can quit via Cmd+Q or right-click Dock → Quit
                        }
                    )
                }
            }
        }
        } // CompositionLocalProvider
    }
}

private fun setupNativeLibraryPaths() {
    // Ensure temp directories exist and are set properly
    val bossDir = BossDirectories.rootDir
    val tempDir = File(bossDir, "temp")
    val pty4jDir = File(tempDir, "pty4j")
    
    // Create directories if they don't exist
    bossDir.mkdirs()
    tempDir.mkdirs()
    pty4jDir.mkdirs()
    
    // Extract PTY4J native libraries from classpath if needed
    extractPty4jNatives(pty4jDir)
    
    // Set system properties for native libraries
    System.setProperty("pty4j.tmpdir", pty4jDir.absolutePath)
    System.setProperty("pty4j.preferred.native.folder", pty4jDir.absolutePath)
    
    // Check if we're running from an app bundle
    val appPath = System.getProperty("java.home")
    if (appPath.contains(".app")) {
        // We're in an app bundle, check for bundled natives
        val bundledNatives = File(appPath, "../../app/pty4j-native")
        if (bundledNatives.exists()) {
            System.setProperty("pty4j.preferred.native.folder", bundledNatives.absolutePath)
        }
    }
    
    // Also set java.io.tmpdir to a proper location
    if (!System.getProperty("java.io.tmpdir").startsWith(System.getProperty("user.home"))) {
        System.setProperty("java.io.tmpdir", tempDir.absolutePath)
    }
}

private fun extractPty4jNatives(targetDir: File) {
    try {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()

        // Determine platform and library name
        val (platformPath, libName) = when {
            osName.contains("mac") || osName.contains("darwin") -> "darwin" to "libpty.dylib"
            osName.contains("linux") -> {
                val arch = when {
                    osArch == "aarch64" || osArch == "arm64" -> "aarch64"
                    osArch == "amd64" || osArch == "x86_64" -> "x86-64"
                    osArch.startsWith("arm") -> "arm"
                    osArch == "ppc64le" -> "ppc64le"
                    osArch == "mips64el" -> "mips64el"
                    osArch == "riscv64" -> "riscv64"
                    osArch.contains("86") -> "x86"
                    else -> osArch
                }
                "linux/$arch" to "libpty.so"
            }
            osName.contains("freebsd") -> {
                val arch = if (osArch == "amd64" || osArch == "x86_64") "x86-64" else "x86"
                "freebsd/$arch" to "libpty.so"
            }
            else -> {
                logger.warn(LogCategory.SYSTEM, "Unsupported platform for PTY4J", mapOf(
                    "os" to osName,
                    "arch" to osArch
                ))
                return
            }
        }

        // Create platform-specific directory
        val platformDir = File(targetDir, platformPath)
        if (!platformDir.exists()) {
            platformDir.mkdirs()
        }

        // Check if native library already exists
        val libptyFile = File(platformDir, libName)
        if (libptyFile.exists() && libptyFile.length() > 0) {
            logger.trace(LogCategory.SYSTEM, "PTY4J natives already extracted", mapOf("platform" to platformPath))
            return
        }

        // Find PTY4J jar in classpath
        val classLoader = Thread.currentThread().contextClassLoader

        // Search for native resources - PTY4J stores them under resources/com/pty4j/native/
        val nativeResources = listOf(
            "com/pty4j/native/$platformPath/$libName",
            "resources/com/pty4j/native/$platformPath/$libName",
            "$platformPath/$libName",
            "native/$platformPath/$libName"
        )

        var extracted = false
        for (resource in nativeResources) {
            try {
                val resourceStream = classLoader.getResourceAsStream(resource)
                if (resourceStream != null) {
                    resourceStream.use { input ->
                        libptyFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    libptyFile.setExecutable(true)
                    logger.debug(LogCategory.SYSTEM, "Extracted PTY4J native", mapOf(
                        "resource" to resource,
                        "target" to libptyFile.absolutePath
                    ))
                    extracted = true
                    break
                }
            } catch (e: Exception) {
                // Try next resource
            }
        }

        if (!extracted) {
            // Expected in normal operation: BossTerm/pty4j is intentionally NOT a host
            // dependency (see composeApp/build.gradle.kts). The terminal-tab plugin bundles
            // pty4j inside its own JAR and extracts its natives from its own classloader, so
            // the host classpath has no pty4j resources to extract. The pty4j.tmpdir /
            // pty4j.preferred.native.folder system properties set above are still honored by
            // the plugin. Logged at debug to avoid a misleading "terminal is broken" warning.
            logger.debug(LogCategory.SYSTEM, "PTY4J natives not on host classpath (handled by terminal-tab plugin)", mapOf(
                "platform" to platformPath,
                "searchedResources" to nativeResources.joinToString()
            ))
        }
    } catch (e: Exception) {
        logger.error(LogCategory.SYSTEM, "Error extracting PTY4J natives", error = e)
    }
}

/**
 * Set WM_CLASS for proper Linux desktop integration.
 * Must be called before any windows are created.
 * Requires JVM arg: --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED
 */
private fun setLinuxWMClass() {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return

    try {
        // Get toolkit instance (creates it if needed)
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name == "sun.awt.X11.XToolkit") {
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(toolkit, "BOSS")
        }
    } catch (e: Exception) {
        System.err.println("Could not set WM_CLASS: ${e.message}")
    }
}
