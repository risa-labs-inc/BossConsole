package ai.rever.boss.updater.source

import ai.rever.boss.updater.GitHubRelease
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException

/**
 * Tries [primary] first; on a thrown error OR an empty result, falls back to
 * [backup]. If the backup also fails, returns a safe empty result (the update
 * service treats "no releases" as "up to date"). Cancellation propagates.
 *
 * Used as Supabase-primary / GitHub-backup for the desktop self-updater.
 */
class FallbackUpdateSource(
    private val primary: UpdateSource,
    private val backup: UpdateSource
) : UpdateSource {

    override val name: String = "${primary.name}->${backup.name}"
    private val logger = BossLogger.forComponent("FallbackUpdateSource")

    override suspend fun listReleases(): List<GitHubRelease> {
        try {
            val releases = primary.listReleases()
            if (releases.isNotEmpty()) {
                logger.debug(LogCategory.NETWORK, "Releases served by ${primary.name}", mapOf("count" to releases.size))
                return releases
            }
            logger.info(LogCategory.NETWORK, "${primary.name} returned no releases; falling back to ${backup.name}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(LogCategory.NETWORK, "${primary.name} failed; falling back to ${backup.name}", error = e)
        }

        return try {
            backup.listReleases().also {
                logger.debug(LogCategory.NETWORK, "Releases served by ${backup.name}", mapOf("count" to it.size))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Both update sources failed (${backup.name})", error = e)
            emptyList()
        }
    }

    override suspend fun getReleaseByTag(tag: String): GitHubRelease? {
        try {
            val release = primary.getReleaseByTag(tag)
            if (release != null) return release
            logger.info(LogCategory.NETWORK, "${primary.name} has no $tag; falling back to ${backup.name}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(LogCategory.NETWORK, "${primary.name} failed for $tag; falling back to ${backup.name}", error = e)
        }

        return try {
            backup.getReleaseByTag(tag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Both update sources failed for $tag (${backup.name})", error = e)
            null
        }
    }
}
