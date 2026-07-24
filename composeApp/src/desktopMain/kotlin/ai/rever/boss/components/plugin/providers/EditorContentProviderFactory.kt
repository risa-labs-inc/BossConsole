package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.EditorContentProvider

/**
 * Desktop implementation of EditorContentProvider factory.
 */
actual fun createEditorContentProvider(): EditorContentProvider? = EditorContentProviderImpl()
