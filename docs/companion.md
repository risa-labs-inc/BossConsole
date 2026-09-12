# BOSS Companion

The BOSS Companion is a floating, non-focus-stealing status surface for long-running BOSS tasks.

## Initial supported task sources

The initial implementation supports:

- **BOSS run executions** through `RunExecutionService`.
- **Terminal command lifecycle events** from `boss-plugin-terminal-tab`.

The companion event model is intentionally independent of these sources so additional task or agent producers can be added later.

## Task lifecycle

Supported task states are:

- `WORKING` - the task is actively running.
- `WAITING_FOR_INPUT` - the task is waiting for user input.
- `COMPLETED` - the task completed successfully.
- `FAILED` - the task completed unsuccessfully.
- `STOPPED` - the task was explicitly stopped.

The companion receives lifecycle events rather than inferring completion from elapsed time.

## Task identity and context

Each run execution is identified by its unique `processId`.

The companion also tracks:

- `configId` - the run configuration.
- `configName` - the human-readable task name.
- `windowId` - the BOSS window that originated the task.
- `terminalId` - the originating terminal tab identity.

Using `processId` prevents simultaneous executions of the same configuration from overwriting or misattributing each other's status.

## Plugin reporting contract

A plugin can report terminal command completion through the BOSS application event bus using:

- `sourcePluginId`: `boss-plugin-terminal-tab`
- `eventName`: `terminal.command.lifecycle`

The event payload includes the terminal/window context and lifecycle information, including:

- `status`
- `windowId`
- `terminalId`
- `tabId`
- `command`
- `exitCode`
- `blockId`

The terminal plugin reports completion only after its command block has finished and an exit code is available. The host then associates that lifecycle event with the corresponding active run process.

This keeps task state event-driven and avoids timer-based completion guesses.

## Contextual actions

The companion provides actions based on the available task context.

- Successful tasks can expose **View result**.
- Failed tasks can expose **Return to session**.
- Waiting tasks retain their originating session context.

A **Review changes** action is not presented unless a trustworthy task-scoped changes source is available. This avoids presenting an action that cannot reliably identify which changes belong to the completed task.

## Navigation behavior

Selecting a task action publishes a navigation request containing the originating:

- BOSS window
- terminal tab

The host uses `WindowFocusManager` to focus the correct BOSS window and selects the corresponding terminal tab.

The companion window itself is non-focusable so status changes do not steal keyboard focus from the user's current application.

## Companion controls

Users can:

- enable or disable the companion;
- drag it to a preferred position;
- dismiss the current companion message;
- snooze companion messages.

Disabling or dismissing the companion does not stop or otherwise modify the underlying task.

## Platform considerations

The companion is implemented using the shared Compose Desktop window model and is intended to work across macOS, Windows, and Linux.

The following areas require native runtime validation on each supported desktop platform:

- floating-window behavior;
- multiple-monitor placement;
- HiDPI/display scaling;
- behavior when BOSS is minimized or another application is active;
- window-manager differences around always-on-top and non-focusable windows.

The implementation deliberately avoids platform-specific focus manipulation in the companion itself and uses BOSS's existing `WindowFocusManager` abstraction for target-window navigation.
