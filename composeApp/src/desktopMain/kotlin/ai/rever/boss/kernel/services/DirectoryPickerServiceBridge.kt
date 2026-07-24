package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DirectoryPickerServiceBridge(
    private val provider: DirectoryPickerProvider,
) : DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineImplBase() {
    override suspend fun pickDirectory(request: Empty): DirectoryPickerResponse {
        val path =
            suspendCancellableCoroutine<String?> { cont ->
                provider.pickDirectory { result ->
                    cont.resume(result)
                }
            }
        return DirectoryPickerResponse
            .newBuilder()
            .setSelected(path != null)
            .setPath(path ?: "")
            .build()
    }
}
