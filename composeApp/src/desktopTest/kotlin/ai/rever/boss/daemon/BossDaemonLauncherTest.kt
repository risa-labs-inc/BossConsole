package ai.rever.boss.daemon

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossDaemonLauncherTest {
    @Test
    fun `packaged mac runtime without java relaunches its native facade`() {
        val root = Files.createTempDirectory("boss-packaged-test").toFile()
        try {
            val bundle = File(root, "BOSS.app")
            val launcher =
                File(bundle, "Contents/MacOS/BOSS").apply {
                    parentFile.mkdirs()
                    writeText("fixture")
                    setExecutable(true)
                }
            val runtime = File(bundle, "Contents/runtime/Contents/Home").apply { mkdirs() }
            val profile = File(root, ".boss/daemon")
            assertEquals(
                listOf(launcher.absolutePath, BossDaemon.ARG, profile.absolutePath),
                BossDaemonLauncher.command(profile, runtime.absolutePath, "ignored"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `java launch carries debug profile and absolute classpath`() {
        val root = Files.createTempDirectory("boss-java-test").toFile()
        try {
            val java =
                File(root, "jdk/bin/java").apply {
                    parentFile.mkdirs()
                    writeText("fixture")
                }
            val command =
                BossDaemonLauncher.command(
                    File(root, ".boss_debug/daemon"),
                    java.parentFile.parent,
                    "relative.jar",
                )
            assertTrue("-Dboss.dev.mode=true" in command)
            assertTrue(File("relative.jar").absolutePath in command)
            assertEquals(BossDaemon.ARG, command[command.lastIndex - 1])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `login plist preserves arguments containing spaces and XML characters`() {
        val arguments =
            listOf(
                "/Applications/BOSS App.app/Contents/MacOS/BOSS",
                "--boss-daemon",
                "/tmp/profile & <two>",
            )
        val xml = BossDaemonLogin.macPlist("test.daemon", arguments, "/tmp/log & output")
        val doc =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }.newDocumentBuilder()
                .parse(xml.byteInputStream())
        val nodes = doc.getElementsByTagName("array").item(0).childNodes
        assertEquals(arguments, (0 until nodes.length).map { nodes.item(it).textContent })
        assertTrue(xml.contains("<key>RunAtLoad</key><true/>"))
    }

    @Test
    fun `activation uses an authenticated payload-free focus verb`() {
        // The protocol version is pinned by SingleInstanceManager; avoid coupling the assertion
        // to an old version when the wire evolves.
        val supported =
            ai.rever.boss.utils
                .parseRequestLine("${ai.rever.boss.utils.PROTOCOL_VERSION} secret FOCUS")
        assertEquals("FOCUS", supported?.verb)
        assertEquals(null, supported?.url)
        assertEquals(
            null,
            ai.rever.boss.utils
                .parseRequestLine("${ai.rever.boss.utils.PROTOCOL_VERSION} secret FOCUS unexpected"),
        )
    }
}
