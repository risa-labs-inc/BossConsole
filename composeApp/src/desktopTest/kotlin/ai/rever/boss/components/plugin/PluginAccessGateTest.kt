package ai.rever.boss.components.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [pluginAccessAllowed] — the pure permission-based plugin
 * gating predicate behind [DynamicPluginManager.canAccess].
 *
 * Covers: admin bypass, the legacy `requiresAdmin` gate, and `containsAll` of
 * `requiredPermissions` (including the empty/legacy "visible to all" case).
 */
class PluginAccessGateTest {
    @Test
    fun `legacy plugin (no requirements) is visible to any authenticated user`() {
        assertTrue(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = setOf("user.read"),
                requiresAdmin = false,
                requiredPermissions = emptyList(),
            ),
        )
    }

    @Test
    fun `requiresAdmin plugin hidden from non-admin even with permissions`() {
        assertFalse(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = setOf("role.read", "role.create"),
                requiresAdmin = true,
                requiredPermissions = emptyList(),
            ),
        )
    }

    @Test
    fun `requiresAdmin plugin visible to admin`() {
        assertTrue(
            pluginAccessAllowed(
                isAdmin = true,
                userPermissions = emptySet(),
                requiresAdmin = true,
                requiredPermissions = emptyList(),
            ),
        )
    }

    @Test
    fun `admin bypasses required permissions`() {
        assertTrue(
            pluginAccessAllowed(
                isAdmin = true,
                userPermissions = emptySet(),
                requiresAdmin = false,
                requiredPermissions = listOf("finance.read", "role.assign"),
            ),
        )
    }

    @Test
    fun `user with all required permissions is allowed`() {
        // e.g. Admin Roles plugin requires role.read + role.assign
        assertTrue(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = setOf("role.read", "role.assign", "user.read"),
                requiresAdmin = false,
                requiredPermissions = listOf("role.read", "role.assign"),
            ),
        )
    }

    @Test
    fun `user missing one required permission is denied`() {
        // boss_admin-shaped set lacks finance.read -> finance plugin hidden
        assertFalse(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = setOf("role.read", "role.create", "role.assign", "user.read"),
                requiresAdmin = false,
                requiredPermissions = listOf("finance.read"),
            ),
        )
    }

    @Test
    fun `requiresAdmin AND requiredPermissions both enforced for non-admin`() {
        // Has the perms but not admin -> still hidden because requiresAdmin.
        assertFalse(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = setOf("role.read"),
                requiresAdmin = true,
                requiredPermissions = listOf("role.read"),
            ),
        )
    }
}
