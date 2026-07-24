package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.NavigationResolverProvider

/**
 * Desktop implementation of NavigationResolverProvider factory.
 *
 * The PSI stack moved into the editor-tab plugin together with BossEditor
 * (which the plugin bundles privately), so the host has nothing to provide.
 * No plugin consumed this provider — navigation runs inside the plugin's
 * bundled BossEditor directly.
 */
actual fun createNavigationResolverProvider(): NavigationResolverProvider? = null
