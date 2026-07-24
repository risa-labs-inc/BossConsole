package ai.rever.boss.services.supabase.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RoleClaims.fromJWTClaims], focused on parsing the new
 * `user_permissions` claim (effective permissions injected by the auth hook)
 * alongside the existing role/admin claims.
 */
class RoleClaimsTest {
    @Test
    fun `parses user_permissions, user_roles and is_admin`() {
        val claims =
            RoleClaims.fromJWTClaims(
                mapOf(
                    "user_role" to "boss_admin",
                    "user_roles" to listOf("boss_admin", "user"),
                    "is_admin" to false,
                    "user_permissions" to listOf("role.read", "role.create", "role.assign", "user.read"),
                ),
            )
        assertNotNull(claims)
        assertEquals("boss_admin", claims.userRole)
        assertEquals(listOf("boss_admin", "user"), claims.userRoles)
        assertFalse(claims.isAdmin)
        assertEquals(listOf("role.read", "role.create", "role.assign", "user.read"), claims.permissions)
        assertTrue(claims.hasPermission("role.create"))
        assertFalse(claims.hasPermission("finance.read"))
        assertTrue(claims.hasRole("boss_admin"))
        assertFalse(claims.hasRole("admin"))
    }

    @Test
    fun `missing user_permissions yields empty permissions`() {
        val claims =
            RoleClaims.fromJWTClaims(
                mapOf(
                    "user_role" to "user",
                    "user_roles" to listOf("user"),
                    "is_admin" to false,
                ),
            )
        assertNotNull(claims)
        assertTrue(claims.permissions.isEmpty())
        assertFalse(claims.hasPermission("user.read"))
    }

    @Test
    fun `non-string entries in user_permissions are filtered out`() {
        val claims =
            RoleClaims.fromJWTClaims(
                mapOf(
                    "user_permissions" to listOf("role.read", 42, null, "user.read"),
                ),
            )
        assertNotNull(claims)
        assertEquals(listOf("role.read", "user.read"), claims.permissions)
    }

    @Test
    fun `defaults applied when role claims absent`() {
        val claims = RoleClaims.fromJWTClaims(emptyMap())
        assertNotNull(claims)
        assertEquals("user", claims.userRole)
        assertEquals(listOf("user"), claims.userRoles)
        assertFalse(claims.isAdmin)
        assertTrue(claims.permissions.isEmpty())
    }

    @Test
    fun `admin claim parsed and effective permissions surfaced`() {
        val claims =
            RoleClaims.fromJWTClaims(
                mapOf(
                    "user_role" to "admin",
                    "user_roles" to listOf("admin", "user"),
                    "is_admin" to true,
                    "user_permissions" to listOf("role.delete", "finance.read", "user.read"),
                ),
            )
        assertNotNull(claims)
        assertTrue(claims.isAdmin)
        assertTrue(claims.hasPermission("finance.read"))
    }
}
