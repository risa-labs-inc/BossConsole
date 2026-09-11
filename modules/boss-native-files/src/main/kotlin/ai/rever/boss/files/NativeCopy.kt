package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Platform
import com.sun.jna.Pointer
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryNotEmptyException

internal fun copyBytes(
    source: SeekableByteChannel,
    destination: SeekableByteChannel,
) {
    val buffer = ByteBuffer.allocate(65_536)
    while (source.read(buffer) >= 0) {
        buffer.flip()
        while (buffer.hasRemaining()) destination.write(buffer)
        buffer.clear()
    }
}

internal object PosixCopy {
    fun copy(
        parent: Int,
        sourceName: String,
        target: Int,
        name: String,
    ) {
        val info = PosixApi.info(parent, sourceName)
        if (info.isLink) {
            Memory(65_536).use { buffer ->
                val count =
                    PosixApi.library.getFunction("readlinkat").invokeLong(
                        arrayOf<Any>(parent, sourceName, buffer, buffer.size()),
                    )
                if (count < 0) throw PosixApi.error("Read link")
                require(count < buffer.size()) { "Link target exceeds native buffer" }
                buffer.setByte(count, 0)
                PosixApi.check(
                    PosixApi.library.getFunction("symlinkat").invokeInt(
                        arrayOf<Any>(buffer, target, name),
                    ),
                    "Copy link",
                )
            }
        } else {
            copyObject(parent, sourceName, target, name, info.isDirectory)
        }
    }

    private fun copyObject(
        parent: Int,
        sourceName: String,
        target: Int,
        name: String,
        directory: Boolean,
    ) {
        val sourceFlags =
            PosixApi.noFollow or PosixApi.closeOnExec or PosixApi.nonBlocking or
                (if (directory) PosixApi.directory else 0)
        val input =
            PosixApi.check(
                PosixApi.library.getFunction("openat").invokeInt(
                    arrayOf<Any>(parent, sourceName, sourceFlags),
                ),
                "Open copy source",
            )
        try {
            if (directory) {
                PosixDirectory(duplicate(input)).use { child ->
                    child.entries { throw DirectoryNotEmptyException(sourceName) }
                }
                PosixApi.check(
                    PosixApi.library.getFunction("mkdirat").invokeInt(
                        arrayOf<Any>(target, name, 511),
                    ),
                    "Copy directory",
                )
            }
            val creation = if (PosixApi.mac) 0x200 or 0x800 else 0x40 or 0x80
            val targetFlags =
                PosixApi.noFollow or PosixApi.closeOnExec or
                    (if (directory) PosixApi.directory else 2 or creation)
            val output =
                PosixApi.check(
                    PosixApi.library.getFunction("openat", 3 shl 7).invokeInt(
                        arrayOf<Any>(target, name, targetFlags, 438),
                    ),
                    "Open copy destination",
                )
            try {
                copyContents(input, output, directory)
            } finally {
                PosixApi.library.getFunction("close").invokeInt(arrayOf<Any>(output))
            }
        } finally {
            PosixApi.library.getFunction("close").invokeInt(arrayOf<Any>(input))
        }
    }

    private fun copyContents(
        input: Int,
        output: Int,
        directory: Boolean,
    ) {
        if (!directory) {
            // Duplicates let the channels own their handles while metadata uses the originals.
            PosixFile(duplicate(input), false).use { reader ->
                PosixFile(duplicate(output), true).use { writer -> copyBytes(reader, writer) }
            }
        }
        attributes(input, output)
    }

    private fun duplicate(descriptor: Int): Int {
        val command = if (PosixApi.mac) 67 else 1030 // F_DUPFD_CLOEXEC
        return PosixApi.check(
            PosixApi.library.getFunction("fcntl", 2 shl 7).invokeInt(
                arrayOf<Any>(descriptor, command, 0),
            ),
            "Duplicate copy handle",
        )
    }

    private fun attributes(
        source: Int,
        destination: Int,
    ) {
        Memory(256).use { stat ->
            PosixApi.check(
                PosixApi.library.getFunction("fstat${PosixApi.inodeSuffix}").invokeInt(
                    arrayOf<Any>(source, stat),
                ),
                "Inspect copy attributes",
            )
            val modeOffset =
                if (PosixApi.mac) {
                    4L
                } else if (Platform.ARCH == "x86-64") {
                    24L
                } else {
                    16L
                }
            val mode = if (PosixApi.mac) stat.getShort(modeOffset).toInt() else stat.getInt(modeOffset)
            val ownerOffset =
                if (PosixApi.mac) {
                    16L
                } else if (Platform.ARCH == "x86-64") {
                    28L
                } else {
                    24L
                }
            // As in NIO's cross-volume move, owner copying can be unavailable to an ordinary user.
            PosixApi.library.getFunction("fchown").invokeInt(
                arrayOf<Any>(destination, stat.getInt(ownerOffset), stat.getInt(ownerOffset + 4)),
            )
            PosixApi.check(
                PosixApi.library.getFunction("fchmod").invokeInt(
                    arrayOf<Any>(destination, mode and 0xfff),
                ),
                "Copy permissions",
            )
            PosixApi.check(
                PosixApi.library.getFunction("futimens").invokeInt(
                    arrayOf<Any>(destination, stat.share(if (PosixApi.mac) 32 else 72)),
                ),
                "Copy timestamps",
            )
        }
    }
}

internal object WindowsCopy {
    fun copy(
        parent: Pointer,
        sourceName: String,
        target: Pointer,
        name: String,
    ) {
        WindowsOpen(parent, sourceName, null).use { request ->
            val input = request.open(0x80000000.toInt(), 1, 0x200000)
            try {
                val info = WindowsApi.info(input)
                if (info.isDirectory && !info.isLink) {
                    WindowsEntries.visit(input) { throw DirectoryNotEmptyException(sourceName) }
                }
                copyObject(input, target, name, info)
            } finally {
                WindowsApi.close(input)
            }
        }
    }

    private fun copyObject(
        input: Pointer,
        target: Pointer,
        name: String,
        info: FileInfo,
    ) {
        WindowsOpen(target, name, null).use { creation ->
            val options = 0x200000 or (if (info.isDirectory) 1 else 0x40)
            val output = creation.open(0xc0000000.toInt(), 2, options)
            try {
                copyContents(input, output, info)
                Memory(40).use { basic ->
                    WindowsApi.check(
                        WindowsApi.kernel.getFunction("GetFileInformationByHandleEx").invokeInt(
                            arrayOf<Any>(input, 0, basic, 40),
                        ),
                        "Read copy timestamps",
                    )
                    WindowsApi.check(
                        WindowsApi.kernel.getFunction("SetFileInformationByHandle").invokeInt(
                            arrayOf<Any>(output, 0, basic, 40),
                        ),
                        "Copy timestamps",
                    )
                }
            } finally {
                WindowsApi.close(output)
            }
        }
    }

    private fun copyContents(
        input: Pointer,
        output: Pointer,
        info: FileInfo,
    ) {
        if (info.isLink) {
            copyReparsePoint(input, output)
        } else if (!info.isDirectory) {
            WindowsFile(WindowsApi.duplicate(input), false).use { reader ->
                WindowsFile(WindowsApi.duplicate(output), true, verifyPrivate = false).use { writer ->
                    copyBytes(reader, writer)
                }
            }
        }
    }

    private fun copyReparsePoint(
        source: Pointer,
        destination: Pointer,
    ) {
        Memory(16_384).use { buffer ->
            Memory(4).use { size ->
                WindowsApi.check(
                    WindowsApi.kernel.getFunction("DeviceIoControl").invokeInt(
                        arrayOf<Any?>(source, 0x900a8, null, 0, buffer, buffer.size().toInt(), size, null),
                    ),
                    "Read reparse point",
                )
                val count = size.getInt(0)
                require(count in 8..buffer.size().toInt()) { "Invalid reparse data size" }
                WindowsApi.check(
                    WindowsApi.kernel.getFunction("DeviceIoControl").invokeInt(
                        arrayOf<Any?>(destination, 0x900a4, buffer, count, null, 0, size, null),
                    ),
                    "Copy reparse point",
                )
            }
        }
    }
}
