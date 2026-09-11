package ai.rever.boss.process

import ai.rever.boss.files.FileInfo
import ai.rever.boss.files.NativeDirectory
import java.nio.channels.SeekableByteChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** Log creation, rotation, and permissions operate on held directories, never reopened child paths. */
internal class ProcessLogDirectory private constructor(
    val path: Path,
    private val directory: NativeDirectory,
) : AutoCloseable {
    val identity = directory.identity

    fun create(name: String): SeekableByteChannel = directory.file(name, create = true)

    fun delete(name: String) {
        try {
            directory.delete(name)
        } catch (_: NoSuchFileException) {
            // Missing rotated files are expected for new logs.
        }
    }

    fun move(
        source: String,
        destination: String,
    ): Boolean =
        try {
            directory.move(source, directory, destination, overwrite = true)
            true
        } catch (_: NoSuchFileException) {
            false
        }

    fun attributes(name: String): FileInfo? = directory.info(name)

    override fun close() = directory.close()

    companion object {
        fun open(
            root: Path,
            processId: String,
        ): ProcessLogDirectory {
            val absolute = root.toAbsolutePath().normalize()
            NativeDirectory.open(absolute, create = true).use { base ->
                base.restrictToOwner()
                val opened = base.child(processId, create = true)
                var delivered = false
                try {
                    opened.restrictToOwner()
                    val result = ProcessLogDirectory(absolute.resolve(processId), opened)
                    delivered = true
                    return result
                } finally {
                    if (!delivered) opened.close()
                }
            }
        }
    }
}
