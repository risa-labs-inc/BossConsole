package ai.rever.boss.kernel

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the jar lookup that decides whether a microkernel service can be spawned at all.
 *
 * The kernel asks for `boss-orchestrator-all.jar`; every module's `fatJar` task actually produces
 * `boss-orchestrator-<version>-all.jar`, because none of them strip the version. Those two have
 * never agreed, so the spawn path silently found nothing and logged "build fat JARs first" even
 * right after someone had.
 */
class ServiceJarLookupTest {
    private val dirs = mutableListOf<File>()

    private fun tempDir(): File =
        File.createTempFile("services", "").let {
            it.delete()
            it.mkdirs()
            dirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `the exact name is used when it is there`() {
        val dir = tempDir()
        val exact = File(dir, "boss-orchestrator-all.jar").also { it.writeText("jar") }

        assertEquals(exact, serviceJarIn(dir, "boss-orchestrator-all.jar"))
    }

    @Test
    fun `a versioned jar is found under the unversioned name`() {
        // This is what `./gradlew :boss-orchestrator:fatJar` leaves behind.
        val dir = tempDir()
        val versioned = File(dir, "boss-orchestrator-1.0.0-all.jar").also { it.writeText("jar") }

        assertEquals(versioned, serviceJarIn(dir, "boss-orchestrator-all.jar"))
    }

    @Test
    fun `the newest build wins when several versions are lying around`() {
        val dir = tempDir()
        val old = File(dir, "boss-orchestrator-1.0.0-all.jar").also { it.writeText("old") }
        val new = File(dir, "boss-orchestrator-1.1.0-all.jar").also { it.writeText("new") }
        old.setLastModified(1_000_000)
        new.setLastModified(2_000_000)

        assertEquals(new, serviceJarIn(dir, "boss-orchestrator-all.jar"))
    }

    @Test
    fun `another service's jar is never mistaken for this one`() {
        val dir = tempDir()
        File(dir, "boss-service-auth-1.0.0-all.jar").writeText("jar")

        assertNull(serviceJarIn(dir, "boss-orchestrator-all.jar"))
    }

    @Test
    fun `a thin jar is not a fat jar`() {
        // `fatJar` and `jar` land in the same directory; only the -all one can be spawned.
        val dir = tempDir()
        File(dir, "boss-orchestrator-1.0.0.jar").writeText("thin")

        assertNull(serviceJarIn(dir, "boss-orchestrator-all.jar"))
    }

    @Test
    fun `a missing directory is simply no match`() {
        assertNull(serviceJarIn(File("/nonexistent/services"), "boss-orchestrator-all.jar"))
    }
}
