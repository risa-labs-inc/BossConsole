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

**Both readers now agree on what `optional` means.** `checkCanUnload` counted *any* declaration,
so the same `optional: true` that the install prompt words as "works without it" was a hard veto
at unload time - and because the Toolbox updates a plugin by uninstalling and reinstalling it,
the veto landed on the Update button. The AI Gateway could not be updated or removed while
jupyter-notebook, flow-tab or llmrpa was loaded, which is all three of its consumers. The
predicate is `PluginDependencyResolution.blockingDependentsOf`, next to `missingFor` so the two
cannot drift again; it also drops a manifest that names itself, which would otherwise make that
plugin permanently unremovable.

**A disabled dependent does not veto either.** `disablePlugin` unregisters the tracking context
and flips the state to `DISABLED` but never calls `pluginLoader.unloadPlugin`, so a disabled
plugin is still in `getLoadedPlugins()` and would refuse on behalf of something that is not
running. `blockingDependentsOf` therefore takes an `isDisabled` predicate with **no default**, so
a new call site has to answer; it **fails closed**, since `PluginState` has more members than
LOADED and DISABLED and treating "not exactly LOADED" as "not running" would drop real dependents.

Refusals must stay visible. `PluginLoaderDelegateImpl.unloadPlugin` returns a bare `Boolean`, and
`uninstallPlugin` logs `Uninstalling plugin` *before* it decides - so a refusal used to leave the
log stopping mid-sequence with the manager's reasons dropped at the delegate boundary. It now logs
`Plugin unload refused` with them, read off `PluginUnloadException.reasons` rather than the joined
message. Refusal and failure log **separately**: `uninstallPlugin` returns `Result.failure` for
both, and they send a reader to opposite places - a refusal means another plugin is in the way,
while "Plugin not found" or a `pluginLoader.unloadPlugin` error is a fault that wants a stack
trace. The plugin side cannot receive those reasons through a `Boolean`, so the Toolbox's message
points at the host log rather than restating the failure.

**Known, and deliberate: the veto does not distinguish Update from Remove.** The host's own update
paths pass `force = true` (`PluginUpdateBridge`, `PluginStoreVersionBridge`), so they never meet
it. The one path that does is the plugin-facing `PluginLoaderDelegateImpl.unloadPlugin`, which
cannot tell which button was pressed - so a plugin with a *required* loaded dependent can still not
be updated from the Toolbox, even though an update ends with it present again at a newer version.
Refusing the removal is right; refusing the update is much harder to justify, and closing it needs
an update-shaped verb (or an intent parameter) on the api rather than a change to this predicate.

`PluginDependencyResolution.missingFor(manifest, installedIds)` now answers what is absent, and
`MissingDependencyDialog` offers to install it. Four things about the placement:

- **The wizard reports once after its whole batch, never per iteration.** A first-run selection
  of `[jupyter-notebook, ai-gateway]` - the pick this feature exists for - otherwise prompted for
  the gateway while the loop was two iterations from installing it, and taking Install raced the
  wizard's own download (the two paths write different filenames for one plugin id, so neither
  the path nor the coalescing key collides). After the loop, `installedAndOnDisk` already holds
  everything the batch installed, so nothing intra-batch is reported at all.
- **`MissingDependencyReporter` is called from the three paths that install for a user**, and
  never from `DynamicPluginManager.installPlugin`. That manager method also serves startup
  restore, the bundled-plugin load and the api hot-swap's reload-all, so reporting there would be
  one dialog per plugin on every launch. The three are `PluginLoaderDelegateImpl.loadPlugin`
  (what plugin-manager's install and update flows reach), `PluginInstallService` (the first-run
  wizard, where several plugins are chosen at once and so the likeliest place for an unmet
  dependency) and `PluginUpdateBridge` (an update can add a dependency the installed version
  never declared). A **reload** must not report: `resetPluginInstances`, the Toolbox reload and
  the evolver's hot reload all end in a load, and none is a user asking for anything.
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

**The downloaded jar is vetted before it is loaded.** Nothing binds a store row to the plugin id
its jar declares: `enforceStoreSignature` binds the hash to the row and the row to the store's
key, not the identity, so an admin uploading the wrong jar is enough. Loading first and checking
after is too late twice over - `installPlugin` inspects the *incoming* manifest and starts a full
api hot swap for a newer `ai.rever.boss.plugin.api` jar, which is exactly what
`NOT_USER_INSTALLABLE` exists to keep out of a two-button dialog, and a jar declaring some other
installed plugin would be registered against a path that is about to be deleted. So the manifest
is read first and a mismatch is refused; the post-load check stays as belt and braces. The
recorded version comes from that manifest too, since update checking compares against it.

Five things `StoreMissingDependencyInstaller` gets right that are easy to get wrong, all found
in review:

- **The download goes to a `<name>.jar.part` sibling and is moved into place, with its
  sidecar.** `downloadPlugin` streams into whatever path it is given and `outputStream()`
  truncates on open, so downloading onto the final name destroys any jar already there the
  instant the connection opens - and a stream that then dies leaves a truncated file at a name
  every later launch tries to load. A "was a file already here" guard does not help: by then its
  contents are gone either way. The suffix deliberately does not end in `.jar`, so a part file
  left by a kill is ignored by the directory scan. Both files move together, because the
  signature is written next to the path `downloadPlugin` was given.
- **Cleanup removes the `.sig` sidecar with the jar.** Reinstalling the same version reuses the
  filename, so a surviving sidecar meets fresh bytes and hard-fails the load, which is worse
  than being unsigned.
- **It writes the `installed.json` entry.** `setPluginEnabled` updates an existing entry and
  does nothing when there is none, so a plugin known only by its presence on disk cannot be
  disabled persistently.
- **Nothing is reported for a plugin that did not actually register.** `installPlugin` returns
  success with `state = DISABLED` when registration failed as binary-incompatible or the plugin
  is hidden for lack of access; reporting then offers to install a second plugin to support a
  dead one. All **three** report paths check `state == LOADED`, not just the delegate.
- **A promotion that half-succeeds cleans up both paths.** If the jar moves and the sidecar step
  then throws, deleting only the part file would leave an unvetted, never-loaded jar at a
  scannable name for the next launch to load.

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
- **The bus filters at report time, not only in the collector.** A prompt the collector is
  certain to discard - declined, or a duplicate of one already waiting - still costs one of four
  buffer slots on the way through, and that can be what refuses a different dependency which
  could have been shown.
- **A declined prompt is remembered for the session, not persisted, and keyed by kind.** "Not
  now" on an *optional* dependency is one answer about that plugin - three consumers declare the
  gateway optional, and being asked three times for one answer is what this prevents. "Skip" on a
  *required* one is keyed by `(dependent, missing)`, because another plugin that hard-requires the
  same thing is a different question and silencing it would be worse, not better. Neither
  persists: an answer that outlived the session would leave no way to be asked again short of
  editing a file.
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

**"Installed" is `state == LOADED || (the jar exists && not recorded incompatible)`.** Both halves are load-bearing and each
was a bug on its own. Requiring only an entry meant a binary-incompatible load - which registers
a DISABLED entry while the installer deletes the jar it rejected - looked installed, so Retry
reported success with nothing installed and every later dependent went silent. Requiring only the
jar meant a *running* plugin whose file had moved looked absent: `PluginJarReconciler` and the
updater both rewrite paths without repointing the manager's in-memory `jarPath`, so the prompt
would fire for something already loaded and Install would fail with "Plugin already loaded".
The incompatibility clause exists because there are **two** binary-incompatibility paths in
`installPlugin` and only one of them fails: the load-time one returns `Result.failure`, but the
*registration-time* one force-unloads the plugin and returns `Result.success` with `state =
DISABLED` and the jar still on disk. Without it, that jar made a plugin which was unloaded and
will not run count as installed - so Install reported success and wrote an `installed.json` entry
for it, and every other dependent of it went unprompted.

One trap worth knowing: `isInstalled` has to mean *usable*, not "the manager has an entry".
`installPlugin` registers a DISABLED entry for a binary-incompatible plugin, and this installer
deletes the jar it rejected - so an entry-only check made Retry close the dialog reporting
success with nothing installed, and silenced the prompt for every other dependent of that
plugin. The check is an entry whose `jarPath` still exists.

### A settings section can offer to install the plugin that serves it

Two sections render a panel that belongs to a plugin - `Editor` and `Language servers`, both
editor-tab. (`Settings > AI Providers` was a third, served by secret-manager, until that section
moved into the plugin's own panel; Settings search reaches it through a **panel signpost** now, and
that entry is filtered on the panel being registered rather than explaining its absence.) Both used
to say "isn't loaded yet" for every reason there was no panel, which is true of exactly one of
them. A plugin that was never
installed, or that the user switched off, does not arrive however long they look at it.

`PluginSettingsUnavailableNotice` now tells the four apart and offers an Install button for the one
case where that is the answer. It leans on `MissingPluginOffer`, so no second install path exists:
the press raises the host's own `MissingDependencyDialog`, which names the plugin **from the store**
and shows the id it will install by, rather than installing on the button's say-so.

**`PluginState.DISABLED` is not one state, and that is why the decision is a pure function.**
`DynamicPluginManager` writes it for at least three unrelated reasons - `disablePlugin`, a
registration that failed as binary incompatible (`:1118`), and a plugin hidden because the user
lacks access (`:1120`, `:2293`) - and "enable it in the Toolbox" is true for only the first. So
`pluginSectionAbsence` takes the crash registry's `isIncompatible` and the manager's
`getInaccessiblePlugins()` alongside the state, and its **order is load-bearing twice**:

- permissions are asked **first**, because an inaccessible plugin is recorded DISABLED too, and
  telling that user to switch it on points them at a row that is not listed for them;
- `isIncompatible` and DISABLED are asked **before** `installed`, because
  `MissingPluginOffer.isInstalled` counts both as installed (the jar is on disk, which
  `MissingDependencyInstaller` documents deliberately) - so the other order offers an Install button
  to someone whose problem a download cannot fix. That exact bug has shipped here once already with
  the bookmarks shelf.

All three orderings are mutation-verified: reversing each one fails a named test.

**The section supplies exactly one fact the manager cannot answer**, `servesNoPanel` - the API is
registered but this version has no panel for that section. Without it a loaded plugin reported
"isn't loaded yet" forever. Everything else, permissions included, is derived from the plugin id, so
a new section gets the whole behaviour by naming its plugin. An earlier version took the permissions
as a parameter and only one of the then-three sections passed it, which left the other two telling a
user who cannot access the plugin to go and switch it on.

**The notice gates on `MissingPluginOffer.isInstalled`, not on "can I reach the API".** Those are
different questions and they disagree exactly when the plugin is installed but not running - which
is the state where a button that appears does nothing. Same rule the tab bar's bookmarks shelf
states.

`null` from `isInstalled` means the question cannot be answered here (no active manager, no injected
installer factory), and it must not become an offer: the section falls back to the neutral wait.

**Known: the consent dialog opens over the main window, not the Settings window.** `SettingsWindow`
is composed inside the main window's subtree and opts *its own* dialogs out of heavyweight overlay
routing precisely so they do not open centred on the main window - but the dependency dialog is
raised through `PluginDependencyEventBus` and composed by `BossAppDialogs`, outside that opt-out. It
is always-on-top so it is not lost, just not where the press happened. Routing it would mean the
prompt carrying a window id, which is the same change `MissingDependencyPrompt` already records as
not built for the two-window case.

**A raised offer is not a shown dialog.** `PluginDependencyEventBus.report` drops silently when a
prompt for that plugin is already queued, so `offerIfMissing` returning true is not proof anything
appeared. The press therefore logs whatever happens, matching the bookmarks shelf's call site.

## Retiring a plugin into another one

`RetiredPlugins.sweep()` uninstalls a plugin whose job another plugin has taken over. It runs at
step 3c of `PluginStoreSetup.loadPersistedPlugins` - after `PluginJarReconciler`, so
`installed.json`'s `jarPath` is trustworthy, and before step 4 reads it, so a retired plugin never
registers a panel or an MCP tool on a machine where its replacement is present. It is `internal`
and **startup-only**: `PluginArtifactCleanup.remove` deletes the jar without unloading anything,
which is safe only before any plugin has loaded (`PluginRemoval` spells out the alternative -
`NoClassDefFoundError` from code that is still running).

There is no "already done" flag. The sweep keys on the plugin being installed, and removing the
`installed.json` row is what makes the next launch a no-op.

**It fails closed at every step, and each step is a bug someone would otherwise ship.** A wrong
"yes" deletes a panel the user still needs, so all of these mean "not yet, wait for a launch that
can prove it":

| Refusal | Why |
|---|---|
| replacement not installed | the user would have no panel at all for what the retired one did |
| replacement disabled | `setPluginEnabled` and a for-lack-of-access install both leave the row and the jar in place |
| replacement's jar missing | `installPlugin` records a DISABLED entry for a plugin it then rejected and deleted |
| replacement's version unparseable or absent | see below |
| replacement older than `minReplacementVersion` | that version has not absorbed the retired plugin's work yet |
| the retired plugin would be restored anyway | a bundled or system plugin is re-copied at step 1 or re-downloaded at step 2, so removing it is a copy-then-delete loop on every launch, notice included |
| its jars could not actually be removed | see below - the row must not outlive the file |

**The row is dropped only once the jars are gone, and that ordering is the whole guard.**
`PluginArtifactCleanup.remove` drops the `installed.json` row unconditionally and only *logs*
whether the delete worked, which is right for the interactive uninstall (the Toolbox already
deleted the file) and wrong here. `DefaultPlugin.loadExternalPlugins` scans the same plugin
directory after the persisted pass, **in the same launch**, and installs every jar the manager
does not know about. So a retirement that drops the row while a jar survives does not leak a
file - it reinstalls the plugin in the session the user was just told it left, `PluginBuildProbe`
writes a fresh row on load, and every launch after that sweeps and announces again. The
copy-then-delete loop the bundled/system veto prevents, by another door.

`purgeJarsFor` therefore deletes **by manifest id, not by the recorded path**: `jarPath` can be
stale while a differently named jar for the same plugin is still in the directory, and artifact
prefixes are prefixes of each other (`boss-plugin-terminal` matches
`boss-plugin-terminal-tab-*.jar`). It re-lists rather than trusting the delete results, because a
Windows lock makes `delete()` return false silently and an already-absent file returns false too -
absence is the postcondition, not the delete count.

**`satisfiesVersionFloor` cannot be used alone here.** It returns `true` for anything
`SemanticVersion.parse` rejects - `dev`, `v1.2.17`, `1.2.x`, a trailing `-` or `+` - by design,
because for gating an *update* an ungated update beats a wrongly gated one. The consequence here
runs the other way, and a locally built or side-loaded jar whose manifest version is not strict
semver is exactly the case that would delete the user's only secrets panel. So the version is
parsed explicitly first; `plugin-dependency` is on composeApp's classpath for that one call
(`plugin-updater` has it as `implementation`, so it is not transitive).

**The restore veto is asked at the call site, not inside `RetiredPlugins`**, which keeps that
object free of both the manager and the system-plugin service. It combines
`PluginRemoval.removalVeto` (the bundled dir) with `PluginStoreSetup.systemPlugins` (the remote
`system_plugins` table merged over the built-in fallback). `ALL` is a list someone will append to
without reading the change that added it, so this is a runtime guard rather than a comment.

**Retirement is also a filter on what is *offered*, not only on what is installed.** Unlisting the
store row is a manual database action outside this repo and outside CI, and until it happens the
store keeps returning the plugin - so a user can install it, have the sweep remove it at the next
launch, and install it again indefinitely. `RetiredPluginIds` (commonMain, because the wizard is
desktopMain and the home tool grid is commonMain) is filtered wherever
`PluginDependencyResolution.NOT_USER_INSTALLABLE` is, and `RetiredPluginsTest` pins it against
`RetiredPlugins.ALL` so the two lists cannot drift.

**One notice per sweep, not per retirement.** `StatusMessageManager.showMessage` cancels the
previous message, so announcing in the loop would show only the last removal - and the sweep is
one-shot, so a missed notice means a panel vanished with no explanation ever. Each retirement's
removal is also wrapped individually, so one failure cannot drop the rest or lose the ids already
removed. `noticeFor` **groups by replacement**: the function only exists for the multi-removal
case, which is exactly the case where two retirements can point at different replacements, and
taking `first().replacementDisplayName` told the user their panel moved somewhere it did not.

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

**The gateway itself lives in `risa-labs-inc/risa-llm-gateway`** (private), not here. It was added
to this repo in PR #136 alongside the desktop client and moved out with its history: it ships in no
BOSS release, deploys on its own Cloud Build, and this repo is public, which made every infra and
authorization change a public one. What stays here is the app's half of the contract -
`RisaLlmTokenCommand`, `CredentialBrokers`, and the `LLM_TOKEN` verb on the single-instance channel. What the two sides owe each other is written down in `docs/llm-gateway-contract.md`, because nothing links the repositories at compile time or in CI any more.

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
  **secret-manager** plugin, in the **AI section of its own panel**. The host has no provider
  list and no settings section for one; it relays the plugin's through
  `PluginContext.llmProvider`. See that plugin's `AGENTS.md`.

  The host used to render `Settings > AI Providers` from that plugin, through a
  `LlmProviderAPIAccess` singleton. Both are gone: the credentials live in that panel's vault,
  so the page that manages them belongs beside them rather than two clicks away in another
  window, and the singleton existed only to give host composables a plugin handle. What remains
  is `DefaultPlugin.llmProvider`, which resolves against **its own** instance's registry -
  deliberately never through a singleton, because `DefaultPlugin` is per window and a shared
  cached reference would hand window 1's plugins whatever window 2 registered.

  A stale `boss://settings?section=LLM_PROVIDERS` deep link now resolves to
  `SettingsDeepLink.Unresolved`, so the window opens on its default section rather than
  failing.

  Settings search still answers for `api key`, `anthropic`, `claude` and the rest: a curated
  **panel signpost** (`panelSignpost` in `SettingsSearchEntries.kt`) opens the Secret Manager
  panel and raises the main window. It is the only search entry that navigates out of the
  Settings window. The delegated-section keywords could not have covered this - a panel is not a
  settings page, so nothing merges it into the index at query time.

  **There is deliberately no version floor on secret-manager.** The AI section exists in that
  plugin only from 1.2.19, and nothing in the host gates on it: secret-manager is not in the
  `system_plugins` manifest, so no `min_version` applies, and plugin updates surface in the
  Toolbox rather than installing themselves. A user on 1.2.18 who takes this host build gets the
  Secret Manager panel with no AI section in it. That is accepted rather than overlooked, and it
  is a weaker case than `RetiredPlugins.minReplacementVersion`, which names a release because
  getting it wrong **deletes** the user's only secrets panel. Here nothing is deleted and nothing
  is lost - the credentials stay in the vault, `PluginContext.llmProvider` keeps serving them to
  every plugin that asks, and updating the plugin restores the page. The floor would have to be
  enforced somewhere, and the only mechanism the host has for that is refusing to load the
  plugin, which would take the vault down with it.
- **AI self-healing / repair** is the one credential the host still resolves itself, because
  `SelfHealingSettingsManager` runs before any window or plugin exists and so cannot reach the
  plugin's store. It reads `AI_REPAIR_API_KEY`, then the provider's own variable
  (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / …), then the legacy `~/.boss/llm_settings.json` and
  its `.migrated` sibling - all as **env vars / files, never local.properties**. A key rotated
  in the Secret Manager panel's AI section does not reach it.

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

## Browser telemetry, and how to turn it off

The integrated browser reports which sites BOSS is used with and how - page views,
dwell vs active time, navigation depth, and in-page interactions (clicks, scroll
depth, field focus, form submits, copy/paste). `BrowserAnalytics` is the privacy
boundary: a full URL goes in and only an **eTLD+1 registrable domain** comes out,
and the injected collector is written never to *read* page text, input values,
labels, ids or URLs in the first place. See its KDoc for what is deliberately not
covered.

**Kill switch**: `BOSS_BROWSER_TELEMETRY_DISABLED=true` (also `1` / `yes` / `on`),
or the `boss.browser.telemetry.disabled` system property. It is enforced at the
single point every browser event is published, *and* stops the collector script
being injected into pages at all. Read once at startup, so a change needs a
restart. There is no Settings row and no per-site exclusion.

**Two things a deployment should know before building on this data:**

- **Any installed plugin can subscribe.** `PluginContext.applicationEventBus` is
  ungated, so `BrowserEvent` / `BrowserInteractionEvent` are readable by every
  plugin, not only the analytics plugin that owns *egress* consent. This is the
  largest change in what a third-party plugin can observe, and it is a deliberate
  choice to keep the bus uniform rather than an oversight - gate at install time
  by choosing which plugins are allowed, not by trusting the bus.
- **The interaction numbers are attacker-influenceable.** `window.__bossInteraction`
  is reachable by any script on the page, so a site can fabricate its own
  engagement (bounded by the rate limiter) or suppress it by pre-setting
  `window.__bossInteractionStarted`. The sanitizers bound what can be *smuggled*
  through; nothing bounds a site lying about its own usage. Treat these as
  indicative, not as measurements, wherever a site has an incentive to lie.
- **Every project the user opens is now on the bus, not only plugin-initiated ones.**
  `ProjectChangeEvent` used to be published from `ProjectDataProviderImpl.selectProject`
  alone, so a path reached plugins only when a plugin had asked for the switch. It is
  now announced from the window state's own `ProjectSelectionCallback`
  (`WindowProjectStateRegistry.newState` / `ProjectChangeAnnouncer`), which covers the
  startup restore, the top bar picker, the CLI, deep links and the KERNEL-mode gRPC
  bridge. Project paths routinely contain usernames, so this widens *when* a filesystem
  path reaches every installed plugin, not *what* - the same install-time-gating stance
  as the bus above applies, and it is recorded here because this paragraph is the
  canonical list of what a third-party plugin can observe.
- **`PluginContext.projectSearchProvider` is the first UNGATED WRITE surface.**
  Like the event bus it is available to every installed plugin, but where the
  bus is a read, its `replaceInProject` rewrites file contents anywhere inside
  the open project (the `project_replace` MCP tool sits behind a
  `project.replace` permission on the *MCP* side, but the provider itself is
  ungated). Confinement is to the open project only - `resolveFile` refuses
  paths outside it, canonical and symlink-checked. A plugin that needs project
  search should be vetted the same way one that subscribes to the bus is.

## Two-finger swipe navigation (macOS)

A two-finger horizontal trackpad swipe navigates back/forward. It is detected **inside the page**
(`BrowserSwipeNavScript` + `swipe-nav.js`), because under `HARDWARE_ACCELERATED` the browser is a
native surface and neither Compose nor AWT sees the wheel. JxBrowser's
`enableOverscrollHistoryNavigation` does NOT provide this - measured 2026-08-28, it does nothing for
a trackpad in either rendering mode, because it is a touchscreen feature.

The recognizer is a port of Chrome's own (`history_swiper.mm`): three cancellation tiers, with
vertical measured as a path length and horizontal as net displacement. Chrome's absolute thresholds
are fractions of the trackpad from `NSTouch.normalizedPosition`, which a page cannot see, so those
carry over as the same fractions of the commit distance.

**It commits at the end of the gesture, not on crossing the commit distance** - and "end of
gesture" is literally `GESTURE_GAP_MS` (120ms) with no wheel event, because AWT does not surface
NSEvent's scroll phases and a time gap is the only segmentation signal there is. So it is not
release: holding past the line and simply STOPPING, fingers still down, commits after 120ms too.
The window in which reversing still cancels is 120ms of continuous motion, not "until you lift".
The decision reads the LAST horizontal position, so easing back below the line cancels.

That makes `GESTURE_GAP_MS` do three jobs at once: segmenting one gesture from the next, setting a
floor on commit latency, and (as the minimum possible gap between two gesture ends) bounding
`SWIPE_NAV_DEBOUNCE_MS` from above. Raising or lowering it touches all three, and
`BrowserSwipeNavTest` reads it out of the script so the third one fails loudly.

**Past the commit distance, vertical drift stops cancelling** (`reachedCommit`). Vertical is a path
length and only ever grows, so every event after the crossing was one more chance to cancel a swipe
the user had already completed. Before the line the three tiers apply unchanged; after it, only
easing back or reversing can still cancel. Native swipe-back behaves the same way.

**Two host-side windows, for two different things** (`BrowserSwipeNavBridge.kt`).
`SWIPE_NAV_DEBOUNCE_MS` (32ms, any direction) catches a double-dispatch bug in the bridge.
`SWIPE_NAV_REPEAT_MS` (400ms, same direction only) is the paused-drag guard: a slow drag that
hesitates past `GESTURE_GAP_MS` with the fingers down is two gestures to the script and would
navigate back twice. That guard cannot live in the page - the first commit navigates the tab and
the script's state dies with the document. The cost is that two intentional same-direction swipes
under 400ms apart become one; that is the deliberate trade, because a dropped swipe is retryable
and an extra step back may not be, since the forward entry need not survive a redirect. A reversal
is never held for the repeat window.

**Momentum phase costs latency and nothing else.** A `CGEvent` tap on this hardware (measured
2026-09-02) shows macOS emitting momentum-phase scroll for 180-870ms after the fingers lift,
carrying 325-2500px of horizontal travel. Whether Chromium forwards those to the renderer as
`wheel` events is NOT confirmed: if it does, each one re-arms the end-of-gesture timer and a flick
commits at end-of-momentum instead of at release. It cannot change the ANSWER - a tail runs the
flick's own direction, so it can neither reverse nor ease back, and `reachedCommit` is what closed
the remaining path, a tail's `deltaY` tripping the vertical tiers. Synthetic phase-tagged events
cannot settle the forwarding question - `CGEventPost` from another process never reaches the
layered native browser surface, and does not even enter the session event stream - so it needs one
real flick against a recording `wheel` listener.

**Off switch**: `Settings > Browser > Trackpad`, stored in `~/.boss/swipe-nav.json`, or
`BOSS_BROWSER_SWIPE_NAV=false` (also `0` / `no` / `off`). The environment wins, and the Settings row
says so. The setting is published as a **system property** because the browser plugin draws the home
surface, lives in another repo, and `PluginContext.settingsProvider` reads nothing - so both halves
of the gesture read one key. An unparseable value owns nothing.

Covered by running it: `node scripts/test/test-swipe-nav.js`, in `build.yml`.

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

## Every OS open request becomes a `boss://` link

Links and files arrive through four different doors and all four normalise to one
deep link before anything else happens, via `fileDeepLinkFor` and
`OsOpenArguments`:

| Door | Platform | Handler |
|---|---|---|
| open-URI AppleEvent | macOS | `Desktop.setOpenURIHandler` |
| open-file AppleEvent | macOS | `Desktop.setOpenFileHandler` |
| a path or URL in `argv` | Windows, Linux | `DeepLinkHandler.processCommandLineArgs` |
| a forward over the single-instance channel | all | `main.kt`, when the lock is held |

The point of funnelling them is that `boss://file` and `boss://url` already carry
the parts each new door would otherwise have to re-implement: path validation, the
window resolve through `WindowFocusManager.resolveActionableWindowId`, and the
`FileHandlerService` / `URLHandlerService` counters that stop the New Tab dialog
destroying the tab being created during a cold start.

Four things that were broken before this and are easy to break again:

- **Nothing registered an open-file handler at all.** BOSS declared
  `CFBundleDocumentTypes`, appeared in Finder's Open With menu, launched when a
  file was double-clicked, and then did nothing with it. macOS discards the
  AppleEvent once the queue drains with no handler set.
- **`processCommandLineArgs` was gated on Windows.** Linux delivers a protocol URL
  in `argv` too (`Exec=<app> %U`) and the JDK's X11 peer supports no
  `APP_OPEN_URI` action, so a Linux user with BOSS as their default browser had
  every cold-start link silently dropped.
- **A CLI invocation must not be extracted as an open request.** `OsOpenArguments`
  returns nothing when any argument names a `createBossCLI` subcommand, or
  `boss file /tmp/x.md` opens the file twice. `OsOpenArgumentsTest` pins the
  subcommand list against `BossCommand.kt`.
- **A `file://` URL is not a path.** `Exec=%U` hands file managers' URLs over, and
  `File(URI)` rejects any authority component, so `file://localhost/tmp/x` needs
  the redundant host stripped before it resolves.

`CLISecurityValidator` now has two path checks, and using the wrong one is a
visible bug either way. `isValidOpenTargetPath` is for a file about to be **read**
into the editor: NUL rejected, canonicalised, nothing else. `isValidPath` is for a
path that may reach a **shell** and rejects `..`, `$`, `&`, `;`, `|` and a
backtick - which are ordinary filename characters, so applying it to a file open
meant `Q&A notes.md` could not be opened at all.

## Default applications, and the engine bundle that stole them

`boss-file-types.json` (in `composeApp/src/desktopMain/resources`) is the single
source of truth for what BOSS can be made the default handler for: five
categories over 83 extensions, which is exactly what `EditorLanguages.EXTENSIONS`
can highlight. Three consumers read it and one test pins it:

- `buildSrc/BossFileTypes` generates the `CFBundleURLTypes`,
  `CFBundleDocumentTypes` and `UTExportedTypeDeclarations` blocks at build time.
- `FileTypeCategories` serves the Settings screen and the registration calls.
- `WindowsFileTypeHandler` and `LinuxFileTypeHandler` derive ProgIDs and MIME
  associations from it.
- `FileTypeCategoriesTest` asserts its extension set and its extension-to-language
  map equal `EditorLanguages.EXTENSIONS`, so what BOSS offers to open cannot drift
  from what it can render. (`buildSrc/BossFileTypesTest` checks the same thing by
  regex, because `buildSrc` compiles first and cannot see the real object.)

**Launch Services can only make an app the default for a *type*, never for an
extension.** 42 of the 83 extensions resolve to a system UTI, which BOSS claims
as-is - 31 distinct UTIs, since several extensions share one (`.cpp`, `.cc` and
`.cxx` are all `public.c-plus-plus-source`). The other 41 have no system UTI
(`UTType(filenameExtension:)` answers with a `dyn.*` placeholder, which cannot be
set as a default), so BOSS exports its own type for them, grouped by language into
24 declarations. The per-extension answers in the resource are
**measured, not derived**, and three of them are the reason: `.ts` resolves to
`public.mpeg-2-transport-stream` (a video container), `.as` to
`com.apple.applesingle-archive` (a binary archive) and `.edn` to `com.adobe.edn`.
Claiming any of those would make BOSS the default application for a format it
cannot open, so they are recorded as `rejectedSystemType` and get an exported type
instead.

**Windows registration is one `reg import`, not hundreds of `reg add`s.**
`WindowsRegistryScript` generates a `.reg` script and `WindowsFileTypeHandler`
imports it in a single process; the status is one `reg query <FileExts> /s` parsed
once. The per-`reg add` version ran on the order of 415 processes for a
five-category "Set all" plus 83 more to read the status, each with its own
timeout, from a Settings screen and from the first-run offer at startup.

It also removes a quoting hazard rather than trying to get it right: the
`shell\open\command` value is `"C:\path\BOSS.exe" "%1"`, a string that both
begins and ends with a double quote, and Java's Windows `ProcessImpl` treats an
argument in that shape as already quoted and passes it through unescaped - so
`reg` would have stored only the exe path and treated `"%1"` as a stray token,
killing the one write that makes a double-clicked file reach BOSS. That premise
could not be verified on a mac (the JDK ships only the Unix `ProcessImpl`), which
is itself a reason to prefer the form where the question cannot arise. In a `.reg`
file the value is escaped by `regEscape` and never passes through a command line.

**The three platform handlers share one fold.** `DefaultHandlerState.reduce` is
the single definition of "BOSS only when every type in the category is BOSS, and
`OurEngine` outranks `Other`". Each handler had its own copy; they agreed, which
is the only reason nothing was broken, and a fourth copy would not have.

**`Settings > Browser` reads that fold too, and used to hold a boolean instead.**
`DefaultBrowserSection` is the older surface for the same two categories, and it
flattened the answer to `Boolean?` - which cannot tell "Safari holds http" from
"a BOSS component holds http". So on exactly the machines the three-way state
exists for, it said "BOSS is not your default browser" while `Settings > Default
Apps` reported `OurEngine` and offered a Repair: two screens, one machine,
opposite stories, and the one a user reaches from `Settings > Browser` was the
wrong one. It now renders all three states, offers **Repair** for `OurEngine`
(the same claim call, relabelled, because setting and repairing are the same
work), and **re-reads** the state after a successful set rather than assuming
`Ours`. `browserHandlerState()` is not on the `expect` declaration:
`DefaultHandlerState` is desktopMain and lifting it into commonMain to widen an
interface with one implementation would be churn.

**Windows counts schemes now, and could not before.** `web-links` is schemes with
no extensions, and `WindowsFileTypeHandler` read only the per-extension
`UserChoice` keys - so that row reported `Other` on every machine, including one
where BOSS did hold http and https, and `register` returned early on "no
extensions" without ever writing the `StartMenuInternet` entry that puts BOSS in
the browser list its own settings page sends the user to. Both sides now consult
`schemesFor`, through `WindowsDefaultBrowserHandler.schemeState` and
`registerAsBrowserCandidate` rather than a second copy of those registry reads
and writes. macOS always counted both. The `reg` calls cannot run on a mac or
Linux runner, so what is pinned in a test is the data fact underneath
(`FileTypeCategoriesTest`: `web-links` has schemes and no extensions, `web-pages`
the reverse) - if that flips, the reason for reading both sides flips with it.

`OurEngine` is unreachable on Windows and Linux, and that is a fact about those
platforms rather than a gap: the second "BOSS" is a macOS `.app` that Launch
Services indexes because it declares `CFBundleURLTypes`. Nothing registers the
engine under `StartMenuInternet` or writes a desktop entry for it. The shared type
is still used on all three so the card has one story to tell.

**The engine bundle used to be a second app called "BOSS".**
`~/.boss/boss-chromium/BOSS.app` is the branded JxBrowser engine, and being
Chromium it inherited `CFBundleURLTypes` (http, https) and
`CFBundleDocumentTypes` (`public.html`). Branded, its `CFBundleName` is also
"BOSS" and its id is `ai.rever.boss.browser` - so System Settings offered two
indistinguishable "BOSS" entries under Default web browser, and picking the wrong
one handed every link to a bare rendering engine with no window, no tabs and no
session. Measured on a real machine: http, https and `public.html` all resolved to
`ai.rever.boss.browser` while the app itself reported "not the default browser".

Both halves of the fix matter. `build-chromium-branding.yml` now deletes both keys
from the engine's `Info.plist` before the re-sign (`EngineBundlePlistStripTest`
pins that, including the ordering: after the re-sign the edit breaks the seal), so
future engines are clean. And `DefaultHandlerState` is a three-way answer -
`Ours` / `OurEngine` / `Other` - so an install already in the broken state is told
what actually happened and offered a Repair rather than "BOSS is not your default
browser", which is the wrong story to tell somebody who did set it.

**An existing install cannot be fixed by a version number.** The engine fix ships
inside a *rebuild* of an already-published engine, so `version.txt` reads the same
before and after and `ChromiumAutoDownloader.isChromiumInstalled`'s equality test
short-circuits. So that function also asks the extracted bundle what it actually
declares (`declaresBrowserTypes`): on macOS, an `Info.plist` still carrying
`CFBundleURLTypes` or `CFBundleDocumentTypes` invalidates the directory and the
existing download path replaces it. Three things about that check:

- It sits beside the execute-bit check in the same function, which exists for the
  same reason: catching a cached engine that is the right *version* and the wrong
  *content*.
- It is **bounded to one attempt per version** by a marker outside the engine
  directory (`~/.boss/boss-chromium.types-repair`; a re-download replaces the
  directory itself). Without it, an engine that still declares the keys after the
  re-download - the rebuild never published, or published unrepaired - would
  re-fetch ~160 MB on every launch, silently.
- It **fails closed**: an unreadable or absent plist answers false, because
  forcing a large download over a file we could not read is the worse mistake.
  It matches `<key>NAME</key>` rather than the bare key name, so a comment or a
  string value naming the key is not a declaration.

`lsregister -u` on the engine bundle was tried first and does nothing: it returns
0 and the bundle is still a candidate for `https` and `public.html` afterwards, so
there is no download-free way to take it out of Launch Services.

Launch Services is reached through `utils/mac/LaunchServices.kt`, bound with JNA.
It replaced a `swift <tempfile>` shell-out per call, which needed Xcode installed
and so could not work at all on most machines; the Swift scripts remain only as a
fallback for URL schemes if `Native.load` fails.

## A missing plugin no longer fails silently

Browser, editor and terminal tabs are plugin-provided. `addTab` logged "Dropped
tab - no factory registered for its type", returned -1, and every caller ignored
it: with the browser plugin absent the OS could hand BOSS a link and nothing at
all appeared, which is what "BOSS is my default browser and clicking a link does
nothing" was.

`SplitViewState.requireTabTypeThen` now gates every open on
`TabTypeAvailability.require`, which:

1. returns immediately when the type is registered (the normal case, so the fast
   path does not suspend);
2. otherwise **waits** through `awaitRegistryCondition` - at a cold start the
   plugins have not registered yet, and prompting there would be a false alarm on
   every launch, the same reason `WorkspaceApplier.awaitTabTypes` exists;
3. only then raises a `MissingHandlerPluginPrompt` on `MissingHandlerPluginEventBus`,
   whose delivery copies `PluginDependencyBus` deliberately: a `Channel` so exactly
   one window asks, buffered so reporting never suspends the open, `trySend` so an
   overflow is refused and logged rather than silently dropped;
4. waits again, up to five minutes, for the plugin to register. **The dialog has no
   success callback**: installing or enabling registers the tab type, which fires
   the registry listeners, which completes this wait and performs the deferred
   open. The file the user double-clicked appears by itself.

It offers **Enable**, not Install, when the plugin is on disk and `DISABLED`.
Installing something already installed cannot fix it, and both halves share
`PluginDependencyResolution.installedAndOnDisk` so they cannot disagree about
"installed" - the trap this repo's own AGENTS.md records as having broken the
dependency prompt once already.

`TabTypePlugins` maps a tab type to the plugin that provides it. It has to be a
literal table: the mapping lives in each plugin's `plugin.json` and when the
plugin is absent there is no manifest to read. It keys on the **type string**, not
the whole `TabTypeId`, whose equality includes `pluginId` and `defaultOrder`.

## Documentation

- [Core Subsystems](docs/SUBSYSTEMS.md) - Auth, UI, keyboard shortcuts, threading, default applications, runner, BossTerm
- [BossEditor](docs/BOSSEDITOR.md) - External editor dependency, LSP, PSI, editor features
- [Application Features](docs/FEATURES.md) - Performance monitoring, dashboard, downloads, Chromium branding
- [Keyboard Shortcuts](docs/KEYBOARD_SHORTCUTS.md) - Detailed shortcuts reference
- [Threading Best Practices](docs/THREADING.md) - Threading patterns and pitfalls
- [RBAC Guide](docs/RBAC_GUIDE.md) - Role-based access control
- [Role Creation](docs/ROLE_CREATION_GUIDE.md) - Creating and managing roles
- [Windows Deep Link](docs/WINDOWS_DEEP_LINK_SETUP.md) - Windows protocol handler setup
- [Release Rebuild](docs/RELEASE_REBUILD_GUIDE.md) - Re-running release builds

