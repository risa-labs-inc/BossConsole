package ai.rever.boss.plugin.repository.remote

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1: `PluginStoreConfig.decodeIsAdmin` used to substring-match the whole JWT payload,
 * so user-writable `user_metadata` containing the word "admin" flipped the client-side
 * admin flag. It now reads only the top-level `is_admin` claim as a JSON boolean.
 *
 * Exercised through the real `accessToken` setter, not a lookalike parser.
 */
class PluginStoreConfigIsAdminTest {
    @AfterTest
    fun clearConfig() {
        PluginStoreConfig.clear()
    }

    private fun jwtWithPayload(payloadJson: String): String = jwtWithRawPayload(payloadJson.toByteArray(Charsets.UTF_8))

    private fun jwtWithRawPayload(payloadBytes: ByteArray): String {
        fun b64(bytes: ByteArray): String =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes)
        val header = JWT_HEADER_JSON.toByteArray(Charsets.UTF_8)
        return b64(header) + "." + b64(payloadBytes) + ".test-signature"
    }

    private companion object {
        const val JWT_HEADER_JSON = """{"alg":"HS256","typ":"JWT"}"""
    }

    private fun isAdminForPayload(payloadJson: String): Boolean {
        PluginStoreConfig.accessToken = jwtWithPayload(payloadJson)
        return PluginStoreConfig.isAdmin
    }

    @Test
    fun `a top-level JSON boolean true is admin`() {
        assertTrue(isAdminForPayload("""{"sub":"user-1","is_admin":true}"""))
    }

    @Test
    fun `a top-level true stays admin when user metadata mentions admin`() {
        assertTrue(
            isAdminForPayload(
                """{"sub":"user-1","is_admin":true,"user_metadata":{"note":"not an admin"}}""",
            ),
        )
    }

    @Test
    fun `whitespace-formatted valid JSON with true is admin`() {
        assertTrue(isAdminForPayload("{\n  \"sub\" : \"user-1\",\n  \"is_admin\" : true\n}"))
    }

    @Test
    fun `user metadata containing the word admin is not admin`() {
        assertFalse(
            isAdminForPayload(
                """{"sub":"user-1","user_metadata":{"note":"admin"}}""",
            ),
        )
    }

    @Test
    fun `a nested user metadata is_admin boolean is not admin`() {
        assertFalse(
            isAdminForPayload(
                """{"sub":"user-1","user_metadata":{"is_admin":true}}""",
            ),
        )
    }

    @Test
    fun `a top-level is_admin JSON string true is not admin`() {
        assertFalse(isAdminForPayload("""{"sub":"user-1","is_admin":"true"}"""))
    }

    @Test
    fun `a top-level is_admin false is not admin`() {
        assertFalse(isAdminForPayload("""{"sub":"user-1","is_admin":false}"""))
    }

    @Test
    fun `a missing is_admin claim is not admin`() {
        assertFalse(isAdminForPayload("""{"sub":"user-1"}"""))
    }

    @Test
    fun `a token with fewer than three parts is not admin`() {
        PluginStoreConfig.accessToken = "header-only"
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `an undecodable payload is not admin`() {
        PluginStoreConfig.accessToken = "eyJhbGciOiJIUzI1NiJ9.!!!not-base64!!!.sig"
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `a non-JSON payload is not admin`() {
        PluginStoreConfig.accessToken = jwtWithPayload("just a string")
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `a null token is not admin`() {
        PluginStoreConfig.accessToken = null
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `invalid UTF-8 payload bytes are not admin`() {
        PluginStoreConfig.accessToken = jwtWithRawPayload(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D))
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `an oversized payload is not admin even with a true claim`() {
        val pad = "x".repeat(20_000)
        assertFalse(isAdminForPayload("""{"is_admin":true,"pad":"$pad"}"""))
    }

    @Test
    fun `nesting past the depth bound is not admin even with a true claim`() {
        // Balanced and otherwise valid JSON: only the lexical depth guard rejects it.
        // Braces inside the string value must not count toward depth.
        val deep = "[".repeat(40) + "]".repeat(40)
        assertFalse(isAdminForPayload("""{"is_admin":true,"note":"{[","d":$deep}"""))
    }

    @Test
    fun `replacing an admin token with a non-admin token clears the flag`() {
        PluginStoreConfig.accessToken = jwtWithPayload("""{"is_admin":true}""")
        assertTrue(PluginStoreConfig.isAdmin)
        PluginStoreConfig.accessToken = jwtWithPayload("""{"sub":"user-1"}""")
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `an empty header or signature segment is not admin`() {
        // A structurally valid three-segment envelope with an empty segment
        // (".<payload>." shape) must not decode (Astra review).
        val payload =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"is_admin":true}""".toByteArray(Charsets.UTF_8))
        PluginStoreConfig.accessToken = ".$payload."
        assertFalse(PluginStoreConfig.isAdmin)
        PluginStoreConfig.accessToken = ".$payload.sig"
        assertFalse(PluginStoreConfig.isAdmin)
    }

    @Test
    fun `legacy role and store_admin entries are not admin`() {
        assertFalse(isAdminForPayload("""{"sub":"user-1","roles":["store_admin"]}"""))
        assertFalse(isAdminForPayload("""{"sub":"user-1","user_roles":["admin"]}"""))
        assertFalse(isAdminForPayload("""{"sub":"user-1","admin":true}"""))
    }

    @Test
    fun `initialize with an admin token reports admin and clear resets it`() {
        PluginStoreConfig.initialize(
            "https://example.invalid/functions/v1",
            "anon-key",
            jwtWithPayload("""{"sub":"user-1","is_admin":true}"""),
        )
        assertTrue(PluginStoreConfig.isAdmin)
        PluginStoreConfig.clear()
        assertFalse(PluginStoreConfig.isAdmin)
    }
}
