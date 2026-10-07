package ai.rever.boss.services.auth

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.services.auth.AuthFlowMarker.Kind
import ai.rever.boss.services.supabase.AuthService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [AuthService.verifyEmail]'s choice between the quarantined exchange and the legacy one. */
class AuthServiceMagicLinkQuarantineTest {
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val verifyEntered = CompletableDeferred<Unit>()
    private val releaseVerify = CompletableDeferred<Unit>()

    private val savedEnabled = AuthService.profilesEnabled
    private val savedLegacy = AuthService.legacyVerify
    private val savedTransport = MagicLinkExchange.transport
    private val savedImporter = MagicLinkExchange.importer
    private val savedStillWanted = MagicLinkExchange.stillWanted

    @BeforeEach
    fun install() {
        clearMarker()
        AuthService.profilesEnabled = { true }
        AuthService.legacyVerify = { token, _ ->
            events += "legacy:$token"
            Result.success(Unit)
        }
        MagicLinkExchange.transport =
            object : MagicLinkExchange.Transport {
                override suspend fun verify(
                    tokenHash: String,
                    type: String,
                ): MagicLinkExchange.Minted {
                    events += "verify:$tokenHash"
                    verifyEntered.complete(Unit)
                    releaseVerify.await()
                    // Another account's session, so the winner fails without touching auth state.
                    return MagicLinkExchange.Minted("access", "refresh", 3600, "u", "b@example.com", "")
                }

                override suspend fun revoke(accessToken: String): Boolean = true
            }
        MagicLinkExchange.importer = {
            events += "import"
            Result.success(Unit)
        }
        MagicLinkExchange.stillWanted = { true }
    }

    @AfterEach
    fun restore() {
        AuthService.profilesEnabled = savedEnabled
        AuthService.legacyVerify = savedLegacy
        MagicLinkExchange.transport = savedTransport
        MagicLinkExchange.importer = savedImporter
        MagicLinkExchange.stillWanted = savedStillWanted
        clearMarker()
    }

    private fun clearMarker() {
        AuthFlowMarker.fileFor(BossDirectories.rootDir).delete()
        AuthFlowMarker.resetForTest()
    }

    @Test
    fun `of two concurrent deliveries of one link the loser never reaches the legacy exchange`(): Unit =
        runBlocking {
            AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
            val first = async(Dispatchers.Default) { AuthService.verifyEmail("tok") }
            withTimeout(5_000) { verifyEntered.await() }
            // The first caller holds the flow, suspended inside /verify.
            val second = async(Dispatchers.Default) { AuthService.verifyEmail("tok") }
            val secondResult = withTimeout(5_000) { second.await() }
            releaseVerify.complete(Unit)
            val firstResult = withTimeout(5_000) { first.await() }

            assertTrue(secondResult.isFailure, "the second caller is refused")
            assertTrue(firstResult.isFailure, "the minted account was not the flow's")
            assertEquals(listOf("verify:tok"), events.toList(), "one exchange, no legacy import, no import")
        }

    @Test
    fun `a main process that asked for no link keeps the legacy exchange`(): Unit =
        runBlocking {
            assertTrue(AuthService.verifyEmail("tok").isSuccess)
            assertEquals(listOf("legacy:tok"), events.toList())
        }

    @Test
    fun `with profiles off every link takes the legacy exchange, marker or not`(): Unit =
        runBlocking {
            AuthService.profilesEnabled = { false }
            AuthFlowMarker.mark(Kind.MAGIC_LINK, "a@example.com")
            assertTrue(AuthService.verifyEmail("tok").isSuccess)
            assertEquals(listOf("legacy:tok"), events.toList())
        }
}
