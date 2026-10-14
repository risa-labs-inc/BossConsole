package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenClientInterceptor
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ActiveTabsServiceGrpcKt
import ai.rever.boss.ipc.proto.services.CreateBrowserTabRequest
import ai.rever.boss.ipc.proto.services.SelectTabRequest
import ai.rever.boss.ipc.proto.services.TabIdRequest
import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The active-tabs bridge, exercised over a real gRPC server (BossConsole#53).
 *
 * Before this bridge checked identity, any process able to open a connection to the kernel IPC
 * server - not only the plugins the host itself loaded - could watch every open tab's title and
 * URL across every window with no credential at all, and could force the browser to navigate to
 * an arbitrary URL via `createBrowserTab`.
 */
class ActiveTabsServiceBridgeTest {
    private lateinit var tokenRegistry: ProcessTokenRegistry
    private lateinit var provider: FakeActiveTabsProvider
    private lateinit var server: Server
    private lateinit var authenticatedChannel: ManagedChannel
    private lateinit var anonymousChannel: ManagedChannel
    private lateinit var authenticated: ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineStub
    private lateinit var anonymous: ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineStub

    @BeforeTest
    fun setUp() {
        tokenRegistry = ProcessTokenRegistry()
        provider = FakeActiveTabsProvider()
        server =
            ServerBuilder
                .forPort(0)
                .intercept(ProcessIdentityInterceptor(tokenRegistry))
                .addService(ActiveTabsServiceBridge(provider))
                .build()
                .start()
        authenticatedChannel =
            ManagedChannelBuilder
                .forAddress("localhost", server.port)
                .usePlaintext()
                .intercept(ProcessTokenClientInterceptor(tokenRegistry.issue(CALLER)))
                .build()
        anonymousChannel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
        authenticated = ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineStub(authenticatedChannel)
        anonymous = ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineStub(anonymousChannel)
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
    fun `every RPC is refused with no credential, and never reaches the tab provider`() =
        runBlocking {
            assertRefused { anonymous.watchActiveTabs(Empty.getDefaultInstance()).take(1).toList() }
            assertRefused { anonymous.refreshTabs(Empty.getDefaultInstance()) }
            assertRefused { anonymous.selectTab(SelectTabRequest.newBuilder().setTabId("t1").build()) }
            assertRefused { anonymous.getTabUrl(TabIdRequest.newBuilder().setTabId("t1").build()) }
            assertRefused { anonymous.getFaviconCacheKey(TabIdRequest.newBuilder().setTabId("t1").build()) }
            assertRefused {
                anonymous.createBrowserTab(CreateBrowserTabRequest.newBuilder().setUrl("https://evil.example").build())
            }
            assertRefused { anonymous.closeTab(TabIdRequest.newBuilder().setTabId("t1").build()) }

            assertTrue(provider.calls.isEmpty(), "a refused call must never reach the tab provider")
        }

    @Test
    fun `an authenticated caller can watch and read tabs`() =
        runBlocking {
            provider.setTabs(listOf(tab("t1", url = "https://example.com")))

            val response =
                authenticated
                    .watchActiveTabs(Empty.getDefaultInstance())
                    .take(1)
                    .toList()
                    .single()
            assertEquals("https://example.com", response.tabsList.single().url)

            val url = authenticated.getTabUrl(TabIdRequest.newBuilder().setTabId("t1").build())
            assertEquals("https://example.com", url.value)
            authenticated.refreshTabs(Empty.getDefaultInstance())
            val favicon = authenticated.getFaviconCacheKey(TabIdRequest.newBuilder().setTabId("t1").build())
            assertEquals("", favicon.value)
            assertEquals(listOf("activeTabs", "getTabUrl", "refreshTabs", "getFaviconCacheKey"), provider.calls)
        }

    @Test
    fun `an authenticated caller can select, create and close tabs`() =
        runBlocking {
            authenticated.selectTab(
                SelectTabRequest
                    .newBuilder()
                    .setTabId("t1")
                    .setPanelId("p1")
                    .build(),
            )
            authenticated.createBrowserTab(CreateBrowserTabRequest.newBuilder().setUrl("https://example.com").build())
            authenticated.closeTab(TabIdRequest.newBuilder().setTabId("t1").build())

            assertEquals(listOf("selectTab", "createBrowserTab", "closeTab"), provider.calls)
        }

    @Test
    fun `revoked caller cannot invoke a unary RPC`() =
        runBlocking {
            tokenRegistry.revoke(CALLER)
            assertRefused { authenticated.refreshTabs(Empty.getDefaultInstance()) }
            assertTrue(provider.calls.isEmpty())
        }

    @Test
    fun `revoked watcher cannot receive a later snapshot`() =
        runBlocking {
            withTimeout(10_000) {
                supervisorScope {
                    val received = Channel<Unit>(Channel.UNLIMITED)
                    val watching =
                        async {
                            authenticated.watchActiveTabs(Empty.getDefaultInstance()).collect { received.send(Unit) }
                        }
                    try {
                        received.receive()
                        tokenRegistry.revoke(CALLER)
                        provider.setTabs(listOf(tab("after-revocation")))
                        val failure = assertFailsWith<StatusException> { watching.await() }
                        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
                        assertTrue(received.tryReceive().isFailure, "revocation must prevent the next snapshot")
                    } finally {
                        watching.cancel()
                        received.close()
                    }
                }
            }
        }

    private suspend fun assertRefused(call: suspend () -> Unit) {
        val failure = assertFailsWith<StatusException> { call() }
        assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
    }

    private fun tab(
        tabId: String,
        url: String? = null,
    ): ActiveTabData =
        ActiveTabData(
            tabId = tabId,
            typeId = "browser",
            title = "Test tab",
            workspaceId = "w1",
            workspaceName = "Workspace",
            panelId = "p1",
            windowId = "win1",
            splitPosition = null,
            url = url,
            faviconCacheKey = null,
        )

    /** Records every method it was actually asked to perform, so a refusal can be proven silent. */
    private class FakeActiveTabsProvider : ActiveTabsProvider {
        val calls = mutableListOf<String>()
        private val _activeTabs = MutableStateFlow<List<ActiveTabData>>(emptyList())
        override val activeTabs: StateFlow<List<ActiveTabData>>
            get() {
                calls += "activeTabs"
                return _activeTabs
            }

        fun setTabs(tabs: List<ActiveTabData>) {
            _activeTabs.value = tabs
        }

        override suspend fun refreshTabs() {
            calls += "refreshTabs"
        }

        override fun selectTab(
            tabId: String,
            panelId: String,
        ) {
            calls += "selectTab"
        }

        override fun getTabUrl(tabId: String): String? {
            calls += "getTabUrl"
            return _activeTabs.value.find { it.tabId == tabId }?.url
        }

        override fun getFaviconCacheKey(tabId: String): String? {
            calls += "getFaviconCacheKey"
            return _activeTabs.value.find { it.tabId == tabId }?.faviconCacheKey
        }

        override fun createBrowserTab(
            url: String,
            title: String,
        ): String? {
            calls += "createBrowserTab"
            return "new-tab-id"
        }

        override fun closeTab(tabId: String): Boolean {
            calls += "closeTab"
            return true
        }

        @Composable
        override fun loadFavicon(cacheKey: String?): Painter? = null

        override fun getFallbackIcon(typeId: String): ImageVector? = null

        override fun getBrowserIntegration(tabId: String): BrowserIntegration? = null
    }

    private companion object {
        const val CALLER = "tabs-panel-plugin"
        const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }
}
