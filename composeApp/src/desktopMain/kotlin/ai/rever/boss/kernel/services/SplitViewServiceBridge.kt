package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Host-side split-view operations for authenticated child processes (BossConsole#53).
 *
 * Every unary RPC requires the process identity verified by the kernel's interceptor before
 * accessing the provider. Implemented mutations log the operation and caller, without exposing
 * URLs or file paths. This is process authentication, not a per-plugin capability or path policy.
 * Other kernel services require their own guards; this bridge does not secure the entire server.
 */
// One method per RPC the generated service base class declares, plus small identity and audit helpers.
@Suppress("TooManyFunctions")
class SplitViewServiceBridge(
    private val provider: SplitViewOperations,
) : SplitViewServiceGrpcKt.SplitViewServiceCoroutineImplBase() {
    override suspend fun openUrlInActivePanel(request: SplitViewOpenUrlRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openUrlInActivePanel")
        logMutation("openUrlInActivePanel", caller)
        provider.openUrlInActivePanel(request.url, request.title, request.forceNewTab)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInActivePanel(request: SplitViewOpenFileRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileInActivePanel")
        logMutation("openFileInActivePanel", caller)
        provider.openFileInActivePanel(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInBrowser(request: SplitViewOpenFileRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileInBrowser")
        logMutation("openFileInBrowser", caller)
        provider.openFileInBrowser(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInEditor(request: SplitViewOpenFileRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileInEditor")
        logMutation("openFileInEditor", caller)
        provider.openFileInEditor(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileAtPosition(request: SplitViewOpenFileAtPositionRequest): Empty {
        val caller = authenticatedCallerOrRefuse("openFileAtPosition")
        logMutation("openFileAtPosition", caller)
        provider.openFileAtPosition(request.filePath, request.fileName, request.line, request.column)
        return Empty.getDefaultInstance()
    }

    override suspend fun setActivePanel(request: SplitViewPanelIdRequest): Empty {
        val caller = authenticatedCallerOrRefuse("setActivePanel")
        logMutation("setActivePanel", caller)
        provider.setActivePanel(request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun preserveCurrentState(request: SplitViewPreserveStateRequest): Empty {
        val caller = authenticatedCallerOrRefuse("preserveCurrentState")
        logMutation("preserveCurrentState", caller)
        provider.preserveCurrentState(request.workspaceId, request.workspaceName)
        return Empty.getDefaultInstance()
    }

    override suspend fun selectTabInPanel(request: SplitViewSelectTabInPanelRequest): Empty {
        val caller = authenticatedCallerOrRefuse("selectTabInPanel")
        logMutation("selectTabInPanel", caller)
        provider.selectTabInPanel(request.tabId, request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun applyWorkspace(request: SplitViewApplyWorkspaceRequest): Empty {
        authenticatedCallerOrRefuse("applyWorkspace")
        // Add mutation auditing when this stub gains provider dispatch.
        // Workspace JSON needs to be deserialized on the host side
        // For now, log the request — full implementation depends on LayoutWorkspace serialization
        return Empty.getDefaultInstance()
    }

    /**
     * Unary RPCs only: this identity is a per-call snapshot. Streaming RPCs must revalidate
     * with `CURRENT_IDENTITY`, as PluginUIServiceBridge.streamUI does.
     * The verified identity, or a thrown `PERMISSION_DENIED` when there is none.
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
        logger.info(
            LogCategory.AUTH,
            "Authenticated split-view mutation requested",
            mapOf("rpc" to rpc, "caller" to caller),
        )
    }

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private companion object {
        val logger = BossLogger.forComponent("SplitViewServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
