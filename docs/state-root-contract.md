# BOSS state-root contract

BOSS treats `~/.boss` as the complete durable state boundary. A deployment may checkpoint,
restore or move that directory as one unit. Restarting BOSS against the restored directory must
recover the user's BOSS-owned state.

Durable host data includes authentication state, settings, Spaces, browser profiles, installed
plugins, plugin-owned storage, MCP policy and audit records, update state and crash reports. Host
and in-repository plugin code must resolve these paths through `BossDirectories.resolve`.

`BossDirectories.resolve` rejects blank paths, absolute paths, traversal outside the root and
escapes through existing symlinks. Plugins receive host-scoped storage under
`~/.boss/plugin-data/<plugin-id>` and must not invent another durable location.

The following are not durable BOSS state:

- user-selected project and import/export paths;
- operating-system registration files and sockets that must live in platform-defined locations;
- temporary files that are safe to discard and are recreated after restart;
- build and test outputs.

Older desktop builds stored Space records under `~/Documents/BOSS/workspaces`. On first use, BOSS
copies missing JSON records into `~/.boss/workspaces`, writes a one-shot migration marker, then
reads and writes only the state-root copy. The marker prevents a later deletion from being
resurrected from the legacy directory. The legacy directory remains untouched as a rollback copy.

New persistence code must satisfy both rules:

1. Its default path is produced by `BossDirectories.resolve`.
2. A test proves the path remains under `BossDirectories.rootDir` and rejects caller-controlled
   escape attempts where applicable.
