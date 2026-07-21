package ai.rever.boss.components.plugin.registries

/**
 * Current user's RBAC snapshot shared by the UI extension registries.
 * Pushed by DynamicPluginManager's access collector (the same signal that
 * gates whole plugins and MCP tools) on login / role change, so gated
 * contributions appear or disappear live.
 */
data class RegistryAccess(
    val isAdmin: Boolean = false,
    val permissions: Set<String> = emptySet(),
) {
    /**
     * Delegates to the single host RBAC predicate
     * [ai.rever.boss.components.plugin.pluginAccessAllowed] so gated
     * extension items follow the exact same rule as whole-plugin gating.
     */
    fun permits(requiresAdmin: Boolean, requiredPermissions: Set<String>): Boolean =
        ai.rever.boss.components.plugin.pluginAccessAllowed(
            isAdmin = isAdmin,
            userPermissions = permissions,
            requiresAdmin = requiresAdmin,
            requiredPermissions = requiredPermissions.toList(),
        )
}
