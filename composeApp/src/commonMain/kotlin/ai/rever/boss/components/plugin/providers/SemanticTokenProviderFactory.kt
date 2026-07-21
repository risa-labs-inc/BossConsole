package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.SemanticTokenProvider

/**
 * Factory function for creating the SemanticTokenProvider.
 *
 * Platform-specific implementations use the host's PSI infrastructure
 * to provide semantic tokens for code highlighting.
 */
expect fun createSemanticTokenProvider(): SemanticTokenProvider?
