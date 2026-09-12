package ai.rever.boss.startup

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Handles early platform-specific configuration and native environment setup.
 *
 * All routines here must run before AWT or Compose UI initialization where noted.
 */
object PlatformSetup {
    private val logger by lazy { BossLogger.forComponent("PlatformSetup") }

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
     * Timing is critical: it is read once when AWT creates the NSApplication, so this must run
     * before anything touches AWT.
     */
    fun applyMacAppearanceFromTheme() {
        if (!SystemUtils.isMacOS) return

        // Reads a small JSON file and touches no UI toolkit, which is what makes it safe this early.
        AppThemeSettingsManager.ensureInitialized()

        val theme = BossThemeController.current
        val appearance =
            if (theme.isLight) {
                "NSAppearanceNameAqua"
            } else {
                "NSAppearanceNameDarkAqua"
            }
        System.setProperty("apple.awt.application.appearance", appearance)

        BossLogger
            .forComponent("MacAppearance")
            .info(
                LogCategory.SYSTEM,
                "Window appearance set from theme",
                mapOf("themeId" to theme.id, "isLight" to theme.isLight.toString(), "appearance" to appearance),
            )
    }

    /**
     * Set WM_CLASS for proper Linux desktop integration.
     * Must be called before any windows are created.
     * Requires JVM arg: --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED
     */
    @Suppress("TooGenericExceptionCaught")
    fun setLinuxWMClass() {
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

    /**
     * Set up proper temp directories for native libraries and configure system properties.
     */
    fun setupNativeLibraryPaths() {
        val bossDir = BossDirectories.rootDir
        val tempDir = File(bossDir, "temp")
        val pty4jDir = File(tempDir, "pty4j")

        bossDir.mkdirs()
        tempDir.mkdirs()
        pty4jDir.mkdirs()

        extractPty4jNatives(pty4jDir)

        System.setProperty("pty4j.tmpdir", pty4jDir.absolutePath)
        System.setProperty("pty4j.preferred.native.folder", pty4jDir.absolutePath)

        // Check if we're running from an app bundle
        val appPath = System.getProperty("java.home")
        if (appPath.contains(".app")) {
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

    /**
     * Extracts PTY4J native libraries from classpath into [targetDir] if available.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    internal fun extractPty4jNatives(
        targetDir: File,
        osName: String = System.getProperty("os.name").lowercase(),
        osArch: String = System.getProperty("os.arch").lowercase(),
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: PlatformSetup::class.java.classLoader,
    ) {
        try {
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

            val platformDir = File(targetDir, platformPath)
            if (!platformDir.exists()) {
                platformDir.mkdirs()
            }

            val libptyFile = File(platformDir, libName)
            if (libptyFile.exists() && libptyFile.length() > 0) {
                logger.trace(LogCategory.SYSTEM, "PTY4J natives already extracted", mapOf("platform" to platformPath))
                return
            }

            val nativeResources =
                listOf(
                    "com/pty4j/native/$platformPath/$libName",
                    "resources/com/pty4j/native/$platformPath/$libName",
                    "$platformPath/$libName",
                    "native/$platformPath/$libName",
                )

            val partialNative = File(platformDir, "$libName.part")
            var extracted = false
            for (resource in nativeResources) {
                try {
                    val resourceStream = classLoader.getResourceAsStream(resource)
                    if (resourceStream != null) {
                        resourceStream.use { input ->
                            partialNative.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        java.nio.file.Files.move(
                            partialNative.toPath(),
                            libptyFile.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
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
                    // A leftover .part from an interrupted host is never accepted as a cached native.
                    partialNative.delete()
                    logger.debug(
                        LogCategory.SYSTEM,
                        "PTY4J native extraction failed for resource - trying next",
                        mapOf("resource" to resource, "error" to e.toString()),
                    )
                }
            }

            if (!extracted) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "PTY4J native unavailable; no candidate completed extraction (handled by terminal-tab plugin)",
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
}
