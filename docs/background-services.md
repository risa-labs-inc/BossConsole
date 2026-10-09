# Shared BOSS daemon

The host handles `--boss-daemon <profile-root>/daemon` before creating desktop UI.
It supervises plugin-owned `DaemonService` workers through a token-authenticated loopback
control channel. Each plugin/service pair is isolated by a classloader and data directory;
worker and API JARs are copied to immutable content-addressed snapshots. UI unload, window
closure and plugin update do not close those loaders. Disable/removal revoke old UI
providers, drain workers and remove their restart registrations.

The first background connection registers login startup for its BOSS profile. macOS uses
a LaunchAgent, Linux an XDG autostart entry, and Windows the current user's Run key.
BOSS and BOSS Debug use separate directories/registrations. An update refreshes the launch
command without killing running workers. Reboot restarts registered services; restoring
actual jobs is the plugin's responsibility. Live PTYs do not survive an OS reboot.

The menu-bar/tray icon is a B inside a square. Open BOSS first sends the authenticated
single-instance FOCUS verb to an existing application. Only when no usable instance exists
does it launch BOSS's facade. Quit BOSS daemon closes intake and waits for admitted requests
and workers before releasing loaders and the instance lock.

The provider requires the new plugin API member (target API 1.0.99, BOSS 9.5.44; verify
actual releases before consumer publication). For local builds only, pass
`-PbossPluginApiJar=/absolute/path/to/the/development-api.jar` with configuration cache
disabled. Never publish a development API under a released version.

## Validation

Automated tests cover real plugin classloader isolation, immutable snapshots after UI JAR
deletion, startup failure cleanup, coroutine drain, explicit stop, restart registrations,
packaged launcher selection, login argument encoding, and authenticated FOCUS parsing.
Host lint and static analysis must also pass.

The optional `TerminalDaemonIntegrationTest` loads a locally built installable terminal
plugin JAR into the real worker classloader, opens a PTY, detaches/reconnects clients and
then drains the worker on disable. Set `BOSS_TERMINAL_DAEMON_TEST_JAR` to that JAR and run
`:composeApp:desktopTest --rerun --tests 'ai.rever.boss.daemon.TerminalDaemonIntegrationTest'`
with the development API override above. The fixture is skipped when the variable is absent;
`--rerun` prevents a previously skipped result from being reused. This test starts a worker
and loopback transport inside the test JVM, not the BOSS application or a login registration.

Manual packaged-app checks are still required on each platform: open/reopen from the icon
with one existing BOSS process, close every BOSS window and reconnect to a terminal, plugin
reload/update, explicit disable/remove, login after reboot, and profile separation. Do not
run the BOSS application from an agent for these checks; the user tests manually.

## Fluck follow-up

This host supplies process/lifecycle ownership for future plugins. It does not yet move
Fluck's AI task runtime, web server, portal/tunnel, approvals or credentials into the daemon.
Those components must share one worker so fluck.ai remains usable after desktop UI closure.
A host-owned background credential broker is still needed; duplicated rotating refresh
sessions are unsafe. Remote access also needs an awake, online machine and a reachable
transport. The daemon does not bypass macOS sleep or make UI automation headless.
