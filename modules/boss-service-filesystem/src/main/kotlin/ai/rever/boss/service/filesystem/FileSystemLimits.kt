package ai.rever.boss.service.filesystem

import io.grpc.Status

/** Bounded RPC pages fit below gRPC's default message limit; callers can page large file reads explicitly. */
internal object FileSystemLimits {
    const val READ_BYTES = 1_048_576
    const val SCAN_ENTRIES = 10_000
    const val SCAN_DEPTH = 64
    const val SCAN_BYTES = 2_097_152
}

internal fun fileSystemLimit(message: String) = Status.RESOURCE_EXHAUSTED.withDescription(message).asRuntimeException()

internal fun enforceFileSystemLimit(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw fileSystemLimit(message)
}
