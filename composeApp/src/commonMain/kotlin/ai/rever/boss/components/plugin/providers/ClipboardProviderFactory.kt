package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.ClipboardProvider

/**
 * Factory function to create platform-specific ClipboardProvider.
 * Desktop implementation returns a provider backed by AWT system clipboard.
 * Returns null on platforms that don't support clipboard access.
 */
expect fun createClipboardProvider(): ClipboardProvider?
