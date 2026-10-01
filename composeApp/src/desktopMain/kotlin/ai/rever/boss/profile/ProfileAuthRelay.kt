package ai.rever.boss.profile

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthFlowMarker
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.net.URI

/**
 * Delivers a sign-in callback to the separate-account BOSS profile that is waiting for it.
 *
 * The OS has a single `boss://` handler, so every sign-in callback lands in the MAIN profile's
 * process, including one for a sign-in a profile started. Each process records when it starts
 * such a flow ([AuthFlowMarker]); the main profile hands the callback to the process with the
 * most recent pending flow, and keeps it itself when that is its own or nobody else is waiting.
 *
 * Only the callbacks a marker can stand for are ever relayed: `boss://auth/verify` (a magic link,
 * marked by `AuthService.sendMagicLink`) and `boss://auth/callback` (a Google / Apple sign-in).
 * Passkey links and every other `boss://auth` link - email confirmation, invites, recovery - stay
 * in the main process whatever markers exist, so a stale marker can never take them away.
 *
 * Misdelivery cannot sign the wrong profile in: completing a Google / Apple sign-in needs the
 * PKCE verifier, which only the process that started it holds, and the receiving process acts on
 * a callback only while a flow of its own is waiting.
 */
object ProfileAuthRelay {
    private val logger = BossLogger.forComponent("ProfileAuthRelay")

    private const val AUTH_HOST = "auth"

    /** The `boss://auth/<path>` callbacks a pending-flow marker stands for. */
    private val RELAYED_PATHS = setOf("/verify", "/callback")

    /**
     * Forwards [uri] to the waiting profile. True when a profile accepted it (the caller must
     * then drop it); false when this process should handle it, as it would have before.
     * Only the main profile relays: a link reaching a profile was already routed to it.
     */
    fun relayIfAwaitedElsewhere(uri: String): Boolean {
        if (!isRelayable(uri)) return false
        val target =
            waitingProfileIds()
                .asSequence()
                .filter { BossProfileLauncher.isRunning(it) }
                .firstOrNull { profileId ->
                    SingleInstanceManager.sendToInstanceAt(
                        BossProfileLauncher.runtimeDirOf(profileId),
                        uri,
                        DeepLinkOrigin.EXTERNAL,
                    )
                }
        target?.let {
            logger.info(LogCategory.AUTH, "Relayed a sign-in callback to a BOSS profile", mapOf("profileId" to it))
        }
        return target != null
    }

    /**
     * Whether [uri] is a sign-in callback some OTHER profile is waiting for. Reads only the
     * profiles' small marker files - no `profile.json`, no channel traffic - so the caller can keep
     * its ordinary synchronous path whenever this is false, which with no profiles is always.
     */
    fun mightRelay(uri: String): Boolean = isRelayable(uri) && waitingProfileIds().isNotEmpty()

    internal fun isRelayable(uri: String): Boolean {
        if (BossDirectories.isProfile) return false
        val parsed = runCatching { URI(uri) }.getOrNull()
        return parsed?.scheme.equals("boss", ignoreCase = true) &&
            parsed?.host.equals(AUTH_HOST, ignoreCase = true) &&
            parsed?.path?.lowercase()?.trimEnd('/') in RELAYED_PATHS
    }

    /** Profiles whose pending sign-in is newer than this process's own, most recent first. */
    internal fun waitingProfileIds(now: Long = System.currentTimeMillis()): List<String> {
        val own = AuthFlowMarker.pendingSince(BossDirectories.rootDir, now) ?: Long.MIN_VALUE
        return BossDirectories
            .profilesDir()
            .listFiles { file: File -> file.isDirectory && BossDirectories.isValidProfileId(file.name) }
            .orEmpty()
            .mapNotNull { dir -> AuthFlowMarker.pendingSince(dir, now)?.let { dir.name to it } }
            .filter { (_, since) -> since > own }
            .sortedByDescending { (_, since) -> since }
            .map { (id, _) -> id }
    }
}
