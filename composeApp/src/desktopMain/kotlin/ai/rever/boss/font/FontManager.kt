package ai.rever.boss.font

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File

/**
 * Central font management service for the BOSS code editor.
 *
 * This manager provides:
 * - System font discovery (monospace and all fonts)
 * - Bundled font loading (JetBrains Mono, Fira Code)
 * - AWT font creation for Swing components
 * - Compose FontFamily creation for UI
 * - Platform-specific defaults
 * - Font availability checking
 *
 * Inspired by BossTerm's FontUtils and IntelliJ's FontPreferences.
 */
object FontManager {

    private val logger = BossLogger.forComponent("FontManager")

    // ===================
    // Constants
    // ===================

    /** Marker for bundled JetBrains Mono font */
    const val BUNDLED_JETBRAINS_MONO = "JetBrains Mono (Bundled)"

    /** Marker for bundled Fira Code font */
    const val BUNDLED_FIRA_CODE = "Fira Code (Bundled)"

    /** Section name for bundled fonts */
    const val SECTION_BUNDLED = "Bundled"

    /** Section name for fixed pitch (monospace) fonts */
    const val SECTION_FIXED_PITCH = "Fixed Pitch"

    /** Section name for variable pitch (proportional) fonts */
    const val SECTION_VARIABLE_PITCH = "Variable Pitch"

    // ===================
    // Platform Detection
    // ===================

    private val isMacOS: Boolean by lazy {
        System.getProperty("os.name")?.lowercase()?.contains("mac") == true
    }

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }

    private val isLinux: Boolean by lazy {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        os.contains("linux") || os.contains("nix") || os.contains("nux")
    }

    // ===================
    // Platform Defaults
    // ===================

    /**
     * Platform-specific default font when no bundled or user font is available.
     */
    val platformDefaultFont: String by lazy {
        when {
            isMacOS -> "Menlo"
            isWindows -> "Consolas"
            isLinux -> "DejaVu Sans Mono"
            else -> "Monospaced"
        }
    }

    /**
     * Platform-specific fallback chain.
     */
    val platformFallbackChain: List<String> by lazy {
        when {
            isMacOS -> listOf("SF Mono", "Monaco", "Monospaced")
            isWindows -> listOf("Cascadia Code", "Courier New", "Monospaced")
            isLinux -> listOf("Ubuntu Mono", "Liberation Mono", "Monospaced")
            else -> listOf("Monospaced")
        }
    }

    // ===================
    // Font Discovery Cache
    // ===================

    /**
     * Cached categorized fonts (lazy-loaded).
     * Map of section name to list of font family names.
     */
    private val cachedCategorizedFonts: Map<String, List<String>> by lazy {
        discoverAndCategorizeFonts()
    }

    /**
     * Cached AWT font family names (lazy-loaded).
     */
    private val awtFontFamilies: Set<String> by lazy {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .toSet()
    }

    // ===================
    // Bundled Font Cache
    // ===================

    /** Cached temp file for bundled JetBrains Mono Regular */
    private var jetBrainsMonoRegularFile: File? = null

    /** Cached temp file for bundled JetBrains Mono Bold */
    private var jetBrainsMonoBoldFile: File? = null

    /** Cached temp file for bundled JetBrains Mono Italic */
    private var jetBrainsMonoItalicFile: File? = null

    /** Cached temp file for bundled Fira Code Regular */
    private var firaCodeRegularFile: File? = null

    /** Cached temp file for bundled Fira Code Bold */
    private var firaCodeBoldFile: File? = null

    /** Flag indicating whether bundled fonts have been registered with AWT */
    private var bundledFontsRegistered = false

    // ===================
    // Public API: Font Discovery
    // ===================

    /**
     * Get fonts organized by category (iTerm2-style).
     * Returns a map with sections: "Bundled", "Fixed Pitch", "Variable Pitch"
     */
    fun getCategorizedFonts(): Map<String, List<String>> = cachedCategorizedFonts

    /**
     * Get list of available monospace fonts including bundled fonts.
     */
    fun getAvailableMonospaceFonts(): List<String> {
        return (cachedCategorizedFonts[SECTION_BUNDLED] ?: emptyList()) +
               (cachedCategorizedFonts[SECTION_FIXED_PITCH] ?: emptyList())
    }

    /**
     * Get list of all available fonts including bundled fonts.
     */
    fun getAllAvailableFonts(): List<String> {
        return (cachedCategorizedFonts[SECTION_BUNDLED] ?: emptyList()) +
               (cachedCategorizedFonts[SECTION_FIXED_PITCH] ?: emptyList()) +
               (cachedCategorizedFonts[SECTION_VARIABLE_PITCH] ?: emptyList())
    }

    // ===================
    // Public API: Font Availability
    // ===================

    /**
     * Check if a font is available (either bundled or system-installed).
     */
    fun isFontAvailable(fontName: String): Boolean {
        // Check bundled fonts
        if (fontName == BUNDLED_JETBRAINS_MONO || fontName == BUNDLED_FIRA_CODE) {
            return true
        }

        // Normalize font names for comparison
        val normalizedName = normalizeFontName(fontName)

        // Check AWT font families
        if (awtFontFamilies.any { normalizeFontName(it) == normalizedName }) {
            return true
        }

        // Check Skia font families
        val allFonts = (cachedCategorizedFonts[SECTION_FIXED_PITCH] ?: emptyList()) +
                       (cachedCategorizedFonts[SECTION_VARIABLE_PITCH] ?: emptyList())
        return allFonts.any { normalizeFontName(it) == normalizedName }
    }

    // ===================
    // Public API: AWT Font Loading
    // ===================

    /**
     * Load an AWT Font for use with Swing components.
     *
     * @param fontName Font family name (or bundled font marker)
     * @param style Font style (Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD or Font.ITALIC)
     * @param size Font size in points
     * @return AWT Font instance
     */
    fun loadAWTFont(fontName: String, style: Int = Font.PLAIN, size: Int = 14): Font {
        ensureBundledFontsRegistered()

        return when (fontName) {
            BUNDLED_JETBRAINS_MONO, "JetBrains Mono" -> {
                loadBundledAWTFont("JetBrains Mono", style, size)
            }
            BUNDLED_FIRA_CODE, "Fira Code" -> {
                loadBundledAWTFont("Fira Code", style, size)
            }
            else -> {
                // Try system font
                if (isFontAvailable(fontName)) {
                    Font(fontName, style, size)
                } else {
                    // Fallback to bundled JetBrains Mono
                    logger.debug(LogCategory.SYSTEM, "Font not found, using bundled JetBrains Mono", mapOf("fontName" to fontName))
                    loadBundledAWTFont("JetBrains Mono", style, size)
                }
            }
        }
    }

    /**
     * Create an AWT Font for the code editor with proper fallback chain.
     *
     * @param fontName Requested font name
     * @param size Font size in points
     * @return AWT Font instance (guaranteed non-null)
     */
    fun createEditorFont(fontName: String, size: Int): Font {
        return loadAWTFont(fontName, Font.PLAIN, size)
    }

    // ===================
    // Public API: Compose FontFamily Loading
    // ===================

    /**
     * Load a Compose FontFamily for UI previews.
     *
     * @param fontName Font family name (or bundled font marker)
     * @return Compose FontFamily instance
     */
    fun loadComposeFontFamily(fontName: String): FontFamily {
        return when (fontName) {
            BUNDLED_JETBRAINS_MONO, "JetBrains Mono" -> {
                loadBundledComposeFontFamily("fonts/JetBrainsMono-Regular.ttf")
            }
            BUNDLED_FIRA_CODE, "Fira Code" -> {
                loadBundledComposeFontFamily("fonts/FiraCode-Regular.ttf")
            }
            else -> {
                // Try system font via Skia
                try {
                    val skiaTypeface = FontMgr.default.matchFamilyStyle(fontName, FontStyle.NORMAL)
                    if (skiaTypeface != null) {
                        FontFamily(Typeface(skiaTypeface))
                    } else {
                        FontFamily.Monospace
                    }
                } catch (e: Exception) {
                    logger.debug(LogCategory.UI, "System font lookup failed - falling back to Monospace", mapOf("font" to fontName, "error" to e.toString()))
                    FontFamily.Monospace
                }
            }
        }
    }

    // ===================
    // Private: Font Discovery
    // ===================

    /**
     * Discover and categorize all available fonts using Skia FontMgr.
     */
    private fun discoverAndCategorizeFonts(): Map<String, List<String>> {
        val fontMgr = FontMgr.default
        val familyCount = fontMgr.familiesCount

        val allFamilies = (0 until familyCount)
            .map { fontMgr.getFamilyName(it) }
            .filter { it.isNotEmpty() }

        val fixedPitch = mutableListOf<String>()
        val variablePitch = mutableListOf<String>()

        for (familyName in allFamilies) {
            try {
                val typeface = fontMgr.matchFamilyStyle(familyName, FontStyle.NORMAL)
                if (typeface != null) {
                    // Check if font is monospace by comparing glyph widths
                    val font = org.jetbrains.skia.Font(typeface, 12f)
                    val widthW = font.measureTextWidth("W")
                    val widthI = font.measureTextWidth("i")
                    // Allow small tolerance for floating point comparison
                    if (kotlin.math.abs(widthW - widthI) < 0.1f) {
                        fixedPitch.add(familyName)
                    } else {
                        variablePitch.add(familyName)
                    }
                }
            } catch (e: Exception) {
                // Skip fonts that fail to load
                logger.debug(LogCategory.UI, "Skipping font that failed to load during discovery", mapOf("font" to familyName, "error" to e.toString()))
            }
        }

        return mapOf(
            SECTION_BUNDLED to listOf(BUNDLED_JETBRAINS_MONO, BUNDLED_FIRA_CODE),
            SECTION_FIXED_PITCH to fixedPitch.sorted(),
            SECTION_VARIABLE_PITCH to variablePitch.sorted()
        )
    }

    // ===================
    // Private: Bundled Font Registration
    // ===================

    /**
     * Ensure bundled fonts are extracted and registered with AWT GraphicsEnvironment.
     */
    @Synchronized
    private fun ensureBundledFontsRegistered() {
        if (bundledFontsRegistered) return

        try {
            // Extract and register JetBrains Mono
            jetBrainsMonoRegularFile = extractFontResource("fonts/JetBrainsMono-Regular.ttf", "JetBrainsMono-Regular")
            jetBrainsMonoBoldFile = extractFontResource("fonts/JetBrainsMono-Bold.ttf", "JetBrainsMono-Bold")
            jetBrainsMonoItalicFile = extractFontResource("fonts/JetBrainsMono-Italic.ttf", "JetBrainsMono-Italic")

            // Extract and register Fira Code
            firaCodeRegularFile = extractFontResource("fonts/FiraCode-Regular.ttf", "FiraCode-Regular")
            firaCodeBoldFile = extractFontResource("fonts/FiraCode-Bold.ttf", "FiraCode-Bold")

            // Register with AWT
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()

            jetBrainsMonoRegularFile?.let { registerFontFile(ge, it) }
            jetBrainsMonoBoldFile?.let { registerFontFile(ge, it) }
            jetBrainsMonoItalicFile?.let { registerFontFile(ge, it) }
            firaCodeRegularFile?.let { registerFontFile(ge, it) }
            firaCodeBoldFile?.let { registerFontFile(ge, it) }

            bundledFontsRegistered = true
            logger.info(LogCategory.SYSTEM, "Bundled fonts registered successfully")
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to register bundled fonts", error = e)
        }
    }

    /**
     * Extract a font resource to a temporary file.
     */
    private fun extractFontResource(resourcePath: String, prefix: String): File? {
        return try {
            val fontStream = javaClass.classLoader?.getResourceAsStream(resourcePath)
            if (fontStream == null) {
                logger.warn(LogCategory.SYSTEM, "Font resource not found", mapOf("resourcePath" to resourcePath))
                return null
            }

            val tempFile = File.createTempFile(prefix, ".ttf")
            tempFile.deleteOnExit()

            fontStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            tempFile
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to extract font", mapOf("resourcePath" to resourcePath), error = e)
            null
        }
    }

    /**
     * Register a font file with AWT GraphicsEnvironment.
     */
    private fun registerFontFile(ge: GraphicsEnvironment, file: File) {
        try {
            val font = Font.createFont(Font.TRUETYPE_FONT, file)
            ge.registerFont(font)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to register font", mapOf("fileName" to file.name), error = e)
        }
    }

    // ===================
    // Private: Bundled AWT Font Loading
    // ===================

    /**
     * Load a bundled AWT font with the specified style.
     */
    private fun loadBundledAWTFont(familyName: String, style: Int, size: Int): Font {
        ensureBundledFontsRegistered()

        // After registration, the font should be available by family name
        return try {
            Font(familyName, style, size)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load bundled font", mapOf("familyName" to familyName), error = e)
            Font(platformDefaultFont, style, size)
        }
    }

    // ===================
    // Private: Bundled Compose Font Loading
    // ===================

    /**
     * Load a bundled font as Compose FontFamily.
     */
    private fun loadBundledComposeFontFamily(resourcePath: String): FontFamily {
        return try {
            val fontStream = javaClass.classLoader?.getResourceAsStream(resourcePath)
            if (fontStream == null) {
                logger.warn(LogCategory.SYSTEM, "Font resource not found for Compose", mapOf("resourcePath" to resourcePath))
                return FontFamily.Monospace
            }

            val tempFile = File.createTempFile("bundled-font", ".ttf")
            tempFile.deleteOnExit()

            fontStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            FontFamily(
                androidx.compose.ui.text.platform.Font(
                    file = tempFile,
                    weight = FontWeight.Normal
                )
            )
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to load bundled Compose font", error = e)
            FontFamily.Monospace
        }
    }

    // ===================
    // Private: Utilities
    // ===================

    /**
     * Normalize font name for comparison (lowercase, no spaces).
     */
    private fun normalizeFontName(name: String): String {
        return name.lowercase().replace(" ", "").replace("-", "")
    }
}
