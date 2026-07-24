package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * IPC proxy implementation of ActiveTabsProvider.
 *
 * Note: Compose-dependent methods (loadFavicon, getFallbackIcon) and
 * browser integration are not available via IPC. These return null/defaults.
 */
class ActiveTabsProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : ActiveTabsProvider {
    private val stub = ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineStub(channel)

    private val _activeTabs = MutableStateFlow<List<ActiveTabData>>(emptyList())
    override val activeTabs: StateFlow<List<ActiveTabData>> = _activeTabs.asStateFlow()

    init {
        scope.launch { watchActiveTabs() }
    }

    private suspend fun watchActiveTabs() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchActiveTabs(Empty.getDefaultInstance()).collect { response ->
                    _activeTabs.value = response.tabsList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    override suspend fun refreshTabs() {
        try {
            stub.refreshTabs(Empty.getDefaultInstance())
        } catch (_: Exception) {
        }
    }

    override fun selectTab(
        tabId: String,
        panelId: String,
    ) {
        scope.launch {
            try {
                stub.selectTab(
                    SelectTabRequest
                        .newBuilder()
                        .setTabId(tabId)
                        .setPanelId(panelId)
                        .build(),
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun getTabUrl(tabId: String): String? =
        try {
            runBlocking {
                val resp: ActiveTabStringResponse = stub.getTabUrl(TabIdRequest.newBuilder().setTabId(tabId).build())
                resp.value.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }

    override fun getFaviconCacheKey(tabId: String): String? =
        try {
            runBlocking {
                val resp: ActiveTabStringResponse = stub.getFaviconCacheKey(TabIdRequest.newBuilder().setTabId(tabId).build())
                resp.value.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }

    @Composable
    override fun loadFavicon(cacheKey: String?): Painter? {
        // Favicon loading requires Compose/host-side resources — not available via IPC
        return null
    }

    override fun getFallbackIcon(typeId: String): ImageVector? {
        // Icon vectors are not serializable over IPC — return null
        return null
    }

    override fun getBrowserIntegration(tabId: String): BrowserIntegration? {
        // Browser integration requires in-process JxBrowser — not available via IPC
        return null
    }

    override fun createBrowserTab(
        url: String,
        title: String,
    ): String? =
        try {
            runBlocking {
                val resp =
                    stub.createBrowserTab(
                        CreateBrowserTabRequest
                            .newBuilder()
                            .setUrl(url)
                            .setTitle(title)
                            .build(),
                    )
                resp.value.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }

    override fun closeTab(tabId: String): Boolean =
        try {
            runBlocking {
                stub.closeTab(TabIdRequest.newBuilder().setTabId(tabId).build()).value
            }
        } catch (_: Exception) {
            false
        }

    private fun ActiveTabProto.toData() =
        ActiveTabData(
            tabId = tabId,
            typeId = typeId,
            title = title,
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            panelId = panelId,
            windowId = windowId,
            splitPosition = splitPosition.takeIf { it.isNotEmpty() },
            url = url.takeIf { it.isNotEmpty() },
            faviconCacheKey = faviconCacheKey.takeIf { it.isNotEmpty() },
        )
}
