package ai.rever.boss.plugin.browser

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private typealias SettingsPathFactory = (Path) -> Path

class BrowserProfileProtectionTest {
    @Test
    fun `missing settings keep an ambiguous durable profile`() =
        assertUntrustedSettingsPreserveNumericProfile { root -> root.resolve("missing.json") }

    @Test
    fun `corrupt settings keep an ambiguous durable profile`() =
        assertUntrustedSettingsPreserveNumericProfile { root ->
            Files.writeString(root.resolve("corrupt.json"), "{not-json")
        }

    @Test
    fun `unreadable settings path keeps an ambiguous durable profile`() =
        assertUntrustedSettingsPreserveNumericProfile { root -> Files.createDirectory(root.resolve("settings.json")) }

    @Test
    fun `valid settings make legacy migration eligible while protecting registered names`() {
        val root = createTempDirectory("boss-settings-trust-")
        try {
            val registeredName = "browser-profile-1720000000000"
            val unregisteredName = "browser-profile-1720000000001"
            val settingsFile =
                Files.writeString(
                    root.resolve("settings.json"),
                    """{"currentProfile":"$registeredName","availableProfiles":["$registeredName"]}""",
                )
            val read = assertIs<BrowserSettingsFileResult.Loaded>(readBrowserSettingsFile(settingsFile.toFile()))
            val registered = Files.createDirectory(root.resolve(registeredName))
            val unregistered = Files.createDirectory(root.resolve(unregisteredName))

            val result =
                TemporaryBrowserProfiles.cleanup(
                    root = root,
                    protectedProfiles = read.settings.availableProfiles.toSet() + read.settings.currentProfile,
                    legacyProfileNamesTrusted = true,
                    olderThanMillis = null,
                )

            assertTrue(registered.exists())
            assertFalse(unregistered.exists())
            assertEquals(1, result.deleted)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `profile registration and cleanup snapshots are thread safe and lossless`() {
        val original = BrowserSettings.profileProtectionSnapshot()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val profiles = (1..1_000).map { "browser-profile-concurrent-$it" }

        try {
            val writer =
                executor.submit {
                    runCatching {
                        start.await()
                        profiles.forEach(BrowserSettings::registerProfile)
                    }.onFailure(errors::add)
                }
            val reader =
                executor.submit {
                    runCatching {
                        start.await()
                        repeat(1_000) { BrowserSettings.profileProtectionSnapshot() }
                    }.onFailure(errors::add)
                }

            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)

            assertTrue(errors.isEmpty(), "Concurrent profile access failed: $errors")
            assertTrue(BrowserSettings.profileProtectionSnapshot().availableProfiles.containsAll(profiles))
        } finally {
            executor.shutdownNow()
            BrowserSettings.installPersistedProfiles(
                currentProfile = original.currentProfile,
                availableProfiles = original.availableProfiles.toList(),
            )
            if (!original.legacyProfileNamesTrusted) BrowserSettings.markLegacyProfileNamesUntrusted()
        }
    }

    private fun assertUntrustedSettingsPreserveNumericProfile(settingsPath: SettingsPathFactory) {
        val root = createTempDirectory("boss-settings-untrusted-")
        try {
            val fileResult = readBrowserSettingsFile(settingsPath(root).toFile())
            assertFalse(fileResult is BrowserSettingsFileResult.Loaded)
            val numeric = Files.createDirectory(root.resolve("browser-profile-1720000000000"))

            val result =
                TemporaryBrowserProfiles.cleanup(
                    root = root,
                    protectedProfiles = setOf("browser-profile"),
                    legacyProfileNamesTrusted = false,
                    olderThanMillis = null,
                )

            assertTrue(numeric.exists())
            assertEquals(0, result.deleted)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
