package ai.rever.boss.services.auth

import ai.rever.boss.services.auth.AuthFlowMarker.Flow
import ai.rever.boss.services.auth.AuthFlowMarker.Kind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The production seam of the quarantine: [MagicLinkExchange.exchange] with its transport and its
 * client import replaced, so the test sees every effect in order. The only step that touches the
 * live Supabase client is the importer; for a wrong or unreadable account it must never run.
 */
class MagicLinkExchangeTest {
    private val events = mutableListOf<String>()
    private val flowA = Flow(Kind.MAGIC_LINK, 0, AuthFlowMarker.hashEmail("a@example.com"), id = "g1")

    private var mintedEmail: String? = "a@example.com"
    private var verifyFails = false
    private var revokeThrows = false

    private val savedTransport = MagicLinkExchange.transport
    private val savedImporter = MagicLinkExchange.importer

    @BeforeEach
    fun install() {
        MagicLinkExchange.transport =
            object : MagicLinkExchange.Transport {
                override suspend fun verify(
                    tokenHash: String,
                    type: String,
                ): MagicLinkExchange.Minted {
                    events += "verify:$tokenHash"
                    check(!verifyFails) { "expired" }
                    return MagicLinkExchange.Minted("access", "refresh", 3600, "user-1", mintedEmail, "")
                }

                override suspend fun revoke(accessToken: String): Boolean {
                    events += "revoke:$accessToken"
                    check(!revokeThrows) { "network down" }
                    return true
                }
            }
        MagicLinkExchange.importer = { minted ->
            events += "import:${minted.email}"
            Result.success(Unit)
        }
    }

    @AfterEach
    fun restore() {
        MagicLinkExchange.transport = savedTransport
        MagicLinkExchange.importer = savedImporter
    }

    @Test
    fun `the right account is imported, after it is checked`(): Unit =
        runBlocking {
            assertTrue(MagicLinkExchange.exchange("tok", "magiclink", flowA).isSuccess)
            assertEquals(listOf("verify:tok", "import:a@example.com"), events)
        }

    @Test
    fun `account matching ignores case and surrounding space`(): Unit =
        runBlocking {
            mintedEmail = " A@Example.COM"
            assertTrue(MagicLinkExchange.exchange("tok", "magiclink", flowA).isSuccess)
        }

    @Test
    fun `a session for another account is revoked and never imported`(): Unit =
        runBlocking {
            mintedEmail = "b@example.com"
            val result = MagicLinkExchange.exchange("tok", "magiclink", flowA)
            assertIs<MagicLinkExchange.WrongAccountException>(result.exceptionOrNull())
            assertEquals(listOf("verify:tok", "revoke:access"), events)
        }

    @Test
    fun `a session whose account cannot be read is never imported`(): Unit =
        runBlocking {
            mintedEmail = null
            assertTrue(MagicLinkExchange.exchange("tok", "magiclink", flowA).isFailure)
            assertTrue(events.none { it.startsWith("import") }, events.toString())
        }

    @Test
    fun `a failed revoke still never imports the wrong account`(): Unit =
        runBlocking {
            mintedEmail = "b@example.com"
            revokeThrows = true
            val result = MagicLinkExchange.exchange("tok", "magiclink", flowA)
            assertIs<MagicLinkExchange.WrongAccountException>(result.exceptionOrNull())
            assertTrue(events.none { it.startsWith("import") }, events.toString())
        }

    @Test
    fun `a link GoTrue refuses imports nothing`(): Unit =
        runBlocking {
            verifyFails = true
            assertTrue(MagicLinkExchange.exchange("tok", "magiclink", flowA).isFailure)
            assertEquals(listOf("verify:tok"), events)
        }

    @Test
    fun `a flow with no account to check against does not spend the link`(): Unit =
        runBlocking {
            val noAccount = flowA.copy(emailHash = null)
            assertTrue(MagicLinkExchange.exchange("tok", "magiclink", noAccount).isFailure)
            assertEquals(emptyList(), events, "the token is not even sent")
        }
}
