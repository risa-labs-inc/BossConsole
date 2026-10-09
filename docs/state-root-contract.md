# BOSS state-root contract

BOSS treats `~/.boss` as the complete durable state boundary in normal operation. A deployment may
checkpoint, restore or move that directory as one unit. Restarting BOSS against the restored
directory must recover the user's BOSS-owned state. Explicit developer mode remains isolated in
`~/.boss_debug`; it is not production user state and is deliberately excluded from deployment
backup and hot-swap flows.

Durable host data includes authentication state, settings, Spaces, browser profiles, installed
plugins, plugin-owned storage, MCP policy and audit records, update state and crash reports. Host
and in-repository plugin code must resolve these paths through `BossDirectories.resolve`.

`BossDirectories.resolve` creates and resolves a missing state root once per process, then rejects
absolute paths, traversal outside the root, dangling symlinks and escapes through
symlinks that exist at resolution time. An unusable root fails closed. This is a path-construction
guard; sensitive writers must still use no-follow and atomic publication at the actual I/O
boundary. Plugins receive host-scoped storage under `~/.boss/plugin-data/<plugin-id>` and must not
invent another durable location.

The following are not durable BOSS state:

- user-selected project and import/export paths;
- operating-system registration files and sockets that must live in platform-defined locations;
- temporary files that are safe to discard and are recreated after restart;
- build and test outputs.

Older desktop builds stored Space records and related workspace documents under
`~/Documents/BOSS/workspaces`. On first use, BOSS copies missing regular files into
`~/.boss/workspaces` using private temporary siblings (owner-only on POSIX) and atomic create-new
hard-link publication. BOSS writes a one-shot migration marker only after every record succeeds or
already has a current-state counterpart, then reads and writes only the state-root copy. Publication
never replaces an existing target, including one created concurrently. The marker prevents a later
deletion from being resurrected from the legacy directory. An interrupted or unsupported migration
leaves the marker absent, blocks workspace access while the failure persists, and retries before
later access.
Published directory entries are forced before the marker where the filesystem supports directory
forcing; rejection of that optional durability operation does not prevent the marker. The legacy
directory remains untouched as a rollback copy. Migration runs on the first storage operation, not
while a caller merely asks for the directory path.

New persistence code must satisfy both rules:

1. Its default path is produced by `BossDirectories.resolve`.
2. A test proves `BossDirectories.contains(path)` and rejects caller-controlled escape attempts
   where applicable. This containment check resolves a deliberately symlinked state root.
