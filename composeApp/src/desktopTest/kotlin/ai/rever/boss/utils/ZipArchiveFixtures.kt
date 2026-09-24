package ai.rever.boss.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Hand-written zip bytes for the extraction tests in this package.
 *
 * WHY raw bytes instead of ZipOutputStream: the fixtures needed here - an entry whose unix
 * mode marks it as a symbolic link, an entry whose central directory understates its real
 * uncompressed size, and archives carrying a fake second central directory inside an
 * end-record comment - cannot be produced through ZipOutputStream, because that api fixes
 * every piece of metadata to the bytes actually written. The bytes are assembled with
 * little-endian field writes per the PKWARE appnote.
 */
internal object ZipArchiveFixtures {
    /** The stat(2) mode a real archive uses for a symbolic-link entry. */
    const val SYMLINK_MODE = 0xA1FF

    /** Junk bytes a fake end record hides behind, so it cannot end the archive itself. */
    private const val JUNK_BYTES = 8

    /** One STORED archive member: its name, its bytes, and the unix mode it carries. */
    class Entry(
        val name: String,
        val content: ByteArray,
        val unixMode: Int,
    ) {
        companion object {
            fun file(
                name: String,
                content: String,
            ) = Entry(name, content.toByteArray(), 0)

            fun symlink(
                name: String,
                target: String,
            ) = Entry(name, target.toByteArray(), SYMLINK_MODE)
        }
    }

    /**
     * A one-entry STORED archive whose entry is a symbolic link named [name] with [content]
     * as its stored target - the mode bits a real archive uses for framework links like
     * `Versions/Current`.
     */
    fun symlinkModeEntry(
        dest: File,
        name: String,
        content: String = "/outside/target",
    ): File = writeStored(dest, name, content.toByteArray(), unixMode = SYMLINK_MODE)

    /**
     * A one-entry DEFLATED archive that really inflates to [actualBytes] of repeated data
     * while its central directory declares only [declaredSize] - the lying-metadata shape
     * the copy-time caps exist for, since a pre-scan that trusts the directory would wave
     * it through.
     */
    fun deflatedEntryWithUnderstatedSize(
        dest: File,
        name: String,
        actualBytes: Int,
        declaredSize: Long = 1L,
    ): File {
        val raw = ByteArray(actualBytes) { 'x'.code.toByte() }
        val compressed =
            ByteArrayOutputStream().let { buffer ->
                // Zip entries carry RAW deflate bytes - JDK's ZipFile inflates with nowrap,
                // so the compressor must skip the zlib wrapper a default Deflater emits.
                DeflaterOutputStream(buffer, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use { deflater ->
                    deflater.write(raw)
                }
                buffer.toByteArray()
            }
        val crc = CRC32().apply { update(raw) }.value
        val nameBytes = name.toByteArray()
        dest.outputStream().use { out ->
            val localOffset = 0
            out.localHeader(nameBytes, compressed, crc, actualBytes.toLong(), method = 8)
            out.write(compressed)
            val centralOffset = 30 + nameBytes.size + compressed.size
            out.centralHeader(
                nameBytes,
                crc = crc,
                compressedSize = compressed.size.toLong(),
                declaredSize = declaredSize,
                method = 8,
                unixMode = 0,
                localOffset = localOffset,
            )
            out.endOfCentralDirectory(centralOffset = centralOffset, nameBytes = nameBytes)
        }
        return dest
    }

    /**
     * A STORED multi-entry archive - one local header and one central record per [Entry],
     * then an end record whose comment is exactly [comment] bytes. Entry order, unix modes
     * and sizes are the caller's, which is what the link-chain fixtures need.
     */
    fun multiEntryArchive(
        dest: File,
        entries: List<Entry>,
        comment: ByteArray = ByteArray(0),
    ): File = dest.apply { writeBytes(archiveBytes(entries, comment)) }

    /**
     * [locals][real central records][real end record][comment = [fakeCentral]][gap of J
     * bytes][fake end record][junk]: the real end record's comment reaches the archive's
     * last byte, while the fake one claims an empty comment with junk still behind it. A
     * [gap] > 0 leaves the fake's declared directory inside the junk, so the JDK's own
     * reader skips it and reads the real one; a [gap] of 0 points the fake at [fakeCentral]
     * and the JDK accepts the fake directory instead of the real one.
     */
    fun craftedArchive(
        dest: File,
        realEntries: List<Entry>,
        fakeCentral: ByteArray,
        gap: Int,
    ): File {
        val fakeCentralOffset = archiveBytes(realEntries, ByteArray(0)).size
        val fakeEnd =
            endOfCentralDirectoryBytes(
                entryCount = 1,
                centralSize = fakeCentral.size,
                centralOffset = fakeCentralOffset,
            )
        val comment =
            fakeCentral +
                ByteArray(gap) { 'J'.code.toByte() } +
                fakeEnd +
                ByteArray(JUNK_BYTES) { 'J'.code.toByte() }
        return multiEntryArchive(dest, realEntries, comment)
    }

    /**
     * One central record exactly as the crafted fixtures embed one in an end-record
     * comment: a member named [name] of [contentSize] stored bytes. [declaredNameLength]
     * overrides the name length the record claims, which is how the lying truncated-record
     * fixture is built.
     */
    fun fakeCentralRecord(
        name: String,
        contentSize: Int,
        declaredNameLength: Int = name.length,
    ): ByteArray {
        val nameBytes = name.toByteArray()
        return centralHeaderBytes(
            nameBytes = nameBytes,
            crc = 0L,
            compressedSize = contentSize.toLong(),
            declaredSize = contentSize.toLong(),
            method = 0,
            unixMode = 0,
            localOffset = 0,
            declaredNameLength = declaredNameLength,
        )
    }

    private fun archiveBytes(
        entries: List<Entry>,
        comment: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val names = entries.map { it.name.toByteArray(Charsets.UTF_8) }
        val offsets = mutableListOf<Int>()
        entries.forEachIndexed { index, entry ->
            offsets += out.size()
            out.localHeader(
                names[index],
                entry.content,
                crc32Of(entry.content),
                entry.content.size.toLong(),
                method = 0,
            )
            out.write(entry.content)
        }
        val centralOffset = out.size()
        entries.forEachIndexed { index, entry ->
            out.write(
                centralHeaderBytes(
                    nameBytes = names[index],
                    crc = crc32Of(entry.content),
                    compressedSize = entry.content.size.toLong(),
                    declaredSize = entry.content.size.toLong(),
                    method = 0,
                    unixMode = entry.unixMode,
                    localOffset = offsets[index],
                    declaredNameLength = names[index].size,
                ),
            )
        }
        out.endOfCentralDirectory(
            entryCount = entries.size,
            centralSize = out.size() - centralOffset,
            centralOffset = centralOffset,
            comment = comment,
        )
        return out.toByteArray()
    }

    private fun crc32Of(content: ByteArray): Long = CRC32().apply { update(content) }.value

    private fun writeStored(
        dest: File,
        name: String,
        content: ByteArray,
        unixMode: Int,
    ): File {
        val crc = CRC32().apply { update(content) }.value
        val nameBytes = name.toByteArray()
        dest.outputStream().use { out ->
            out.localHeader(nameBytes, content, crc, content.size.toLong(), method = 0)
            out.write(content)
            val centralOffset = 30 + nameBytes.size + content.size
            out.centralHeader(
                nameBytes,
                crc = crc,
                compressedSize = content.size.toLong(),
                declaredSize = content.size.toLong(),
                method = 0,
                unixMode = unixMode,
                localOffset = 0,
            )
            out.endOfCentralDirectory(centralOffset = centralOffset, nameBytes = nameBytes)
        }
        return dest
    }

    private fun OutputStream.localHeader(
        nameBytes: ByteArray,
        content: ByteArray,
        crc: Long,
        size: Long,
        method: Int,
    ) {
        le32(0x04034b50L)
        le16(20)
        le16(0)
        le16(method)
        le16(0)
        le16(0x21)
        le32(crc)
        le32(content.size.toLong())
        le32(size)
        le16(nameBytes.size)
        le16(0)
        write(nameBytes)
    }

    private fun OutputStream.centralHeader(
        nameBytes: ByteArray,
        crc: Long,
        compressedSize: Long,
        declaredSize: Long,
        method: Int,
        unixMode: Int,
        localOffset: Int,
    ) {
        write(
            centralHeaderBytes(
                nameBytes = nameBytes,
                crc = crc,
                compressedSize = compressedSize,
                declaredSize = declaredSize,
                method = method,
                unixMode = unixMode,
                localOffset = localOffset,
                declaredNameLength = nameBytes.size,
            ),
        )
    }

    private fun centralHeaderBytes(
        nameBytes: ByteArray,
        crc: Long,
        compressedSize: Long,
        declaredSize: Long,
        method: Int,
        unixMode: Int,
        localOffset: Int,
        declaredNameLength: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.le32(0x02014b50L)
        out.le16(0x031E)
        out.le16(20)
        out.le16(0)
        out.le16(method)
        out.le16(0)
        out.le16(0x21)
        out.le32(crc)
        out.le32(compressedSize)
        out.le32(declaredSize)
        out.le16(declaredNameLength)
        out.le16(0)
        out.le16(0)
        out.le16(0)
        out.le16(0)
        out.le32((unixMode.toLong() and 0xFFFFL) shl 16)
        out.le32(localOffset.toLong())
        out.write(nameBytes)
        return out.toByteArray()
    }

    private fun OutputStream.endOfCentralDirectory(
        centralOffset: Int,
        nameBytes: ByteArray,
    ) {
        write(
            endOfCentralDirectoryBytes(
                entryCount = 1,
                centralSize = 46 + nameBytes.size,
                centralOffset = centralOffset,
            ),
        )
    }

    private fun OutputStream.endOfCentralDirectory(
        entryCount: Int,
        centralSize: Int,
        centralOffset: Int,
        comment: ByteArray = ByteArray(0),
    ) {
        write(
            endOfCentralDirectoryBytes(
                entryCount = entryCount,
                centralSize = centralSize,
                centralOffset = centralOffset,
                comment = comment,
            ),
        )
    }

    private fun endOfCentralDirectoryBytes(
        entryCount: Int,
        centralSize: Int,
        centralOffset: Int,
        comment: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.le32(0x06054b50L)
        out.le16(0)
        out.le16(0)
        out.le16(entryCount)
        out.le16(entryCount)
        out.le32(centralSize.toLong())
        out.le32(centralOffset.toLong())
        out.le16(comment.size)
        out.write(comment)
        return out.toByteArray()
    }

    private fun OutputStream.le16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun OutputStream.le32(value: Long) {
        for (shift in 0 until 4) {
            write(((value shr (8 * shift)) and 0xFFL).toInt())
        }
    }
}
