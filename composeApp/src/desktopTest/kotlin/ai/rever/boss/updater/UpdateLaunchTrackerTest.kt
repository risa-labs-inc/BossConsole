package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateLaunchTrackerTest {
    private val directory = Files.createTempDirectory("bossterm-update-launch-test").toFile()
    private val record = File(directory, "version.json")
    private val old = Version(1, 0, 0)
    private val updated = Version(1, 1, 0)

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun `first launch and unchanged running version produce no notification`() {
        assertNull(UpdateLaunchTracker(record).recordLaunch(old))
        assertNull(UpdateLaunchTracker(record).recordLaunch(old))
    }

    @Test
    fun `new running version notifies once across subsequent launches`() {
        UpdateLaunchTracker(record).recordLaunch(old)
        assertEquals(updated, UpdateLaunchTracker(record).recordLaunch(updated))
        assertNull(UpdateLaunchTracker(record).recordLaunch(updated))
    }

    @Test
    fun `failed update still running the old version never claims success`() {
        UpdateLaunchTracker(record).recordLaunch(old)
        assertNull(UpdateLaunchTracker(record).recordLaunch(old))
    }

    @Test
    fun `downgrade does not claim an update`() {
        UpdateLaunchTracker(record).recordLaunch(updated)
        assertNull(UpdateLaunchTracker(record).recordLaunch(old))
    }

    @Test
    fun `corrupt launch record is repaired without a false success notification`() {
        record.writeText("incomplete JSON")
        assertNull(UpdateLaunchTracker(record).recordLaunch(old))
        assertEquals(updated, UpdateLaunchTracker(record).recordLaunch(updated))
    }

    @Test
    fun `separate installations track versions independently`() {
        UpdateLaunchTracker(record).recordLaunch(old)
        assertNull(UpdateLaunchTracker(File(directory, "another-install.json")).recordLaunch(updated))
        assertEquals(updated, UpdateLaunchTracker(record).recordLaunch(updated))
    }

    @Test
    fun `concurrent startups claim one notification`() {
        UpdateLaunchTracker(record).recordLaunch(old)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                executor
                    .invokeAll(
                        List(2) {
                            java.util.concurrent.Callable { UpdateLaunchTracker(record).recordLaunch(updated) }
                        },
                    ).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == updated })
        } finally {
            executor.shutdownNow()
        }
    }
}
