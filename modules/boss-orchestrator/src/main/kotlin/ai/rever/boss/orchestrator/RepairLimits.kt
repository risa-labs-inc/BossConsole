package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import io.grpc.Status

/** Server ceilings keep a failure report from multiplying into unbounded analysis and retained state. */
internal object RepairLimits {
    // Shipped manifests declare at most two short hints; leave room for larger service inventories.
    const val HINT_COUNT = 32
    const val PATTERN_CHARS = 512
    const val REGEX_INSTRUCTIONS = 4096
    const val ERROR_TYPE_CHARS = 1024
    const val MESSAGE_CHARS = 8192
    const val STACK_CHARS = 65_536
    const val SOURCE_COUNT = 16
    const val SOURCE_BYTES = 262_144

    fun bounded(report: ProcessFailureReport): ProcessFailureReport {
        if (report.processId.length !in 1..200) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid failure report process ID").asRuntimeException()
        }
        return report
            .toBuilder()
            .setErrorType(report.errorType.take(ERROR_TYPE_CHARS))
            .setErrorMessage(report.errorMessage.take(MESSAGE_CHARS))
            .setStackTrace(report.stackTrace.take(STACK_CHARS))
            .build()
    }
}
