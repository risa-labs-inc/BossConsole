package ai.rever.boss.profile

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.profile.ProfileAuthRelay.Outcome
import ai.rever.boss.profile.ProfileAuthRelay.Route
import ai.rever.boss.services.auth.AuthFlowMarker
import ai.rever.boss.services.auth.AuthFlowMarker.Flow
import ai.rever.boss.services.auth.AuthFlowMarker.Kind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import ai.rever.boss.utils.SingleInstanceManager.AuthClaimAnswer as Answer

/** Runs in the main profile of composeApp's hermetic test home. */
class ProfileAuthRelayTest {
    private val a = AuthFlowMarker.hashEmail("a@example.com")
    private val b = AuthFlowMarker.hashEmail("b@example.com")

    private fun magic(
        at: Long,
        email: String?,
    ) = Flow(Kind.MAGIC_LINK, at, email)

    private fun magicRoute(
        own: Flow?,
        vararg waiting: Pair<String, Flow>,
    ) = ProfileAuthRelay.route(Kind.MAGIC_LINK, own, waiting.toList(), running = waiting.map { it.first }.toSet())

    private fun writeFlow(
        root: File,
        kind: Kind,
        at: Long,
        emailHash: String? = null,
    ) {
        val hash = emailHash?.let { ""","emailHash":"$it"""" }.orEmpty()
        write(root, """{"kind":"${kind.name}","startedAtMs":$at$hash}""")
    }

    private val savedClaimer = ProfileAuthRelay.claimer
    private val savedRoutes = ProfileAuthRelay.routes

    @BeforeEach
    @AfterEach
    fun cleanUp() {
        BossDirectories.profilesDir().deleteRecursively()
        AuthFlowMarker.fileFor(BossDirectories.rootDir).delete()
        AuthFlowMarker.resetForTest()
        ProfileAuthRelay.claimer = savedClaimer
    }

    /** The main process of an opted-in run; the off state is set per test. */
    @BeforeEach
    fun routeAsMain() {
        ProfileAuthRelay.routes = { true }
    }

    @AfterEach
    fun restoreRoutes() {
        ProfileAuthRelay.routes = savedRoutes
    }

    @Test
    fun `with profiles off the main process keeps every link, whatever a profile waits for`() {
        ProfileAuthRelay.routes = { false }
        writeFlow(BossDirectories.profileRoot("waiting"), Kind.MAGIC_LINK, System.currentTimeMillis(), a)
        assertFalse(ProfileAuthRelay.mightRelay("boss://auth/verify?token=x"))
        assertEquals(Outcome.KEEP, ProfileAuthRelay.relay("boss://auth/verify?token=x"))
    }

    @Test
    fun `only magic links are ever routed`() {
        assertEquals(Kind.MAGIC_LINK, ProfileAuthRelay.kindOf("boss://auth/verify?token=x"))
        assertEquals(Kind.MAGIC_LINK, ProfileAuthRelay.kindOf("boss://auth/verify?token=x&type=signup"))
        listOf(
            // No process records an OAuth flow yet, so its callback always stays in the main process.
            "boss://auth/callback?code=x",
            // /verify also carries recovery and invite links, which are never routed.
            "boss://auth/verify?token=x&type=recovery",
            "boss://auth/verify?token=x&type=invite",
            // An OS-mangled form no process can parse is not routed; nothing could claim it.
            "boss://auth/verify/?token=x",
            "boss://passkey/authenticated?session=x",
            "boss://auth/recovery?token=x",
            "boss://auth",
            "boss://workspace?path=/x",
            "https://auth/verify",
            "not a uri",
        ).forEach { assertNull(ProfileAuthRelay.kindOf(it), it) }
    }

    @Test
    fun `with no profile waiting the link stays here, as before profiles`() {
        assertEquals(Route.KeepHere, magicRoute(null))
        assertEquals(Route.KeepHere, magicRoute(magic(1, a)))
    }

    @Test
    fun `main with no flow and one profile waiting offers it there, and keeps it from main if refused`() {
        val route = magicRoute(null, "p" to magic(1, b))
        assertEquals(Route.Offer(listOf("p"), fallback = Outcome.REFUSED), route)
    }

    @Test
    fun `flows for different accounts make a magic link ambiguous, so it is used nowhere`() {
        assertEquals(
            Route.Refuse,
            magicRoute(magic(1, a), "p" to magic(2, b)),
        )
        assertEquals(
            Route.Refuse,
            magicRoute(null, "p" to magic(1, a), "q" to magic(2, b)),
        )
    }

    @Test
    fun `an account that cannot be told is ambiguous too`() {
        assertEquals(Route.Refuse, magicRoute(null, "p" to magic(1, null)))
    }

    @Test
    fun `one account waiting in several places is kept here when main asked for it`() {
        assertEquals(
            Route.KeepHere,
            magicRoute(magic(1, a), "p" to magic(2, a)),
        )
        assertEquals(
            Route.Offer(listOf("q", "p"), fallback = Outcome.REFUSED),
            magicRoute(null, "p" to magic(1, a), "q" to magic(2, a)),
        )
    }

    @Test
    fun `a dead profile's flow keeps the link from being spent here when this process asked for none`() {
        val dead = listOf("dead" to magic(1, b))
        val route = ProfileAuthRelay.route(Kind.MAGIC_LINK, own = null, allWaiting = dead, running = emptySet())
        assertEquals(Route.Offer(emptyList(), fallback = Outcome.REFUSED), route)
    }

    @Test
    fun `a dead profile's flow cannot block this process's own sign-in`() {
        val route =
            ProfileAuthRelay.route(
                Kind.MAGIC_LINK,
                own = magic(2, a),
                allWaiting = listOf("dead" to magic(1, b)),
                running = emptySet(),
            )
        assertEquals(Route.KeepHere, route)
    }

    @Test
    fun `waiting flows are read from the profiles' markers, live ones only`() {
        val now = System.currentTimeMillis()
        writeFlow(BossDirectories.profileRoot("live"), Kind.MAGIC_LINK, now - 1_000, a)
        writeFlow(BossDirectories.profileRoot("stale"), Kind.MAGIC_LINK, now - Kind.MAGIC_LINK.maxAgeMs - 1)
        write(BossDirectories.profileRoot("oauth"), """{"kind":"OAUTH","startedAtMs":${now - 1_000}}""")
        writeFlow(File(BossDirectories.profilesDir(), "Not Valid"), Kind.MAGIC_LINK, now - 1_000)
        assertEquals(listOf("live"), ProfileAuthRelay.waitingFlows(Kind.MAGIC_LINK, now).map { it.first })
    }

    @Test
    fun `a waiting profile that is not running cannot take the link, and main does not spend it either`() {
        writeFlow(BossDirectories.profileRoot("not-running"), Kind.MAGIC_LINK, System.currentTimeMillis(), b)
        // Not running means no one to offer it to; the link was most likely theirs, so it is refused.
        assertEquals(Outcome.REFUSED, ProfileAuthRelay.relay("boss://auth/verify?token=x"))
    }

    @Test
    fun `the main process never claims a link for itself through the profile path`() {
        AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
        assertFalse(ProfileAuthRelay.claimHere("boss://auth/verify?token=x"))
        AuthFlowMarker.fileFor(BossDirectories.rootDir).delete()
    }

    @Test
    fun `a lost reply from the first receiver stops the offer, so the second never gets the link`() {
        val offered = mutableListOf<String>()
        ProfileAuthRelay.claimer = { id, _ ->
            offered += id
            if (id == "first") Answer.NO_ANSWER else Answer.CLAIMED
        }
        val now = System.currentTimeMillis()
        // Running state is decided by the launcher; route the decision directly with both live.
        val route =
            ProfileAuthRelay.route(
                Kind.MAGIC_LINK,
                null,
                listOf("first" to magic(now, b), "second" to magic(now - 1, b)),
                setOf("first", "second"),
            )
        assertEquals(Route.Offer(listOf("first", "second"), fallback = Outcome.REFUSED), route)
        val outcome = ProfileAuthRelay.offerForTest(route as Route.Offer, "boss://auth/verify?token=x")
        assertEquals(Outcome.UNCERTAIN, outcome)
        assertEquals(listOf("first"), offered)
    }

    @Test
    fun `a receiver that declines passes the link to the next, and one that claims ends the offer`() {
        val offered = mutableListOf<String>()
        ProfileAuthRelay.claimer = { id, _ ->
            offered += id
            if (id == "first") Answer.DECLINED else Answer.CLAIMED
        }
        val offer = Route.Offer(listOf("first", "second", "third"), fallback = Outcome.REFUSED)
        assertEquals(Outcome.RELAYED, ProfileAuthRelay.offerForTest(offer, "boss://auth/verify?token=x"))
        assertEquals(listOf("first", "second"), offered)
    }

    @Test
    fun `when every receiver declines the fallback decides, and for a magic link that is refusal`() {
        ProfileAuthRelay.claimer = { _, _ -> Answer.DECLINED }
        val offer = Route.Offer(listOf("first", "second"), fallback = Outcome.REFUSED)
        assertEquals(Outcome.REFUSED, ProfileAuthRelay.offerForTest(offer, "boss://auth/verify?token=x"))
    }

    private fun write(
        root: File,
        json: String,
    ) {
        AuthFlowMarker.fileFor(root).apply { parentFile.mkdirs() }.writeText(json)
    }
}
