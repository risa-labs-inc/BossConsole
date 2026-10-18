package ai.rever.boss.daemon

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File

/** Login starts the same host facade as on-demand launch, with a separate registration per profile. */
internal object BossDaemonLogin {
    private val installed = mutableSetOf<String>()

    @Synchronized
    fun install(directory: File) {
        val command = BossDaemonLauncher.command(directory)
        val id = "ai.rever.boss.daemon." + sha256(directory.canonicalPath.toByteArray()).take(12)
        val key = command.joinToString("\u0000")
        if (key in installed) return
        runCatching {
            val os = System.getProperty("os.name").lowercase()
            when {
                "mac" in os -> {
                    val plist = File(System.getProperty("user.home"), "Library/LaunchAgents/$id.plist")
                    plist.parentFile.mkdirs()
                    ownerFile(plist)
                    plist.writeText(macPlist(id, command, File(directory, "daemon.log").absolutePath))
                    // LaunchAgents are discovered at the next login. Never bootout a live worker
                    // merely to refresh its launch command; the on-demand launcher handles now.
                }

                "win" in os -> {
                    val line = command.joinToString(" ", transform = ::windowsArgument)
                    val process =
                        ProcessBuilder(
                            "reg",
                            "add",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                            "/v",
                            id,
                            "/t",
                            "REG_SZ",
                            "/d",
                            line,
                            "/f",
                        ).redirectErrorStream(true).start()
                    process.inputStream.use { it.readBytes() }
                    check(process.waitFor() == 0) { "Could not register BOSS daemon at login" }
                }

                else -> {
                    val file = File(System.getProperty("user.home"), ".config/autostart/$id.desktop")
                    ownerDirectory(file.parentFile)
                    ownerFile(file)
                    file.writeText(xdgDesktop(command))
                }
            }
            installed += key
        }.onFailure {
            BossLogger.forComponent("BossDaemon").warn(
                LogCategory.SYSTEM,
                "Could not register BOSS daemon at login",
                mapOf("type" to it.javaClass.simpleName),
            )
        }
    }

    internal fun macPlist(
        id: String,
        command: List<String>,
        log: String,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>${xml(id)}</string>
<key>ProgramArguments</key><array>${command.joinToString("") { "<string>${xml(it)}</string>" }}</array>
<key>RunAtLoad</key><true/>
<key>ProcessType</key><string>Interactive</string>
<key>StandardOutPath</key><string>${xml(log)}</string>
<key>StandardErrorPath</key><string>${xml(log)}</string>
</dict></plist>
"""

    internal fun xdgDesktop(command: List<String>): String =
        "[Desktop Entry]\nType=Application\nName=BOSS Background Services\nExec=" +
            command.joinToString(" ") { arg ->
                "\"" +
                    arg
                        .replace("%", "%%")
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("`", "\\`")
                        .replace("$", "\\$") + "\""
            } + "\nTerminal=false\nX-GNOME-Autostart-enabled=true\n"

    internal fun windowsArgument(value: String): String =
        "\"" +
            Regex("(\\\\*)\"")
                .replace(value) { it.groupValues[1].repeat(2) + "\\\"" }
                .replace(Regex("\\\\+$")) { it.value.repeat(2) } + "\""

    private fun xml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
