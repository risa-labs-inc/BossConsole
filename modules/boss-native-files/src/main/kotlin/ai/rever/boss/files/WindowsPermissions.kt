package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer

/** Verify the filesystem actually retained a protected DACL granting only the process owner access. */
internal object WindowsPermissions {
    fun verify(
        file: Pointer,
        expectedOwner: String,
    ) {
        Memory(4).use { required ->
            val query = WindowsApi.nt.getFunction("NtQuerySecurityObject")
            query.invokeInt(arrayOf<Any?>(file, 5, null, 0, required))
            val size = required.getInt(0)
            require(size in 20..65_536) { "Unexpected file permissions size" }
            Memory(size.toLong()).use { descriptor ->
                val result = query.invokeInt(arrayOf<Any>(file, 5, descriptor, size, required))
                WindowsApi.checkStatus(result, "Inspect file permissions")
                checkDescriptor(descriptor, expectedOwner)
            }
        }
    }

    private fun checkDescriptor(
        descriptor: Memory,
        expectedOwner: String,
    ) {
        val control = descriptor.getShort(2).toInt() and 0xffff
        require(control and 0x9004 == 0x9004) { "Private permissions require a protected DACL" }
        val owner = descriptor.getInt(4).toLong() and 0xffffffffL
        val dacl = descriptor.getInt(16).toLong() and 0xffffffffL
        require(owner in 20..descriptor.size() - 8 && dacl in 20..descriptor.size() - 16) {
            "Invalid private permissions descriptor"
        }
        val count = descriptor.getShort(dacl + 4).toInt() and 0xffff
        require(count == 1 && descriptor.getByte(dacl + 8) == 0.toByte()) { "Expected one owner permission entry" }
        require(descriptor.getByte(dacl + 9) == 0.toByte()) { "Private permissions cannot be inherited" }
        require(descriptor.getInt(dacl + 12) == 0x1f01ff) { "Owner must retain full file access" }
        require(sidString(boundedSid(descriptor, owner)) == expectedOwner) { "Unexpected file owner" }
        require(sidString(boundedSid(descriptor, dacl + 16)) == expectedOwner) { "Unexpected file access grant" }
    }

    internal fun boundedSid(
        descriptor: Memory,
        offset: Long,
    ): Pointer {
        require(offset in 20..descriptor.size() - 8) { "Invalid SID offset" }
        val count = descriptor.getByte(offset + 1).toInt() and 0xff
        require(descriptor.getByte(offset) == 1.toByte() && count <= 15) { "Invalid SID header" }
        require(8L + count * 4L <= descriptor.size() - offset) { "Truncated SID" }
        return descriptor.share(offset)
    }

    fun sidString(sid: Pointer): String =
        Memory(8).use { output ->
            val result = WindowsApi.security.getFunction("ConvertSidToStringSidW").invokeInt(arrayOf(sid, output))
            WindowsApi.check(result, "Inspect file owner")
            val text = checkNotNull(output.getPointer(0))
            try {
                text.getWideString(0)
            } finally {
                WindowsApi.kernel.getFunction("LocalFree").invokePointer(arrayOf(text))
            }
        }
}
