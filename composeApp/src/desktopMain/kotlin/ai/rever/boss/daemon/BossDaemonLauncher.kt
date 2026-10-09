package ai.rever.boss.daemon

import java.io.File

internal object BossDaemonLauncher {
    private var opening: Process? = null
    private const val MAIN = "ai.rever.boss.MainKt"

    /** jpackage runtimes may contain no bin/java. Relaunch the host's native facade in that case. */
    fun command(
        directory: File,
        javaHome: String = System.getProperty("java.home"),
        classpath: String = System.getProperty("java.class.path"),
    ): List<String> {
        val home = File(javaHome)
        val java = listOf(File(home, "bin/javaw.exe"), File(home, "bin/java")).firstOrNull { it.isFile }
        val arguments = listOf(BossDaemon.ARG, directory.absolutePath)
        if (java !=
            null
        ) {
            return listOf(java.absolutePath, "-Dapple.awt.UIElement=true") + profileProperties(directory) +
                listOf("-cp", absoluteClasspath(classpath), MAIN) +
                arguments
        }
        val launcher = requireNotNull(packagedLauncher(javaHome)) { "Cannot locate BOSS launcher" }
        return listOf(launcher.absolutePath) + arguments
    }

    internal fun packagedLauncher(javaHome: String): File? {
        val normalized = javaHome.replace('\\', '/')
        val (dir, preferred) =
            when {
                normalized.contains(".app/Contents/") -> {
                    val bundle = File(normalized.substringBefore(".app/Contents/") + ".app")
                    File(bundle, "Contents/MacOS") to bundle.nameWithoutExtension
                }

                File(javaHome).name.equals("runtime", true) && File(javaHome).parentFile?.name == "lib" -> {
                    val install = File(javaHome).parentFile.parentFile
                    File(install, "bin") to install.name
                }

                File(javaHome).name.equals("runtime", true) -> {
                    File(javaHome).parentFile.let { it to it.name }
                }

                else -> {
                    return null
                }
            }
        val candidates =
            dir.listFiles().orEmpty().filter {
                it.isFile && (it.canExecute() || it.extension.equals("exe", true))
            }
        return candidates.firstOrNull { it.nameWithoutExtension.equals(preferred, true) } ?: candidates.singleOrNull()
    }

    fun spawn(directory: File) {
        val log = File(directory, "daemon.log").also(::ownerFile)
        ProcessBuilder(command(directory))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
            .start()
    }

    @Synchronized
    fun openApplication() {
        if (ai.rever.boss.utils.SingleInstanceManager
                .activateExistingInstance()
        ) {
            return
        }
        if (opening?.isAlive == true) return
        val home = System.getProperty("java.home").replace('\\', '/')
        if (home.contains(".app/Contents/")) {
            opening = ProcessBuilder("open", "-a", home.substringBefore(".app/Contents/") + ".app").start()
        } else {
            // BOSS's existing single-instance/deep-link dispatcher activates the running host.
            val java = File(System.getProperty("java.home"), "bin/java")
            val command =
                if (java.isFile) {
                    listOf(java.absolutePath) +
                        profileProperties(File(ai.rever.boss.plugin.pathutils.BossDirectories.rootDir, "daemon")) +
                        listOf("-cp", absoluteClasspath(System.getProperty("java.class.path")), MAIN)
                } else {
                    listOfNotNull(packagedLauncher(System.getProperty("java.home"))?.absolutePath)
                }
            if (command.isNotEmpty()) opening = ProcessBuilder(command).start()
        }
    }

    private fun profileProperties(directory: File): List<String> =
        listOf(
            "-Duser.home=${System.getProperty("user.home")}",
            "-Dboss.dev.mode=${directory.parentFile.name == ".boss_debug"}",
        ) +
            listOf("compose.application.resources.dir", "java.library.path").mapNotNull { key ->
                System.getProperty(key)?.let { "-D$key=$it" }
            }

    private fun absoluteClasspath(classpath: String) =
        classpath.split(File.pathSeparator).joinToString(File.pathSeparator) {
            File(it.ifEmpty { "." }).absolutePath
        }
}
