package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.plugin.api.SettingsProvider
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of SettingsProvider.
 *
 * [SettingsProvider.openSettings] is a UI-trigger action that must run
 * in the kernel process's UI thread. This proxy publishes a JSON event to
 * the EventBusService so the kernel-side listener can open the settings
 * dialog in the appropriate window.
 *
 * Event type: "boss.ui.OpenSettingsEvent"
 * Payload:    {"windowId": "...", "section": "..."}
 */
class SettingsProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : SettingsProvider {

    private val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel)

    override fun openSettings(windowId: String, section: String) {
        scope.launch {
            try {
                val payload = """{"windowId":${windowId.jsonEscape()},"section":${section.jsonEscape()}}"""
                stub.publish(
                    EventEnvelope.newBuilder()
                        .setEventType("boss.ui.OpenSettingsEvent")
                        .setPayload(ByteString.copyFromUtf8(payload))
                        .setSourceWindowId(windowId)
                        .setTimestamp(System.currentTimeMillis())
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    private fun String.jsonEscape(): String {
        val sb = StringBuilder("\"")
        for (ch in this) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
