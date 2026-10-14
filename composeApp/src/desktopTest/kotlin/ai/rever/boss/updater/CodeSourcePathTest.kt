package ai.rever.boss.updater

import java.io.File
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeSourcePathTest {
    @Test
    fun `encoded code source finds its app bundle`() {
        val directory = Files.createTempDirectory("BOSS code source ").toFile()
        try {
            for (name in listOf("BOSS App.app", "BOSS+Beta.app", "BOSS%20.app", "BOSS Café.app")) {
                val bundle = File(directory, name)
                val codeSource = File(bundle, "Contents/app/BOSS.jar").toURI().toURL()

                assertTrue(codeSource.toString().contains("%20"))
                assertEquals(bundle.absoluteFile, appBundleAncestorOf(codeSource)?.absoluteFile)
            }
        } finally {
            directory.delete()
        }
    }

    @Test
    fun `unsupported code source leaves bundle lookup available for fallback`() {
        assertNull(appBundleAncestorOf(null))
        assertNull(appBundleAncestorOf(URL("jar:file:/BOSS.app/Contents/app/BOSS.jar!/")))
        assertNull(appBundleAncestorOf(URL("file:/BOSS App.app/Contents/app/BOSS.jar")))
    }

    @Test
    fun `unusable code source selects installed applications bundle`() {
        val directory = Files.createTempDirectory("BOSS fallback ").toFile()
        val installed = File(directory, "BOSS.app")
        try {
            assertTrue(installed.mkdir())
            val expected = AppBundleCandidate(installed, fromCodeSource = false)

            assertEquals(expected, appBundleFromCodeSourceOrApplications(null, installed))
            assertEquals(
                expected,
                appBundleFromCodeSourceOrApplications(URL("jar:file:/BOSS.app/Contents/app/BOSS.jar!/"), installed),
            )
            assertEquals(
                expected,
                appBundleFromCodeSourceOrApplications(URL("file:/BOSS App.app/Contents/app/BOSS.jar"), installed),
            )

            val running = File(directory, "Running App.app/Contents/app/BOSS.jar").toURI().toURL()
            assertEquals(
                AppBundleCandidate(File(directory, "Running App.app"), fromCodeSource = true),
                appBundleFromCodeSourceOrApplications(running, installed),
            )
        } finally {
            installed.delete()
            directory.delete()
        }
    }

    @Test
    fun `bundle walk checks six levels`() {
        val bundle = File("BOSS.app").absoluteFile
        val withinLimit = File(bundle, "one/two/three/four/BOSS.jar")
        val beyondLimit = File(bundle, "one/two/three/four/five/BOSS.jar")

        assertEquals(bundle, appBundleAncestorOf(withinLimit.toURI().toURL()))
        assertNull(appBundleAncestorOf(beyondLimit.toURI().toURL()))
    }
}
