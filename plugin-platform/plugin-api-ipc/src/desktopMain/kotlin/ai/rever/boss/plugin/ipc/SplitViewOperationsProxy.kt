package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.TabsComponent
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of SplitViewOperations.
 */
class SplitViewOperationsProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : SplitViewOperations {

    private val stub = SplitViewServiceGrpcKt.SplitViewServiceCoroutineStub(channel)

    override fun openUrlInActivePanel(url: String, title: String, forceNewTab: Boolean) {
        scope.launch {
            try {
                stub.openUrlInActivePanel(
                    SplitViewOpenUrlRequest.newBuilder()
                        .setUrl(url).setTitle(title).setForceNewTab(forceNewTab)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun openFileInActivePanel(filePath: String, fileName: String) {
        scope.launch {
            try {
                stub.openFileInActivePanel(
                    SplitViewOpenFileRequest.newBuilder().setFilePath(filePath).setFileName(fileName).build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun openFileInEditor(filePath: String, fileName: String) {
        scope.launch {
            try {
                stub.openFileInEditor(
                    SplitViewOpenFileRequest.newBuilder().setFilePath(filePath).setFileName(fileName).build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun openFileAtPosition(filePath: String, fileName: String, line: Int, column: Int) {
        scope.launch {
            try {
                stub.openFileAtPosition(
                    SplitViewOpenFileAtPositionRequest.newBuilder()
                        .setFilePath(filePath).setFileName(fileName)
                        .setLine(line).setColumn(column)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun setActivePanel(panelId: String) {
        scope.launch {
            try {
                stub.setActivePanel(SplitViewPanelIdRequest.newBuilder().setPanelId(panelId).build())
            } catch (_: Exception) {}
        }
    }

    override fun preserveCurrentState(workspaceId: String, workspaceName: String) {
        scope.launch {
            try {
                stub.preserveCurrentState(
                    SplitViewPreserveStateRequest.newBuilder()
                        .setWorkspaceId(workspaceId).setWorkspaceName(workspaceName)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun getActiveTabsComponent(): TabsComponent? {
        // TabsComponent requires in-process access — not available via IPC
        return null
    }

    override fun applyWorkspace(workspace: LayoutWorkspace) {
        scope.launch {
            try {
                stub.applyWorkspace(
                    SplitViewApplyWorkspaceRequest.newBuilder()
                        .setWorkspaceJson(WorkspaceSerializer.serialize(workspace))
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun selectTabInPanel(tabId: String, panelId: String) {
        scope.launch {
            try {
                stub.selectTabInPanel(
                    SplitViewSelectTabInPanelRequest.newBuilder()
                        .setTabId(tabId).setPanelId(panelId)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }
}
