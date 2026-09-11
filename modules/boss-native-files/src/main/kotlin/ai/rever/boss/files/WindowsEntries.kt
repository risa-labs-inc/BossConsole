package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.io.IOException

/** FILE_NAMES_INFORMATION reads names from the owned handle; no FindFirstFile path is reopened. */
internal object WindowsEntries {
    fun visit(
        directory: Pointer,
        visitor: (String) -> Boolean,
    ) {
        Memory(65_552).use { buffer ->
            val io = buffer.share(65_536)
            var restart = 1.toByte()
            var proceed = true
            while (proceed) {
                val arguments =
                    arrayOf<Any?>(
                        directory,
                        null,
                        null,
                        null,
                        io,
                        buffer,
                        65_536,
                        12,
                        0.toByte(),
                        null,
                        restart,
                    )
                val status = WindowsApi.nt.getFunction("NtQueryDirectoryFile").invokeInt(arguments)
                if (status == 0x80000006.toInt()) break // STATUS_NO_MORE_FILES
                WindowsApi.checkStatus(status, "List directory")
                val count = io.getLong(8)
                require(count in 12..65_536) { "Invalid directory result length" }
                proceed = entries(buffer, count, visitor)
                restart = 0
            }
        }
    }

    private fun entries(
        buffer: Memory,
        size: Long,
        visitor: (String) -> Boolean,
    ): Boolean {
        var offset = 0L
        var proceed = true
        while (proceed) {
            check(offset <= size - 12) { "Invalid directory entry offset" }
            val next = buffer.getInt(offset).toLong() and 0xffffffffL
            val length = buffer.getInt(offset + 8)
            if (length < 0 || length % 2 != 0 || length > size - offset - 12) {
                throw IOException("Invalid directory entry name")
            }
            val name = String(buffer.getByteArray(offset + 12, length), Charsets.UTF_16LE)
            if (name != "." && name != "..") proceed = visitor(name)
            if (next == 0L) break
            if (next < 12 || next > size - offset) throw IOException("Invalid next directory entry")
            offset += next
        }
        return proceed
    }
}
