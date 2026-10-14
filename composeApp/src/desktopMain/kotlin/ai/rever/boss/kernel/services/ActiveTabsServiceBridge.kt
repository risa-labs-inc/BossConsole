package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Kernel-side bridge for `ActiveTabsService`.
 *
 * Revocation is checked before each emission; idle streams are not proactively disconnected.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. Before this bridge checked identity
 * at all, any process able to open a connection to the kernel IPC server - not only the plugins
 * the host itself loaded - could [watchActiveTabs] to see every open tab's title and URL across
 * every window (browsing history and open documents, disclosed with no credential), and could
 * [createBrowserTab] to force the browser to navigate to an arbitrary URL, or [closeTab]/
 * [selectTab] to manipulate what the user is looking at.
 *
 * These guards protect this service only. BossConsole#53 tracks the remaining unguarded
 * services on this server.
 */
// One method per RPC the generated service base class declares, plus small identity and audit helpers.
@Suppress("TooManyFunctions")
class ActiveTabsServiceBridge(
    private val provider: ActiveTabsProvider,
) : ActiveTabsServiceGrpcKt.ActiveTabsServiceCoroutineImplBase() {
    override fun watchActiveTabs(request: Empty): Flow<ActiveTabListResponse> {
        // Capture the gRPC context before returning the asynchronously collected flow.
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchActiveTabs")
            provider.activeTabs.collect { tabs ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchActiveTabs", "revocation")
                }
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
    }

    override suspend fun refreshTabs(request: Empty): Empty {
        authenticatedCallerOrRefuse("refreshTabs")
        provider.refreshTabs()
        return Empty.getDefaultInstance()
    }

    override suspend fun selectTab(request: SelectTabRequest): Empty {
        val caller = authenticatedCallerOrRefuse("selectTab")
        logMutation("selectTab", caller)
        provider.selectTab(request.tabId, request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun getTabUrl(request: TabIdRequest): ActiveTabStringResponse {
        authenticatedCallerOrRefuse("getTabUrl")
        val url = provider.getTabUrl(request.tabId)
        return ActiveTabStringResponse
            .newBuilder()
            .setValue(url ?: "")
            .build()
    }

    override suspend fun getFaviconCacheKey(request: TabIdRequest): ActiveTabStringResponse {
        authenticatedCallerOrRefuse("getFaviconCacheKey")
        val key = provider.getFaviconCacheKey(request.tabId)
        return ActiveTabStringResponse
            .newBuilder()
            .setValue(key ?: "")
            .build()
    }

    override suspend fun createBrowserTab(request: CreateBrowserTabRequest): ActiveTabStringResponse {
        val caller = authenticatedCallerOrRefuse("createBrowserTab")
        logMutation("createBrowserTab", caller)
        val tabId = provider.createBrowserTab(request.url, request.title)
        return ActiveTabStringResponse
            .newBuilder()
            .setValue(tabId ?: "")
            .build()
    }

    override suspend fun closeTab(request: TabIdRequest): ActiveTabBoolResponse {
        val caller = authenticatedCallerOrRefuse("closeTab")
        logMutation("closeTab", caller)
        val success = provider.closeTab(request.tabId)
        return ActiveTabBoolResponse
            .newBuilder()
            .setValue(success)
            .build()
    }

    /**
     * Unary RPCs only: the verified identity, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun logMutation(
        rpc: String,
        caller: String,
    ) {
        logger.info(LogCategory.AUTH, "Authenticated tab mutation requested", mapOf("rpc" to rpc, "caller" to caller))
    }

    private fun refuseIdentity(
        rpc: String,
        stage: String = "bind",
    ): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc, "stage" to stage),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private companion object {
        val logger = BossLogger.forComponent("ActiveTabsServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
