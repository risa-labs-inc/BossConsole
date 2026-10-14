package ai.rever.boss.updater

import java.io.File

/** Replaces a JAR only after the owning process exits; never launches the app. */
internal fun generateDeferredJarUpdateScript(
    downloadPath: String,
    targetPath: String,
    appPid: Long,
): File {
    UpdatePathValidator.validatePath(downloadPath, "Downloaded JAR path")
    UpdatePathValidator.validatePath(targetPath, "Target JAR path")
    val downloaded = UpdateScriptGenerator.escapeShellArg(downloadPath)
    val target = UpdateScriptGenerator.escapeShellArg(targetPath)
    return File.createTempFile("update-boss-jar-", ".sh", updaterTempDir()).apply {
        writeText(
            """
            #!/bin/bash
            set -e
            while kill -0 $appPid 2>/dev/null; do
                sleep 1
            done
            cp $target $target.backup
            cp $downloaded $target.update
            mv -f $target.update $target
            echo "Open BOSS again manually."
            rm -f "${'$'}0"
            """.trimIndent(),
        )
        check(setExecutable(true, true)) { "Could not make the deferred JAR helper executable" }
    }
}
