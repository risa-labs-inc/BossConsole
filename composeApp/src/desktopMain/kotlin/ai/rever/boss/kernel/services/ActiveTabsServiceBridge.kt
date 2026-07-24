package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ActiveTabsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ActiveTabsServiceBridge(
    private val provider: ActiveTabsProvider,
) : ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineImplBase() {
    override fun watchActiveTabs(request: Empty): Flow<ActiveTabListResponse> =
        flow {
            provider.activeTabs.collect { tabs ->
                emit(
                    ActiveTabListResponse
                        .newBuilder()
                        .addAllTabs(
                            tabs.map { tab ->
                                ActiveTabProto
                                    .newBuilder()
                                    .setTabId(tab.tabId)
                                    .setTypeId(tab.typeId)
                                    .setTitle(tab.title)
                                    .setWorkspaceId(tab.workspaceId)
                                    .setWorkspaceName(tab.workspaceName)
                                    .setPanelId(tab.panelId)
                                    .setWindowId(tab.windowId)
                                    .setSplitPosition(tab.splitPosition ?: "")
                                    .setUrl(tab.url ?: "")
                                    .setFaviconCacheKey(tab.faviconCacheKey ?: "")
                                    .build()
                            },
                        ).build(),
                )
            }
        }

    override suspend fun refreshTabs(request: Empty): Empty {
        provider.refreshTabs()
        return Empty.getDefaultInstance()
    }

    override suspend fun selectTab(request: SelectTabRequest): Empty {
        provider.selectTab(request.tabId, request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun getTabUrl(request: TabIdRequest): ActiveTabStringResponse {
        val url = provider.getTabUrl(request.tabId)
        return ActiveTabStringResponse
            .newBuilder()
            .setValue(url ?: "")
            .build()
    }

    override suspend fun getFaviconCacheKey(request: TabIdRequest): ActiveTabStringResponse {
        val key = provider.getFaviconCacheKey(request.tabId)
        return ActiveTabStringResponse
            .newBuilder()
            .setValue(key ?: "")
            .build()
    }

    override suspend fun createBrowserTab(request: CreateBrowserTabRequest): ActiveTabStringResponse {
        val tabId = provider.createBrowserTab(request.url, request.title)
        return ActiveTabStringResponse
            .newBuilder()
            .setValue(tabId ?: "")
            .build()
    }

    override suspend fun closeTab(request: TabIdRequest): ActiveTabBoolResponse {
        val success = provider.closeTab(request.tabId)
        return ActiveTabBoolResponse
            .newBuilder()
            .setValue(success)
            .build()
    }
}
