package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException

/**
 * Records that THIS process is waiting for a `boss://auth` callback (a Google / Apple sign-in, or
 * a magic link), so the callback can find its way back when several BOSS profiles are running.
 *
 * The OS has one handler for `boss://`, and it always delivers to the main profile's process,
 * whichever process started the sign-in. Each process therefore drops a timestamp in its own
 * `run/` directory when it starts a flow, and the main profile relays an auth callback to the
 * process whose flow is the most recent (see `ProfileAuthRelay`). The marker carries no secret:
 * the PKCE verifier stays in the starting process's own encrypted store, so a misrouted callback
 * fails its exchange rather than signing the wrong profile in.
 */
object AuthFlowMarker {
    private val logger = BossLogger.forComponent("AuthFlowMarker")

    private const val FILE_NAME = "auth-pending"

    /** A flow older than this is no longer waited for. Matches the OAuth flow's own limit. */
    const val MAX_AGE_MS = 15 * 60 * 1000L

    fun fileFor(root: File): File = File(File(root, "run"), FILE_NAME)

    /** This process started a sign-in that will complete through a `boss://auth` link. */
    fun mark() {
        val file = fileFor(BossDirectories.rootDir)
        try {
            file.parentFile.mkdirs()
            file.writeText(System.currentTimeMillis().toString())
        } catch (e: IOException) {
            logger.warn(LogCategory.AUTH, "Could not record the pending sign-in", error = e)
        }
    }

    /** The sign-in completed or was abandoned; stop claiming callbacks. */
    fun clear() {
        runCatching { fileFor(BossDirectories.rootDir).delete() }
    }

    /** When [root]'s process last started a flow, or null when it is not waiting for one. */
    fun pendingSince(
        root: File,
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val file = fileFor(root)
        val at = if (file.isFile) runCatching { file.readText().trim().toLong() }.getOrNull() else null
        return at?.takeIf { now - it in 0..MAX_AGE_MS }
    }
}
