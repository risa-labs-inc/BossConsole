package ai.rever.boss.config

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.sha256Of
import java.io.File

/**
 * Integrity gate for a downloaded engine archive, run before it is extracted:
 * the archive's bytes must match the catalog sha256 pinned on its candidate
 * (the engine counterpart of the plugin update jar identity vet).
 *
 * The gate fails CLOSED on both failure modes:
 *
 * - A candidate that pins NO hash (the catalog lookup failed, or the release
 *   row predates the hash column) has no integrity anchor at all. The archive
 *   is extracted into the engine directory and its binaries are later
 *   EXECUTED, and the backup runs precisely when the primary source is
 *   already misbehaving, so installing those bytes unverified was the
 *   residual gap the fallback-checksum fix (BossConsole#798 follow-up)
 *   recorded and pinned as "stays unverified". The plugin update path made
 *   the opposite call for jars whose identity cannot be read: refuse and
 *   keep the running plugin. Engines now match: no pinned hash, no install.
 * - Bytes that mismatch the pinned hash (corruption, a truncated transfer, a
 *   tampered mirror) are refused, as before.
 *
 * A refusal never disturbs the installed engine: [ChromiumAutoDownloader]
 * treats it like any failed candidate, falls through to the next source, and
 * the per-candidate finally already discards the temp file.
 */
internal object EngineArchiveIntegrityVet {
    private val logger = BossLogger.forComponent("EngineArchiveIntegrityVet")

    /**
     * Accept [archive] only when [candidate] pins a catalog sha256 and the
     * downloaded bytes match it. Every other outcome is a refusal, so a
     * partial or corrupted download can never be extracted.
     */
    fun vet(
        candidate: EngineDownloadCandidate,
        archive: File,
    ): Result<Unit> {
        val actualSha = runCatching { sha256Of(archive) }
        val refusal = refusalReason(candidate, actualSha)
        if (refusal == null) {
            logger.info(
                LogCategory.BROWSER,
                "Engine archive checksum verified",
                mapOf("source" to candidate.sourceName),
            )
            return Result.success(Unit)
        }
        logger.warn(
            LogCategory.BROWSER,
            "Refusing an engine archive that cannot be verified",
            mapOf(
                "source" to candidate.sourceName,
                "path" to archive.toString(),
            ),
            error = actualSha.exceptionOrNull(),
        )
        return Result.failure(IllegalStateException(refusal, actualSha.exceptionOrNull()))
    }

    /**
     * The full refusal message for [candidate] against [actualSha], or null
     * when the archive is verified. The mismatch arm keeps the wording the
     * fallback-checksum regression asserts on.
     */
    private fun refusalReason(
        candidate: EngineDownloadCandidate,
        actualSha: Result<String>,
    ): String? {
        val expected = candidate.sha256
        val actual = actualSha.getOrNull()
        return when {
            expected.isNullOrBlank() -> {
                "Engine archive from ${candidate.sourceName} refused: no catalog checksum pins it, " +
                    "so its integrity cannot be verified. The engine was not installed."
            }

            actual == null -> {
                "Engine archive from ${candidate.sourceName} refused: its checksum could not be " +
                    "computed. The engine was not installed."
            }

            !expected.equals(actual, ignoreCase = true) -> {
                "Engine archive checksum mismatch from ${candidate.sourceName} " +
                    "(expected $expected, got $actual). The engine was not installed."
            }

            else -> {
                null
            }
        }
    }
}
