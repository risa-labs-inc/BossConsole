package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import javax.imageio.ImageIO

/**
 * The colour scheme a favicon was drawn for. Sites such as GitHub serve a different icon per
 * `prefers-color-scheme`, so one icon per URL is wrong for half the themes.
 */
enum class FaviconScheme(
    val suffix: String,
) {
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun of(dark: Boolean): FaviconScheme = if (dark) DARK else LIGHT

        /** The scheme of the BOSS theme on screen now, which the browser engine mirrors. */
        fun current(): FaviconScheme = of(!BossThemes.byId(BossThemeController.currentId).isLight)
    }
}

private val logger = BossLogger.forComponent("FaviconCache")

/**
 * Copies the base file just written to [scheme]'s variant and returns the key naming it, or the
 * base key if the copy fails: the base file already holds this icon, so the tab still gets it.
 */
// Runs inside FaviconCache.saveFavicon, which must not throw at its JxBrowser listener caller.
@Suppress("TooGenericExceptionCaught")
internal fun saveSchemeVariant(
    cacheKey: String,
    cacheFile: File,
    dir: File,
    scheme: FaviconScheme,
): String {
    var tempFile: File? = null
    return try {
        tempFile = File.createTempFile("favicon_", ".png", dir)
        cacheFile.copyTo(tempFile, overwrite = true)
        File(dir, "${variantKey(cacheKey, scheme)}.png").atomicMoveFrom(tempFile)
        variantKey(cacheKey, scheme)
    } catch (e: Exception) {
        logger.debug(LogCategory.BROWSER, "Could not save favicon scheme variant", mapOf("error" to e.toString()))
        cacheKey
    } finally {
        tempFile?.delete()
    }
}

private fun variantKey(
    baseKey: String,
    scheme: FaviconScheme,
) = "$baseKey.${scheme.suffix}"

/**
 * The files that may hold the icon for [cacheKey], best first: the variant for the theme on
 * screen now, then the variant the key itself names, then the base file. A key from before
 * variants existed has no suffix and resolves exactly as it always did.
 */
internal fun candidateFiles(
    cacheKey: String,
    scheme: FaviconScheme?,
    dir: File,
): List<File> {
    val base = cacheKey.substringBefore('.')
    val stored = FaviconScheme.entries.firstOrNull { cacheKey == variantKey(base, it) }
    return listOfNotNull(scheme, stored)
        .distinct()
        .map { File(dir, "${variantKey(base, it)}.png") }
        .plus(File(dir, "$base.png"))
}

/** [FaviconCache.loadFavicon] against [dir]: the first of [candidateFiles] that exists, or null. */
// A corrupt or half-written PNG must cost the tab its icon, never the composition reading it.
@Suppress("TooGenericExceptionCaught")
internal fun loadSchemeFavicon(
    cacheKey: String,
    scheme: FaviconScheme?,
    dir: File,
): TabIcon.Image? =
    try {
        candidateFiles(cacheKey, scheme, dir)
            .firstOrNull { it.exists() }
            ?.let { file ->
                ImageIO.read(file).also {
                    if (it == null) {
                        logger.warn(LogCategory.BROWSER, "Failed to read cached favicon", mapOf("cacheKey" to cacheKey))
                    }
                }
            }?.let { TabIcon.Image(BitmapPainter(it.toComposeImageBitmap())) }
    } catch (e: Exception) {
        logger.warn(LogCategory.BROWSER, "Error loading favicon", mapOf("cacheKey" to cacheKey), error = e)
        null
    }
