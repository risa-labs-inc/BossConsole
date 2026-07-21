package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.EditorContentProvider

/**
 * Factory function to create platform-specific EditorContentProvider.
 * Desktop implementation returns EditorContentProviderImpl.
 * Returns null on platforms that don't support the code editor.
 */
expect fun createEditorContentProvider(): EditorContentProvider?
