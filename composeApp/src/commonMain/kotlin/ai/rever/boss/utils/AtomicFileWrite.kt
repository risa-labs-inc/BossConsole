package ai.rever.boss.utils

import java.io.File
import java.io.IOException

/**
 * Write [text] to this file atomically: content goes to a UNIQUE sibling
 * temp file first, then replaces the target via rename (with the
 * delete-and-retry fallback for platforms where rename won't clobber an
 * existing target). A crash mid-write leaves at most a stray temp file,
 * never a truncated target; concurrent writers each use their own temp file
 * so bytes can't interleave — last rename wins.
 *
 * Shared by the persisted-registry writers (MCP disabled-tools list,
 * system-plugins manifest cache); previously each open-coded this dance
 * with a FIXED temp name, which concurrent writers could clobber.
 */
fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val tmp = File.createTempFile("$name.", ".tmp", parentFile)
    try {
        tmp.writeText(text)
        if (!tmp.renameTo(this)) {
            delete()
            if (!tmp.renameTo(this)) {
                throw IOException("rename ${tmp.name} -> $name failed")
            }
        }
    } finally {
        // No-op when the rename moved it away; cleans up on failure paths.
        tmp.delete()
    }
}
