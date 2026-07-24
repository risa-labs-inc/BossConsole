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

    // OFF_SCREEN mode for lightweight Compose popups compatibility.
    // HARDWARE_ACCELERATED renders into a foreign native window/CALayer owned by
    // Chromium's GPU process, which always sits above the Compose scene — Compose
    // overlays (menus, dialogs, toasts) cannot draw over it. Re-verified against
    // JxBrowser 9.3 + Compose Multiplatform 1.11: still true (CMP interop blending
    // only blends Swing-rendered content, not foreign GPU surfaces), so OFF_SCREEN
    // remains the required mode. Rendering-perf work should target the engine
    // boot path and Chromium switches (see FluckEngine.applyPerformanceSwitches).
    val renderingMode = com.teamdev.jxbrowser.engine.RenderingMode.OFF_SCREEN
}
