package ai.rever.boss.service.auth

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Admin status and permissions come from the access token's claims, which only the server's
 * `custom_access_token_hook` can set - never from `user_metadata`, which the user can edit.
 */
class AccessTokenClaimsTest {
    private fun token(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"HS256","typ":"JWT"}""", payload)
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) } + ".signature"
    }

    @Test
    fun `the hook's claims are read as issued`() {
        val claims =
            accessTokenClaims(
                token("""{"sub":"u1","is_admin":true,"user_permissions":["role.read","plugins.create"]}"""),
            )
        assertEquals(AccessTokenClaims(isAdmin = true, permissions = setOf("role.read", "plugins.create")), claims)
    }

    @Test
    fun `missing claims grant nothing`() {
        assertEquals(AccessTokenClaims.NONE, accessTokenClaims(token("""{"sub":"u1"}""")))
        val explicitNone = token("""{"sub":"u1","is_admin":false,"user_permissions":[]}""")
        assertEquals(AccessTokenClaims.NONE, accessTokenClaims(explicitNone))
    }

    @Test
    fun `claims a user writes into user_metadata grant nothing`() {
        // What an account can give itself with updateUser(data = {...}).
        val selfAssigned =
            token("""{"sub":"u1","user_metadata":{"is_admin":true,"user_permissions":["role.read"]}}""")
        assertEquals(AccessTokenClaims.NONE, accessTokenClaims(selfAssigned))
    }

    @Test
    fun `only a value reading as true is an admin`() {
        assertEquals(false, accessTokenClaims(token("""{"is_admin":"yes"}"""))?.isAdmin)
        assertEquals(false, accessTokenClaims(token("""{"is_admin":1}"""))?.isAdmin)
        // The string "true" counts, as in the host's RoleService; the hook itself writes a boolean.
        assertEquals(true, accessTokenClaims(token("""{"is_admin":"true"}"""))?.isAdmin)
    }

    @Test
    fun `a non-primitive is_admin makes the whole token unreadable, permissions included`() {
        assertNull(accessTokenClaims(token("""{"is_admin":{"value":true},"user_permissions":["role.read"]}""")))
        assertNull(accessTokenClaims(token("""{"is_admin":[true],"user_permissions":["role.read"]}""")))
    }

    @Test
    fun `only string permission entries count`() {
        val claims = accessTokenClaims(token("""{"user_permissions":["role.read",1,true,null,["x"],{"y":1}]}"""))
        assertEquals(setOf("role.read"), claims?.permissions)
        assertEquals(emptySet(), accessTokenClaims(token("""{"user_permissions":"role.read"}"""))?.permissions)
    }

    @Test
    fun `an unreadable token is reported as unreadable`() {
        assertNull(accessTokenClaims("not-a-jwt"))
        assertNull(accessTokenClaims("a.%%%.c"))
        assertNull(accessTokenClaims(token("""not json""")))
    }
}
