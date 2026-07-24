package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.SemanticTokenProvider

/**
 * Desktop implementation of SemanticTokenProvider factory.
 *
 * The PSI stack moved into the editor-tab plugin together with BossEditor
 * (which the plugin bundles privately), so the host has nothing to provide —
 * the plugin runs its own PluginSemanticTokenProvider internally.
 */
actual fun createSemanticTokenProvider(): SemanticTokenProvider? = null
