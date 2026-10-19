package ai.rever.boss.health

internal actual fun readWorkspaceHealth(): WorkspaceHealthReport = WorkspaceHealthCollector().collect()
