package ai.rever.boss.components.plugin.registries

/**
 * An RBAC-gated extension registry that must be kept in sync with the current
 * user's access. Implementations register themselves in [ACCESS_GATED] so the
 * host's single access collector pushes to all of them in one loop — a new
 * gated registry can no longer be silently forgotten (which would strand its
 * items visible to everyone).
 */
interface AccessGatedRegistry {
    fun updateAccess(
        isAdmin: Boolean,
        permissions: Set<String>,
    )

    companion object {
        /**
         * All RBAC-gated extension registries. The one place
         * DynamicPluginManager's access collector iterates.
         */
        val ACCESS_GATED: List<AccessGatedRegistry> =
            listOf(
                PanelMenuRegistryImpl,
                SettingsPageRegistryImpl,
                StatusBarRegistryImpl,
            )
    }
}
