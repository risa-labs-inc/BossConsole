package ai.rever.boss.profile

import ai.rever.boss.components.auth.AuthDeepLink
import ai.rever.boss.components.auth.AuthDeepLinks
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthFlowMarker
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.net.URI

/**
 * Routes a sign-in callback to the separate-account BOSS profile that is waiting for it.
 *
 * The OS has a single `boss://` handler, so every sign-in callback lands in the MAIN profile's
 * process, including one for a sign-in a profile started. Each process records the flow it starts
 * ([AuthFlowMarker]) and the main process routes by those records ([route]):
 *
 * - **Nothing else waits:** the link stays here, exactly as before profiles existed.
 * - **A magic link** (`boss://auth/verify`) carries nothing that says who asked for it, so routing
 *   rests on the account each flow was sent to. Flows for different accounts waiting at once make
 *   the link ambiguous and it is used nowhere; the user requests a new one from the right window.
 *   One account waiting in profiles only: the profiles are offered it, newest first.
 * - **A Google / Apple callback** (`boss://auth/callback`) is not routed yet: no process records an
 *   OAuth flow, so it always stays in the main process, and a separate-account profile signs in by
 *   magic link only.
 *
 * An offer is [SingleInstanceManager.claimAuthAt]: the profile takes the link only after it has
 * atomically claimed a pending flow of its own, so a link is acted on by one process at most. A
 * magic link that no waiting profile claims is not handed back to the main process to spend either -
 * one was waiting, so the link was most likely theirs.
 *
 * Only magic links are ever routed. Passkey links and every other `boss://auth` link -
 * email confirmation, invites, recovery - stay in the main process whatever markers exist.
 */
object ProfileAuthRelay {
    private val logger = BossLogger.forComponent("ProfileAuthRelay")

    private const val AUTH_HOST = "auth"

    /** What the main process does with a callback. */
    enum class Outcome {
        /** Handle it here, as without profiles. */
        KEEP,

        /** A profile claimed it; drop it here. */
        RELAYED,

        /** Ambiguous or unclaimed: act on it nowhere, and tell the user to request a new link. */
        REFUSED,

        /**
         * Offered, and the reply was lost: a profile may have taken it, so it is offered nowhere
         * else and not kept here either.
         */
        UNCERTAIN,
    }

    /** How a profile is offered a callback; replaceable so lost replies can be tested. */
    internal var claimer: (profileId: String, uri: String) -> SingleInstanceManager.AuthClaimAnswer = { id, uri ->
        SingleInstanceManager.claimAuthAt(BossProfileLauncher.runtimeDirOf(id), uri)
    }

    /** The routing decision for one callback, before any profile is asked. */
    internal sealed interface Route {
        data object KeepHere : Route

        data object Refuse : Route

        /** Offer to these profiles in order; [fallback] when none claims it. */
        data class Offer(
            val profileIds: List<String>,
            val fallback: Outcome,
        ) : Route
    }

    /**
     * Routes [uri]. Only meaningful in the main process; a profile never relays.
     * Blocking: an offer is a channel round trip, so call it off the UI thread.
     */
    fun relay(uri: String): Outcome {
        val kind = kindOf(uri) ?: return Outcome.KEEP
        val now = System.currentTimeMillis()
        val waiting = waitingFlows(kind, now)
        val running = waiting.map { it.first }.filter(BossProfileLauncher::isRunning).toSet()
        val own = AuthFlowMarker.pending(BossDirectories.rootDir, kind, now)
        return when (val decision = route(kind, own, waiting, running)) {
            Route.KeepHere -> {
                Outcome.KEEP
            }

            Route.Refuse -> {
                logger.warn(LogCategory.AUTH, "Refused an ambiguous sign-in link: several accounts are waiting")
                Outcome.REFUSED
            }

            is Route.Offer -> {
                offer(decision, uri)
            }
        }
    }

    /**
     * Offers [uri] down [decision]'s list until a profile claims it. Only an explicit DECLINED moves
     * on to the next profile: after a lost reply the first one may hold the link, and a second
     * offer could have it exchanged twice.
     */
    internal fun offerForTest(
        decision: Route.Offer,
        uri: String,
    ): Outcome = offer(decision, uri)

    @Suppress("ReturnCount") // a claim and a lost reply each end the offer at once
    private fun offer(
        decision: Route.Offer,
        uri: String,
    ): Outcome {
        for (id in decision.profileIds) {
            when (claimer(id, uri)) {
                SingleInstanceManager.AuthClaimAnswer.CLAIMED -> {
                    logger.info(LogCategory.AUTH, "A BOSS profile claimed a sign-in callback", mapOf("profileId" to id))
                    return Outcome.RELAYED
                }

                SingleInstanceManager.AuthClaimAnswer.NO_ANSWER -> {
                    logger.warn(
                        LogCategory.AUTH,
                        "A sign-in offer got no reply; not offering it again",
                        mapOf("profileId" to id),
                    )
                    return Outcome.UNCERTAIN
                }

                SingleInstanceManager.AuthClaimAnswer.DECLINED -> {
                    Unit
                }
            }
        }
        return decision.fallback
    }

    /**
     * Whether [uri] needs routing at all: a routable callback while some profile waits for one.
     * Reads only the profiles' small marker files, so the caller keeps its ordinary synchronous
     * path whenever this is false, which with no profiles is always.
     */
    fun mightRelay(uri: String): Boolean {
        val kind = kindOf(uri) ?: return false
        return waitingFlows(kind, System.currentTimeMillis()).isNotEmpty()
    }

    /** The flow kind a routable callback completes, or null for every link that is never routed. */
    internal fun kindOf(uri: String): AuthFlowMarker.Kind? = if (BossDirectories.isProfile) null else callbackKind(uri)

    /** The routing rules, separated from the I/O so every case is testable. */
    internal fun route(
        kind: AuthFlowMarker.Kind,
        own: AuthFlowMarker.Flow?,
        allWaiting: List<Pair<String, AuthFlowMarker.Flow>>,
        running: Set<String>,
    ): Route {
        // A profile whose process is gone cannot finish its flow. While this process waits for a
        // link of its own such a flow is ignored, so it cannot block this sign-in; while this
        // process waits for none, it still counts, so a link that was most likely the dead
        // profile's is not spent here either (its offer list is simply empty).
        val waiting = if (own != null) allWaiting.filter { it.first in running } else allWaiting
        if (waiting.isEmpty()) return Route.KeepHere
        val newestFirst = waiting.sortedByDescending { (_, flow) -> flow.startedAtMs }.filter { it.first in running }
        check(kind == AuthFlowMarker.Kind.MAGIC_LINK)
        val accounts = (listOfNotNull(own) + waiting.map { it.second }).map { it.emailHash }.toSet()
        return when {
            // Different accounts (or an account that cannot be told) are waiting: the link
            // could sign any of them in, so it signs in none.
            accounts.size != 1 || accounts.single() == null -> Route.Refuse

            // One account, and this process asked for it too: same account, no hand-off.
            own != null -> Route.KeepHere

            else -> Route.Offer(newestFirst.map { it.first }, fallback = Outcome.REFUSED)
        }
    }

    /** Every profile's live pending flow of [kind]. */
    internal fun waitingFlows(
        kind: AuthFlowMarker.Kind,
        now: Long,
    ): List<Pair<String, AuthFlowMarker.Flow>> =
        BossDirectories
            .profilesDir()
            .listFiles { file: File -> file.isDirectory && BossDirectories.isValidProfileId(file.name) }
            .orEmpty()
            .mapNotNull { dir -> AuthFlowMarker.pending(dir, kind, now)?.let { dir.name to it } }

    /**
     * The receiving half, installed in every profile process: claim a pending flow of this
     * process's own for [uri], then act on the link. Answers false - and leaves the link alone -
     * when there is no such flow, so a stale or misrouted link is never spent here.
     */
    fun claimHere(uri: String): Boolean {
        // Bound to the link's own token, so the claimed flow can be redeemed by this link only.
        val token = (AuthDeepLinks.parse(uri) as? AuthDeepLink.MagicLinkVerify)?.token
        val kind = if (BossDirectories.isProfile) callbackKind(uri) else null
        val claimed =
            kind == AuthFlowMarker.Kind.MAGIC_LINK && token != null && AuthFlowMarker.claim(kind, token) != null
        if (claimed) DeepLinkHandler.processDeepLink(uri, DeepLinkOrigin.EXTERNAL)
        return claimed
    }

    /** [kindOf] without the main-process gate, for the receiving side. */
    private fun callbackKind(uri: String): AuthFlowMarker.Kind? {
        val parsed =
            runCatching { URI(uri) }
                .getOrNull()
                ?.takeIf { it.scheme.equals("boss", ignoreCase = true) && it.host.equals(AUTH_HOST, ignoreCase = true) }
        return when (parsed?.path?.lowercase()?.trimEnd('/')) {
            "/verify" -> AuthFlowMarker.Kind.MAGIC_LINK
            else -> null
        }
    }
}
