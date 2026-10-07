package ai.rever.boss.services.auth

import ai.rever.boss.mcp.secrets.captureHostLogs
import ai.rever.boss.services.auth.AuthFlowMarker.Flow
import ai.rever.boss.services.auth.AuthFlowMarker.Kind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    private var wanted = true
    private var wantedAfterVerify = true

    private val savedTransport = MagicLinkExchange.transport
    private val savedImporter = MagicLinkExchange.importer
    private val savedStillWanted = MagicLinkExchange.stillWanted

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
                    wanted = wantedAfterVerify
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
        MagicLinkExchange.stillWanted = { wanted }
    }

    @AfterEach
    fun restore() {
        MagicLinkExchange.transport = savedTransport
        MagicLinkExchange.importer = savedImporter
        MagicLinkExchange.stillWanted = savedStillWanted
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

    @Test
    fun `a superseded or expired flow does not even spend the link`(): Unit =
        runBlocking {
            wanted = false
            assertIs<MagicLinkExchange.SupersededException>(
                MagicLinkExchange.exchange("tok", "magiclink", flowA).exceptionOrNull(),
            )
            assertEquals(emptyList(), events)
        }

    @Test
    fun `a flow superseded while verify was suspended is revoked and never imported`(): Unit =
        runBlocking {
            wantedAfterVerify = false
            assertIs<MagicLinkExchange.SupersededException>(
                MagicLinkExchange.exchange("tok", "magiclink", flowA).exceptionOrNull(),
            )
            assertEquals(listOf("verify:tok", "revoke:access"), events)
        }

    @Test
    fun `a success body that does not decode puts no token in the failure or the log`(): Unit =
        runBlocking {
            val access = "eyJ-access-SECRET-1"
            val refresh = "refresh-SECRET-2"
            // A 2xx with tokens but without a required field: kotlinx quotes the input on failure.
            val body = """{"access_token":"$access","refresh_token":"$refresh","user":{"id":"u"}}"""
            val engine =
                MockEngine {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val transport = MagicLinkExchange.HttpTransport(engine, { "https://example.invalid" }, { "anon" })
            val (failure, logged) =
                captureHostLogs {
                    runBlocking { runCatching { transport.verify("tok", "magiclink") }.exceptionOrNull() }
                }
            val error = assertNotNull(failure)
            assertNull(error.cause, "the decoder's exception carries the body")
            val message = error.message.orEmpty()
            assertFalse(message.contains(access) || message.contains(refresh), message)
            assertTrue(logged.isNotEmpty(), "the decode failure is logged")
            for (entry in logged) {
                val text = "${entry.message} ${entry.data} ${entry.error}"
                assertFalse(text.contains(access) || text.contains(refresh), text)
            }
        }
}
