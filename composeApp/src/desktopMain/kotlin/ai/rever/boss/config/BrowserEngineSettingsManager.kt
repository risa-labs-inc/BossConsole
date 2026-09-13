package ai.rever.boss.config

import ai.rever.boss.utils.VersionConstants
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Provides the effective engine version to use.
 */
object BrowserEngineSettingsManager {
    private val logger = BossLogger.forComponent("BrowserEngineSettingsManager")

    /**
     * The engine version the app should install and run:
     * user pin from system property, else the bundled JxBrowser version.
     */
    val effectiveVersion: String
        get() {
            val propertyPin = System.getProperty("boss.browser.engine.version")?.trim()?.takeIf { it.isNotEmpty() }
            if (propertyPin != null && propertyPin != VersionConstants.JXBROWSER_VERSION) {
                logger.debug(
                    LogCategory.BROWSER,
                    "Using browser engine version from system property",
                    mapOf("pinned" to propertyPin, "bundled" to VersionConstants.JXBROWSER_VERSION),
                )
                return propertyPin
            }
            return VersionConstants.JXBROWSER_VERSION
        }
}
