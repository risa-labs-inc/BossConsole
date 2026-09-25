package ai.rever.boss.html

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlFileSettingsStoreTest {
    @TempDir
    lateinit var temporary: File

    @Test
    fun `first routing read waits for the persisted preference`() =
        runBlocking {
            val file = File(temporary, "settings.json").apply { writeText("""{"openMode":"BROWSER"}""") }
            val store = HtmlFileSettingsStore(file) { throw it }
            assertEquals(HtmlFileOpenMode.BROWSER, store.awaitSettings().openMode)
        }

    @Test
    fun `update before initial load is never overwritten by old disk settings`() =
        runBlocking {
            val file = File(temporary, "settings.json").apply { writeText("""{"openMode":"BROWSER"}""") }
            val store = HtmlFileSettingsStore(file) { throw it }
            store.update(HtmlFileSettings(HtmlFileOpenMode.EDITOR))
            assertEquals(HtmlFileOpenMode.EDITOR, store.awaitSettings().openMode)
            assertEquals(HtmlFileOpenMode.EDITOR, HtmlFileSettingsStore(file) { throw it }.awaitSettings().openMode)
        }

    @Test
    fun `concurrent writes leave a complete document matching published state`() =
        runBlocking {
            val file = File(temporary, "settings.json").apply { writeText("{}") }
            val store = HtmlFileSettingsStore(file) { throw it }
            (1..30)
                .map { index ->
                    async { store.update(HtmlFileSettings(HtmlFileOpenMode.entries[index % 3])) }
                }.awaitAll()
            assertEquals(store.currentSettings.value, HtmlFileSettingsStore(file) { throw it }.awaitSettings())
        }

    @Test
    fun `corrupt settings fall back to asking and can be repaired by a new choice`() =
        runBlocking {
            val file = File(temporary, "settings.json").apply { writeText("{broken") }
            val failures = mutableListOf<Exception>()
            val store = HtmlFileSettingsStore(file) { failures.add(it) }
            assertEquals(HtmlFileOpenMode.ALWAYS_ASK, store.awaitSettings().openMode)
            assertEquals(1, failures.size)
            store.update(HtmlFileSettings(HtmlFileOpenMode.BROWSER))
            assertEquals(HtmlFileOpenMode.BROWSER, HtmlFileSettingsStore(file) { throw it }.awaitSettings().openMode)
        }

    @Test
    fun `denied settings reads fall back to asking without wedging routing`() =
        runBlocking {
            val file =
                object : File(temporary, "denied.json") {
                    override fun exists(): Boolean = throw SecurityException("denied")
                }
            val failures = mutableListOf<Exception>()
            val store = HtmlFileSettingsStore(file) { failures.add(it) }
            assertEquals(HtmlFileSettings(), store.awaitSettings())
            assertEquals(1, failures.size)
            store.update(HtmlFileSettings(HtmlFileOpenMode.EDITOR))
            assertEquals(HtmlFileOpenMode.EDITOR, store.awaitSettings().openMode)
        }

    @Test
    fun `update writes atomically and leaves no temporary file behind`() =
        runBlocking {
            val file = File(temporary, "settings.json").apply { writeText("{}") }
            val store = HtmlFileSettingsStore(file) { throw it }
            store.update(HtmlFileSettings(HtmlFileOpenMode.EDITOR))
            // The settings file re-parses to the published state...
            assertEquals(HtmlFileOpenMode.EDITOR, HtmlFileSettingsStore(file) { throw it }.awaitSettings().openMode)
            // ...and the atomic temp-then-move leaves nothing half-written in the directory.
            val leftovers = temporary.listFiles()?.map { it.name }?.filter { it != "settings.json" } ?: emptyList()
            assertTrue(leftovers.isEmpty(), "unexpected leftover files: $leftovers")
        }

    @Test
    fun `update goes through the atomic helper, which pins the file to owner-only 0600`() =
        runBlocking {
            // atomicWriteText pins POSIX files to 0600; a plain writeText leaves the umask default
            // (commonly 0644). Asserting the permission is what makes this fail if a call site
            // regresses to a non-atomic write - the "no leftover temp file" check above passes
            // either way and so does not pin the helper.
            val file = File(temporary, "settings.json").apply { writeText("{}") }
            val posix = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
            assumeTrue(posix != null, "POSIX permissions unavailable on this filesystem (e.g. Windows)")

            val store = HtmlFileSettingsStore(file) { throw it }
            store.update(HtmlFileSettings(HtmlFileOpenMode.EDITOR))

            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
                "atomicWriteText must pin the settings file to 0600",
            )
        }

    @Test
    fun `reset persists always ask`() =
        runBlocking {
            val file = File(temporary, "settings.json").apply { writeText("""{"openMode":"EDITOR"}""") }
            val store = HtmlFileSettingsStore(file) { throw it }
            store.update(HtmlFileSettings())
            assertEquals(HtmlFileSettings(), HtmlFileSettingsStore(file) { throw it }.awaitSettings())
        }
}
