package ai.rever.boss.utils

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial extraction tests for [BoundedZipExtractor].
 *
 * WHY: the engine bundle is fetched over the network and inflated into a directory whose
 * binaries the app then executes. The catalog checksum authenticates WHICH archive arrives;
 * these tests prove the extractor itself contains whatever arrives - a `../` or absolute
 * entry name never escapes the root, a symlink entry is never materialized, and a bomb is
 * cut off at its caps rather than at the disk, whether its central directory tells the
 * truth or lies about its sizes. The crafted fixtures additionally pin the parser-agreement
 * refusals: a fake second central directory planted in an end-record comment can neither
 * hide a symlink from the scan nor steer the scan and the reader to judge different
 * bytes, and the tree `ditto` writes is audited for real-path containment and the same
 * caps once it returns.
 */
class BoundedZipExtractorTest {
    @TempDir
    lateinit var root: File

    private val extractDir: File get() = File(root, "engine")

    private val tinyLimits =
        BoundedZipExtractor.Limits(
            maxEntries = 2,
            maxTotalUncompressedBytes = 64,
            maxEntryUncompressedBytes = 32,
        )

    private val chainLimits =
        BoundedZipExtractor.Limits(
            maxEntries = 64,
            maxTotalUncompressedBytes = 4096,
            maxEntryUncompressedBytes = 4096,
        )

    private val auditLimits =
        BoundedZipExtractor.Limits(
            maxEntries = 16,
            maxTotalUncompressedBytes = 64,
            maxEntryUncompressedBytes = 32,
        )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val zip = File(root, "archive.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    private fun extract(
        zip: File,
        limits: BoundedZipExtractor.Limits = tinyLimits,
        onFile: (ZipEntry, Path) -> Unit = { _, _ -> },
    ) {
        BoundedZipExtractor.extract(zip.toPath(), extractDir.toPath(), limits, onFile)
    }

    private fun verifyMacZip(
        zip: File,
        limits: BoundedZipExtractor.Limits = chainLimits,
    ) {
        BoundedZipExtractor.verifyExtractableWithin(
            zip.toPath(),
            extractDir.toPath(),
            limits,
            allowFrameworkSymlinks = true,
        )
    }

    @Test
    fun `nested files and directories extract with names preserved`() {
        val seen = mutableListOf<String>()

        extract(zipOf("a/b/c.txt" to "hello".toByteArray(), "top.txt" to "x".toByteArray())) { entry, _ ->
            seen += entry.name
        }

        assertTrue(File(extractDir, "a/b").isDirectory)
        assertTrue(File(extractDir, "a/b/c.txt").readText() == "hello")
        assertTrue(File(extractDir, "top.txt").isFile)
        assertTrue(seen == listOf("a/b/c.txt", "top.txt"), "every file must be reported, in order")
    }

    @Test
    fun `a dot-dot entry is refused and never reaches outside the root`() {
        val zip =
            zipOf(
                "inside.txt" to "ok".toByteArray(),
                "../escape.txt" to "pwned".toByteArray(),
            )

        assertFailsWith<SecurityException> { extract(zip) }

        assertFalse(File(root, "escape.txt").exists(), "nothing may be written outside the root")
        assertFalse(File(extractDir, "escape.txt").exists())
    }

    @Test
    fun `an absolute-path entry is refused`() {
        val canary = "/BOSS-zip-slip-canary.txt"
        try {
            val zip = zipOf(canary to "pwned".toByteArray())

            assertFailsWith<SecurityException> { extract(zip) }

            assertFalse(File(canary).exists(), "an absolute entry name must not escape the root")
        } finally {
            File(canary).delete()
        }
    }

    @Test
    fun `a symlink-mode entry is refused`() {
        val zip = ZipArchiveFixtures.symlinkModeEntry(File(root, "symlink.zip"), "innocent.txt")

        assertFailsWith<SecurityException> { extract(zip) }

        assertFalse(File(extractDir, "innocent.txt").exists(), "no link and no file may land")
    }

    @Test
    fun `more entries than the limit are refused before extraction starts`() {
        val zip =
            zipOf(
                "a.txt" to "1".toByteArray(),
                "b.txt" to "2".toByteArray(),
                "c.txt" to "3".toByteArray(),
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `a declared total beyond the limit is refused before extraction starts`() {
        val limits =
            BoundedZipExtractor.Limits(
                maxEntries = 2,
                maxTotalUncompressedBytes = 64,
                maxEntryUncompressedBytes = 64,
            )
        val zip = zipOf("a.txt" to ByteArray(40), "b.txt" to ByteArray(40))

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip, limits) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `a single entry larger than the per-entry cap is refused`() {
        val zip = zipOf("big.bin" to ByteArray(40))

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `a lying central directory cannot smuggle bytes past the per-entry cap`() {
        val zip =
            ZipArchiveFixtures.deflatedEntryWithUnderstatedSize(
                File(root, "understated.zip"),
                "smuggled.bin",
                actualBytes = 4096,
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        val partial = File(extractDir, "smuggled.bin")
        assertTrue(!partial.exists() || partial.length() <= 32, "the crossing chunk is never written")
    }

    @Test
    fun `a lying central directory cannot smuggle bytes past the total cap`() {
        val limits =
            BoundedZipExtractor.Limits(
                maxEntries = 1,
                maxTotalUncompressedBytes = 2048,
                maxEntryUncompressedBytes = 100_000,
            )
        val zip =
            ZipArchiveFixtures.deflatedEntryWithUnderstatedSize(
                File(root, "understated-total.zip"),
                "smuggled.bin",
                actualBytes = 4096,
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip, limits) }

        val partial = File(extractDir, "smuggled.bin")
        assertTrue(!partial.exists() || partial.length() <= 2048, "the crossing chunk is never written")
    }

    @Test
    fun `the declared pre-scan gates archives for extractors that cannot cap`() {
        val zip = zipOf("a.txt" to ByteArray(40), "b.txt" to ByteArray(40))
        val refusingLimits = BoundedZipExtractor.Limits(2, 64, 64)
        val acceptingLimits = BoundedZipExtractor.Limits(2, 80, 64)

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> {
            BoundedZipExtractor.verifyDeclaredWithinLimits(zip.toPath(), refusingLimits)
        }
        BoundedZipExtractor.verifyDeclaredWithinLimits(zip.toPath(), acceptingLimits)
    }

    @Test
    fun `the pre-scan refuses escaping entry names for extractors that cannot contain`() {
        val zip =
            zipOf(
                "inside.txt" to "ok".toByteArray(),
                "../escape.txt" to "pwned".toByteArray(),
            )

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractableWithin(zip.toPath(), extractDir.toPath())
        }

        assertFalse(File(root, "escape.txt").exists(), "the pre-scan writes nothing at all")
    }

    @Test
    fun `the pre-scan refuses symlink entries for extractors that cannot contain`() {
        val zip = ZipArchiveFixtures.symlinkModeEntry(File(root, "symlink.zip"), "innocent.txt")

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractableWithin(zip.toPath(), extractDir.toPath())
        }
    }

    @Test
    fun `the mac pre-scan permits an internal framework Versions Current link`() {
        val zip =
            ZipArchiveFixtures.symlinkModeEntry(
                File(root, "framework-link.zip"),
                "Chromium Framework.framework/Versions/Current",
                "A",
            )

        BoundedZipExtractor.verifyExtractableWithin(zip.toPath(), extractDir.toPath(), allowFrameworkSymlinks = true)
    }

    @Test
    fun `the mac pre-scan refuses a framework link escaping the extraction root`() {
        val zip =
            ZipArchiveFixtures.symlinkModeEntry(
                File(root, "escaping-link.zip"),
                "Chromium Framework.framework/Versions/Current",
                "../../../../outside",
            )

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractableWithin(
                zip.toPath(),
                extractDir.toPath(),
                allowFrameworkSymlinks = true,
            )
        }
    }

    @Test
    fun `a fake end record cannot hide a symlink entry from the scan`() {
        val zip =
            ZipArchiveFixtures.craftedArchive(
                File(root, "t1.zip"),
                listOf(
                    ZipArchiveFixtures.Entry.symlink("Chromium Framework.framework/x", "../../outside"),
                    ZipArchiveFixtures.Entry.file("innocent.txt", "ok"),
                ),
                fakeCentral = ZipArchiveFixtures.fakeCentralRecord("innocent.txt", contentSize = 2),
                gap = 30,
            )

        assertFailsWith<SecurityException> { extract(zip) }

        assertFalse(File(extractDir, "innocent.txt").exists(), "nothing from the real directory may land")
        assertFalse(File(root, "outside").exists(), "the hidden link must not be materialized")
    }

    @Test
    fun `extraction succeeds through the real directory when the fake record is truncated`() {
        val zip =
            ZipArchiveFixtures.craftedArchive(
                File(root, "t2.zip"),
                listOf(ZipArchiveFixtures.Entry.file("ok.txt", "ok")),
                fakeCentral =
                    ZipArchiveFixtures.fakeCentralRecord(
                        "ok.txt",
                        contentSize = 2,
                        declaredNameLength = 100,
                    ),
                gap = 30,
            )

        extract(zip)

        assertEquals("ok", File(extractDir, "ok.txt").readText())
    }

    @Test
    fun `a shadow directory the zip reader accepts is refused by parser agreement`() {
        val zip =
            ZipArchiveFixtures.craftedArchive(
                File(root, "t3.zip"),
                listOf(
                    ZipArchiveFixtures.Entry.file("a.txt", "a"),
                    ZipArchiveFixtures.Entry.file("b.txt", "b"),
                ),
                fakeCentral = ZipArchiveFixtures.fakeCentralRecord("shadow.txt", contentSize = 1),
                gap = 0,
            )

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> { extract(zip) }

        assertFalse(extractDir.exists(), "the refusal must happen before anything is written")
    }

    @Test
    fun `an entry path through a framework link stays inside the root`() {
        val zip =
            ZipArchiveFixtures.multiEntryArchive(
                File(root, "through.zip"),
                listOf(
                    ZipArchiveFixtures.Entry.symlink("Lib.framework/Versions/Current", "A"),
                    ZipArchiveFixtures.Entry.file("Lib.framework/Versions/Current/helper.txt", "ok"),
                ),
            )

        verifyMacZip(zip)
    }

    @Test
    fun `a chain of framework links resolves inside the root`() {
        val zip =
            ZipArchiveFixtures.multiEntryArchive(
                File(root, "chain.zip"),
                listOf(
                    ZipArchiveFixtures.Entry.symlink("App.framework/one", "two"),
                    ZipArchiveFixtures.Entry.symlink("App.framework/two", "three"),
                    ZipArchiveFixtures.Entry.symlink("App.framework/three", "real"),
                    ZipArchiveFixtures.Entry.file("App.framework/one/file.txt", "ok"),
                ),
            )

        verifyMacZip(zip)
    }

    @Test
    fun `mutually linked framework entries are refused as a cycle`() {
        val zip =
            ZipArchiveFixtures.multiEntryArchive(
                File(root, "cycle.zip"),
                listOf(
                    ZipArchiveFixtures.Entry.symlink("Loop.framework/a", "b"),
                    ZipArchiveFixtures.Entry.symlink("Loop.framework/b", "a"),
                ),
            )

        assertFailsWith<SecurityException> { verifyMacZip(zip) }
    }

    @Test
    fun `a link chain past the depth guard is refused`() {
        val links =
            (0..41).map { index ->
                ZipArchiveFixtures.Entry.symlink(
                    "Deep.framework/link$index",
                    if (index == 41) "landing" else "link${index + 1}",
                )
            }
        val zip = ZipArchiveFixtures.multiEntryArchive(File(root, "deep.zip"), links)

        assertFailsWith<SecurityException> { verifyMacZip(zip) }
    }

    @Test
    fun `a symlink outside a framework bundle is refused even on the mac path`() {
        val zip =
            ZipArchiveFixtures.multiEntryArchive(
                File(root, "plain.zip"),
                listOf(
                    ZipArchiveFixtures.Entry.symlink("plain/innocent", "target"),
                    ZipArchiveFixtures.Entry.file("plain/target", "ok"),
                ),
            )

        assertFailsWith<SecurityException> { verifyMacZip(zip) }
    }

    @Test
    fun `an entry cap at the format ceiling is refused as unenforceable`() {
        val zip = zipOf("a.txt" to "1".toByteArray())
        val unenforceable =
            BoundedZipExtractor.Limits(
                maxEntries = 0xFFFF,
                maxTotalUncompressedBytes = 64,
                maxEntryUncompressedBytes = 32,
            )

        assertFailsWith<IllegalArgumentException> { extract(zip, unenforceable) }
    }

    @Test
    fun `a written tree within the caps passes the audit`() {
        extractDir.mkdirs()
        File(extractDir, "a.txt").writeText("0123456789")
        File(extractDir, "sub").mkdirs()
        File(extractDir, "sub/b.txt").writeText("0123456789")

        BoundedZipExtractor.verifyExtractedTreeWithin(extractDir.toPath(), auditLimits)

        assertTrue(File(extractDir, "a.txt").exists(), "an accepted tree is left in place")
    }

    @Test
    fun `a tree past the total cap is refused and deleted`() {
        extractDir.mkdirs()
        File(extractDir, "a.txt").writeText("0".repeat(23))
        File(extractDir, "b.txt").writeText("0".repeat(23))
        File(extractDir, "c.txt").writeText("0".repeat(19))

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> {
            BoundedZipExtractor.verifyExtractedTreeWithin(extractDir.toPath(), auditLimits)
        }

        assertFalse(extractDir.exists(), "a refused tree must be deleted")
    }

    @Test
    fun `a tree file past the per-entry cap is refused and deleted`() {
        extractDir.mkdirs()
        File(extractDir, "big.bin").writeText("0".repeat(33))

        assertFailsWith<BoundedZipExtractor.ArchiveLimitExceededException> {
            BoundedZipExtractor.verifyExtractedTreeWithin(extractDir.toPath(), auditLimits)
        }

        assertFalse(extractDir.exists(), "a refused tree must be deleted")
    }

    @Test
    fun `a written link escaping the root is refused and deleted`() {
        extractDir.mkdirs()
        File(extractDir, "inside.txt").writeText("ok")
        val escape = extractDir.toPath().resolve("escape")
        val creationFailure: Exception? =
            try {
                Files.createSymbolicLink(escape, Path.of("../../outside"))
                null
            } catch (denied: IOException) {
                denied
            } catch (unsupported: UnsupportedOperationException) {
                unsupported
            }
        assumeTrue(creationFailure == null, "symlink creation is not permitted on this host: $creationFailure")

        assertFailsWith<SecurityException> {
            BoundedZipExtractor.verifyExtractedTreeWithin(extractDir.toPath(), auditLimits)
        }

        assertFalse(extractDir.exists(), "a refused tree must be deleted")
    }
}
