package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretEntry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A decode failure must never carry the response body into a log.
 *
 * `SecretService` returns its exceptions through `Result.failure` and its callers log
 * `e.message` - that is how the `org_id` WARN reached the log in the first place. The
 * bodies of these four RPCs contain passwords the server has already decrypted, and
 * recovery codes, so an exception that quotes the body writes live credentials into a file
 * users attach to bug reports.
 */
class SupabaseJsonTest {
    private val password = "SUPERSECRET_PW"
    private val recoveryCode = "RECOVERY_ABC"

    /** Truncated mid-array, as a cut connection or a proxy error would leave it. */
    private val malformedBody =
        """[{"id":"1","website":"github.com","password":"$password","recovery_codes":["$recoveryCode"] """

    @Test
    fun `a malformed body is not quoted back through the sanitiser`() {
        val raw = runCatching { supabaseJson.parseToJsonElement(malformedBody) }.exceptionOrNull()
        assertTrue(raw is SerializationException, "expected a serialization failure, got $raw")

        val safe = sanitizeSupabaseFailure("getUserSecrets", raw)

        val message = safe.message.orEmpty()
        assertFalse(message.contains(password), "password leaked into: $message")
        assertFalse(message.contains(recoveryCode), "recovery code leaked into: $message")
        assertFalse(message.contains("JSON input"), "body marker survived: $message")
    }

    @Test
    fun `the sanitiser is doing work, not decorating an already-safe message`() {
        // Canary. kotlinx appends the offending document to a PARSE failure today, which is
        // the entire reason sanitizeSupabaseFailure exists. If an upgrade stops doing that,
        // this fails - and that failure is worth having: it says the threat model moved and
        // the sanitiser may now be dead code, rather than letting it rot untested.
        val raw = runCatching { supabaseJson.parseToJsonElement(malformedBody) }.exceptionOrNull()

        assertTrue(
            raw?.message.orEmpty().contains(password),
            "kotlinx no longer quotes the input - re-evaluate whether the sanitiser is still needed",
        )
    }

    @Test
    fun `the diagnostic half of the message survives`() {
        // Stripping the whole message would trade a credential leak for an undiagnosable
        // outage. "Encountered an unknown key 'org_id'" is precisely what identified this bug.
        val body = """[{"id":"1","org_id":null}]"""
        val raw = runCatching { Json.decodeFromString<List<SecretEntry>>(body) }.exceptionOrNull()

        val safe = sanitizeSupabaseFailure("getUserSecrets", raw!!)

        assertTrue(safe.message.orEmpty().contains("getUserSecrets"), "operation name missing")
        assertTrue(safe.message.orEmpty().contains("org_id"), "key name missing: ${safe.message}")
    }

    @Test
    fun `schema failures were already safe and stay that way`() {
        // An unknown key or a null in a non-nullable slot names the key and the path and
        // nothing else. Sanitising uniformly is about not asking callers to know which is
        // which, so confirm the safe path is not made worse.
        val nullPassword =
            """[{"id":"1","website":"w","username":"u","password":null,"tags":[],""" +
                """"created_at":"x","updated_at":"x"}]"""
        val raw = runCatching { supabaseJson.decodeFromString<List<SecretEntry>>(nullPassword) }.exceptionOrNull()

        val safe = sanitizeSupabaseFailure("getUserSecrets", raw!!)

        assertFalse(safe.message.orEmpty().contains("JSON input"))
        assertTrue(safe.message.orEmpty().contains("password"), "should still name the offending field")
    }

    @Test
    fun `a non-serialization failure passes through untouched`() {
        // Network and auth failures are not ours to rewrite, and losing their type would
        // break any caller that distinguishes them.
        val original = IllegalStateException("connection reset")

        val result = sanitizeSupabaseFailure("getUserSecrets", original)

        assertEquals(original, result)
    }

    @Test
    fun `the sanitised failure carries no cause chain back to the payload`() {
        // Attaching the original as `cause` would put the body back within reach of any
        // handler that walks the chain, which defeats the whole exercise.
        val raw = runCatching { supabaseJson.parseToJsonElement(malformedBody) }.exceptionOrNull()!!

        val safe = sanitizeSupabaseFailure("getUserSecrets", raw)

        // assertNull for the same reason SecretDecodingTest argues for it: it takes Any? and
        // so keeps compiling if the type changes, failing for the real reason.
        assertNull(safe.cause)
        assertTrue(safe is SupabaseFailure)
    }
}

/**
 * The plugin-facing path, exercised for real.
 *
 * `SupabaseDataProviderImpl.rpc` parses caller-supplied `parameters` before it touches the
 * network, so a malformed params string fails without a backend - which makes this the one
 * leak path in the package that can be tested by execution rather than by reading source.
 *
 * It is worth executing because reasoning got it wrong twice. First I cleared this function
 * on the grounds that untyped parsing cannot fail on unknown keys (true, and beside the
 * point - it can still fail on malformed input). Then I sanitised its log and left the
 * returned exception built from the raw message, which strips the document from our log and
 * hands it to the plugin instead.
 */
class SupabaseDataProviderLeakTest {
    private val password = "SUPERSECRET_PW"

    @Test
    fun `a malformed create_secret params string does not return the password to the plugin`() =
        kotlinx.coroutines.runBlocking {
            // Truncated mid-object, as a serialisation bug or a cut write would leave it.
            val params = """{"p_website":"github.com","p_password":"$password" """

            val result = SupabaseDataProviderImpl().rpc("create_secret", params)

            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(result.isFailure, "expected the malformed params to fail")
            assertFalse(message.contains(password), "password returned to caller in: $message")
            assertFalse(message.contains("JSON input"), "body marker returned to caller in: $message")
            // Still says which call failed, or the plugin author is left with nothing.
            assertTrue(message.contains("create_secret"), "lost the function name: $message")
        }
}
