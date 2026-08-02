package ai.rever.boss.config

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Configuration for JxBrowser.
 *
 * The license key can be provided through:
 * 1. Environment variable: JXBROWSER_LICENSE_KEY
 * 2. System property: jxbrowser.license.key
 * 3. local.properties file: jxbrowser.license.key=YOUR_KEY
 * 4. Embedded build config (baked in at build time from CI secrets)
 *
 * There is deliberately no fallback key in source: this repo is public.
 */
object JxBrowserConfig {
    private val logger = BossLogger.forComponent("JxBrowserConfig")

    /**
     * JxBrowser license key loaded from secure sources.
     * Blank when unconfigured — browser features will fail to initialize.
     */
    val licenseKey: String by lazy {
        ConfigLoader.getConfig("JXBROWSER_LICENSE_KEY")
            ?: ConfigLoader.getConfig("jxbrowser.license.key")
            ?: run {
                logger.error(
                    LogCategory.BROWSER,
                    "JxBrowser license key not configured — set JXBROWSER_LICENSE_KEY " +
                        "(env var) or jxbrowser.license.key in local.properties. " +
                        "Browser features will be unavailable.",
                )
                ""
            }
    }

    // Other JxBrowser configuration options
    val defaultUrl: String =
        ConfigLoader.getConfig(
            "jxbrowser.default.url",
            "https://www.risalabs.ai",
        ) ?: "https://www.risalabs.ai"

    /**
     * How the embedded Chromium hands its frames to Compose. **Windows defaults
     * to HARDWARE_ACCELERATED; macOS and Linux stay on OFF_SCREEN.**
     *
     * OFF_SCREEN is the mode Compose overlays are built around: HARDWARE_ACCELERATED
     * renders into a foreign native window owned by Chromium's GPU process, which
     * composites against the Compose scene rather than inside it, so Compose overlays
     * (menus, dialogs, toasts) can be drawn under browser content. That is why every
     * platform used to pin OFF_SCREEN.
     *
     * On Windows it is also where the performance goes. Measured on Windows 11 /
     * Chromium 150 / Core Ultra 7 155H (2026-07-31), fluck tab vs Edge 150 on
     * Speedometer 3.1 — same engine generation:
     *
     *  - fluck 7.5-11.5 against Edge's 24.7, and the deficit is UNIFORM across all
     *    206 metrics (Sync 3.0x, Async 4.8x, median per-test 3.13x) with no suite
     *    signature.
     *  - It is not compute: a tight arithmetic loop is only 1.17x slower, and idle
     *    rAF (16.65ms) and setTimeout(0) (4.16ms) match Edge exactly.
     *  - It is not scheduling: the active renderer measured Normal priority with
     *    EcoQoS default, so it is not demoted to E-cores.
     *  - It is not a software-raster fallback: ANGLE/D3D11 on Intel Arc with
     *    gpu_compositing and rasterization both enabled.
     *  - During a run the renderer burns only 0.26 of a core while the whole machine
     *    sits at 0.65 — it is blocked waiting for frames, not computing.
     *  - Six Chromium switch sets were measured and NONE helped:
     *    --disable-gpu-compositing (11.0), --disable-gpu (11.0), --use-angle=gl
     *    (11.5), --disable-gpu-vsync --disable-frame-rate-limit (9.56, worse) and
     *    --force-device-scale-factor=1 (4.85, half speed) against an 11.5/10.2
     *    control. This is NOT reachable from FluckEngine.applyPerformanceSwitches.
     *  - Switching to HARDWARE_ACCELERATED won 3 of 3 interleaved pairs for a median
     *    +47% (9.56->15.3, 10.8->15.9, 11.0->14.3).
     *
     * macOS does not pay this and must not be changed: fluck measures 47.9 there,
     * ahead of Chrome, because the off-screen surface can be shared with the GPU.
     * Windows/D3D11 needs a real per-frame readback instead. Linux is unmeasured, so
     * it keeps the old default rather than inheriting a Windows finding.
     *
     * The Compose-overlay consequence is real and is handled, not ignored:
     * BossConsoleLite defaulted to HARDWARE first and its Windows fleet hit three
     * regressions — the browser surface sitting ~toolbar-height too high, Ctrl+R not
     * reaching a focused page, and hover tooltips rendering behind the browser. The
     * fixes are ported alongside this (see `OverlayConfig` and the heavyweight
     * overlay renderers). Lite's own heavyweight popup/modal are still marked DRAFT
     * there, so overlay behaviour in HARDWARE mode is improving rather than settled.
     *
     * Escape hatch, no rebuild needed: BOSS_RENDERING_MODE=OFF_SCREEN. The name
     * matches Lite's so one setting means the same thing in both repos.
     */
    val renderingMode: com.teamdev.jxbrowser.engine.RenderingMode by lazy {
        // ConfigLoader, not getenv: matches Lite, and lets this be set by env var,
        // system property or local.properties rather than env var only.
        val raw = ConfigLoader.getConfig("BOSS_RENDERING_MODE")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val mode = resolveRenderingMode(raw, os)
        if (!raw.isNullOrBlank() && !isRecognizedRenderingMode(raw)) {
            // Unset is the normal case and stays silent; only a value we could not
            // honour is worth a warning, since it silently keeps the platform default.
            logger.warn(
                LogCategory.BROWSER,
                "Ignoring unrecognized BOSS_RENDERING_MODE - using the platform default",
                mapOf("value" to raw, "using" to mode.name),
            )
        }
        logger.info(
            LogCategory.BROWSER,
            "Browser rendering mode selected",
            mapOf("mode" to mode.name, "override" to (raw ?: "default"), "os" to os),
        )
        mode
    }

    /**
     * Pure part of [renderingMode], split out so the platform decision is testable
     * without an engine.
     *
     * An explicit, recognized BOSS_RENDERING_MODE always wins — including forcing
     * OFF_SCREEN back on Windows, which is the escape hatch if the overlay work
     * above turns out to be incomplete on a given machine. Anything unrecognized
     * (typo, blank, unset) falls back to the platform default rather than to a
     * guess, so a mistyped value can never silently change how the browser
     * composites. Spellings match Lite's so the same value works in both repos.
     */
    internal fun resolveRenderingMode(
        raw: String?,
        os: String,
    ): com.teamdev.jxbrowser.engine.RenderingMode =
        when (raw?.trim()?.uppercase()) {
            in HARDWARE_SPELLINGS -> {
                com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED
            }

            in OFF_SCREEN_SPELLINGS -> {
                com.teamdev.jxbrowser.engine.RenderingMode.OFF_SCREEN
            }

            // Windows only. macOS is measurably fine on OFF_SCREEN (and Lite reports
            // HARDWARE costs it the two-finger swipe-back gesture), and Linux is
            // unmeasured here, so neither inherits the Windows finding.
            else -> {
                // "windows", not "win" — "darwin" contains "win". Unreachable with a HotSpot
                // os.name of "Mac OS X", but it is a trap worth not leaving in a platform switch,
                // and the rest of the repo matches on "windows".
                if (os.contains("windows")) {
                    com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED
                } else {
                    com.teamdev.jxbrowser.engine.RenderingMode.OFF_SCREEN
                }
            }
        }

    private val HARDWARE_SPELLINGS = setOf("HARDWARE", "HARDWARE_ACCELERATED", "GPU")
    private val OFF_SCREEN_SPELLINGS = setOf("OFF_SCREEN", "OFFSCREEN", "SOFTWARE")

    /** Whether [raw] names a mode we honour, as opposed to falling back to the platform default. */
    internal fun isRecognizedRenderingMode(raw: String?): Boolean =
        raw?.trim()?.uppercase().let { it in HARDWARE_SPELLINGS || it in OFF_SCREEN_SPELLINGS }
}
