# AGENTS.md

This file provides guidance to coding agents working with this repository.

## Project Overview

BOSS (Business Operating System Service) is a desktop application built with Kotlin Multiplatform and Compose Multiplatform. It features WebAuthn/passkey authentication, integrated browser (JxBrowser), terminal integration (BossTerm), customizable keyboard shortcuts, and role-based access control.

**Target Platforms**: Desktop only (macOS, Windows, Linux).

## Essential Commands

```bash
./gradlew run                    # Run desktop application
./gradlew showVersion           # Display current version
./gradlew test                  # Run tests
./gradlew build                 # Build application
./gradlew packageDmg            # Build macOS DMG
./gradlew packageMsi            # Build Windows MSI
./gradlew packageDistributionForCurrentOS  # Linux packages
./gradlew incrementVersion      # Increment patch version
```

## Workflow Rules

**IMPORTANT**: Do NOT run `./gradlew run` in a blocking/foreground way just to test - the user runs and tests the app themselves. **Exception:** launching the app **in a dedicated bottom split pane is allowed** (backgrounded so it doesn't wedge the pane).

### Running commands in a visible terminal pane

When a terminal MCP server is available, prefer it over the plain `Bash` tool for commands worth showing - it runs in a visible BossTerm pane and still returns stdout/stderr/exit code. Two servers may be present depending on which app hosts the session; use whichever the session's `SessionStart` hook designates:

- **`mcp__boss__*`** - exposed by the `terminal-tab` plugin inside BossConsole (e.g. `mcp__boss__run_command`, `run_in_sidebar`).
- **`mcp__bossterm__*`** - exposed by the standalone BossTerm app.

For a bottom split use `panel: horizontal_split`. Reuse a pane across calls by passing back its `pane_id`. Keep plain `Bash` for trivial read-only commands where opening a visible pane is churn. (Do not mix the two servers in one session - they target different app instances.)

## Architecture

### Module Structure
- **`composeApp/`** - Main Compose Multiplatform UI application
- **`server/`** - Minimal Ktor server component
- **`modules/`** - Microkernel / out-of-process architecture Gradle modules
  (`boss-ipc`, `boss-service-*`, `boss-orchestrator`, `boss-ui-sdk`,
  `boss-mastery-*`, `boss-app-*`). They keep flat Gradle paths (`:boss-ipc`),
  only the directory lives under `modules/`; excluded on Windows-ARM64 (no
  protoc binary). `boss-ipc`/`boss-ui-sdk` publish as upstream jars consumed by
  the standalone `boss-microkernel-runtime` repo.
- **`plugin-platform/`** - Host-side plugin platform / SDK modules
  (`plugin-loader`, `plugin-repository`, `plugin-api-core`, …). This is the
  infrastructure that loads and runs plugins, **not** the plugins themselves -
  those live in the separate `boss_plugins` repo.
- **`supabase/`** - Database migrations and Edge Functions

### External Dependencies
- **BossEditor** (`com.risaboss:bosseditor-compose-desktop`) - Standalone code editor with LSP and PSI support. **Not a host dependency** - bundled privately inside the `editor-tab` plugin, like BossTerm inside `terminal-tab` (see [docs/BOSSEDITOR.md](docs/BOSSEDITOR.md))

### Key Technologies
- Kotlin Multiplatform + Compose Multiplatform
- JxBrowser (version pinned in `gradle/libs.versions.toml`, with BOSS-branded Chromium)
- Decompose for navigation
- Supabase + Edge Functions
- BossTerm for terminal integration (bundled in the `terminal-tab` plugin)

## Plugin dependencies are resolved at install time

`plugin.json` `dependencies` used to be read in exactly one place -
`DynamicPluginManager.checkCanUnload`, which refuses to uninstall a plugin something else
depends on. Nothing looked at them when a plugin was *installed*, so installing a plugin whose
dependency was absent produced no signal at all and the user met the consequence later, as a
feature that silently did nothing. The AI Gateway made that concrete: three plugins declare it
`optional: true` and each falls back to an unconfigured state.

`PluginDependencyResolution.missingFor(manifest, installedIds)` now answers what is absent, and
`MissingDependencyDialog` offers to install it. Four things about the placement:

- **The hook is in `PluginLoaderDelegateImpl.loadPlugin`, not `installPlugin`.** That manager
  method also serves startup restore, the bundled-plugin load and the api hot-swap's
  reload-all, so a prompt there would be one dialog per plugin on every launch. The delegate is
  the path plugin-manager install and update flows take, which is the only place a *user* asked
  for something.
- **Optional dependencies are reported, flagged, not dropped.** An optional dependency is how a
  plugin says "this feature needs that plugin". Dropping them would leave this reporting
  nothing for the case it was built for.
- **The event bus is a `Channel`, not a `SharedFlow`.** A broadcast would put the same dialog in
  front of every open window and let each of them start the same install. The collector applies
  back-pressure (`snapshotFlow { … }.first { it == null }`) so a second missing dependency is
  asked about after the first rather than replacing it, and re-checks `isInstalled` before
  showing - two dependents of one missing plugin each raise a prompt, so installing for the
  first satisfies the second.
- **Installing is the host's to do.** `PluginRepository.getPlugin(id)` plus `downloadPlugin`
  resolve an id to a jar, which no plugin can do - a plugin holding a null API can only send
  the user to the Toolbox to search by name.

Four things `StoreMissingDependencyInstaller` gets right that are easy to get wrong, all found
in review:

- **A failed download deletes its jar.** `RemotePluginRepository.downloadPlugin` writes straight
  into the target path and only deletes on a hash or signature rejection, so a connection that
  dies mid-stream leaves a truncated jar at the *final* filename - which `PluginJarReconciler`
  then finds and retries on every launch.
- **Cleanup removes the `.sig` sidecar with the jar.** Reinstalling the same version reuses the
  filename, so a surviving sidecar meets fresh bytes and hard-fails the load, which is worse
  than being unsigned.
- **It downloads straight to the final name**, not through a `-downloading.jar` rename. The
  sidecar is written next to whatever path `downloadPlugin` was given, so a rename afterwards
  orphans it. (`PluginInstallService` does rename, and does leave the sidecar behind.)
- **It writes the `installed.json` entry.** `setPluginEnabled` updates an existing entry and
  does nothing when there is none, so a plugin known only by its presence on disk cannot be
  disabled persistently.

Installs are detached and coalesced per plugin id (`KeyedDetachedJobs`, as reloads are): the
prompt is driven from a window's scope, so closing that window mid-download would otherwise
abort the install and leave the partial jar.

Reporting is gated to the install entry point. `doReloadPlugin` finishes by calling
`loadPlugin` too, and reload is reached by `resetPluginInstances`, the Toolbox's update flow and
the evolver's hot reload - none of which is a user asking to install anything, and re-offering a
dependency someone declined on every reload would be worse than silence.

Deliberately out of scope, so nobody assumes more than exists:

- **Transitive dependencies are not chased.** The dependency loads through the manager directly,
  so answering one question never produces a second dialog.
- **`PluginDependency.version` is ignored.** Presence is by id, matching `checkCanUnload`. A
  plugin needing 2.x is satisfied by 1.x, and a prompt could not usefully fix a wrong-version
  install anyway.
- **`NOT_USER_INSTALLABLE` ids are never offered**: the microkernel runtime (which
  `loadPlugin` refuses outright and `DefaultPlugin` skips on scan, so it looks missing to every
  manifest naming it) and the api plugin (whose install is an unload-all / swap / reload-all hot
  swap, not something to start from a dialog about something else).
- **With two windows open, the window that asks may not be the one that reported.** The install
  is still correct; the answering window may just not show the change until relaunch. See
  `MissingDependencyPrompt`.
- **The dependent is not reloaded after its dependency installs.** It has already loaded and
  already resolved its handle to the dependency, typically to null. This is survivable because
  the consumers resolve the API *lazily, per call* - which their own AGENTS.md files require,
  precisely because plugin load order is not guaranteed - so they pick it up without a reload. A
  consumer that cached the handle at `register()` would stay broken until relaunch, and would be
  wrong for the same reason on a normal cold start.

**Both halves must share one definition of "installed".** The reporter filters the manifest's
dependencies against what is present; the installer's Install guard asks the same question. When
those disagreed - reporter on the raw `pluginStates` keys, installer on keys-plus-jar - a failed
install left a dangling entry that made every *later* dependent of that plugin report nothing at
all, silently re-creating the problem this feature exists to remove. There is now one
`installedAndOnDisk` predicate in `PluginLoaderDelegateImpl`, passed to both.

One trap worth knowing: `isInstalled` has to mean *usable*, not "the manager has an entry".
`installPlugin` registers a DISABLED entry for a binary-incompatible plugin, and this installer
deletes the jar it rejected - so an entry-only check made Retry close the dialog reporting
success with nothing installed, and silenced the prompt for every other dependent of that
plugin. The check is an entry whose `jarPath` still exists.

## Configuration

Create `local.properties`:
```properties
jxbrowser.license.key=<your-license-key>
SUPABASE_URL=https://api.risaboss.com
SUPABASE_ANON_KEY=<anon-key>
SUPABASE_FUNCTION_URL=https://api.risaboss.com/functions/v1
GITHUB_TOKEN=ghp_your_token_here  # Optional, 60 req/hr without
MACOS_DEVELOPER_ID=Developer ID Application: ...  # Optional, signs local packaging
```

**Priority**: Environment variables > System properties > local.properties > Embedded build config

### Credential brokers

A provider whose credential nobody types in: the user is signed in to BOSS and an
organisation gateway mints a short-lived scoped key for that identity. RISA Codex GLM works
this way.

The exchange is **host-side and stays that way**. Nothing on `PluginContext` exposes the
Supabase access token - `AuthDataProvider` gives identity only, `SupabaseDataProvider` proxies
queries with the host attaching auth - so `CredentialBrokerClient` hands a plugin the
*downstream* credential and never the session that bought it.

**A broker is named by id, never by URL.** `CredentialBrokers` owns the id to endpoint map. An
`exchange(url)` shape would have handed every installed plugin a way to post the user's session
token to a host of its choosing; as it is, the worst a plugin can do is name a broker this
build does not have. `CredentialBrokersTest` pins that, plus that every broker declares an
https endpoint and a `scopedTo` prefix (published so a careful plugin can check where it is
about to send a bearer token).

`RisaLlmTokenCommand` is a thin adapter over the same registry, so the `BOSS llm-token` helper
Codex invokes and the plugin-facing `BrokeredCredentialProvider` cannot drift apart or hold two
copies of the endpoint.

Adding one means an entry in `CredentialBrokers.all()`. The plugin-facing side needs no change.

**The three-wrapper rule applies.** `brokeredCredentialProvider` is overridden in
`DefaultPlugin`, `TrackingPluginContext` **and**
`plugin-platform/plugin-sandbox/.../SandboxedPluginContext`. Missing the sandbox one returns
null for every plugin, silently - that has happened before with `mcpToolRegistry`. The
implementation lives in `desktopMain` (it speaks HTTP), so `DefaultPlugin` reads it through
`BrokeredCredentialAccess`, a commonMain holder that `main.kt` populates at startup.

### AI credentials are not configured here

`OPENAI_API_KEY` used to be listed above as a `local.properties` key that enabled AI
self-healing. Nothing has ever read it from that file - it is an **environment variable**, and
the priority order above does not apply to it.

- **AI providers** (chat, agents, plugin AI features) are owned entirely by the
  **secret-manager** plugin: `Settings → AI Providers`. The host has no provider list; it
  relays the plugin's through `PluginContext.llmProvider`. See that plugin's `AGENTS.md`.
- **AI self-healing / repair** is the one credential the host still resolves itself, because
  `SelfHealingSettingsManager` runs before any window or plugin exists and so cannot reach the
  plugin's store. It reads `AI_REPAIR_API_KEY`, then the provider's own variable
  (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / …), then the legacy `~/.boss/llm_settings.json` and
  its `.migrated` sibling - all as **env vars / files, never local.properties**. A key rotated
  in Settings → AI Providers does not reach it.

There are NO credential fallbacks in source (public repo). Packaged builds get
the JxBrowser license and Supabase settings baked in by the
`generateEmbeddedConfig` Gradle task, which reads env vars (CI secrets) or
local.properties at build time and emits a git-ignored classpath resource.

### Supabase Deployment
```bash
supabase functions deploy <function-name> --project-ref pcnwqamqdnsadranufjv --no-verify-jwt
supabase link --project-ref pcnwqamqdnsadranufjv  # First time
```

### Decoding Supabase payloads

Two rules in `ai.rever.boss.services.supabase`, both enforced by `SupabaseWiringTest`:

**Decode through `supabaseJson`, never the `Json` default.** The database is migrated
ahead of installed builds, so a client will meet columns it does not model. Strict
decoding treats those as a hard error, and since these RPCs return lists, one new
column throws and takes the whole page with it. That is not theoretical - the
organisation migration extended four secret RPCs and emptied the secret panels on every
installed build at once. Note the wildcard `kotlinx.serialization.json.*` import in these
files keeps `Json.Default` in scope, so the broken thing is what you get by not thinking
about it.

Leniency covers extra keys and nothing else. A null in a non-nullable slot still throws
for the whole list, so **declare every projected column `T? = null`** except the key. Unlike
the other two rules here, this one is **convention, upheld by review** - no test enforces it,
and the existing models do not all follow it yet (they are safe only because the columns
behind them are `NOT NULL` today).

**Log `sanitizeSupabaseFailure(op, e)`, never the raw exception.** kotlinx appends the
whole offending document to a malformed-input error, and these bodies carry passwords the
server has already decrypted, recovery codes, and JWT claim sets. The request direction
counts too: `SupabaseDataProviderImpl.rpc` parses caller-supplied parameters, and a plugin
calling `create_secret` puts the new password in them.

## Code Quality

- Use Compose Multiplatform Resource API (not Android resources)
- Location: `composeApp/src/commonMain/composeResources/`
- Use `BossLogger` for logging (not `println()` or `printStackTrace()`)
- All Kotlin files must end with newline
- Formatting is gated by ktlint (`./gradlew ktlintCheck`; fix with
  `./gradlew ktlintFormat`). Static analysis is gated by detekt with
  per-module baselines.
- **Blame**: the tree-wide ktlint reformat is listed in
  `.git-blame-ignore-revs`; run
  `git config blame.ignoreRevsFile .git-blame-ignore-revs` once per clone so
  `git blame` skips it.

### Prose style: no em-dashes

Write a spaced hyphen (` - `), never an em-dash (U+2014), in anything a person
reads: docs, READMEs, release notes, commit messages, PR descriptions, UI labels
and log lines. Existing code **comments** are out of scope and were deliberately
left alone, so do not sweep them.

(This paragraph names the character by codepoint rather than printing it, because
the guard below would otherwise flag the rule that defines it.)

What is actually gated, so nobody assumes more than exists:

| Surface | Enforcement |
|---|---|
| `*.md`, `*.html` | `build.yml` fails a PR that **adds** one (added lines only, so legacy prose never blocks the build) |
| the generated release-notes table | `test-release-download-table.sh` asserts its output has none, both the stable and pre-release copy |
| string literals in code | **convention, upheld by review.** A diff cannot tell a comment from a literal, so `*.kt` is out of the guard |
| code comments | out of scope entirely |

The guard matches U+2014 only. En-dashes stay: they are used deliberately for
ranges and arrows.

The rule exists because generated text is the main source. `release-notes.yml`
has Claude write every release's notes, and 343 of this repo's 549 doc em-dashes
came from `docs/release-notes/`.

## Logging

Use structured logging via `BossLogger` (SLF4J backend):

```kotlin
private val logger = BossLogger.forComponent("MyComponent")

logger.info(LogCategory.AUTH, "User signed in", mapOf("email" to LogSanitizer.maskEmail(email)))
logger.error(LogCategory.NETWORK, "Request failed", error = exception)
```

**Categories**: AUTH, PASSKEY, BROWSER, TERMINAL, NETWORK, UI, SYSTEM, EDITOR, FILE, WORKSPACE, GENERAL

**Security**: Always use `LogSanitizer` for sensitive data:
- `maskEmail()`, `maskToken()`, `maskCredentialId()`, `maskUserId()`, `maskUriParams()`

**Config**: Set `BOSS_LOG_LEVEL` env var or `boss.log.level` system property (TRACE/DEBUG/INFO/WARN/ERROR)

**Three modules apply the Compose compiler with no Compose code, on purpose** -
`plugin-logging`, `plugin-bookmark-types` and `plugin-workspace-types`.
`boss-plugin-api` ships this same `ai.rever.boss.plugin.logging` package and *is* a Compose
project, so its `ComponentLogger` carries the synthetic `$stable` field. This module's copy
shadows it parent-first inside plugin classloaders, so a plugin that merely holds a
`ComponentLogger` **property** emits `getstatic ComponentLogger.$stable` - which links against
the api jar at build time and is missing at runtime. `BinaryCompatibilityValidator` then rejects
the *entire* plugin and the host disables it as binary incompatible. That made secret-manager
1.2.6 and 1.2.7 unloadable on every host. `$stable` was verified (javap, member by member) to be
the only public difference between the two copies, so emitting it here makes them interchangeable
and repairs already-built plugins with no api release.

Scope: diffing every api package the host also bundles found the field missing from **15 classes
across those three modules** - `ComponentLogger`/`BossLogger`/`LogEntry`/`BossLoggerConfig`/
`LogSanitizer`, `Bookmark`/`BookmarkCollection`/`FavoriteWorkspace`/`WorkspacePanelTarget`, and
`LayoutWorkspace`/`TabConfig`/`PanelConfig`/`SplitConfig`/`BreadcrumbConfig`/`WorkspaceSerializer`.
Only `ComponentLogger` had actually bitten us; the data types are more exposed, since plugins hold
them as properties routinely.

`plugin-logging` gets the Compose runtime as **`compileOnly`** (the compiler needs it, the
generated field does not, so the published POM stays clean). The other two already had it as
`implementation` for `@Immutable`, so only the compiler plugin was added there.

`LoggingStableFieldTest`, `BookmarkStableFieldTest` and `WorkspaceStableFieldTest` pin this, and
each module's publish task depends on its own `desktopTest` - co-location alone does *not* put a
test on the publish path. Do not delete them to make a build pass.

The guard is **not** universal: `release.yml` runs only `createDistributable`/`packageDmg`/
`packageMsi`/`packageDeb`, so app **packaging** never runs these tests. Merges are gated (PR CI
runs `./gradlew build` → `check` → `allTests`; note a bare `./gradlew test` does *not* cover them,
because a `jvm("desktop")` target registers `desktopTest`, not `test`), and the Maven publish path
is now gated - packaging relies on those.

**Publish `plugin-bookmark-types` and `plugin-workspace-types` together.** bookmark-types has
`implementation(projects.pluginPlatform.pluginWorkspaceTypes)`, so its POM pins the sibling at the
current project version. `publish-maven-central.yml` takes a free-form `packages` input, and
dispatching bookmark-types alone would ship a POM requiring a `plugin-workspace-types` version that
does not exist on Central. `all` is safe - workspace-types publishes first. BossConsole#81 tracks the
durable guard: diffing public members against the api jar `plugin-api-core` already downloads,
covering all eight duplicated packages rather than this one field.

## Build and Deployment

**GitHub Actions**: `build.yml` (multi-platform tests), `release.yml` (signed builds)

**Secrets Required**: `JXBROWSER_LICENSE_KEY`, `SUPABASE_ANON_KEY`, code signing certs

## Development Notes

**Current Focus**: RBAC, LSP integration, performance monitoring, cross-device auth

**Known Issues**:
- Issue #33: Remove hardcoded credential fallbacks after testing
- Issue #34: Use JxBrowser for login instead of system browser

## Key Files

**Client**: `AuthService.kt`, `SessionManager.kt`, `DesktopPasskeyService.kt`, `SupabaseConfig.kt`

**Server**: `supabase/functions/passkey/services/auth.ts`, `utils/jwt.ts`, `utils/crypto.ts`

**Config**: `version.properties`, `build.gradle.kts`

## Deep Links

App registers `boss://` protocol for authentication callbacks from external browsers.

Because the scheme is registered with the OS, a `boss://` link is not evidence
that the operator asked for anything - any program that can ask the OS to open a
URL produces the same input. Entry points therefore tag each link with a
`DeepLinkOrigin`:

- `OPERATOR_CLI` - BOSS's own CLI parsed it out of this process's `argv`
  (`createBossCLI`), i.e. arguments passed to the BOSS executable directly.
- `EXTERNAL` - the OS URL-open handler, a `boss://` argument from the registered
  protocol handler, or a forward over the single-instance channel that said so.
  Also the default for an unstated origin, so a new caller that forgets to say
  gets the cautious handling.

Only `boss://terminal?command=` consults it today: an `OPERATOR_CLI` command runs
as before, anything else is shown to the operator for confirmation first (the
`boss` shell shim converts to a `boss://` URL and opens it via the OS, so its
`terminal -c` still works, with one confirmation). Other hosts - including
`boss://plugin?id=…&action=…` - are unchanged.

**Single-instance channel**: `SingleInstanceManager` publishes
`~/.boss/run/single-instance` (owner-only) with the channel endpoint and a token
minted at startup, and listens on a Unix-domain socket in that directory (macOS,
Linux) or a loopback port (Windows). Every request must present the token,
"another instance is running" means something answered on the channel rather than
a pid existing, and a descriptor nobody answers on is reclaimed.

## Documentation

- [Core Subsystems](docs/SUBSYSTEMS.md) - Auth, UI, keyboard shortcuts, threading, default browser, runner, BossTerm
- [BossEditor](docs/BOSSEDITOR.md) - External editor dependency, LSP, PSI, editor features
- [Application Features](docs/FEATURES.md) - Performance monitoring, dashboard, downloads, Chromium branding
- [Keyboard Shortcuts](docs/KEYBOARD_SHORTCUTS.md) - Detailed shortcuts reference
- [Threading Best Practices](docs/THREADING.md) - Threading patterns and pitfalls
- [RBAC Guide](docs/RBAC_GUIDE.md) - Role-based access control
- [Role Creation](docs/ROLE_CREATION_GUIDE.md) - Creating and managing roles
- [Windows Deep Link](docs/WINDOWS_DEEP_LINK_SETUP.md) - Windows protocol handler setup
- [Release Rebuild](docs/RELEASE_REBUILD_GUIDE.md) - Re-running release builds

