package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.SplitViewOperations

class SplitViewServiceBridge(
    private val provider: SplitViewOperations,
) : SplitViewServiceGrpcKt.SplitViewServiceCoroutineImplBase() {

    override suspend fun openUrlInActivePanel(request: SplitViewOpenUrlRequest): Empty {
        provider.openUrlInActivePanel(request.url, request.title, request.forceNewTab)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInActivePanel(request: SplitViewOpenFileRequest): Empty {
        provider.openFileInActivePanel(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInBrowser(request: SplitViewOpenFileRequest): Empty {
        provider.openFileInBrowser(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileInEditor(request: SplitViewOpenFileRequest): Empty {
        provider.openFileInEditor(request.filePath, request.fileName)
        return Empty.getDefaultInstance()
    }

    override suspend fun openFileAtPosition(request: SplitViewOpenFileAtPositionRequest): Empty {
        provider.openFileAtPosition(request.filePath, request.fileName, request.line, request.column)
        return Empty.getDefaultInstance()
    }

    override suspend fun setActivePanel(request: SplitViewPanelIdRequest): Empty {
        provider.setActivePanel(request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun preserveCurrentState(request: SplitViewPreserveStateRequest): Empty {
        provider.preserveCurrentState(request.workspaceId, request.workspaceName)
        return Empty.getDefaultInstance()
    }

    override suspend fun selectTabInPanel(request: SplitViewSelectTabInPanelRequest): Empty {
        provider.selectTabInPanel(request.tabId, request.panelId)
        return Empty.getDefaultInstance()
    }

    override suspend fun applyWorkspace(request: SplitViewApplyWorkspaceRequest): Empty {
        // Workspace JSON needs to be deserialized on the host side
        // For now, log the request — full implementation depends on LayoutWorkspace serialization
        return Empty.getDefaultInstance()
    }
}
