package ai.rever.boss.plugin.loader

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginBundledTrustTest {
    private val tempDir = createTempDirectory("bundled-trust-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a freshly-marked jar is trusted`() {
        val jar = File(tempDir, "bundled.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        assertTrue(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a failed marker replacement preserves the last complete trust record`() {
        // The defect #1108 fixed: the old in-place `File.writeText()` truncated the existing
        // marker before writing, so a writer that crashed (or had no write permission on the
        // directory) could leave a reader holding nothing at all - the bundled JAR would lose
        // its exemption for the rest of the session. The new staging path cannot create its
        // sibling in a read-only directory, so the last complete marker stays untouched. This
        // test is skipped where no POSIX attribute view exists; the existing trust and
        // enforcement tests remain cross-platform.
        val jar = File(tempDir, "atomic-marker.jar").apply { writeText("bundled-bytes") }
        val digest = FileHashing.sha256(jar)
        PluginBundledTrust.markTrusted(jar.absolutePath, digest)
        if (Files.getFileAttributeView(tempDir.toPath(), PosixFileAttributeView::class.java) == null) return

        try {
            Files.setPosixFilePermissions(
                tempDir.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
            )

            PluginBundledTrust.markTrusted(jar.absolutePath, "not-the-jar-digest")
            assertTrue(
                PluginBundledTrust.isTrusted(jar.absolutePath),
                "a staging failure must not truncate or replace the last complete marker",
            )
        } finally {
            Files.setPosixFilePermissions(
                tempDir.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    @Test
    fun `a jar with no marker is not trusted`() {
        val jar = File(tempDir, "unmarked.jar").apply { writeText("jar-bytes") }
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `content changing after the marker was written loses trust`() {
        val jar = File(tempDir, "changed.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        jar.appendBytes("more-bytes".toByteArray())
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a marker for one jar does not trust a different jar at the same path`() {
        // Same scenario as content changing, framed the way it actually happens: a stale jar is
        // deleted and a different plugin's jar lands at the same filename.
        val path = File(tempDir, "reused.jar").absolutePath
        File(path).writeText("original-plugin")
        PluginBundledTrust.markTrusted(path, FileHashing.sha256(File(path)))
        File(path).writeText("a-completely-different-plugin")
        assertFalse(PluginBundledTrust.isTrusted(path))
    }

    @Test
    fun `delete removes the marker`() {
        val jar = File(tempDir, "toDelete.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        PluginBundledTrust.delete(jar.absolutePath)
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
        assertFalse(File(PluginBundledTrust.pathFor(jar.absolutePath)).exists())
    }

    @Test
    fun `an empty marker file is not trusted`() {
        val jar = File(tempDir, "emptyMarker.jar").apply { writeText("jar-bytes") }
        File(PluginBundledTrust.pathFor(jar.absolutePath)).writeText("   ")
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `an older installed copy gains trust without being overwritten`() {
        val source = File(tempDir, "bundle.jar").apply { writeText("trusted-bytes") }
        val installed = source.copyTo(File(tempDir, "old-host-copy.jar"))
        assertFalse(PluginBundledTrust.isTrusted(installed.absolutePath))
        assertTrue(PluginBundledTrust.bindToBundle(installed.absolutePath, source))
        assertTrue(PluginBundledTrust.isTrusted(installed.absolutePath))
    }

    @Test
    fun `binding never trusts different installed bytes`() {
        val source = File(tempDir, "bundle.jar").apply { writeText("trusted-bytes") }
        val installed = File(tempDir, "installed.jar").apply { writeText("changed-bytes") }
        assertFalse(PluginBundledTrust.bindToBundle(installed.absolutePath, source))
        assertFalse(PluginBundledTrust.isTrusted(installed.absolutePath))
    }

    @Test
    fun `missing bundle and unwritable marker cannot establish trust`() {
        val installed = File(tempDir, "installed.jar").apply { writeText("trusted-bytes") }
        assertFalse(PluginBundledTrust.bindToBundle(installed.absolutePath, File(tempDir, "missing.jar")))
        File(PluginBundledTrust.pathFor(installed.absolutePath)).mkdir()
        assertFalse(PluginBundledTrust.bindToBundle(installed.absolutePath, installed))
        assertFalse(PluginBundledTrust.isTrusted(installed.absolutePath))
    }

    @Test
    fun `copying provenance rejects changed source or destination bytes`() {
        val source = File(tempDir, "source.jar").apply { writeText("trusted") }
        val destination = source.copyTo(File(tempDir, "snapshot.jar"))
        PluginBundledTrust.bindToBundle(source.absolutePath, source)
        assertTrue(PluginBundledTrust.copyTrust(source.absolutePath, destination.absolutePath))
        destination.writeText("changed")
        assertFalse(PluginBundledTrust.copyTrust(source.absolutePath, destination.absolutePath))
        assertFalse(File(PluginBundledTrust.pathFor(destination.absolutePath)).exists())
        source.writeText("changed")
        assertFalse(PluginBundledTrust.copyTrust(source.absolutePath, destination.absolutePath))
        assertFalse(PluginBundledTrust.isTrusted(destination.absolutePath))
    }

    @Test
    fun `a half-written marker is rejected even when its bytes match a sha256 prefix`() {
        // The defect #1108 fixed: File.writeText truncates before writing, so a process kill
        // mid-write (or any out-of-process touch) can leave a partial digest on disk. A reader
        // would have compared it to the JAR's full digest and concluded "not trusted", which is
        // safe but strips the exemption from a byte-for-byte bundled plugin. The fix validates
        // the recorded text as a complete 64-char hex string; a 32-char prefix is structurally
        // impossible and must read as absent.
        val jar = File(tempDir, "partialMarker.jar").apply { writeText("jar-bytes") }
        val digest = FileHashing.sha256(jar)
        File(PluginBundledTrust.pathFor(jar.absolutePath)).writeText(digest.substring(0, 32))
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a marker with the right length but non-hex bytes is rejected`() {
        val jar = File(tempDir, "garbageMarker.jar").apply { writeText("jar-bytes") }
        File(PluginBundledTrust.pathFor(jar.absolutePath))
            .writeText("z".repeat(64))
        assertFalse(PluginBundledTrust.isTrusted(jar.absolutePath))
    }

    @Test
    fun `a marker with the wrong length - longer or shorter than 64 chars - is rejected`() {
        val shortJar = File(tempDir, "shortMarker.jar").apply { writeText("jar-bytes") }
        File(PluginBundledTrust.pathFor(shortJar.absolutePath))
            .writeText("a".repeat(63))
        assertFalse(PluginBundledTrust.isTrusted(shortJar.absolutePath))

        val longJar = File(tempDir, "longMarker.jar").apply { writeText("jar-bytes") }
        File(PluginBundledTrust.pathFor(longJar.absolutePath))
            .writeText("a".repeat(65))
        assertFalse(PluginBundledTrust.isTrusted(longJar.absolutePath))
    }

    @Test
    fun `an atomic write does not leave a sibling tmp on disk`() {
        val jar = File(tempDir, "cleanMarker.jar").apply { writeText("jar-bytes") }
        PluginBundledTrust.markTrusted(jar.absolutePath, FileHashing.sha256(jar))
        val leftover = tempDir.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue(leftover.isEmpty(), "tmp siblings leaked: ${leftover.map { it.name }}")
    }

    @Test
    fun `concurrent write and read never observe a partial marker`() {
        // A reader that landed between the truncate and completion of the old in-place writer
        // would see a half-written digest and conclude "not trusted", stripping the exemption
        // from a byte-for-byte bundled JAR for the rest of the session. With the monitor held
        // by every read/write/delete and atomic rename replacing the target as a single
        // observable step, every read returns either a complete old digest or a complete new
        // one. Pin that here with a tight producer/consumer loop.
        val jar = File(tempDir, "racing.jar").apply { writeText("jar-bytes") }
        val originalDigest = FileHashing.sha256(jar)
        PluginBundledTrust.markTrusted(jar.absolutePath, originalDigest)

        val executor = Executors.newFixedThreadPool(2)
        // Hoisted out of the `try` so the post-loop assertion below can read it. The
        // reference is also published from inside the reader thread, but capture is
        // by reference and the `AtomicReference` gives the safe-publication guarantee.
        val partialObserved = AtomicReference<String?>(null)
        try {
            val stop = AtomicBoolean(false)
            val start = CountDownLatch(1)
            val writer =
                executor.submit {
                    start.await()
                    while (!stop.get()) {
                        PluginBundledTrust.markTrusted(jar.absolutePath, originalDigest)
                    }
                }
            val reader =
                executor.submit {
                    start.await()
                    while (!stop.get()) {
                        // The reader is out-of-process: Files.move(REPLACE_EXISTING) transiently
                        // unlinks the target on Windows, so a raw read can briefly see absence
                        // rather than either digest. That's the existing behaviour the
                        // synchronized monitor protects against - count it as a non-observation
                        // (not a partial read) and keep scanning.
                        val raw =
                            try {
                                File(PluginBundledTrust.pathFor(jar.absolutePath)).readText().trim()
                            } catch (_: java.io.FileNotFoundException) {
                                continue
                            }
                        // Every observation must be either the complete original digest or a
                        // string this object would have refused to publish. In particular,
                        // never a strict prefix.
                        if (looksLikePartialPrefix(raw, originalDigest)) {
                            partialObserved.compareAndSet(null, raw)
                        }
                    }
                }
            start.countDown()
            Thread.sleep(250)
            stop.set(true)
            writer.get(5, TimeUnit.SECONDS)
            reader.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(PluginBundledTrust.isTrusted(jar.absolutePath))
        // Every observation was either absence or the complete published digest; a partial
        // prefix captured here would mean the synchronized write/read contract regressed. Pin
        // that the producer/consumer loop never surfaced a partial digest, not just that the
        // final state is consistent.
        assertNull(
            partialObserved.get(),
            "Reader observed a partial digest under concurrent writes: ${partialObserved.get()}",
        )
        assertFalse(
            File(tempDir, "racing.jar.bundled-trust.tmp").exists(),
            "fixed-name tmp leaked across concurrent writes",
        )
    }

    private fun looksLikePartialPrefix(
        raw: String,
        originalDigest: String,
    ): Boolean =
        raw != originalDigest &&
            raw.isNotEmpty() &&
            raw.length < originalDigest.length &&
            raw.startsWith(originalDigest.substring(0, originalDigest.length / 2))
}
