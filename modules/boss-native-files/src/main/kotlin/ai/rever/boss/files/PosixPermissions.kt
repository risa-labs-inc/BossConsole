package ai.rever.boss.files

internal object PosixPermissions {
    fun restrictDirectory(descriptor: Int) {
        // Darwin evaluates ACL grants before BSD mode bits. Clear both access and inheritable entries
        // before any log child or content is created, using the held object rather than its pathname.
        if (PosixApi.mac) clearDarwinAcl(descriptor)
        val result = PosixApi.library.getFunction("fchmod").invokeInt(arrayOf(descriptor, 448))
        PosixApi.check(result, "Set directory permissions")
    }

    private fun clearDarwinAcl(descriptor: Int) {
        val empty =
            PosixApi.library.getFunction("acl_init").invokePointer(arrayOf(0))
                ?: throw PosixApi.error("Create private directory ACL")
        try {
            val arguments = arrayOf<Any>(descriptor, empty, 0x100) // ACL_TYPE_EXTENDED
            val result = PosixApi.library.getFunction("acl_set_fd_np").invokeInt(arguments)
            PosixApi.check(result, "Set private directory ACL")
        } finally {
            PosixApi.library.getFunction("acl_free").invokeInt(arrayOf(empty))
        }
    }
}
