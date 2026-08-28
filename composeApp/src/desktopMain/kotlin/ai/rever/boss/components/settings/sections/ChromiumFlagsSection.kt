package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsNumberInput
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTextArea
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.config.ChromiumFlagKeys
import ai.rever.boss.config.ChromiumFlagsSettings
import ai.rever.boss.config.ChromiumFlagsSettingsManager
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.ApplicationRestarter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Settings > Browser Engine: the Chromium flags the embedded engine launches with.
 *
 * Split out of [BrowserEngineSettings] rather than appended to it — that composable owns
 * the engine *version* (download, stage, restart), this one owns the engine's *options*,
 * and the two share nothing but the screen they render on.
 *
 * Three things shape the whole design here:
 *
 *  1. **Nothing applies live.** Chromium's options are fixed when the engine is built,
 *     once per process, and the heavyweight-overlay routing is decided at startup from
 *     the rendering mode. So every control writes a preference for the next launch and
 *     the screen offers a restart — it never pretends to have changed the running
 *     browser.
 *  2. **An environment variable still wins**, because the operator's one-session
 *     override predates this screen and should keep working. A row the environment has
 *     taken over says so and disables itself, since silently doing nothing is the worse
 *     failure: the user concludes the feature is broken.
 *  3. **"No opinion" is a real state, distinct from off.** A user who has never opened
 *     this screen must get byte-identical engine options to before it existed, so a
 *     control at its shipped value stores null rather than an explicit value. That also
 *     keeps "Reset to defaults" meaningful instead of always available.
 */
@Composable
fun ChromiumFlagsSections() {
    val settings by ChromiumFlagsSettingsManager.currentSettings.collectAsState()
    val scope = rememberCoroutineScope()

    // Fire-and-forget: the StateFlow updates synchronously inside updateSettings, so the UI
    // reflects the change immediately and the disk write is the only asynchronous part.
    // Remembered so it stays the same instance across recompositions — every section below takes
    // it as a parameter, and a fresh lambda each pass would recompose all of them on any change.
    val save =
        remember(scope) {
            { transform: (ChromiumFlagsSettings) -> ChromiumFlagsSettings ->
                scope.launch { ChromiumFlagsSettingsManager.updateSettings(transform) }
                Unit
            }
        }

    val os = remember { System.getProperty("os.name").orEmpty().lowercase() }
    val arch = remember { System.getProperty("os.arch").orEmpty().lowercase() }
    val isMacAarch64 = os.contains("mac") && arch.contains("aarch64")
    val isLinux = os.contains("linux")
    val isWindows = os.contains("windows")

    var confirming by remember { mutableStateOf<PendingDangerousFlag?>(null) }
    var confirmingRestart by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }

    RenderingSection(settings, save)
    Spacer(modifier = Modifier.height(16.dp))
    PerformanceSection(settings, save, isMacAarch64 = isMacAarch64, isLinux = isLinux, isWindows = isWindows)
    Spacer(modifier = Modifier.height(16.dp))
    NetworkSection(settings, save)
    Spacer(modifier = Modifier.height(16.dp))
    AdvancedSection(settings, save)
    Spacer(modifier = Modifier.height(16.dp))
    DangerZoneSection(settings, save, onConfirm = { confirming = it })
    Spacer(modifier = Modifier.height(16.dp))
    EffectiveCommandLineSection(settings, os, arch)
    Spacer(modifier = Modifier.height(16.dp))

    ApplySection(
        settings = settings,
        onRestart = { confirmingRestart = true },
        onReset = { confirmingReset = true },
    )

    confirming?.let { pending ->
        ConfirmationDialog(
            title = pending.title,
            message = pending.message,
            confirmText = pending.confirmText,
            onConfirm = {
                save { current -> pending.apply(current) }
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }

    if (confirmingRestart) {
        ConfirmationDialog(
            title = "Restart Required",
            message =
                "BOSS will close and reopen to apply the browser engine flags. " +
                    "Your tabs are restored; running terminal processes end.",
            confirmText = "Restart Now",
            onConfirm = {
                confirmingRestart = false
                ApplicationRestarter.scheduleRestart(delayMillis = 500)
            },
            onDismiss = { confirmingRestart = false },
        )
    }

    if (confirmingReset) {
        ConfirmationDialog(
            title = "Reset engine flags?",
            message =
                "Every flag on this screen returns to its default, including the DevTools remote " +
                    "debugging port and the Chromium sandbox opt-out. Takes effect on the next launch.",
            confirmText = "Reset",
            onConfirm = {
                confirmingReset = false
                scope.launch { ChromiumFlagsSettingsManager.resetToDefault() }
            },
            onDismiss = { confirmingReset = false },
        )
    }
}

/**
 * Whether restarting would actually apply anything, i.e. any published key resolves differently
 * from what this process booted with once the environment has had its say.
 *
 * The naive `settings != bootSettings` offers a restart whenever the stored object differs - even
 * for a key an environment variable owns, where the next launch resolves to exactly what is
 * running now and the user loses their session for nothing.
 */
internal fun restartWouldChangeAnything(
    settings: ChromiumFlagsSettings,
    // Injectable purely so tests are hermetic. bootSettings is a val read from the DEFAULT path at
    // object construction, before any test can redirect settingsFile, so a test comparing against
    // it depends on whether the developer's machine happens to have saved a Chromium setting -
    // including one written by the very bug an earlier commit here fixed. Production never passes
    // this.
    boot: ChromiumFlagsSettings = ChromiumFlagsSettingsManager.bootSettings,
): Boolean {
    if (settings == boot) return false
    // Settings-only fields have no config key, so previewValue cannot speak for them; any change
    // to one is a real change. Published keys are compared as the next launch would resolve them.
    val publishedDiffers =
        ChromiumFlagKeys.PUBLISHED.any { key ->
            val now = ChromiumFlagsSettingsManager.previewValue(settings, key)
            val before = ChromiumFlagsSettingsManager.previewValue(boot, key)
            if (key == ChromiumFlagKeys.SKIA_GRAPHITE) {
                // Compared RESOLVED, not raw. Graphite's default follows the rendering mode, so
                // toggling it off and back on leaves an explicit "true" where boot held null -
                // different strings, identical behaviour - and offering a restart for that is a
                // lost session for no effect. Every other key resolves from its raw value alone.
                FluckEngine.resolveSkiaGraphite(now, nextRenderingMode(settings)) !=
                    FluckEngine.resolveSkiaGraphite(before, nextRenderingMode(boot))
            } else {
                now != before
            }
        }
    // Settings-only fields have no config key, so previewValue cannot speak for them and any
    // change to one is a real change.
    //
    // Names the SETTINGS-ONLY fields rather than the published ones, which is the safer list to
    // restate: the same six are already enumerated in ChromiumFlagsSettingsTest, where a test
    // asserts fieldCount == PUBLISHED.size + settingsOnly.size. So adding a field to
    // ChromiumFlagsSettings fails that test until it is categorised, which is the guard the
    // previous version of this function (restating the eight published names, unguarded) lacked.
    val settingsOnlyDiffers =
        boot.copy(
            diskCacheMb = settings.diskCacheMb,
            noPings = settings.noPings,
            disableDomainReliability = settings.disableDomainReliability,
            disableWinOcclusion = settings.disableWinOcclusion,
            enableVaapi = settings.enableVaapi,
            remoteDebuggingPort = settings.remoteDebuggingPort,
        ) != boot
    return publishedDiffers || settingsOnlyDiffers
}

/**
 * Restart and reset, the two actions that act on the screen as a whole.
 *
 * The restart row appears only when the current settings differ from the ones this process
 * booted with, which is the exact condition under which a restart would change anything.
 */
@Composable
private fun ApplySection(
    settings: ChromiumFlagsSettings,
    onRestart: () -> Unit,
    onReset: () -> Unit,
) {
    SettingsSection(title = "Apply") {
        if (restartWouldChangeAnything(settings)) {
            SettingsButtonRow(
                label = "Changes are waiting for a restart",
                buttonText = "Restart BOSS",
                onClick = onRestart,
                isDestructive = true,
                description =
                    "The engine's options are fixed when it starts, so nothing above has changed the " +
                        "running browser yet. BOSS reopens with your tabs restored; running terminal processes end.",
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        SettingsButtonRow(
            label = "Reset every flag to its default",
            buttonText = "Reset",
            onClick = onReset,
            enabled = !settings.isDefault,
            description =
                if (settings.isDefault) {
                    "Every flag is already at its default."
                } else {
                    "Clears all of the above, including the DevTools port and the sandbox opt-out."
                },
        )
    }
}

/**
 * A flag that weakens the browser's security, so it is applied only after the user has
 * read what it does.
 *
 * Modelled as data rather than a boolean per flag because the confirmation is the same
 * mechanism each time and only the wording and the resulting edit differ — and because a
 * single nullable holder makes "two confirmations open at once" unrepresentable.
 */
private data class PendingDangerousFlag(
    val title: String,
    val message: String,
    val confirmText: String,
    val apply: (ChromiumFlagsSettings) -> ChromiumFlagsSettings,
)

/**
 * The note a row shows when the environment has taken it over, or null.
 *
 * Without this a user with BOSS_RENDERING_MODE exported changes the dropdown, sees it
 * move, restarts, and gets the old behaviour — with nothing anywhere to explain why.
 */
private fun envNote(key: String): String? =
    ChromiumFlagsSettingsManager.envOverride(key)?.let { value ->
        "Overridden by $key=$value in this session's environment. Unset it to use this setting."
    }

/**
 * The dropdown label for a stored rendering-mode value.
 *
 * Routed through the parser rather than a null check. The UI only ever writes null or
 * "OFF_SCREEN", but "HARDWARE" is documented as an honoured spelling, so a hand-edited file
 * saying that would otherwise display as Off-screen while the app ran Hardware.
 */
private fun renderingModeLabel(
    stored: String?,
    hostOs: String,
): String =
    if (JxBrowserConfig.resolveRenderingMode(stored, hostOs) ==
        com.teamdev.jxbrowser.engine.RenderingMode.OFF_SCREEN
    ) {
        OFF_SCREEN_LABEL
    } else {
        HARDWARE_LABEL
    }

private const val HARDWARE_LABEL = "Hardware accelerated (default)"

/** Internal, not private: the command-line report names the mode a restart would switch to. */
internal const val OFF_SCREEN_LABEL = "Off-screen"

@Composable
private fun RenderingSection(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
) {
    SettingsSection(
        title = "Rendering",
        description =
            "How the embedded Chromium hands frames to the app. Hardware accelerated composites on " +
                "the GPU and is the default on every platform; off-screen renders to a CPU bitmap.",
    ) {
        val hostOs = remember { System.getProperty("os.name").orEmpty().lowercase() }
        val modeEnvNote = envNote(ChromiumFlagKeys.RENDERING_MODE)
        // Two options, not three. "Platform default" would be a third label resolving to exactly
        // the same mode as "Hardware accelerated" on every platform BOSS ships to, and an option
        // that is indistinguishable in effect from its neighbour is a question with no answer.
        SettingsDropdown(
            label = "Rendering mode",
            options = listOf(HARDWARE_LABEL, OFF_SCREEN_LABEL),
            // Routed through the parser rather than a null check: the UI only ever writes null or
            // "OFF_SCREEN", but the stored spelling "HARDWARE" is documented as honoured, and a
            // hand-edited file saying that would display as Off-screen while running Hardware.
            selectedOption = renderingModeLabel(settings.renderingMode, hostOs),
            onOptionSelected = { selection ->
                val mode = if (selection == OFF_SCREEN_LABEL) "OFF_SCREEN" else null
                save { current -> current.copy(renderingMode = mode) }
            },
            enabled = modeEnvNote == null,
            description =
                modeEnvNote
                    ?: "Off-screen is the escape hatch: app menus and dialogs draw over the page as " +
                    "ordinary Compose overlays, at a cost in idle CPU and memory.",
        )

        Spacer(modifier = Modifier.height(8.dp))

        val skikoEnvNote = envNote(ChromiumFlagKeys.SKIKO_RENDER_API)
        SettingsDropdown(
            label = "App rendering backend (Skiko)",
            options = listOf(AUTO_LABEL) + ChromiumFlagKeys.SKIKO_RENDER_APIS,
            selectedOption = settings.skikoRenderApi ?: AUTO_LABEL,
            onOptionSelected = { selection ->
                save { current -> current.copy(skikoRenderApi = selection.takeIf { it != AUTO_LABEL }) }
            },
            enabled = skikoEnvNote == null,
            description =
                skikoEnvNote
                    ?: "The BOSS interface's own backend, separate from the browser's. Leave on Auto " +
                    "unless you are diagnosing a machine with no working GPU - pinning a backend " +
                    "that cannot initialise prevents the app from starting.",
        )

        Spacer(modifier = Modifier.height(8.dp))

        val insetEnvNote = envNote(ChromiumFlagKeys.TOP_INSET_DP)
        SettingsNumberInput(
            label = "Browser surface top offset (dp)",
            value = settings.topInsetDp ?: 0,
            onValueChange = { save { current -> current.copy(topInsetDp = it.takeIf { v -> v != 0 }) } },
            range = 0..200,
            enabled = insetEnvNote == null,
            description =
                insetEnvNote
                    ?: "Per-machine correction for hardware accelerated mode, where the page can sit " +
                    "a little too high and slide under the toolbar. 0 is correct almost everywhere.",
        )
    }
}

private const val AUTO_LABEL = "Auto"

@Composable
private fun PerformanceSection(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
    isMacAarch64: Boolean,
    isLinux: Boolean,
    isWindows: Boolean,
) {
    SettingsSection(title = "Performance") {
        SettingsNumberInput(
            label = "HTTP disk cache (MB)",
            value = settings.diskCacheMb ?: FluckEngine.DEFAULT_DISK_CACHE_MB,
            onValueChange = {
                val mb = it.takeIf { v -> v != FluckEngine.DEFAULT_DISK_CACHE_MB }
                save { current -> current.copy(diskCacheMb = mb) }
            },
            range = 1..8192,
            description =
                "Bigger means faster repeat page loads. Chromium's own auto-sizing caps around " +
                    "320 MB, which is why the default exceeds it.",
        )

        Spacer(modifier = Modifier.height(8.dp))

        val capEnvNote = envNote(ChromiumFlagKeys.RENDERER_PROCESS_LIMIT)
        SettingsNumberInput(
            label = "Renderer process limit",
            // 0 and null are the same thing to Chromium — renderCapSwitch drops any value <= 0
            // because --renderer-process-limit=0 is not a cap — so 0 is spelled as "no limit"
            // rather than stored, and no separate "unlimited" control is needed.
            value = settings.rendererProcessLimit ?: 0,
            onValueChange = { save { current -> current.copy(rendererProcessLimit = it.takeIf { v -> v > 0 }) } },
            range = 0..64,
            enabled = capEnvNote == null,
            description =
                capEnvNote
                    ?: "0 means no limit. Capping this saves memory in many-tab sessions by making " +
                    "tabs share renderer processes - which also means one crashing page can take " +
                    "its neighbours with it.",
        )

        Spacer(modifier = Modifier.height(8.dp))

        val prewarmEnvNote = envNote(ChromiumFlagKeys.PREWARM)
        SettingsToggle(
            label = "Pre-warm the engine at startup",
            checked = settings.browserPrewarm ?: true,
            onCheckedChange = { save { current -> current.copy(browserPrewarm = it.takeIf { v -> !v }) } },
            enabled = prewarmEnvNote == null,
            description =
                prewarmEnvNote
                    ?: "Boots Chromium in the background so the first browser tab does not pay for it. " +
                    "Only ever runs when this machine has used the browser before.",
        )

        PlatformPerformanceRows(settings, save, isMacAarch64 = isMacAarch64, isLinux = isLinux, isWindows = isWindows)
    }
}

/**
 * The performance rows that only exist on one platform.
 *
 * Rendered only where they do something, rather than shown disabled: a greyed-out "Windows
 * only" row on a Mac is noise in a screen that is already dense, and the effective command
 * line below is where a user checks what their platform actually got.
 */
@Composable
private fun PlatformPerformanceRows(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
    isMacAarch64: Boolean,
    isLinux: Boolean,
    isWindows: Boolean,
) {
    Column {
        if (isMacAarch64) {
            Spacer(modifier = Modifier.height(8.dp))
            val graphiteEnvNote = envNote(ChromiumFlagKeys.SKIA_GRAPHITE)
            // The default is not a constant — it follows the rendering mode, so the unset state
            // has to be shown as whatever that mode resolves to rather than as a fixed false.
            // The mode the next launch will use, not the latched one — otherwise selecting
            // Off-screen leaves this reading ON with an explanation about hardware rendering.
            val graphiteDefault =
                FluckEngine.resolveSkiaGraphite(null, nextRenderingMode(settings))
            SettingsToggle(
                label = "Skia Graphite (Metal) raster backend",
                checked = settings.enableSkiaGraphite ?: graphiteDefault,
                // Stores the explicit boolean rather than collapsing one side to null. With a
                // mode-dependent default, "off" is a real choice that has to survive a switch back
                // to hardware rendering — collapsing it would silently re-enable Graphite.
                onCheckedChange = { save { current -> current.copy(enableSkiaGraphite = it) } },
                enabled = graphiteEnvNote == null,
                description =
                    graphiteEnvNote
                        ?: if (graphiteDefault) {
                            "On by default in hardware rendering: Chromium's Metal-native raster " +
                                "backend, and what stable Chrome uses on Apple Silicon. Turn it off if " +
                                "pages render oddly."
                        } else {
                            "Off by default in off-screen rendering, where it is known to blank the " +
                                "page - frames never reach the app surface. It switches on by itself " +
                                "if you return to hardware rendering."
                        },
            )
        }

        if (isLinux) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsToggle(
                label = "VA-API hardware video decode",
                checked = settings.enableVaapi ?: true,
                onCheckedChange = { save { current -> current.copy(enableVaapi = it.takeIf { v -> !v }) } },
                description =
                    "On by default. Turn it off if video is glitching or failing to play - VA-API " +
                        "needs a working driver, and forcing it on a broken one is worse than software decode.",
            )
        }

        if (isWindows) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsToggle(
                label = "Disable native-window occlusion tracking",
                checked = settings.disableWinOcclusion ?: true,
                onCheckedChange = { save { current -> current.copy(disableWinOcclusion = it.takeIf { v -> !v }) } },
                description =
                    "On by default. Chromium can decide the embedded window is fully covered and stop " +
                        "producing frames, which shows up as a browser that freezes until you interact with it.",
            )
        }
    }
}

@Composable
private fun NetworkSection(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
) {
    SettingsSection(
        title = "Privacy & Network",
        description = "Background traffic Chromium sends that an embedded browser has no use for.",
    ) {
        SettingsToggle(
            label = "Drop hyperlink auditing pings",
            checked = settings.noPings ?: true,
            onCheckedChange = { save { current -> current.copy(noPings = it.takeIf { v -> !v }) } },
            description = "--no-pings. On by default. Turn off only if a site depends on ping tracking.",
        )
        Spacer(modifier = Modifier.height(8.dp))
        SettingsToggle(
            label = "Disable Domain Reliability reporting",
            checked = settings.disableDomainReliability ?: true,
            onCheckedChange = { save { current -> current.copy(disableDomainReliability = it.takeIf { v -> !v }) } },
            description = "--disable-domain-reliability. On by default. Stops Chrome's error reports to Google.",
        )
    }
}

@Composable
private fun AdvancedSection(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
) {
    SettingsSection(title = "Advanced") {
        val extrasEnvNote = envNote(ChromiumFlagKeys.EXTRA_SWITCHES)
        val raw = settings.extraSwitches ?: ""
        // Validated with the same function the engine parses these with, so what the UI calls
        // rejected and what boot silently drops can never disagree.
        val parsed = remember(raw) { FluckEngine.partitionExtraSwitches(raw) }

        SettingsTextArea(
            label = "Extra Chromium switches",
            value = raw,
            onValueChange = { save { current -> current.copy(extraSwitches = it.takeIf { v -> v.isNotBlank() }) } },
            placeholder = "--some-switch --another=value",
            enabled = extrasEnvNote == null,
            minLines = 3,
            description =
                extrasEnvNote
                    ?: "Whitespace-separated, like a Chromium command line. Every entry must start " +
                    "with --. Note that --enable-features and --disable-features are NOT additive: " +
                    "the last one wins, so your own value REPLACES the platform set above rather " +
                    "than adding to it.",
        )

        // Two rows, not one. Folding both rejections into a single "not switch-shaped" message
        // told anyone who typed --no-sandbox that their entry did not start with "--", which is
        // false and points at the wrong fix.
        if (parsed.malformed.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(
                label = "Will be ignored",
                value = "${parsed.malformed.size}",
                description =
                    "These are not switch-shaped and will be dropped at startup: " +
                        parsed.malformed.joinToString(" "),
            )
        }
        if (parsed.gated.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(
                label = "Has its own setting above",
                value = "${parsed.gated.size}",
                description =
                    "Refused here because it would skip the confirmation on its own row: " +
                        parsed.gated.joinToString(" ") + ". Use the Danger zone controls instead.",
            )
        }
    }
}

@Composable
private fun DangerZoneSection(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
    onConfirm: (PendingDangerousFlag) -> Unit,
) {
    SettingsSection(
        title = "Danger zone",
        description = "Both of these weaken the browser's security. Neither is needed for normal use.",
    ) {
        SandboxRow(settings, save, onConfirm)
        Spacer(modifier = Modifier.height(8.dp))
        DevToolsPortRows(settings, save, onConfirm)
    }
}

@Composable
private fun SandboxRow(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
    onConfirm: (PendingDangerousFlag) -> Unit,
) {
    Column {
        val sandboxEnvNote = envNote(ChromiumFlagKeys.DISABLE_SANDBOX)
        val sandboxDisabled = settings.disableSandbox == true
        SettingsToggle(
            label = "Disable the Chromium sandbox",
            checked = sandboxDisabled,
            onCheckedChange = { enable ->
                // Only turning it ON is confirmed. Turning it back off restores the protection,
                // and putting a dialog in front of that would discourage the safe direction.
                if (enable) {
                    onConfirm(
                        PendingDangerousFlag(
                            title = "Disable the Chromium sandbox?",
                            message =
                                "The sandbox is what stops a compromised web page from reaching the rest of " +
                                    "your machine, and the browser renders arbitrary web content. It measured no " +
                                    "performance benefit when it was tested.\n\n" +
                                    "Only do this if a specific environment cannot start the sandbox at all.",
                            confirmText = "Disable sandbox",
                            apply = { it.copy(disableSandbox = true) },
                        ),
                    )
                } else {
                    save { current -> current.copy(disableSandbox = null) }
                }
            },
            enabled = sandboxEnvNote == null,
            description =
                sandboxEnvNote
                    ?: "Containers turn this on automatically when the sandbox cannot start; you do not " +
                    "need to set it for them.",
        )
    }
}

/** The DevTools toggle plus the port field it reveals, which only makes sense alongside it. */
@Composable
private fun DevToolsPortRows(
    settings: ChromiumFlagsSettings,
    save: ((ChromiumFlagsSettings) -> ChromiumFlagsSettings) -> Unit,
    onConfirm: (PendingDangerousFlag) -> Unit,
) {
    Column {
        val portEnvNote = envNote(ChromiumFlagKeys.REMOTE_DEBUGGING_PORT)
        val port = settings.remoteDebuggingPort
        SettingsToggle(
            label = "DevTools remote debugging",
            checked = port != null,
            onCheckedChange = { enable ->
                if (enable) {
                    onConfirm(
                        PendingDangerousFlag(
                            title = "Open a DevTools port?",
                            message =
                                "An open DevTools port is full control of this browser profile. Any other " +
                                    "program on this machine can read your cookies and session tokens, and drive " +
                                    "navigation, with no prompt and nothing shown in the interface.\n\n" +
                                    "Unlike the environment variable, this setting PERSISTS across restarts until " +
                                    "you turn it off. Chromium binds the port to this machine only.",
                            confirmText = "Open port $DEFAULT_DEBUG_PORT",
                            apply = { it.copy(remoteDebuggingPort = DEFAULT_DEBUG_PORT) },
                        ),
                    )
                } else {
                    save { current -> current.copy(remoteDebuggingPort = null) }
                }
            },
            enabled = portEnvNote == null,
            description =
                portEnvNote
                    ?: "Off unless you are profiling the browser with an external CDP tool.",
        )

        if (port != null && portEnvNote == null) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsNumberInput(
                label = "DevTools port",
                value = port,
                // Ports below 1024 are privileged and 0 means "pick any free port" to Chromium —
                // a debugging endpoint nobody knows is open. The range keeps both unreachable.
                onValueChange = { save { current -> current.copy(remoteDebuggingPort = it) } },
                range = 1024..65535,
                description = "1024-65535. Bound to localhost by Chromium.",
            )
        }
    }
}

private const val DEFAULT_DEBUG_PORT = 9222
