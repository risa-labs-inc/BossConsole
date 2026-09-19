package ai.rever.boss.service.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `isAdmin` comes from the access token's `is_admin` claim, which only the server's
 * `custom_access_token_hook` can set - never from `user_metadata`, which the user can edit.
 */
class AdminClaimTest {
    private fun token(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"HS256","typ":"JWT"}""", payload)
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) } + ".signature"
    }

    @Test
    fun `the server's is_admin claim makes an admin`() {
        assertEquals(true, isAdminClaim(token("""{"sub":"u1","is_admin":true}""")))
    }

    @Test
    fun `no claim, or a false one, is not an admin`() {
        assertEquals(false, isAdminClaim(token("""{"sub":"u1"}""")))
        assertEquals(false, isAdminClaim(token("""{"sub":"u1","is_admin":false}""")))
    }

    @Test
    fun `is_admin in user_metadata grants nothing`() {
        // What an account can give itself with updateUser(data = {"is_admin": true}).
        val selfAssigned = token("""{"sub":"u1","user_metadata":{"is_admin":true}}""")
        assertEquals(false, isAdminClaim(selfAssigned))
    }

    @Test
    fun `an unreadable token is reported as unreadable`() {
        assertNull(isAdminClaim("not-a-jwt"))
        assertNull(isAdminClaim("a.%%%.c"))
        assertNull(isAdminClaim(token("""not json""")))
    }

    @Test
    fun `anything but a strict boolean is not an admin`() {
        assertEquals(false, isAdminClaim(token("""{"is_admin":"yes"}""")))
        assertEquals(false, isAdminClaim(token("""{"is_admin":1}""")))
    }
}
