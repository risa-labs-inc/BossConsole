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

    // -----------------------------------------------------------------------
    // Least-privilege plugin authoring (boss_plugin_admin / plugins.create).
    //
    // The authoring permission and the store-wide moderation permission are one
    // word apart, and the gate is where a mix-up would surface as "Tool Creator
    // is invisible" or, worse, "a plugin author can moderate the store". Both
    // directions are pinned.
    // -----------------------------------------------------------------------

    /** Effective permissions a `boss_plugin_admin` carries: its two, plus the inherited `user.*` baseline. */
    private val bossPluginAdmin =
        setOf(
            "plugins.create",
            "api_key.create",
            "user.read",
            "user.write",
            "user.update",
            "user.delete",
        )

    @Test
    fun `boss_plugin_admin sees Tool Creator`() {
        // tool-creator's manifest: requiredPermissions = ["plugins.create", "api_key.create"]
        assertTrue(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = bossPluginAdmin,
                requiresAdmin = false,
                requiredPermissions = listOf("plugins.create", "api_key.create"),
            ),
        )
    }

    @Test
    fun `boss_plugin_admin is denied a moderation-gated plugin`() {
        assertFalse(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = bossPluginAdmin,
                requiresAdmin = false,
                requiredPermissions = listOf("plugins.admin.publish"),
            ),
        )
    }

    @Test
    fun `boss_plugin_admin is denied the secret vault and role tooling`() {
        // The whole point of the role: authoring, and nothing else.
        for (required in listOf(
            listOf("secret.read"),
            listOf("role.read", "role.assign"),
            listOf("role.read", "role.create"),
        )) {
            assertFalse(
                pluginAccessAllowed(
                    isAdmin = false,
                    userPermissions = bossPluginAdmin,
                    requiresAdmin = false,
                    requiredPermissions = required,
                ),
                "boss_plugin_admin must not reach a plugin requiring $required",
            )
        }
    }

    @Test
    fun `holding only the moderation permission does not admit Tool Creator`() {
        // A pre-migration boss_admin claim set: plugins.admin.publish is NOT a
        // substitute for plugins.create, which is why a stale JWT makes Tool
        // Creator disappear until the token refreshes.
        assertFalse(
            pluginAccessAllowed(
                isAdmin = false,
                userPermissions = setOf("plugins.admin.publish", "api_key.create", "user.read"),
                requiresAdmin = false,
                requiredPermissions = listOf("plugins.create", "api_key.create"),
            ),
        )
    }
}
