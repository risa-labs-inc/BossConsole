package ai.rever.boss

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import java.nio.file.Paths

private val logger = BossLogger.forComponent("ChromiumDownloader")

/**
 * Utility to download JxBrowser's Chromium binaries.
 *
 * This is used by CI to pre-download Chromium for branding.
 * The license is bound to the ai.rever.boss package, so this must run
 * within the context of this project.
 */
fun main(args: Array<String>) {
    val chromiumDir =
        if (args.isNotEmpty()) {
            Paths.get(args[0])
        } else {
            Paths.get(System.getProperty("user.home"), "chromium-binaries")
        }

    logger.info(LogCategory.BROWSER, "Downloading Chromium binaries", mapOf("targetDir" to chromiumDir.toString()))

    val licenseKey =
        System.getenv("JXBROWSER_LICENSE_KEY")
            ?: System.getProperty("jxbrowser.license.key")
            ?: error("JXBROWSER_LICENSE_KEY environment variable or jxbrowser.license.key property not set")

    // Check if we should disable sandbox (needed on Linux CI where user namespaces aren't available)
    val disableSandbox = System.getenv("JXBROWSER_DISABLE_SANDBOX")?.toBoolean() ?: false
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    try {
        val optionsBuilder =
            EngineOptions
                .newBuilder(RenderingMode.OFF_SCREEN)
                .licenseKey(licenseKey)
                .chromiumDir(chromiumDir)

        // Disable sandbox on Linux CI environments where user namespaces aren't supported
        if (disableSandbox || (isLinux && System.getenv("CI") != null)) {
            logger.info(LogCategory.BROWSER, "Disabling Chromium sandbox for CI environment")
            optionsBuilder.disableSandbox()
        }

        val options = optionsBuilder.build()

        logger.info(LogCategory.BROWSER, "Creating engine (this triggers download)")
        val engine = Engine.newInstance(options)
        logger.info(LogCategory.BROWSER, "Chromium downloaded successfully")

        // List contents to verify
        val files = chromiumDir.toFile().listFiles()
        logger.debug(LogCategory.BROWSER, "Directory contents", mapOf("files" to (files?.map { it.name } ?: emptyList())))

        engine.close()
        logger.info(LogCategory.BROWSER, "Chromium download complete")
    } catch (e: Exception) {
        // On headless Linux CI, the engine may fail to start even after downloading
        // Check if binaries were downloaded successfully
        val files = chromiumDir.toFile().listFiles()
        val hasChromium =
            files?.any {
                it.name.contains("chromium", ignoreCase = true) ||
                    it.name.contains("chrome", ignoreCase = true) ||
                    it.name == "jxbrowser-chromium" ||
                    it.isDirectory
            } == true

        if (hasChromium && isLinux) {
            logger.info(LogCategory.BROWSER, "Engine failed to start but binaries were downloaded")
            logger.debug(LogCategory.BROWSER, "Directory contents", mapOf("files" to files.map { it.name }))
            logger.info(LogCategory.BROWSER, "Download complete (download-only mode on Linux CI)")
        } else {
            logger.error(LogCategory.BROWSER, "Failed to download Chromium", error = e)
            System.exit(1)
        }
    }
}
