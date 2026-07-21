package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * IPC proxy implementation of DirectoryPickerProvider.
 *
 * The actual file picker dialog runs in the kernel process (requires AWT/Swing).
 * This proxy sends a gRPC request and blocks until the user selects or cancels.
 */
class DirectoryPickerProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : DirectoryPickerProvider {

    private val stub = DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineStub(channel)

    override fun pickDirectory(onResult: (String?) -> Unit) {
        scope.launch {
            try {
                val resp = stub.pickDirectory(Empty.getDefaultInstance())
                onResult(if (resp.selected) resp.path else null)
            } catch (_: Exception) {
                onResult(null)
            }
        }
    }
}
