package ai.rever.boss.plugin.browser

import java.nio.file.Path

/**
 * Pick the path that may actually be written, including any [requiredExtension].
 *
 * A native save panel protects the path visible in its name field. If the user removes a required
 * extension, appending it after the panel closes can point at a different existing file, one the
 * panel never asked permission to replace. In that one case this loop presents the real target as
 * the next suggestion. Accepting it makes the native panel perform its own overwrite confirmation;
 * cancelling refuses the save. A different name is resolved by the same rule before it can leave.
 *
 * [pick] and [targetExists] are parameters because the native panel needs a display, while this
 * state transition must remain testable on every CI platform. An exception checking the target is
 * allowed to escape to `NativeFileDialogs.answerOnce`, whose fail-closed path cancels the browser
 * callback.
 */
internal fun chooseSaveTarget(
    suggestedFileName: String,
    suggestedDirectory: String,
    requiredExtension: String?,
    targetExists: (Path) -> Boolean,
    pick: (suggestedFileName: String, suggestedDirectory: String) -> Path?,
): Path? {
    var nextFileName = suggestedFileName
    var nextDirectory = suggestedDirectory
    var result: Path? = null
    var resolved = false

    while (!resolved) {
        val selected = pick(nextFileName, nextDirectory)
        if (selected == null) {
            resolved = true
        } else if (requiredExtension == null) {
            result = selected
            resolved = true
        } else {
            val target = pathWithExtension(selected, requiredExtension)
            if (target == selected || !targetExists(target)) {
                result = target
                resolved = true
            } else {
                nextFileName = target.fileName?.toString().orEmpty()
                nextDirectory = target.parent?.toString().orEmpty()
            }
        }
    }
    return result
}
