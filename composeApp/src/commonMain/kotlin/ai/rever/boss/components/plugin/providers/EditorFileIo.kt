// The file I/O behind EditorContentProvider, which is all the host still owns of
// the editor.
//
// These were part of `tab_types/CodeEditor.kt`, the host's own editor, back when
// the host rendered code itself. It does not any more -- the editor-tab plugin
// bundles BossEditor and owns every editing surface -- so the editor went and
// this stayed, next to the provider that is its only caller. Serving the
// plugin's `editor_read_file` and `editor_write_file` MCP tools is what these
// are for.

package ai.rever.boss.components.plugin.providers

/**
 * Reads file content with size validation.
 *
 * Files larger than [maxSize] return [FileReadOutcome.FileTooLarge] rather than
 * loading, so an accidental `editor_read_file` on a multi-gigabyte artifact
 * cannot take the host down with it.
 *
 * @param filePath Path to the file
 * @param maxSize Maximum allowed file size in bytes (default: 100MB)
 */
expect fun readFileContentSafe(
    filePath: String,
    maxSize: Long = 100 * 1024 * 1024, // 100 MB default
): FileReadOutcome

/** Writes [content] to [filePath], creating parent directories. */
expect fun writeFileContent(
    filePath: String,
    content: String,
): Boolean
