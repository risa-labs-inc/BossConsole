package ai.rever.boss.profile

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthFlowMarker
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.net.URI

/**
 * Delivers a `boss://auth` (or `boss://passkey`) callback to the BOSS profile that is waiting
 * for it.
 *
 * The OS has a single `boss://` handler, so every sign-in callback lands in the MAIN profile's
 * process, including one for a sign-in a profile started. Each process records when it starts
 * such a flow ([AuthFlowMarker]); the main profile hands the callback to the process with the
 * most recent pending flow, and keeps it itself when that is its own or nobody else is waiting.
 *
 * Misdelivery cannot sign the wrong profile in: completing a Google / Apple sign-in needs the
 * PKCE verifier, which only the process that started it holds, and the receiving process acts on
 * a callback only while a flow of its own is waiting.
 */
object ProfileAuthRelay {
    private val logger = BossLogger.forComponent("ProfileAuthRelay")

    private val RELAYED_HOSTS = setOf("auth", "passkey")

    /**
     * Forwards [uri] to the waiting profile. True when a profile accepted it (the caller must
     * then drop it); false when this process should handle it, as it would have before.
     * Only the main profile relays: a link reaching a profile was already routed to it.
     */
    fun relayIfAwaitedElsewhere(uri: String): Boolean {
        val host = relayedHostOf(uri) ?: return false
        val target =
            waitingProfiles()
                .asSequence()
                .filter { BossProfileLauncher.isRunning(it.id) }
                .firstOrNull { profile ->
                    SingleInstanceManager.sendToInstanceAt(
                        BossProfileLauncher.runtimeDirOf(profile.id),
                        uri,
                        DeepLinkOrigin.EXTERNAL,
                    )
                }
        target?.let {
            logger.info(
                LogCategory.AUTH,
                "Relayed a sign-in callback to a BOSS profile",
                mapOf("profileId" to it.id, "host" to host),
            )
        }
        return target != null
    }

    /**
     * Whether [uri] is a sign-in callback some OTHER profile is waiting for. Local file reads
     * only, no channel traffic, so the caller can keep its ordinary synchronous path whenever
     * this is false - which, with no profiles in use, is always.
     */
    fun mightRelay(uri: String): Boolean = relayedHostOf(uri) != null && waitingProfiles().isNotEmpty()

    private fun relayedHostOf(uri: String): String? =
        if (BossDirectories.isProfile) {
            null
        } else {
            runCatching { URI(uri).host?.lowercase() }.getOrNull()?.takeIf { it in RELAYED_HOSTS }
        }

    /** Profiles whose pending sign-in is newer than this process's own, most recent first. */
    private fun waitingProfiles(): List<BossProfile> {
        if (!BossDirectories.profilesDir().isDirectory) return emptyList()
        val now = System.currentTimeMillis()
        val own = AuthFlowMarker.pendingSince(BossDirectories.rootDir, now) ?: Long.MIN_VALUE
        return BossProfileStore
            .list()
            .mapNotNull { profile -> AuthFlowMarker.pendingSince(profile.root, now)?.let { profile to it } }
            .filter { (_, since) -> since > own }
            .sortedByDescending { (_, since) -> since }
            .map { (profile, _) -> profile }
    }
}
