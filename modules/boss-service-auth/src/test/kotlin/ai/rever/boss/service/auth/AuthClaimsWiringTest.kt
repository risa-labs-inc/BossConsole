package ai.rever.boss.service.auth

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.PermissionRequest
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AccessTokenClaimsTest] proves the parser; this proves the parser is what a sign-in actually
 * uses. Issues #1112 and #1128 both lived at the call sites - `isAdmin` read from `user_metadata`,
 * and permissions stored as an empty set - so each test here fails if its bug comes back.
 */
class AuthClaimsWiringTest {
    private fun token(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"HS256","typ":"JWT"}""", payload)
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) } + ".signature"
    }

    private fun success(payload: String) =
        successFrom(accessToken = token(payload), userId = "u1", email = "a@example.com", displayName = "A")

    @Test
    fun `a session's admin status and permissions come from its token's claims`() {
        val result = success("""{"sub":"u1","is_admin":true,"user_permissions":["role.read","plugins.create"]}""")

        assertTrue(result.isAdmin)
        assertEquals(setOf("role.read", "plugins.create"), result.permissions)
    }

    @Test
    fun `admin status a user gives itself in user_metadata does not make the session admin`() {
        val result = success("""{"sub":"u1","user_metadata":{"is_admin":true,"user_permissions":["role.read"]}}""")

        assertFalse(result.isAdmin)
        assertEquals(emptySet(), result.permissions)
    }

    @Test
    fun `an unreadable token still signs in, with no admin status or permissions`() {
        val result = successFrom(accessToken = "not-a-jwt", userId = "u1", email = "a@example.com", displayName = "A")

        assertFalse(result.isAdmin)
        assertEquals(emptySet(), result.permissions)
    }

    @Test
    fun `the service answers permission and admin checks from the signed-in session's claims`() =
        runBlocking {
            val service = AuthServiceGrpcImpl()
            service.applySuccess(success("""{"sub":"u1","is_admin":true,"user_permissions":["role.read"]}"""))

            assertTrue(service.hasPermission(permission("role.read")).granted)
            assertFalse(service.hasPermission(permission("plugins.create")).granted)
            assertEquals(listOf("role.read"), service.getUserPermissions(Empty.getDefaultInstance()).permissionsList)
            assertTrue(service.isAdmin(Empty.getDefaultInstance()).isAdmin)
        }

    private fun permission(name: String) = PermissionRequest.newBuilder().setPermission(name).build()
}
