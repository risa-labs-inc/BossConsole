package ai.rever.boss.services

import ai.rever.boss.cli.CLICommandHandler

/**
 * Desktop implementation of WorkspaceHandlerService.
 *
 * Delegates to CLICommandHandler for workspace event queueing management.
 */
actual object WorkspaceHandlerService {
    actual fun markReady() {
        CLICommandHandler.getInstance().markWorkspaceHandlerReady()
    }
}
