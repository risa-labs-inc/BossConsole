package ai.rever.boss.components.plugin.remote

import ai.rever.boss.ipc.proto.ClickEvent
import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.TextChangeEvent
import ai.rever.boss.ipc.proto.ToggleEvent
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ui.sdk.WidgetTree
import androidx.compose.runtime.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Host-side panel component that renders a remote plugin's UI.
 *
 * Connects to the plugin process via gRPC [PluginUIService], collects
 * widget tree updates, and re-renders using [RemoteWidgetRenderer].
 *
 * UI events from user interactions are forwarded back to the plugin process
 * via the bidirectional stream.
 */
class RemotePanelComponent(
    val panelId: String,
    val displayName: String,
    private val processId: String,
    private val uiAddress: String,
) {
    private val logger = LoggerFactory.getLogger(RemotePanelComponent::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _widgetTree = mutableStateOf<WidgetTree?>(null)
    private val _connected = mutableStateOf(false)

    /** Outgoing UI events from kernel to plugin process. */
    private val outgoingEvents = MutableSharedFlow<UIEvent>(extraBufferCapacity = 256)

    /** Whether the component is connected to the plugin process. */
    val connected: State<Boolean> get() = _connected

    /**
     * Compose content for this remote panel.
     */
    @Composable
    fun Content() {
        val tree by _widgetTree
        tree?.let { widgetTree ->
            RemoteWidgetRenderer(
                tree = widgetTree,
                onEvent = { nodeId, eventType, eventData ->
                    logger.debug(
                        "Panel UI event: panel={}, node={}, type={}, data={}",
                        panelId, nodeId, eventType, eventData
                    )
                    // Forward event to plugin process via gRPC
                    scope.launch {
                        sendUIEvent(nodeId, eventType, eventData)
                    }
                }
            )
        }
    }

    /**
     * Connect to the plugin process and start streaming widget updates.
     * Call this when the panel is first displayed.
     */
    fun connect() {
        logger.info("Connecting to remote panel: panelId={}, process={}, address={}", panelId, processId, uiAddress)
        scope.launch {
            connectToPluginProcess()
        }
    }

    /**
     * Connect to the plugin process and start streaming widget updates.
     * Uses the provided gRPC channel instead of creating one from the address.
     */
    fun connect(channel: ManagedChannel) {
        logger.info("Connecting to remote panel via channel: panelId={}, process={}", panelId, processId)
        scope.launch {
            connectWithChannel(channel)
        }
    }

    /**
     * Update the displayed widget tree (called from IPC handler or direct test).
     */
    fun updateTree(tree: WidgetTree) {
        _widgetTree.value = tree
    }

    fun dispose() {
        scope.cancel()
        _connected.value = false
        logger.info("Remote panel disposed: panelId={}", panelId)
    }

    // ---- Internal ----

    private suspend fun connectToPluginProcess() {
        try {
            val client = ai.rever.boss.ipc.BossIpcClient(uiAddress)
            connectWithChannel(client.channel)
        } catch (e: Exception) {
            logger.error("Failed to connect to plugin process: panelId={}", panelId, e)
            _connected.value = false
        }
    }

    private suspend fun connectWithChannel(channel: ManagedChannel) {
        val stub = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(channel)

        try {
            _connected.value = true

            // StreamUI is bidirectional: kernel sends WidgetUpdates, plugin sends UIEvents.
            // We wrap outgoing UIEvents as the request stream and collect incoming UIEvents
            // from the response stream.
            val widgetUpdateStream = channelFlow {
                // Forward UI events from kernel to plugin process as WidgetUpdate wrappers
                outgoingEvents.collect { event ->
                    val update = ai.rever.boss.ipc.proto.WidgetUpdate.newBuilder()
                        .setSurfaceId(event.surfaceId)
                        .build()
                    send(update)
                }
            }

            stub.streamUI(widgetUpdateStream).collect { uiEvent ->
                logger.debug("Received UI event from plugin: surface={}", uiEvent.surfaceId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _connected.value = false
            logger.warn("Connection to plugin process lost: panelId={}, error={}", panelId, e.message)
        }
    }

    private suspend fun sendUIEvent(nodeId: String, eventType: String, eventData: String) {
        val eventBuilder = UIEvent.newBuilder()
            .setSurfaceId(panelId)
            .setTargetNodeId(nodeId)
            .setTimestamp(System.currentTimeMillis())

        when (eventType) {
            "click" -> eventBuilder.setClick(
                ClickEvent.newBuilder().setEventId(eventData).build()
            )
            "textChange" -> eventBuilder.setTextChange(
                TextChangeEvent.newBuilder().setNewValue(eventData).build()
            )
            "toggle" -> eventBuilder.setToggle(
                ToggleEvent.newBuilder().setChecked(eventData.toBoolean()).build()
            )
        }

        outgoingEvents.emit(eventBuilder.build())
    }
}
