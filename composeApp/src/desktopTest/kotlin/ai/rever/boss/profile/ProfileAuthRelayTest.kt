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

/** Runs in the main profile of composeApp's hermetic test home. */
class ProfileAuthRelayTest {
    private val a = AuthFlowMarker.hashEmail("a@example.com")
    private val b = AuthFlowMarker.hashEmail("b@example.com")

    private fun magic(
        at: Long,
        email: String?,
    ) = Flow(Kind.MAGIC_LINK, at, email)

    private fun oauth(at: Long) = Flow(Kind.OAUTH, at)

    private fun magicRoute(
        own: Flow?,
        vararg waiting: Pair<String, Flow>,
    ) = ProfileAuthRelay.route(Kind.MAGIC_LINK, own, waiting.toList(), running = waiting.map { it.first }.toSet())

    private fun oauthRoute(
        own: Flow?,
        vararg waiting: Pair<String, Flow>,
    ) = ProfileAuthRelay.route(Kind.OAUTH, own, waiting.toList(), running = waiting.map { it.first }.toSet())

    private fun writeFlow(
        root: File,
        kind: Kind,
        at: Long,
        emailHash: String? = null,
    ) {
        val hash = emailHash?.let { ""","emailHash":"$it"""" }.orEmpty()
        write(root, """{"kind":"${kind.name}","startedAtMs":$at$hash}""")
    }

    @BeforeEach
    @AfterEach
    fun cleanUp() {
        BossDirectories.profilesDir().deleteRecursively()
        AuthFlowMarker.clear()
    }

    @Test
    fun `only the two sign-in callbacks are ever routed`() {
        assertEquals(Kind.MAGIC_LINK, ProfileAuthRelay.kindOf("boss://auth/verify?token=x"))
        assertEquals(Kind.MAGIC_LINK, ProfileAuthRelay.kindOf("boss://auth/verify/?token=x"))
        assertEquals(Kind.OAUTH, ProfileAuthRelay.kindOf("boss://auth/callback?code=x"))
        listOf(
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
    fun `an oauth callback goes to the newest flow first and falls back here`() {
        assertEquals(
            Route.Offer(listOf("newer"), fallback = Outcome.KEEP),
            oauthRoute(oauth(2), "older" to oauth(1), "newer" to oauth(3)),
        )
        assertEquals(Route.KeepHere, oauthRoute(oauth(5), "p" to oauth(1)))
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
        writeFlow(BossDirectories.profileRoot("oauth"), Kind.OAUTH, now - 1_000)
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
    }

    private fun write(
        root: File,
        json: String,
    ) {
        AuthFlowMarker.fileFor(root).apply { parentFile.mkdirs() }.writeText(json)
    }
}
