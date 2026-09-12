package ai.rever.boss.files

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.io.IOException

/** The ACL is installed by the creation syscall, before another process can open the new file. */
internal object WindowsSecurity {
    fun restrict(directory: Pointer) {
        privateDescriptor(directory) { descriptor ->
            val arguments = arrayOf<Any>(directory, 0x80000004.toInt(), descriptor)
            val status = WindowsApi.nt.getFunction("NtSetSecurityObject").invokeInt(arguments)
            WindowsApi.checkStatus(status, "Set private directory permissions")
            WindowsPermissions.verify(directory, ownerSid())
        }
    }

    fun verify(file: Pointer) = WindowsPermissions.verify(file, ownerSid())

    fun <T> privateDescriptor(
        parent: Pointer,
        action: (Pointer) -> T,
    ): T {
        requirePersistentAcls(parent)
        val sid = ownerSid()
        return Memory(8).use { result ->
            val sddl = WString("O:${sid}D:P(A;;FA;;;$sid)")
            val arguments = arrayOf<Any?>(sddl, 1, result, null)
            val created =
                WindowsApi.security
                    .getFunction("ConvertStringSecurityDescriptorToSecurityDescriptorW")
                    .invokeInt(arguments)
            WindowsApi.check(created, "Create private permissions")
            val descriptor = checkNotNull(result.getPointer(0))
            try {
                action(descriptor)
            } finally {
                WindowsApi.kernel.getFunction("LocalFree").invokePointer(arrayOf<Any?>(descriptor))
            }
        }
    }

    private fun ownerSid(): String =
        Memory(8).use { token ->
            val process = WindowsApi.kernel.getFunction("GetCurrentProcess").invokePointer(emptyArray())
            val opened = WindowsApi.security.getFunction("OpenProcessToken").invokeInt(arrayOf<Any?>(process, 8, token))
            WindowsApi.check(opened, "Read process identity")
            val handle = checkNotNull(token.getPointer(0))
            try {
                tokenSid(handle)
            } finally {
                WindowsApi.close(handle)
            }
        }

    private fun tokenSid(token: Pointer): String =
        Memory(4).use { size ->
            val query = WindowsApi.security.getFunction("GetTokenInformation")
            query.invokeInt(arrayOf<Any?>(token, 1, null, 0, size))
            val length = size.getInt(0)
            require(length in 16..65_536) { "Unexpected process identity size" }
            Memory(length.toLong()).use { info ->
                WindowsApi.check(query.invokeInt(arrayOf<Any?>(token, 1, info, length, size)), "Read process identity")
                WindowsPermissions.sidString(info.getPointer(0))
            }
        }

    private fun requirePersistentAcls(directory: Pointer) {
        Memory(4).use { flags ->
            val arguments = arrayOf<Any?>(directory, null, 0, null, null, flags, null, 0)
            WindowsApi.check(
                WindowsApi.kernel.getFunction("GetVolumeInformationByHandleW").invokeInt(arguments),
                "Inspect filesystem permissions",
            )
            if (flags.getInt(0) and 8 == 0) throw IOException("This filesystem cannot enforce private file permissions")
        }
    }
}
