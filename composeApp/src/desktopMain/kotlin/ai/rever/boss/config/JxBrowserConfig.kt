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
                    "JxBrowser license key not configured - set JXBROWSER_LICENSE_KEY " +
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
     * How the embedded Chromium hands its frames to Compose. **HARDWARE_ACCELERATED
     * on every platform**, with OFF_SCREEN reachable per install.
     *
     * OFF_SCREEN is the mode Compose overlays were originally built around:
     * HARDWARE_ACCELERATED renders into a foreign native window owned by Chromium's
     * GPU process, which composites against the Compose scene rather than inside it,
     * so an ordinary Compose overlay (menu, dialog, toast) is drawn *under* browser
     * content. That is why every platform used to pin OFF_SCREEN, and it is now
     * handled rather than avoided — see `OverlayConfig` and the heavyweight overlay
     * renderers, which activate off this value.
     *
     * Windows moved first, on throughput. Measured on Windows 11 / Chromium 150 /
     * Core Ultra 7 155H (2026-07-31), fluck tab vs Edge 150 on Speedometer 3.1 —
     * same engine generation:
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
     * **macOS and Linux now default to HARDWARE too, but for a different reason, and
     * it is worth not confusing the two.** macOS does not have the Windows throughput
     * problem at all: fluck measures 47.9 on Speedometer there, ahead of Chrome,
     * because the off-screen surface can be shared with the GPU (Windows/D3D11 needs a
     * real per-frame readback instead). What macOS pays on OFF_SCREEN is *power and
     * memory*, which Speedometer does not measure. BossConsoleLite defaulted macOS to
     * HARDWARE (`3fd5e36c`) and recorded a content-matched A/B over clean 94-sample
     * idle windows (`6e637198`):
     *
     *  - idle CPU 0.59 -> 0.06 cores (~10x)
     *  - RSS 3095 -> 1974 MB (-1.1 GB, -36%)
     *  - peak CPU -14%, peak RSS -25%
     *
     * So both readings are true: OFF_SCREEN is fast on macOS and expensive to sit
     * idle in, and the fleet's complaints are about battery and RAM. Linux follows
     * Lite (`f8d7c708`) and remains the least-measured of the three — the arm to run
     * there is the power/memory one, not Speedometer (see benchmarks/speedometer/UNIX.md).
     *
     * One macOS cost is accepted rather than solved: every app overlay routes through a
     * heavyweight Swing window instead of a Compose Popup. That path exists because
     * Windows needed it, and macOS is the second platform to exercise it.
     *
     * Pinch-to-zoom would have joined it and did not: it is gated on Compose hover, which a
     * heavyweight surface never reports — see `shouldAllowPinch` in BrowserHandleImpl, where
     * the gate is mode-aware so the gesture survives.
     *
     * The two-finger swipe-back is NOT on this list either, and the reason is worth recording
     * because this file used to claim otherwise. It was described here, and in the Settings
     * copy for OFF_SCREEN, as something HARDWARE_ACCELERATED took away and OFF_SCREEN gave
     * back. **Measured 2026-08-28, it does neither.** A build launched with
     * `BOSS_RENDERING_MODE=OFF_SCREEN` (log line confirming `mode=OFF_SCREEN`), with
     * `enableOverscrollHistoryNavigation` on as it always is and the in-page detector switched
     * off with `BOSS_BROWSER_SWIPE_NAV=false`, does not navigate on a two-finger swipe. So the
     * gesture never worked in either mode, and the rendering mode was never what cost it.
     * JxBrowser's setting is enabled for touchscreens — see its call site's own comment in
     * BrowserServiceImpl — and a trackpad wheel is not a touch gesture. The working gesture is
     * detected inside the page instead, which is mode-independent; see `BrowserSwipeNavScript`.
     *
     * Windows additionally hit three regressions Lite found first — the browser
     * surface sitting ~toolbar-height too high, Ctrl+R not reaching a focused page,
     * and hover tooltips rendering behind the browser. Those fixes shipped with the
     * Windows flip and apply to every platform now.
     *
     * Escape hatch, no rebuild needed: BOSS_RENDERING_MODE=OFF_SCREEN, or the
     * rendering-mode control in Settings > Browser Engine. The env-var name matches
     * Lite's so one value means the same thing in both repos.
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
     * An explicit, recognized BOSS_RENDERING_MODE always wins — which is now the only
     * way to get OFF_SCREEN on any platform, and the escape hatch if the overlay work
     * above turns out to be incomplete on a given machine. Anything unrecognized
     * (typo, blank, unset) falls back to the default rather than to a guess, so a
     * mistyped value can never silently change how the browser composites. Spellings
     * match Lite's so the same value works in both repos.
     *
     * [os] is no longer read — the default is uniform. It is kept as a parameter
     * deliberately: a per-platform carve-out is a live possibility (Linux is the
     * least-measured of the three), and
     * removing the parameter would mean re-threading it through every caller and test
     * to reintroduce one. Unused here means "no platform disagrees today", not "this
     * decision cannot be platform-specific".
     */
    @Suppress("UNUSED_PARAMETER")
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

            // Every platform. Windows for throughput (+47% on Speedometer), macOS and
            // Linux for idle power and memory (~10x idle CPU, -1.1 GB RSS in Lite's
            // A/B) — see the KDoc above for why those are different findings.
            else -> {
                com.teamdev.jxbrowser.engine.RenderingMode.HARDWARE_ACCELERATED
            }
        }

    private val HARDWARE_SPELLINGS = setOf("HARDWARE", "HARDWARE_ACCELERATED", "GPU")
    private val OFF_SCREEN_SPELLINGS = setOf("OFF_SCREEN", "OFFSCREEN", "SOFTWARE")

    /** Whether [raw] names a mode we honour, as opposed to falling back to the platform default. */
    internal fun isRecognizedRenderingMode(raw: String?): Boolean =
        raw?.trim()?.uppercase().let { it in HARDWARE_SPELLINGS || it in OFF_SCREEN_SPELLINGS }
}
