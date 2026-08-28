package ai.rever.boss

import BossTheme
import ai.rever.boss.cli.CLICommandHandler
import ai.rever.boss.cli.createBossCLI
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.ChromiumDownloadContent
import ai.rever.boss.config.ChromiumAutoDownloader
import ai.rever.boss.crash.CrashHandler
import ai.rever.boss.crash.RENDER_RECOVERY_TOAST_MILLIS
import ai.rever.boss.crash.RenderCrashPolicy
import ai.rever.boss.crash.RenderRecoveryToaster
import ai.rever.boss.crash.WindowExceptionRoute
import ai.rever.boss.crash.decideWindowExceptionRoute
import ai.rever.boss.crash.hasFatalCause
import ai.rever.boss.crash.noteRecoveryOutcome
import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.performance.PerformanceDataProviderImpl
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.sandbox.ui.PluginCrashInterceptor
import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.services.passkey.PasskeyPlatformInit
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.OsOpenArguments
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.WindowsProtocolHandler
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
import com.github.ajalt.clikt.core.main
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Window
import java.io.File
import javax.swing.JPopupMenu
import kotlin.system.exitProcess

private val logger = BossLogger.forComponent("Main")

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
 * ending the app. `TerminalTabPluginAPIImpl.setPendingSidebarCommand` recursed
 * into itself from a click and took BOSS down that way, with all ~1024 surviving
 * frames naming the plugin.
 *
 * Recorded through `recordCrash` rather than `recordRenderFault`: unlike
 * [PluginRenderRecovery]'s narrowing loop, which quarantines a *suspect* and must
 * not close somebody's tab on a guess, this is a plugin we can name — from the
 * host's own execution-boundary tag, or from a stack made of nothing else.
 *
 * The window is kept. No repaint: nothing here rebuilt the scene, and repainting
 * is what must not happen after a stack overflow.
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

/**
 * Point macOS's own chrome at the BOSS theme, before AWT starts.
 *
 * macOS draws the traffic lights itself, from the window's NSAppearance, and nothing the app
 * paints changes them. The INACTIVE ones - what an unfocused window shows - are pale in a
 * dark-appearance window, so a light BOSS theme in a dark-appearance window loses them against
 * its own light chrome. The active ones are always red/amber/green, which is why the bug is only
 * visible when the window is not focused.
 *
 * `apple.awt.application.appearance` is the property that decides it. The build passes
 * `=system`, which ties the window to the macOS setting rather than to the theme - fine while the
 * two agree and wrong the moment they do not.
 *
 * **Timing is the whole thing.** It is read once, when AWT creates the NSApplication, so this has
 * to run before anything touches AWT. `AWTKeyboardInterceptor.install()` does, a few hundred
 * lines below, and so does every window. Logging above it does not, which is why this sits
 * directly under it. Per-window client properties were tried first and do nothing:
 * `apple.awt.windowAppearance` is not among the properties this JDK reads.
 *
 * **Known limit, stated because somebody will hit it**: switching themes at runtime does not move
 * the lights. The property has already been read by then, and there is no supported way to change
 * an NSAppearance from Java afterwards. It takes a restart.
 */
private fun applyMacAppearanceFromTheme() {
    if (!SystemUtils.isMacOS) return

    // Reads a small JSON file and touches no UI toolkit, which is what makes it safe this early.
    ai.rever.boss.theme.AppThemeSettingsManager
        .ensureInitialized()

    val theme = BossThemeController.current
    val appearance =
        if (theme.isLight) {
            "NSAppearanceNameAqua"
        } else {
            "NSAppearanceNameDarkAqua"
        }
    System.setProperty("apple.awt.application.appearance", appearance)

    // Logged because the failure is invisible from inside the app: the lights are drawn by macOS,
    // and the only way to tell "the theme was read too early" from "macOS ignored us" is to see
    // which theme this ran with. Costs one line at startup.
    BossLogger
        .forComponent("MacAppearance")
        .info(
            LogCategory.SYSTEM,
            "Window appearance set from theme",
            mapOf("themeId" to theme.id, "isLight" to theme.isLight.toString(), "appearance" to appearance),
        )
}

fun main(args: Array<String>) {
    // Codex invokes this headless credential helper. Handle it before AWT,
    // plugins, logging, or the single-instance lock so stdout stays token-only.
    if (ai.rever.boss.llm.RisaLlmTokenCommand
            .isRequested(args)
    ) {
        exitProcess(
            ai.rever.boss.llm.RisaLlmTokenCommand
                .execute(),
        )
    }

    val startupBeganMs = System.currentTimeMillis()

    // Logging FIRST, before anything that can log. Everything below this line does:
    // setLinuxWMClass and setupNativeLibraryPaths both log, applyToSystemProperties emits the
    // audit trail of which Chromium flags this session runs with, ChromiumFlagsSettingsManager's
    // init warns about a corrupt settings file, and the Skiko block warns about an unrecognised
    // backend. Configuring the level afterwards meant none of them respected BOSS_LOG_LEVEL - and
    // the flag audit is the line most worth being able to turn up.
    //
    // Safe this early, checked rather than assumed: configureFromEnvironment only reads env and
    // system properties, and initialize() only registers a shutdown hook. Neither resolves a path,
    // so setupNativeLibraryPaths reassigning java.io.tmpdir below cannot affect them - file
    // logging is opt-in through configure() with an explicit path.
    BossLogger.configureFromEnvironment()
    BossLogger.initialize() // Register shutdown hook for log flushing

    applyMacAppearanceFromTheme()

    // Serve credential brokers to plugins. Registered from here rather than from
    // BossAppStartupEffects because the implementation exchanges a Supabase session over
    // HTTP and so lives in desktopMain, while the PluginContext that exposes it is
    // commonMain. Safe this early: the object holds no state and touches nothing until a
    // plugin actually asks for a broker.
    ai.rever.boss.services.llm.BrokeredCredentialAccess
        .initialize(ai.rever.boss.llm.BrokeredCredentialProviderImpl)

    // Same arrangement, for the dialog that offers a way out of a plugin version floor: the
    // remedies reach the app updater, the plugin store and the plugins directory, all desktopMain,
    // while the dialog is mounted from commonMain. Registered BEFORE any plugin loads, because the
    // refusal this exists for happens during startup plugin loading - a refusal recorded before
    // this runs would sit in the registry with nothing able to act on it.
    ai.rever.boss.components.plugin.PluginLoadRemedyAccess
        .initialize(ai.rever.boss.components.plugin.DesktopPluginLoadRemedyResolver)

    // Warm the two settings singletons that load their file synchronously in `init`
    // (WorkspaceSettingsManager and FocusModeSettingsManager). Both must be readable the
    // instant a startup effect asks - the workspace one decides which layout a window opens
    // on, and losing that read to an async load is the bug this replaced - but their first
    // accessor would otherwise be a composition-thread LaunchedEffect, putting mkdirs, a
    // read, and on the first launch after an upgrade a migration write, on the UI thread.
    //
    // This narrows that rather than eliminating it, and is correct either way: JVM class
    // initialisation is locked, so a window that gets there first simply waits for this to
    // finish instead of racing it. Launched here, hundreds of milliseconds of setup before
    // any window composes, it has essentially always finished by then.
    startupScope.launch(Dispatchers.IO) {
        ai.rever.boss.components.workspaces.WorkspaceSettingsManager.currentSettings
        ai.rever.boss.focusmode.FocusModeSettingsManager.currentSettings
    }

    // Set WM_CLASS for Linux desktop integration (must be before any AWT init)
    setLinuxWMClass()

    // Set up proper temp directories for native libraries
    setupNativeLibraryPaths()

    // Publish the Chromium flags chosen in Settings > Browser Engine as system properties, so
    // every existing ConfigLoader read site picks them up without knowing settings exist.
    // Position is load-bearing and this is as early as it can go: the Skiko block immediately
    // below reads BOSS_SKIKO_RENDER_API before AWT initialises, and JxBrowserConfig.renderingMode
    // is a `by lazy` that caches the first answer for the life of the process. An environment
    // variable still outranks anything published here - see applyToSystemProperties.
    ai.rever.boss.config.ChromiumFlagsSettingsManager
        .applyToSystemProperties()

    // The swipe setting, published for the browser PLUGIN rather than for a ConfigLoader read site.
    // It runs in this process but in another repo, and PluginContext.settingsProvider only opens
    // the Settings window - it reads nothing - so a system property is the only channel the two
    // halves of this gesture share. Republished whenever the setting changes, because it is read
    // per gesture and must not need a relaunch.
    ai.rever.boss.config.SwipeNavSettingsManager
        .publish()

    // Opt-in override for the Compose UI's own rendering backend (Skiko) - separate from the
    // BROWSER's rendering mode in JxBrowserConfig. Lets a backend be A/B'd on a real machine
    // without a rebuild: pin DIRECT3D, or confirm the GPU-less Windows RDP/VM cohort that falls
    // back to software. UNSET by default so Skiko keeps its own auto-detection - forcing a
    // backend that cannot initialize would break exactly the machines a pin is meant to help.
    // Must run before any AWT/Skiko init, hence its position here.
    //   BOSS_SKIKO_RENDER_API = DIRECT3D | OPENGL | METAL | SOFTWARE_FAST | SOFTWARE
    // Validated against an allowlist, not forwarded raw: this runs before AWT/Skiko init, so an
    // unrecognised value surfaces as a startup crash with no BOSS log line to explain it - on
    // exactly the GPU-less RDP/VM machines the pin exists to help. Unknown values are ignored with
    // a warning, matching how the other tunables added alongside this behave.
    ai.rever.boss.config.ConfigLoader
        .getConfig("BOSS_SKIKO_RENDER_API")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { requested ->
            // Shared with the Settings dropdown, so it can never offer a value rejected here.
            val known = ai.rever.boss.config.ChromiumFlagKeys.SKIKO_RENDER_APIS
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

    // Disable lightweight popups for HARDWARE_ACCELERATED rendering mode (#258)
    // This ensures Swing popup menus (context menus) appear above the browser view
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)

    // Uninstall hook (Windows): `BOSS.exe --unregister-protocol` removes the boss://
    // handler that WindowsProtocolHandler registers at runtime, so uninstalling does not
    // leave a registry handler pointing at a deleted executable. Handled before any
    // app/single-instance initialization so it stays callable from an installer action.
    // Exit codes are documented on unregisterProtocolExitCode().
    if (args.contains("--unregister-protocol")) {
        exitProcess(WindowsProtocolHandler.unregisterProtocolExitCode())
    }

    // Install crash handler after logger is ready
    CrashHandler.install()

    // Install plugin crash interceptor (chains after CrashHandler to catch plugin-specific crashes)
    ai.rever.boss.plugin.sandbox.ui
        .installCrashInterceptor()

    // Teach the attribution boundary how to identify a plugin classloader for real.
    // Without this it falls back to asking the loader for its own id, which a plugin
    // that defines classes through a nested loader of its own could answer with
    // somebody else's - and attribution now decides which plugin gets disabled and
    // written out of installed.json. A type check against a class only the host
    // constructs cannot be forged. Installed before any plugin loads.
    ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
        .installPluginIdResolver(
            ai.rever.boss.crash
                .hostPluginIdResolver(),
        )

    // Register notification callback for plugin crashes.
    // Tab closing is handled directly by PluginCrashRegistry via the closeAction
    // registered in BossMainPanelContent. This callback only shows the status message.
    ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry.onCrashNotify = { pluginId, error ->
        // Both halves are plugin-controlled and share one status-bar slot: the id
        // comes from a manifest, and the message from plugin code. A newline or a
        // few hundred characters in either pushes the rest of the line out of view.
        val errorMsg =
            (error.message ?: error.javaClass.simpleName)
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .take(60)
        ai.rever.boss.components.bars.horizontal.StatusMessageManager.showMessage(
            "Plugin '${ai.rever.boss.crash.displayPluginId(pluginId)}' crashed: $errorMsg",
            durationMs = 8000,
        )
    }

    logger.info(LogCategory.SYSTEM, "BOSS starting up")

    // Initialize microkernel infrastructure (no-op in MONOLITH mode, which is default)
    // On Windows ARM64, boss-ipc/boss-process-manager modules are excluded (no protoc),
    // so KernelBootstrap may not be available — silently skip.
    val kernelBootstrap: Any? =
        try {
            val bossMode =
                System.getenv("BOSS_MODE")
                    ?: ai.rever.boss.config.ConfigLoader
                        .getConfig("BOSS_MODE")
            if (bossMode == "KERNEL") {
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

    // Single-instance check: ensure only one BOSS instance runs
    // On Windows, this prevents multiple windows when clicking deep links
    if (!SingleInstanceManager.acquireLock()) {
        logger.info(LogCategory.SYSTEM, "Another BOSS instance is already running")

        // Everything the OS is asking this launch to open, as `boss://` links.
        // File paths count, not only URL schemes: on Windows and Linux a
        // double-clicked file arrives as a path in argv, and this branch used to
        // ignore it and exit 0 with "No URL to send", so the file never opened
        // while BOSS was running. See OsOpenArguments.
        val deepLinks = OsOpenArguments.deepLinksFrom(args)

        if (deepLinks.isNotEmpty()) {
            logger.info(
                LogCategory.SYSTEM,
                "Sending open requests to existing instance",
                mapOf("count" to deepLinks.size),
            )

            // The origin is stated, not assumed: a `boss://` argument in this
            // process's argv is how the OS protocol handler delivers a URL
            // somebody asked it to open, so it is forwarded as external. The
            // running instance takes that label rather than inferring anything
            // from the fact that this process could present the channel token.

            // Try to send with retry logic (important for auth deep links during sign-in)
            // Note: runBlocking is acceptable here as this runs during pre-UI initialization,
            // before the Compose application starts. No UI thread exists yet to block.
            val maxRetries = 3

            fun forward(link: String): Boolean {
                for (attempt in 1..maxRetries) {
                    if (SingleInstanceManager.sendToExistingInstance(link, DeepLinkOrigin.EXTERNAL)) {
                        logger.info(LogCategory.SYSTEM, "URL sent successfully", mapOf("attempt" to attempt))
                        return true
                    }
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Failed to send URL",
                        mapOf(
                            "attempt" to attempt,
                            "maxRetries" to maxRetries,
                        ),
                    )
                    if (attempt < maxRetries) {
                        // Use coroutine delay instead of Thread.sleep to avoid blocking
                        kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.delay(500)
                        }
                    }
                }
                return false
            }

            // Every link is attempted, and success means every one landed.
            // `fold` rather than `all`, which would short-circuit and silently
            // drop the rest of a multi-file selection after one failure.
            val success = deepLinks.fold(true) { acc, link -> forward(link) && acc }

            if (success) {
                exitProcess(0)
            } else {
                // IPC failed after retries - DO NOT create new window
                // This prevents duplicate windows during sign-in
                logger.error(
                    LogCategory.SYSTEM,
                    "Could not send URL to existing instance after retries",
                    mapOf(
                        "maxRetries" to maxRetries,
                    ),
                )
                exitProcess(1)
            }
        } else {
            logger.info(LogCategory.SYSTEM, "No URL to send - existing BOSS window should be visible")
            exitProcess(0)
        }
    }

    // Brand any window BOSS does not compose itself - JxBrowser's own Swing dialogs, JFileChooser,
    // a frame opened by a plugin - so none of them shows the JDK's default Java icon on Windows.
    // Every window this app opens sets its own icon; this is only the net under them.
    //
    // Position is fenced on both sides. Registering an AWT event listener initialises the toolkit,
    // so this cannot go up beside setLinuxWMClass: the ChromiumFlagsSettingsManager and
    // BOSS_SKIKO_RENDER_API blocks up there have to publish their system properties before AWT/Skiko
    // reads them, and skiko.renderApi in particular is read at init and never again. It also has to
    // stay below the two paths that exit without ever showing a window - `--unregister-protocol`
    // (an installer action) and the single-instance deep-link forward - which is why it is here
    // rather than beside JPopupMenu.setDefaultLightWeightPopupEnabled: neither should be made to
    // start a window system to do its job. Everything from here on is a session that gets a window.
    DefaultWindowIcon.install()

    // Create ~/BossProjects before a window asks for it. Every no-project path now resolves
    // there instead of to the home directory (see DefaultWorkingDirectory), and the first of
    // them is a window opening a terminal - creating it on demand would put the mkdirs on the
    // thread doing that. Best-effort and idempotent: ensureDefaultDirectory() creates the directory itself if
    // this has not finished, or did not work.
    //
    // Here for the same reason the icon install above is - "everything from here on is a
    // session that gets a window". Up with the other startup warm-ups it would run for
    // `--unregister-protocol` and for a deep-link forward, both of which exitProcess after
    // doing something headless, and neither should leave a folder in the user's home behind.
    //
    // Unconditional, on every platform, and that is the decision rather than an oversight: a
    // browser-only user on Windows has no TCC prompts to avoid and may never create a
    // project, so they get an empty folder they did not ask for. Gating it on macOS would
    // buy that user nothing back - the placeholder fallback and every terminal resolve there
    // on all three platforms, so the directory gets created on first use anyway - while
    // giving the two platforms different startup states to reason about.
    startupScope.launch(Dispatchers.IO) {
        DefaultWorkingDirectory.ensureDefaultDirectory()
    }

    // Register shutdown hook to release the single-instance lock AND close browser engine
    Runtime.getRuntime().addShutdownHook(
        Thread {
            try {
                // Save "Last Session" for the exits that never dispose a Compose
                // composition, so the window-dispose save never runs: macOS
                // app-menu Quit / Cmd+Q (the JDK's default QuitStrategy is
                // NORMAL_EXIT, i.e. System.exit(0) - see com.apple.eawt
                // ._AppEventHandler, and nothing here opts into
                // CLOSE_ALL_WINDOWS), ApplicationRestarter's exitProcess paths
                // (including quit-for-update), and SIGTERM.
                //
                // Runs first in the hook: window state is still live here, and
                // BossLogger is shut down further down. No-op when a window
                // dispose already saved this session (#19).
                ai.rever.boss.app.LastSessionCoordinator.instance
                    .saveOnProcessExit()
            } catch (e: Exception) {
                System.err.println("Error saving Last Session on exit: ${e.message}")
            }
            try {
                // Stop performance monitoring to cancel background coroutines
                ai.rever.boss.performance.PerformanceMonitor
                    .stop()
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
                ai.rever.boss.cache.HighQualityFaviconService
                    .close()
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
                ai.rever.boss.updater.AppUpdateRealtimeService.instance
                    .dispose()
            } catch (e: Exception) {
                System.err.println("Error stopping app update realtime: ${e.message}")
            }
            try {
                // App-level updater teardown: the ONLY place the process-wide
                // updater is shut down. Window close releases its UpdateHandle
                // instead, so closing one window no longer stops periodic checks
                // or cancels a download for the windows still open (#19, #37).
                ai.rever.boss.updater.UpdateCoordinator.instance
                    .shutdown()
            } catch (e: Exception) {
                System.err.println("Error shutting down updater: ${e.message}")
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
        },
    )

    logger.info(LogCategory.SYSTEM, "Successfully acquired single-instance lock")

    // Route app overlays (context menus, dropdowns, tooltips) through heavyweight windows when the
    // browser is GPU-composited. In HARDWARE_ACCELERATED mode the JxBrowser view is a heavyweight
    // native surface that paints above lightweight Compose, so an ordinary Compose Popup renders
    // BEHIND the page. Dormant - a no-op - wherever OFF_SCREEN is the mode (macOS, Linux), so the
    // unchanged platforms cannot regress. See JxBrowserConfig.renderingMode and
    // benchmarks/speedometer/win/WINDOWS.md.
    ai.rever.boss.components.overlays.OverlayConfig.heavyweightPopup =
        { onDismiss, anchorInWindow, anchoring, popupOffset, focusable, popupContent ->
            ai.rever.boss.components.overlays
                .HeavyweightPopup(onDismiss, anchorInWindow, anchoring, popupOffset, focusable, popupContent)
        }
    ai.rever.boss.components.overlays.OverlayConfig.heavyweightModal = { properties, onDismiss, modalContent ->
        ai.rever.boss.components.overlays
            .HeavyweightModal(properties, onDismiss, modalContent)
    }
    // Lets host UI in commonMain offer to install a plugin it needs. The installer is built in
    // desktopMain because resolving and downloading a plugin is a desktop concern; see
    // MissingPluginOffer for why the seam exists at all.
    ai.rever.boss.components.plugin.MissingPluginOffer.installerFactory = { manager ->
        ai.rever.boss.plugin.MissingDependencyReporter
            .installerFor(manager)
    }

    ai.rever.boss.components.overlays.OverlayConfig.heavyweightTooltip = { text ->
        ai.rever.boss.components.overlays.SwingTooltip
            .show(text)
    }
    ai.rever.boss.components.overlays.OverlayConfig.hideHeavyweightTooltip = {
        ai.rever.boss.components.overlays.SwingTooltip
            .hide()
    }
    ai.rever.boss.components.overlays.OverlayConfig.heavyweightHud = { alignment, hudContent ->
        ai.rever.boss.components.overlays
            .HeavyweightHud(alignment, hudContent)
    }
    ai.rever.boss.components.overlays.OverlayConfig.heavyweightGhost = { size, hotspot, ghostContent ->
        ai.rever.boss.components.overlays
            .HeavyweightGhost(size, hotspot, ghostContent)
    }
    ai.rever.boss.components.overlays.OverlayConfig.heavyweightCorner = {
        alignment,
        initialSize,
        inset,
        focusable,
        regionInWindow,
        cornerContent,
        ->
        ai.rever.boss.components.overlays
            .HeavyweightCorner(alignment, initialSize, inset, focusable, regionInWindow, cornerContent)
    }
    // plugin-ui-core owns the modal registry (plugins draw dialogs too) and depends on nothing but
    // Compose, so it cannot log. Give it this logger instead: the condition it reports is a dialog
    // that silently fell back to lightweight and is now hidden behind the page, which is invisible
    // on screen and would otherwise have to be diagnosed from a screenshot.
    ai.rever.boss.plugin.ui.BossOverlayHost.diagnostics = { message ->
        logger.warn(LogCategory.UI, message)
    }
    ai.rever.boss.components.overlays.OverlayConfig.useHeavyweightPopups =
        ai.rever.boss.config.JxBrowserConfig.renderingMode ==
        com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED

    // Proactively clean up stale JxBrowser lock files from previous sessions
    // This is especially important for debug mode where shutdown hooks may not run
    try {
        ai.rever.boss.plugin.browser.FluckEngine
            .proactiveCleanupOnStartup()
    } catch (e: Exception) {
        logger.warn(LogCategory.SYSTEM, "Proactive browser lock cleanup failed", error = e)
    }

    // Decide whether the installed engine is usable BEFORE anything boots it.
    //
    // This has to precede every engine-creating call below, and the ordering is
    // load-bearing rather than cosmetic. JxBrowser resolves its native toolkit
    // under Versions/<VersionInfo.chromiumVersion()>, baked into the jar, so an
    // engine directory left over from an older app version fails the native load
    // with an UnsatisfiedLinkError. That is recoverable — isChromiumInstalled
    // spots the mismatch and the download UI below repairs it — but only if the
    // check runs first. With the check downstream of the pre-warm and of
    // PasskeyPlatformInit, both booted against the stale engine and threw before
    // the repair path was ever reached, so shipping a JxBrowser bump broke the
    // browser for every existing install instead of prompting a download.
    //
    // promotePendingInstall must also stay ahead of any engine creation: it
    // renames the engine directory, which cannot be done safely once a running
    // engine holds files inside it.
    ChromiumAutoDownloader.promotePendingInstall()

    // Ask the question we are about to act on: will an engine actually boot?
    //
    // isChromiumInstalled() answers a narrower one — "does the *cache* hold the
    // right engine?" — which is the right input for deciding whether to download,
    // but wrong for deciding whether to boot. FluckEngine prefers a bundled engine
    // from the app image and only falls back to the cache, so a release that
    // bundles one could pass the cache check and still boot something else, or fail
    // it and skip the pre-warm despite a perfectly good bundled engine
    // (BossConsole#121). resolveEngineDir applies the same priority order and the
    // same version check the boot will.
    // One read of the cache's health, shared by the resolver and the decision so
    // the two can never disagree about it.
    val cacheHealthy = ChromiumAutoDownloader.isChromiumInstalled()
    val hasUsableEngine =
        ai.rever.boss.plugin.browser.FluckEngine
            .hasUsableEngine(cacheHealthy)
    val engineAction =
        ai.rever.boss.plugin.browser.FluckEngine
            .engineStartupAction(hasUsableEngine, cacheHealthy)

    // Named in the download dialog: it blocks the whole app for a several-hundred-MB
    // fetch, and which engine it is turns out to be the first thing anyone asks when
    // it appears unexpectedly — an engine mismatch is exactly what triggers it.
    val engineLabel = "BOSS Browser Engine ${ChromiumAutoDownloader.effectiveVersion}"

    val chromiumNeedsDownload =
        engineAction == ai.rever.boss.plugin.browser.FluckEngine.EngineStartupAction.Download
    when (engineAction) {
        ai.rever.boss.plugin.browser.FluckEngine.EngineStartupAction.BootAndReport -> {
            logger.error(
                LogCategory.SYSTEM,
                "Installed engine is healthy and stamped with the required version but is still " +
                    "unusable - the published archive does not match this build; not re-downloading",
                mapOf("required" to ChromiumAutoDownloader.effectiveVersion),
            )
        }

        ai.rever.boss.plugin.browser.FluckEngine.EngineStartupAction.Download -> {
            logger.info(
                LogCategory.SYSTEM,
                "No usable browser engine - will prompt for download",
                mapOf("required" to ChromiumAutoDownloader.effectiveVersion),
            )
        }

        ai.rever.boss.plugin.browser.FluckEngine.EngineStartupAction.Boot -> {
            Unit
        }
    }

    // Pre-warm the browser engine off the UI thread so the first browser tab
    // opens against an already-running Chromium instead of paying the full
    // engine boot inside its composition. Opt out with BOSS_BROWSER_PREWARM=false.
    //
    // Skipped when the engine needs downloading: pre-warming against a mismatched
    // directory cannot succeed, and its only effect is to raise the very error
    // the download is about to fix.
    // The "is there a usable engine" precondition is no longer applied here. It used to be, for
    // the BootAndReport case - no usable engine AND no download, where a boot could only burn an
    // attempt and set an error - and that reasoning still holds; it just belongs in the one gate
    // every caller passes through. prewarmDecision re-evaluates it, freshly rather than from this
    // startup-time snapshot, and logs which reason refused the call. Two copies of a precondition
    // are two things that can disagree.
    try {
        ai.rever.boss.plugin.browser.FluckEngine
            .prewarmInBackground()
    } catch (e: Exception) {
        logger.warn(LogCategory.SYSTEM, "Browser engine pre-warm failed to start", error = e)
    }

    // Parse CLI arguments if provided
    if (args.isNotEmpty()) {
        try {
            // What the OS is asking this cold start to open: URL-scheme args as
            // before, plus file paths, which Clikt cannot parse (it has `boss
            // file <path>`, no bare-path argument) and used to fail on with a
            // usage error - so a double-clicked file did nothing on a cold start.
            val osOpenRequests = OsOpenArguments.deepLinksFrom(args)

            if (osOpenRequests.isEmpty()) {
                // Not an OS open request, so it is the operator's CLI.
                logger.debug(LogCategory.SYSTEM, "Processing CLI arguments", mapOf("args" to args.joinToString(" ")))
                createBossCLI().main(args)
                // Commands are queued, continue with app initialization
            }
            // Otherwise these are links and files the OS wants opened;
            // `DeepLinkHandler.processCommandLineArgs` below is the single place
            // that processes them, so nothing is opened twice.
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "CLI error", error = e)
            // Don't exit - let the app start normally
            // CLI errors shouldn't prevent GUI from launching
        }
    }

    // Process command line arguments for deep links (Windows).
    // This first member access also triggers DeepLinkHandler's object init,
    // so no separate bare-reference "initialization" line is needed.
    DeepLinkHandler.processCommandLineArgs(args)

    // Install AWT keyboard interceptor to capture shortcuts before BossTerm
    // This ensures Cmd+N, Cmd+W, etc. work even when terminal has focus
    AWTKeyboardInterceptor.install()

    // Apply the persisted app theme before any UI composes, so the app opens
    // in the user's chosen look rather than flashing the default first.
    //
    // Not necessarily the FIRST such call any more: on macOS applyMacAppearanceFromTheme needs the
    // theme before AWT starts, so it initialises there and this one is a no-op. Kept because it is
    // the call every other platform relies on, and idempotent either way.
    ai.rever.boss.theme.AppThemeSettingsManager
        .ensureInitialized()

    // Initialize passkey service for desktop platforms
    PasskeyPlatformInit.initialize()

    // Initialize plugin store (remote repository, download cache, update manager)
    PluginStoreSetup.initialize()

    // Start app-update Realtime push (Supabase) so the app learns about new releases
    // instantly instead of polling; route events into the existing update manager.
    // Off the main thread: building the Supabase client is not needed for first paint.
    startupScope.launch {
        ai.rever.boss.updater.AppUpdateRealtimeService.instance.apply {
            onReleaseChanged = {
                // App-level trigger through the app-level owner.
                ai.rever.boss.updater.UpdateCoordinator.instance
                    .checkForUpdatesInBackground()
            }
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

    // Hand the tier's browser settings to plugins, which cannot see host classes. Before any
    // plugin loads, so fluck-browser sees it when it first builds a tab.
    ai.rever.boss.config.ResourceModeConfig
        .publishToPlugins()

    // Start performance monitoring from app startup — unless the resource tier says the
    // sampler's own overhead is not worth paying on this machine.
    if (ai.rever.boss.config.ResourceModeConfig.mode.backgroundSamplingEnabled) {
        ai.rever.boss.performance.PerformanceMonitor
            .start()
    } else {
        logger.info(
            LogCategory.SYSTEM,
            "Performance sampling disabled by the resource mode",
            mapOf("mode" to ai.rever.boss.config.ResourceModeConfig.mode.name),
        )
    }

    // Watch for the case the startup decision cannot see: a machine with plenty of installed
    // RAM that is nonetheless out of it, because of everything else the user is running.
    ai.rever.boss.performance.MemoryPressureWatchdog
        .start(startupScope)

    // Debug: Log environment info
    logger.debug(
        LogCategory.SYSTEM,
        "Environment info",
        mapOf(
            "cwd" to System.getProperty("user.dir"),
            "javaVersion" to System.getProperty("java.version"),
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        ),
    )

    // NOTE: there used to be an "API key availability" probe here, logging whether four LLM
    // provider environment variables were set. It is gone because the host no longer owns any
    // provider list — the secret-manager plugin does, and it knows seven providers plus custom,
    // resolving each from `-D` properties, `launchctl getenv` and `~/.boss/env_vars` as well as
    // the environment. A hardcoded four-name probe here could only ever disagree with it. The
    // one credential the host itself still resolves is the AI-repair key, and
    // SelfHealingSettings reports its own readiness.

    // chromiumNeedsDownload was resolved far earlier, above the first engine boot
    // — see the comment there for why that ordering matters.

    // Create initial window BEFORE application{} to prevent auto-recreation
    // This runs once on startup, not during recomposition
    // Note: Window creation is deferred if Chromium download is needed
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
        val (platformPath, libName) =
            when {
                osName.contains("mac") || osName.contains("darwin") -> {
                    "darwin" to "libpty.dylib"
                }

                osName.contains("linux") -> {
                    val arch =
                        when {
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
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Unsupported platform for PTY4J",
                        mapOf(
                            "os" to osName,
                            "arch" to osArch,
                        ),
                    )
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
        val nativeResources =
            listOf(
                "com/pty4j/native/$platformPath/$libName",
                "resources/com/pty4j/native/$platformPath/$libName",
                "$platformPath/$libName",
                "native/$platformPath/$libName",
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
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Extracted PTY4J native",
                        mapOf(
                            "resource" to resource,
                            "target" to libptyFile.absolutePath,
                        ),
                    )
                    extracted = true
                    break
                }
            } catch (e: Exception) {
                // Try next resource
                logger.debug(
                    LogCategory.SYSTEM,
                    "PTY4J native extraction failed for resource - trying next",
                    mapOf("resource" to resource, "error" to e.toString()),
                )
            }
        }

        if (!extracted) {
            // Expected in normal operation: BossTerm/pty4j is intentionally NOT a host
            // dependency (see composeApp/build.gradle.kts). The terminal-tab plugin bundles
            // pty4j inside its own JAR and extracts its natives from its own classloader, so
            // the host classpath has no pty4j resources to extract. The pty4j.tmpdir /
            // pty4j.preferred.native.folder system properties set above are still honored by
            // the plugin. Logged at debug to avoid a misleading "terminal is broken" warning.
            logger.debug(
                LogCategory.SYSTEM,
                "PTY4J natives not on host classpath (handled by terminal-tab plugin)",
                mapOf(
                    "platform" to platformPath,
                    "searchedResources" to nativeResources.joinToString(),
                ),
            )
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
