# Filesystem service authorization

Every RPC in `FileSystemServiceImpl` must call `IpcCall.requireHost()` before
logging paths, validating paths or performing I/O. A refused caller must not gain
path-existence information or put its supplied path into a log or exception.
`FileSystemServiceAuthorizationTest` pins the descriptor method set and exercises
both non-host roles over authenticated TLS; keep that coverage when adding RPCs.

Watch collection checks authority before registration and again before emission.
Idle credential revocation is implemented by the shared IPC interceptor, which
closes the call and cancels its coroutine; the filesystem test verifies this through
a quiet live watch. This PR depends on the authenticated transport from #575.

`FileSystemDataProviderProxy` currently has no production construction site. A
plugin PROCESS credential is deliberately refused by this host-only service.
Wiring that proxy for plugins requires a separately reviewed path-grant design;
do not remove the host guard just to make a plugin call succeed.

Run focused validation with the workspace shared build lock:
`./gradlew :boss-service-filesystem:test :boss-service-filesystem:ktlintCheck :boss-service-filesystem:detekt`.
Do not launch the application for these tests.

System-path validation resolves existing ancestors before read/create/write or watch access,
including missing destinations below symlinked directories. Rename/delete validate the
parent but operate on the final directory entry without following its symlink; recursive
delete must not traverse symlinked directories. Invalid paths return INVALID_ARGUMENT.
This host-only guard is not a sandbox against concurrent filesystem mutation.
