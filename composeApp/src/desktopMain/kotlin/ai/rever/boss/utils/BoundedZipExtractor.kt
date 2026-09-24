package ai.rever.boss.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * Containment and decompression limits for the zip archives BossConsole extracts to disk.
 *
 * WHY this object exists: the one archive the desktop app inflates onto the filesystem is the
 * BOSS-branded Chromium engine bundle, and plugins install as single JARs that are moved
 * atomically rather than unpacked, so every file an untrusted archive can place on disk flows
 * through here. A crafted engine archive must not be able to write outside the extraction root
 * via `../` or absolute entry names, materialize symlink entries, or exhaust the disk with a
 * small archive that inflates without bound. The engine installer's checksum verification
 * authenticates WHICH archive gets extracted; these checks bound WHAT any archive can do to
 * the filesystem, whatever the checksums say and whichever platform extractor runs (Java here,
 * `ditto` on macOS, which extracts only after the same entry-name and declared-metadata gate
 * and whose written tree is audited once more after it returns).
 *
 * Containment is lexical, mirroring the editor file IO policy: the caller creates the
 * extraction root fresh, every entry name is resolved against it, and any name that
 * normalizes back outside it is refused before a file handle is opened. The unix mode that
 * marks an entry as a symbolic link lives in the raw central directory bytes - JDK's ZipEntry
 * api does not expose external attributes - so the extractor reads the directory itself and
 * refuses those entries rather than silently writing them out as broken text files. That raw
 * read must agree with the reader that extracts: the scan returns every name it parsed, and
 * an archive is refused unless those names are exactly the ones `ZipFile` reports and the
 * records filled the declared directory to the byte - so nothing can make the two parsers
 * judge different bytes.
 */
@Suppress("TooManyFunctions") // one cohesive bounded ZIP reader; splitting it would scatter the invariants
internal object BoundedZipExtractor {
    /**
     * Thrown when an archive declares or inflates past [Limits]. Entry escapes and symlink
     * entries throw [SecurityException] instead, like the extractor this object replaced.
     */
    class ArchiveLimitExceededException(
        message: String,
    ) : IllegalStateException(message)

    /** Upper bounds for one extraction. All sizes are uncompressed bytes. */
    data class Limits(
        val maxEntries: Long,
        val maxTotalUncompressedBytes: Long,
        val maxEntryUncompressedBytes: Long,
    )

    /**
     * What one strict pass over the central directory bytes found: every entry name it
     * parsed, the subset whose unix mode marks a symbolic link, the bytes those records
     * consumed, and the bytes the directory declared. The reader and the scan agree only
     * when the last two are equal.
     */
    private data class CentralDirectoryScan(
        val entryNames: Set<String>,
        val symlinkNames: Set<String>,
        val consumedBytes: Int,
        val declaredBytes: Int,
    )

    /**
     * Engine limits. A real bundle is ~160 MB compressed and well under 2 GB installed on any
     * platform with tens of thousands of files, so 50k entries / 4 GB total / 2 GB per entry
     * leaves generous headroom for bundle growth while staying far below anything a bomb
     * would need to matter.
     */
    val ENGINE_LIMITS =
        Limits(
            maxEntries = 50_000L,
            maxTotalUncompressedBytes = 4L * 1024L * 1024L * 1024L,
            maxEntryUncompressedBytes = 2L * 1024L * 1024L * 1024L,
        )

    /** stat(2) file-type bits, and the value that marks an entry as a symbolic link. */
    private const val FILE_TYPE_MASK = 0xF000
    private const val SYMLINK_TYPE = 0xA000

    private const val COPY_BUFFER_BYTES = 64 * 1024

    /**
     * The most entries a zip end-of-central directory record can declare: its count is a
     * 16-bit field. A limit at or above that ceiling could never be enforced where entry
     * counts are parsed, so the agreement between the strict scan and the reader would be
     * built on a number the format cannot even express.
     */
    private const val MAX_DECLARABLE_ENTRIES = 0xFFFF

    /** Zip structure offsets (PKWARE appnote) and sanity caps for reading the central directory. */
    private const val END_OF_CENTRAL_DIR_BYTES = 22
    private const val TOTAL_ENTRIES_OFFSET = 10
    private const val DIRECTORY_SIZE_OFFSET = 12
    private const val DIRECTORY_OFFSET_OFFSET = 16
    private const val EOCD_COMMENT_LENGTH_OFFSET = 20
    private const val CENTRAL_HEADER_BYTES = 46
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
    private const val NAME_LENGTH_OFFSET = 28
    private const val EXTRA_LENGTH_OFFSET = 30
    private const val COMMENT_LENGTH_OFFSET = 32
    private const val MODE_OFFSET = 38
    private const val NAME_OFFSET = CENTRAL_HEADER_BYTES
    private const val MAX_CENTRAL_DIR_BYTES = 16L * 1024L * 1024L
    private const val MAX_TAIL_BYTES = 66_000L

    /**
     * Refuse an archive whose central directory already declares more entries, a larger
     * single entry, or more total uncompressed bytes than [limits] allow. Runs before a
     * platform extractor that cannot enforce limits itself (macOS `ditto`) touches the
     * archive. A central directory can understate real sizes, which is why the Java
     * extraction path enforces [limits] on the bytes actually written as well.
     */
    fun verifyDeclaredWithinLimits(
        zipPath: Path,
        limits: Limits = ENGINE_LIMITS,
    ) {
        ZipFile(zipPath.toFile()).use { zip -> verifyDeclaredWithin(zip, limits) }
    }

    /**
     * Gate an archive for an extractor that cannot contain it itself (macOS `ditto`):
     * the declared-limits refusal above, plus the same entry-name containment and symlink
     * refusal [extract] enforces per entry. `ditto` honours none of these, so for that
     * path this gate is the whole containment - it must run before the extractor does.
     * The Java path re-checks everything while extracting; passing this gate is what
     * guarantees both platforms refuse the same archives.
     */
    fun verifyExtractableWithin(
        zipPath: Path,
        rootDir: Path,
        limits: Limits = ENGINE_LIMITS,
        allowFrameworkSymlinks: Boolean = false,
    ) {
        requireEntriesBelowFormatCeiling(limits)
        ZipFile(zipPath.toFile()).use { zip ->
            verifyDeclaredWithin(zip, limits)
            val scan = scanArchive(zipPath, limits)
            verifyScanAgreesWithReader(zip, scan)
            val root = rootDir.toAbsolutePath().normalize()
            val links = if (allowFrameworkSymlinks) frameworkLinks(zip, root, scan.symlinkNames) else emptyMap()
            verifyEntryPaths(zip, root, scan.symlinkNames, links, allowFrameworkSymlinks)
        }
    }

    /**
     * Audit the tree a platform extractor wrote under [rootDir] once it returns: every
     * path on disk must really resolve inside the root - `ditto` honours no containment,
     * so a link it materialized must not reach out of the tree whatever entry name
     * carried it in - and the files must sit within the same entry, per-entry and total
     * caps the Java path enforces while writing. A tree that fails the audit is deleted
     * before the refusal is rethrown, so nothing refused stays behind to be executed.
     */
    fun verifyExtractedTreeWithin(
        rootDir: Path,
        limits: Limits = ENGINE_LIMITS,
    ) {
        val root = rootDir.toAbsolutePath().normalize()
        if (!Files.exists(root)) return
        try {
            auditExtractedTree(root, limits)
        } catch (refusal: SecurityException) {
            root.toFile().deleteRecursively()
            throw refusal
        } catch (refusal: ArchiveLimitExceededException) {
            root.toFile().deleteRecursively()
            throw refusal
        }
    }

    /**
     * Extract every entry of [zipPath] into [targetDir], refusing entry escapes, symlink
     * entries, and archives beyond [limits]. [onFileExtracted] sees each written file so a
     * caller can apply platform policy on it (the engine installer marks its binaries
     * executable); it never runs for directory entries. The directory is created only
     * after the archive passed every gate, so a refusal leaves nothing behind.
     */
    fun extract(
        zipPath: Path,
        targetDir: Path,
        limits: Limits = ENGINE_LIMITS,
        onFileExtracted: (ZipEntry, Path) -> Unit,
    ) {
        requireEntriesBelowFormatCeiling(limits)
        ZipFile(zipPath.toFile()).use { zip ->
            verifyDeclaredWithin(zip, limits)
            val scan = scanArchive(zipPath, limits)
            verifyScanAgreesWithReader(zip, scan)
            val root = targetDir.toAbsolutePath().normalize()
            Files.createDirectories(root)
            var extractedEntries = 0L
            var writtenTotal = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                extractedEntries++
                if (extractedEntries > limits.maxEntries) {
                    throw ArchiveLimitExceededException(
                        "Archive holds more than ${limits.maxEntries} entries",
                    )
                }
                val targetPath = resolveWithin(root, entry, scan.symlinkNames)
                if (entry.isDirectory) {
                    Files.createDirectories(targetPath)
                } else {
                    writtenTotal += copyEntry(zip, entry, targetPath, limits, writtenTotal)
                    onFileExtracted(entry, targetPath)
                }
            }
        }
    }

    /**
     * The scan and the reader can only agree if every entry an archive really holds is
     * representable in the end-of-central directory's 16-bit count, so a limit at or above
     * that ceiling could never be enforced where the count is parsed.
     */
    private fun requireEntriesBelowFormatCeiling(limits: Limits) {
        require(limits.maxEntries < MAX_DECLARABLE_ENTRIES) {
            "maxEntries must stay below the 16-bit count a zip central directory can declare"
        }
    }

    private fun verifyDeclaredWithin(
        zip: ZipFile,
        limits: Limits,
    ) {
        var declaredEntries = 0L
        var declaredTotal = 0L
        var largestEntry = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            declaredEntries++
            if (entry.size > largestEntry) largestEntry = entry.size
            if (entry.size > 0) declaredTotal += entry.size
        }
        refuseBeyondLimits(
            entries = declaredEntries,
            total = declaredTotal,
            largest = largestEntry,
            limits = limits,
        )
    }

    private fun frameworkLinks(
        zip: ZipFile,
        root: Path,
        symlinkNames: Set<String>,
    ): Map<Path, Path> {
        val links = mutableMapOf<Path, Path>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name in symlinkNames) {
                val linkPath = resolveEntryName(root, entry.name)
                links[linkPath] = frameworkLinkTarget(zip, entry, root, linkPath)
            }
        }
        return links
    }

    private fun frameworkLinkTarget(
        zip: ZipFile,
        entry: ZipEntry,
        root: Path,
        linkPath: Path,
    ): Path {
        requireFrameworkLink(entry)
        val target = readFrameworkLinkTarget(zip, entry)
        val targetPath = linkPath.parent.resolve(target).normalize()
        if (!targetPath.startsWith(root)) {
            throw SecurityException("Symlink target escapes extraction root: ${entry.name}")
        }
        return targetPath
    }

    private fun requireFrameworkLink(entry: ZipEntry) {
        if (!entry.name.contains(".framework/")) {
            throw SecurityException("Refusing non-framework symlink: ${entry.name}")
        }
    }

    private fun readFrameworkLinkTarget(
        zip: ZipFile,
        entry: ZipEntry,
    ): String {
        val target = zip.getInputStream(entry).use { String(it.readNBytes(4_097), Charsets.UTF_8) }
        val hasInvalidBytes = target.isEmpty() || target.length > 4_096 || '\u0000' in target
        if (hasInvalidBytes || Path.of(target).isAbsolute) {
            throw SecurityException("Refusing unsafe symlink target: ${entry.name}")
        }
        return target
    }

    private fun verifyEntryPaths(
        zip: ZipFile,
        root: Path,
        symlinkNames: Set<String>,
        links: Map<Path, Path>,
        allowFrameworkSymlinks: Boolean,
    ) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!allowFrameworkSymlinks && entry.name in symlinkNames) {
                throw SecurityException("Refusing to extract symlink entry: ${entry.name}")
            }
            val path = resolveEntryName(root, entry.name)
            resolveLinkPath(root, root.relativize(path), links)
        }
    }

    private fun resolveEntryName(
        root: Path,
        name: String,
    ): Path {
        if (name.split('/').any { it == ".." }) throw SecurityException("Zip entry contains parent traversal: $name")
        val path = root.resolve(name).normalize()
        if (!path.startsWith(root)) throw SecurityException("Zip entry outside target directory: $name")
        return path
    }

    private fun resolveLinkPath(
        root: Path,
        relative: Path,
        links: Map<Path, Path>,
        depth: Int = 0,
    ): Path {
        if (depth > 40) throw SecurityException("Archive symlink cycle or chain is too long")
        var current = root
        for (part in relative) {
            current = current.resolve(part).normalize()
            if (!current.startsWith(root)) throw SecurityException("Archive path escapes extraction root")
            val destination = links[current]
            if (destination != null) {
                current = resolveLinkPath(root, root.relativize(destination), links, depth + 1)
            }
        }
        return current
    }

    /**
     * Resolve [entry] against [root], refusing symlink entries and any name that escapes it.
     * A symlink entry's whole purpose is to point somewhere the extractor never chose, so it
     * is refused loudly instead of being silently flattened into a broken text file.
     */
    private fun resolveWithin(
        root: Path,
        entry: ZipEntry,
        symlinkNames: Set<String>,
    ): Path {
        if (entry.name in symlinkNames) {
            throw SecurityException("Refusing to extract symlink entry: ${entry.name}")
        }
        val targetPath = root.resolve(entry.name).normalize()
        if (!targetPath.startsWith(root)) {
            throw SecurityException("Zip entry outside target directory: ${entry.name}")
        }
        return targetPath
    }

    private fun copyEntry(
        zip: ZipFile,
        entry: ZipEntry,
        targetPath: Path,
        limits: Limits,
        writtenBefore: Long,
    ): Long {
        Files.createDirectories(targetPath.parent)
        zip.getInputStream(entry).use { input ->
            Files.newOutputStream(targetPath).use { output ->
                return copyBounded(input, output, entry, limits, writtenBefore)
            }
        }
    }

    /**
     * Copy [input] to [output] for one entry, refusing once the entry alone, or the archive
     * so far, passes its cap. The refusal happens before the chunk that crosses a cap is
     * written, so the bytes on disk never exceed the limits whatever the directory said.
     */
    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        entry: ZipEntry,
        limits: Limits,
        writtenBefore: Long,
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var entryBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            entryBytes += read
            if (entryBytes > limits.maxEntryUncompressedBytes) {
                throw ArchiveLimitExceededException(
                    "Entry exceeds the per-entry cap: ${entry.name}",
                )
            }
            if (writtenBefore + entryBytes > limits.maxTotalUncompressedBytes) {
                throw ArchiveLimitExceededException(
                    "Archive exceeds the total uncompressed cap at: ${entry.name}",
                )
            }
            output.write(buffer, 0, read)
        }
        return entryBytes
    }

    private fun refuseBeyondLimits(
        entries: Long,
        total: Long,
        largest: Long,
        limits: Limits,
    ) {
        val violation: String? =
            if (entries > limits.maxEntries) {
                "declares $entries entries (limit ${limits.maxEntries})"
            } else if (largest > limits.maxEntryUncompressedBytes) {
                "declares an entry of $largest bytes (limit ${limits.maxEntryUncompressedBytes})"
            } else if (total > limits.maxTotalUncompressedBytes) {
                "declares $total uncompressed bytes (limit ${limits.maxTotalUncompressedBytes})"
            } else {
                null
            }
        if (violation != null) {
            throw ArchiveLimitExceededException("Refusing archive that $violation")
        }
    }

    /**
     * Walk the tree under [root] once it is fully written: count every extracted node,
     * measure the regular files, and refuse the moment the tree passes any cap. Each
     * visited path must really resolve inside [root] - a link the extractor wrote is
     * followed to its destination, and a broken link is judged by the target it names.
     */
    private fun auditExtractedTree(
        root: Path,
        limits: Limits,
    ) {
        val realRoot = root.toRealPath()
        var auditedEntries = 0L
        var auditedTotal = 0L
        var largestFile = 0L
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (directory != root) {
                        auditedEntries++
                        refuseBeyondTreeCaps(auditedEntries, auditedTotal, largestFile, limits, directory)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    auditedEntries++
                    refuseIfRealPathEscapes(file, realRoot)
                    if (attributes.isRegularFile) {
                        if (attributes.size() > largestFile) largestFile = attributes.size()
                        auditedTotal += attributes.size()
                    }
                    refuseBeyondTreeCaps(auditedEntries, auditedTotal, largestFile, limits, file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    failure: IOException,
                ): FileVisitResult = throw SecurityException("Extracted path cannot be audited: $file", failure)
            },
        )
    }

    private fun refuseIfRealPathEscapes(
        file: Path,
        realRoot: Path,
    ) {
        if (!realPathOrLexicalLinkTarget(file).startsWith(realRoot)) {
            throw SecurityException("Extracted path escapes its root: $file")
        }
    }

    /**
     * Where [file] really points, following every link on the way. A link whose target is
     * not on disk cannot be followed, but its destination is still named, so it is read
     * from the link's own text and judged lexically: a destination outside the root is a
     * refusal however unreachable it currently is.
     */
    private fun realPathOrLexicalLinkTarget(file: Path): Path =
        try {
            file.toRealPath()
        } catch (
            @Suppress("SwallowedException") unresolved: IOException,
        ) {
            // a broken link's destination is still named by the link's own text - that
            // name, not the missing inode, is what the audit judges
            lexicalLinkTarget(file)
        }

    private fun lexicalLinkTarget(file: Path): Path {
        if (!Files.isSymbolicLink(file)) return file.toAbsolutePath().normalize()
        val target = Files.readSymbolicLink(file)
        return file.parent.resolve(target).normalize()
    }

    private fun refuseBeyondTreeCaps(
        entries: Long,
        total: Long,
        largest: Long,
        limits: Limits,
        at: Path,
    ) {
        val violation: String? =
            if (entries > limits.maxEntries) {
                "holds $entries entries (limit ${limits.maxEntries})"
            } else if (largest > limits.maxEntryUncompressedBytes) {
                "holds a file of $largest bytes (limit ${limits.maxEntryUncompressedBytes})"
            } else if (total > limits.maxTotalUncompressedBytes) {
                "holds $total uncompressed bytes (limit ${limits.maxTotalUncompressedBytes})"
            } else {
                null
            }
        if (violation != null) {
            throw ArchiveLimitExceededException("Refusing extracted tree that $violation at: $at")
        }
    }

    /**
     * One strict pass over the central directory that the end-of-central directory record
     * really ending the archive declares: every parsed name, the names whose unix mode
     * marks a symlink, and how many bytes the records consumed. A declared count beyond
     * the limit, or a record that runs past the declared bytes, is a limit refusal rather
     * than a crash - a truncated record is exactly what a hidden fake directory uses to
     * make a reader read past its end.
     */
    private fun scanArchive(
        zipPath: Path,
        limits: Limits,
    ): CentralDirectoryScan {
        val archive = zipPath.toFile()
        val size = archive.length()
        val tail = tailOf(archive, size)
        val directoryEnd =
            locateEndOfCentralDirectory(tail)
                ?: throw ArchiveLimitExceededException("archive has no readable central directory")
        val entryCount = u16(tail, directoryEnd + TOTAL_ENTRIES_OFFSET)
        if (entryCount > limits.maxEntries) {
            throw ArchiveLimitExceededException(
                "Refusing archive that declares $entryCount entries (limit ${limits.maxEntries})",
            )
        }
        val central = centralDirectoryBytes(archive, size, tail, directoryEnd)
        return scanCentralDirectory(central, entryCount)
    }

    private fun tailOf(
        archive: File,
        size: Long,
    ): ByteArray {
        val tailLength = min(size, MAX_TAIL_BYTES)
        val tail = ByteArray(tailLength.toInt())
        RandomAccessFile(archive, "r").use { reader ->
            reader.seek(size - tailLength)
            reader.readFully(tail)
        }
        return tail
    }

    private fun centralDirectoryBytes(
        archive: File,
        size: Long,
        tail: ByteArray,
        directoryEnd: Int,
    ): ByteArray {
        val directorySize = u32(tail, directoryEnd + DIRECTORY_SIZE_OFFSET)
        val directoryOffset = u32(tail, directoryEnd + DIRECTORY_OFFSET_OFFSET)
        if (directorySize > MAX_CENTRAL_DIR_BYTES || directoryOffset + directorySize > size) {
            throw ArchiveLimitExceededException("central directory lies outside the archive")
        }
        val central = ByteArray(directorySize.toInt())
        RandomAccessFile(archive, "r").use { reader ->
            reader.seek(directoryOffset)
            reader.readFully(central)
        }
        return central
    }

    /**
     * Parse [entryCount] records out of the declared directory bytes, refusing any record
     * whose fixed header or name runs past them: the bounds are the archive's own
     * declaration, so reading past them can never be an index crash.
     */
    private fun scanCentralDirectory(
        central: ByteArray,
        entryCount: Int,
    ): CentralDirectoryScan {
        val entryNames = mutableSetOf<String>()
        val symlinkNames = mutableSetOf<String>()
        var position = 0
        repeat(entryCount) {
            requireRecordWithin(central, position)
            if (u32(central, position) != CENTRAL_HEADER_SIGNATURE) {
                throw ArchiveLimitExceededException("central directory is not readable")
            }
            val nameLength = u16(central, position + NAME_LENGTH_OFFSET)
            requireRecordWithin(central, position, nameLength)
            val mode = (u32(central, position + MODE_OFFSET) shr 16).toInt()
            val name = String(central, position + NAME_OFFSET, nameLength, Charsets.UTF_8)
            entryNames += name
            if (mode and FILE_TYPE_MASK == SYMLINK_TYPE) {
                symlinkNames += name
            }
            position +=
                CENTRAL_HEADER_BYTES + nameLength +
                u16(central, position + EXTRA_LENGTH_OFFSET) +
                u16(central, position + COMMENT_LENGTH_OFFSET)
        }
        return CentralDirectoryScan(entryNames, symlinkNames, position, central.size)
    }

    /**
     * The bounds are the archive's own declaration: a record whose fixed header, or whose
     * name once [nameLength] is known, runs past the declared bytes is a limit refusal,
     * never a read past the end of the buffer.
     */
    private fun requireRecordWithin(
        central: ByteArray,
        position: Int,
        nameLength: Int = 0,
    ) {
        if (position + CENTRAL_HEADER_BYTES + nameLength > central.size) {
            throw ArchiveLimitExceededException("central directory record is truncated")
        }
    }

    /**
     * Refuse the archive unless the strict scan and the [zip] reader judge the same bytes:
     * the parsed names must be exactly the reader's names, and the records must fill the
     * declared directory to the byte. Either drift means the scan's symlink verdicts
     * describe a directory the reader does not extract, so nothing downstream may run.
     */
    private fun verifyScanAgreesWithReader(
        zip: ZipFile,
        scan: CentralDirectoryScan,
    ) {
        if (scan.consumedBytes != scan.declaredBytes) {
            throw ArchiveLimitExceededException(
                "central directory declares ${scan.declaredBytes} bytes but ${scan.consumedBytes} were parsed",
            )
        }
        val readerNames = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            readerNames += entries.nextElement().name
        }
        if (readerNames != scan.entryNames) {
            throw ArchiveLimitExceededException(
                "strict central directory scan and zip reader disagree on the entry set",
            )
        }
    }

    private fun isEndOfCentralDirectory(
        tail: ByteArray,
        index: Int,
    ): Boolean {
        val signature = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x05.toByte(), 0x06.toByte())
        for (offset in signature.indices) {
            if (tail[index + offset] != signature[offset]) return false
        }
        return true
    }

    /**
     * Locate the end-of-central directory record that really ends [tail]: a record is
     * accepted only when its own comment length reaches the archive's last byte. A fake
     * record planted mid-archive - the classic hiding place for a second central
     * directory - leaves bytes behind it, so the scan can never be steered to a
     * directory the reader that extracts does not see.
     */
    private fun locateEndOfCentralDirectory(tail: ByteArray): Int? {
        var found: Int? = null
        val last = tail.size - END_OF_CENTRAL_DIR_BYTES
        if (last >= 0) {
            for (i in last downTo 0) {
                if (isEndOfCentralDirectory(tail, i) && endsAtArchiveEnd(tail, i)) {
                    found = i
                    break
                }
            }
        }
        return found
    }

    private fun endsAtArchiveEnd(
        tail: ByteArray,
        index: Int,
    ): Boolean = index + END_OF_CENTRAL_DIR_BYTES + u16(tail, index + EOCD_COMMENT_LENGTH_OFFSET) == tail.size

    private fun u16(
        data: ByteArray,
        offset: Int,
    ): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(
        data: ByteArray,
        offset: Int,
    ): Long = u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16)
}
