package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ClosePanelRequest
import ai.rever.boss.ipc.proto.services.ContextMenuActionRequest
import ai.rever.boss.ipc.proto.services.ContextMenuIdRequest
import ai.rever.boss.ipc.proto.services.ContextMenuServiceGrpcKt
import ai.rever.boss.ipc.proto.services.DirectoryPickerServiceGrpcKt
import ai.rever.boss.ipc.proto.services.NotificationDuration
import ai.rever.boss.ipc.proto.services.NotificationIdRequest
import ai.rever.boss.ipc.proto.services.NotificationServiceGrpcKt
import ai.rever.boss.ipc.proto.services.NotificationType
import ai.rever.boss.ipc.proto.services.OpenPanelRequest
import ai.rever.boss.ipc.proto.services.PanelEventServiceGrpcKt
import ai.rever.boss.ipc.proto.services.PerformanceServiceGrpcKt
import ai.rever.boss.ipc.proto.services.PerformanceSettingsProto
import ai.rever.boss.ipc.proto.services.RegisterContextMenuRequest
import ai.rever.boss.ipc.proto.services.ShowToastRequest
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ai.rever.boss.plugin.api.NotificationDuration as ApiNotificationDuration
import ai.rever.boss.plugin.api.NotificationType as ApiNotificationType

/**
 * The five kernel service bridges that historically skipped the verified-caller-identity
 * requirement (BossConsole#1218), exercised the way their hardened siblings are: over a real
 * gRPC server with the production interceptor (no credential → `UNAUTHENTICATED`, and the
 * provider never hears about it), plus direct calls with no identity context at all, which must
 * fail closed with `PERMISSION_DENIED` even without the interceptor in front — the fail-closed
 * contract PR #505 introduced for the Secret Service (BossConsole#53).
 */
class UnattributedBridgesIdentityTest {
    private val tls = IpcTlsIdentity.create()
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var notificationProvider: FakeNotificationProvider
    private lateinit var panelEvents: FakePanelEventProvider
    private lateinit var directoryPicker: FakeDirectoryPickerProvider
    private lateinit var performance: FakePerformanceDataProvider
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticatedNotification: NotificationServiceGrpcKt.NotificationServiceCoroutineStub
    private lateinit var anonymousNotification: NotificationServiceGrpcKt.NotificationServiceCoroutineStub
    private lateinit var authenticatedContextMenu: ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineStub
    private lateinit var anonymousContextMenu: ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineStub
    private lateinit var authenticatedPicker: DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineStub
    private lateinit var anonymousPicker: DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineStub
    private lateinit var authenticatedPanelEvents: PanelEventServiceGrpcKt.PanelEventServiceCoroutineStub
    private lateinit var anonymousPanelEvents: PanelEventServiceGrpcKt.PanelEventServiceCoroutineStub
    private lateinit var authenticatedPerformance: PerformanceServiceGrpcKt.PerformanceServiceCoroutineStub
    private lateinit var anonymousPerformance: PerformanceServiceGrpcKt.PerformanceServiceCoroutineStub

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        notificationProvider = FakeNotificationProvider()
        panelEvents = FakePanelEventProvider()
        directoryPicker = FakeDirectoryPickerProvider("/tmp/picked")
        performance = FakePerformanceDataProvider()
        server =
            NettyServerBuilder
                .forPort(0)
                .sslContext(tls.serverContext())
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(NotificationServiceBridge(notificationProvider))
                .addService(ContextMenuServiceBridge())
                .addService(DirectoryPickerServiceBridge(directoryPicker))
                .addService(PanelEventServiceBridge(panelEvents))
                .addService(PerformanceServiceBridge(performance))
                .build()
                .start()
        authenticatedChannel = channelFor(tokenRegistry.issue(CALLER))
        anonymousChannel = channelFor(null)
        authenticatedNotification = NotificationServiceGrpcKt.NotificationServiceCoroutineStub(authenticatedChannel)
        anonymousNotification = NotificationServiceGrpcKt.NotificationServiceCoroutineStub(anonymousChannel)
        authenticatedContextMenu = ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineStub(authenticatedChannel)
        anonymousContextMenu = ContextMenuServiceGrpcKt.ContextMenuServiceCoroutineStub(anonymousChannel)
        authenticatedPicker = DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineStub(authenticatedChannel)
        anonymousPicker = DirectoryPickerServiceGrpcKt.DirectoryPickerServiceCoroutineStub(anonymousChannel)
        authenticatedPanelEvents = PanelEventServiceGrpcKt.PanelEventServiceCoroutineStub(authenticatedChannel)
        anonymousPanelEvents = PanelEventServiceGrpcKt.PanelEventServiceCoroutineStub(anonymousChannel)
        authenticatedPerformance = PerformanceServiceGrpcKt.PerformanceServiceCoroutineStub(authenticatedChannel)
        anonymousPerformance = PerformanceServiceGrpcKt.PerformanceServiceCoroutineStub(anonymousChannel)
    }

    @AfterTest
    fun tearDown() {
        authenticatedChannel.shutdownNow()
        authenticatedChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        anonymousChannel.shutdownNow()
        anonymousChannel.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        server.shutdownNow()
        server.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `every RPC on the five bridges is refused with no credential, and never reaches the provider`() =
        runBlocking {
            assertUnauthenticated { anonymousNotification.showToast(toastRequest()) }
            assertUnauthenticated {
                anonymousNotification.dismiss(
                    NotificationIdRequest.newBuilder().setNotificationId("n-1").build(),
                )
            }
            assertUnauthenticated { anonymousNotification.dismissAll(Empty.getDefaultInstance()) }
            assertUnauthenticated { anonymousContextMenu.registerContextMenu(menuRequest()) }
            assertUnauthenticated {
                anonymousContextMenu.unregisterContextMenu(
                    ContextMenuIdRequest.newBuilder().setContextMenuId("ctx-1").build(),
                )
            }
            assertUnauthenticated {
                anonymousContextMenu.onContextMenuAction(
                    ContextMenuActionRequest
                        .newBuilder()
                        .setContextMenuId("ctx-1")
                        .setActionId("copy")
                        .build(),
                )
            }
            assertUnauthenticated { anonymousPicker.pickDirectory(Empty.getDefaultInstance()) }
            assertUnauthenticated { anonymousPanelEvents.openPanel(panelRequest()) }
            assertUnauthenticated { anonymousPanelEvents.closePanel(closePanelRequest()) }
            assertUnauthenticated { anonymousPerformance.requestGC(Empty.getDefaultInstance()) }
            assertUnauthenticated { anonymousPerformance.exportMetrics(Empty.getDefaultInstance()) }
            assertUnauthenticated { anonymousPerformance.updateSettings(settingsProto()) }
            assertUnauthenticated {
                anonymousPerformance.watchCurrentSnapshot(Empty.getDefaultInstance()).take(1).toList()
            }
            assertUnauthenticated {
                anonymousPerformance.watchHistory(Empty.getDefaultInstance()).take(1).toList()
            }
            assertUnauthenticated {
                anonymousPerformance.watchSettings(Empty.getDefaultInstance()).take(1).toList()
            }

            assertTrue(notificationProvider.calls.isEmpty(), "a refused call must never reach the provider")
            assertTrue(
                panelEvents.opened.isEmpty() && panelEvents.closed.isEmpty(),
                "a refused call must never reach the provider",
            )
            assertTrue(directoryPicker.calls.isEmpty(), "a refused call must never reach the provider")
            assertTrue(performance.calls.isEmpty(), "a refused call must never reach the provider")
        }

    @Test
    fun `an authenticated caller reaches each provider`() =
        runBlocking {
            val shown = authenticatedNotification.showToast(toastRequest())
            assertEquals("notification-1", shown.notificationId)
            authenticatedNotification.dismiss(
                NotificationIdRequest.newBuilder().setNotificationId("notification-1").build(),
            )
            authenticatedNotification.dismissAll(Empty.getDefaultInstance())

            assertEquals(Empty.getDefaultInstance(), authenticatedContextMenu.registerContextMenu(menuRequest()))
            assertEquals(
                Empty.getDefaultInstance(),
                authenticatedContextMenu.unregisterContextMenu(
                    ContextMenuIdRequest.newBuilder().setContextMenuId("ctx-1").build(),
                ),
            )
            assertEquals(
                Empty.getDefaultInstance(),
                authenticatedContextMenu.onContextMenuAction(
                    ContextMenuActionRequest
                        .newBuilder()
                        .setContextMenuId("ctx-1")
                        .setActionId("copy")
                        .build(),
                ),
            )

            val picked = authenticatedPicker.pickDirectory(Empty.getDefaultInstance())
            assertTrue(picked.selected, "the fake picker selected a directory")
            assertEquals("/tmp/picked", picked.path)

            authenticatedPanelEvents.openPanel(panelRequest())
            authenticatedPanelEvents.closePanel(closePanelRequest())

            authenticatedPerformance.requestGC(Empty.getDefaultInstance())
            val exported = authenticatedPerformance.exportMetrics(Empty.getDefaultInstance())
            assertTrue(exported.success, "the fake exporter reports success")
            authenticatedPerformance.updateSettings(settingsProto())

            assertEquals(listOf("showToast", "dismiss", "dismissAll"), notificationProvider.calls)
            assertEquals(1, panelEvents.opened.size)
            assertEquals(1, panelEvents.closed.size)
            assertEquals(1, directoryPicker.calls.size)
            assertEquals(listOf("requestGC", "exportMetrics", "updateSettings"), performance.calls)
        }

    @Test
    fun `each bridge fails closed with no identity context even without the interceptor`() =
        runBlocking {
            val directNotification = NotificationServiceBridge(notificationProvider)
            assertPermissionDenied { directNotification.showToast(toastRequest()) }
            assertPermissionDenied {
                directNotification.dismiss(
                    NotificationIdRequest.newBuilder().setNotificationId("n-1").build(),
                )
            }
            assertPermissionDenied { directNotification.dismissAll(Empty.getDefaultInstance()) }

            val directMenu = ContextMenuServiceBridge()
            assertPermissionDenied { directMenu.registerContextMenu(menuRequest()) }
            assertPermissionDenied {
                directMenu.unregisterContextMenu(
                    ContextMenuIdRequest.newBuilder().setContextMenuId("ctx-1").build(),
                )
            }
            assertPermissionDenied {
                directMenu.onContextMenuAction(
                    ContextMenuActionRequest
                        .newBuilder()
                        .setContextMenuId("ctx-1")
                        .setActionId("copy")
                        .build(),
                )
            }

            val directPicker = DirectoryPickerServiceBridge(directoryPicker)
            assertPermissionDenied { directPicker.pickDirectory(Empty.getDefaultInstance()) }

            val directPanels = PanelEventServiceBridge(panelEvents)
            assertPermissionDenied { directPanels.openPanel(panelRequest()) }
            assertPermissionDenied { directPanels.closePanel(closePanelRequest()) }

            val directPerformance = PerformanceServiceBridge(performance)
            assertPermissionDenied { directPerformance.requestGC(Empty.getDefaultInstance()) }
            assertPermissionDenied { directPerformance.exportMetrics(Empty.getDefaultInstance()) }
            assertPermissionDenied { directPerformance.updateSettings(settingsProto()) }
            assertPermissionDenied {
                directPerformance.watchCurrentSnapshot(Empty.getDefaultInstance()).take(1).toList()
            }
            assertPermissionDenied {
                directPerformance.watchHistory(Empty.getDefaultInstance()).take(1).toList()
            }
            assertPermissionDenied {
                directPerformance.watchSettings(Empty.getDefaultInstance()).take(1).toList()
            }

            assertTrue(notificationProvider.calls.isEmpty(), "a refused call must never reach the provider")
            assertTrue(
                panelEvents.opened.isEmpty() && panelEvents.closed.isEmpty(),
                "a refused call must never reach the provider",
            )
            assertTrue(directoryPicker.calls.isEmpty(), "a refused call must never reach the provider")
            assertTrue(performance.calls.isEmpty(), "a refused call must never reach the provider")
        }

    private fun channelFor(token: String?): ManagedChannel {
        val builder =
            NettyChannelBuilder
                .forAddress("localhost", server.port)
                .sslContext(IpcTlsIdentity.clientContext(tls.certificateBase64))
                .overrideAuthority(IpcTlsIdentity.AUTHORITY)
        if (token != null) {
            builder.intercept(ProcessTokenClientInterceptor(token))
        }
        return builder.build()
    }

    private suspend fun assertUnauthenticated(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
    }

    private suspend fun assertPermissionDenied(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
    }

    private fun toastRequest(): ShowToastRequest =
        ShowToastRequest
            .newBuilder()
            .setMessage("hello operator")
            .setType(NotificationType.NOTIFICATION_TYPE_SUCCESS)
            .setDuration(NotificationDuration.NOTIFICATION_DURATION_SHORT)
            .build()

    private fun menuRequest(): RegisterContextMenuRequest =
        RegisterContextMenuRequest
            .newBuilder()
            .setContextMenuId("ctx-1")
            .setNodeId("node-1")
            .build()

    private fun panelRequest(): OpenPanelRequest =
        OpenPanelRequest
            .newBuilder()
            .setPanelId("panel-1")
            .setPluginId("plugin-1")
            .setDefaultOrder(0)
            .setWindowId("window-1")
            .build()

    private fun closePanelRequest(): ClosePanelRequest =
        ClosePanelRequest
            .newBuilder()
            .setPanelId("panel-1")
            .setPluginId("plugin-1")
            .setDefaultOrder(0)
            .setWindowId("window-1")
            .build()

    private fun settingsProto(): PerformanceSettingsProto =
        PerformanceSettingsProto
            .newBuilder()
            .setEnabled(true)
            .build()

    private companion object {
        const val CALLER = "unattribated-bridges-test"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}

/** Records every method it was actually asked to perform, so a refusal can be proven silent. */
private class FakeNotificationProvider : NotificationProvider {
    val calls = mutableListOf<String>()

    override fun showToast(
        message: String,
        type: ApiNotificationType,
        duration: ApiNotificationDuration,
        title: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ): String {
        calls += "showToast"
        return "notification-1"
    }

    override fun dismiss(notificationId: String) {
        calls += "dismiss"
    }

    override fun dismissAll() {
        calls += "dismissAll"
    }
}

/** Records every panel it was actually asked to move, so a refusal can be proven silent. */
private class FakePanelEventProvider : PanelEventProvider {
    val opened = mutableListOf<PanelId>()
    val closed = mutableListOf<PanelId>()

    override suspend fun closePanel(
        panelId: PanelId,
        windowId: String,
    ) {
        closed += panelId
    }

    override suspend fun openPanel(
        panelId: PanelId,
        windowId: String,
    ) {
        opened += panelId
    }
}

/** Records every native dialog it was actually asked to summon, so a refusal can be proven silent. */
private class FakeDirectoryPickerProvider(
    private val path: String?,
) : DirectoryPickerProvider {
    val calls = mutableListOf<Unit>()

    override fun pickDirectory(onResult: (String?) -> Unit) {
        calls += Unit
        onResult(path)
    }
}

/** Records every performance surface it was actually asked to touch, so a refusal can be proven silent. */
private class FakePerformanceDataProvider : PerformanceDataProvider {
    val calls = mutableListOf<String>()

    override val currentSnapshot: StateFlow<PerformanceSnapshotData> =
        MutableStateFlow(
            PerformanceSnapshotData(
                0L,
                0L,
                0L,
                0L,
                0f,
                0L,
                0L,
                0f,
                0f,
                0,
                0,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )

    override val history: StateFlow<List<PerformanceSnapshotData>> = MutableStateFlow(emptyList())

    override val settings: StateFlow<PerformanceSettingsData> =
        MutableStateFlow(
            PerformanceSettingsData(
                enabled = true,
                showIndicator = true,
                memoryWarningThresholdPercent = 80,
                memoryCriticalThresholdPercent = 90,
                cpuWarningThresholdPercent = 70,
                cpuCriticalThresholdPercent = 90,
                memorySampleIntervalMs = 1_000L,
                cpuSampleIntervalMs = 1_000L,
                historyRetentionMinutes = 30,
                pluginJvmHeapMb = 512,
                pluginJvmInitialHeapMb = 256,
            ),
        )

    override fun requestGC() {
        calls += "requestGC"
    }

    override suspend fun exportMetrics(): Result<String> {
        calls += "exportMetrics"
        return Result.success("/tmp/boss-metrics.json")
    }

    override suspend fun updateSettings(settings: PerformanceSettingsData) {
        calls += "updateSettings"
    }
}
