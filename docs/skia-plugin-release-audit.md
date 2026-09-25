# Shared Skia/Skiko release audit

Checked September 25, 2026 for BossConsole PR #1720.

Scope: all 36 entries returned by the public plugin-store catalog. Downloaded
30 latest store JARs and verified their SHA-256 against the download response.
Six store downloads returned HTTP 403; inspected all nine JAR assets from their
matching GitHub release versions instead. Those release assets were not verified
against the inaccessible store bytes. Private/unlisted plugins and historical
versions are outside this audit.

Scanned archive entries for `.class` files under `org/jetbrains/skia/` and
`org/jetbrains/skiko/`. The 30 store JARs contain no nested JARs. Separately scanned
all 30 currently installed JARs under `~/.boss/plugins/`: none bundle these classes.

The sole store artifact with rendering classes is Microkernel Runtime 1.0.26
(692 classes). Its manifest declares `isDynamic: false` and describes a child-JVM
runtime, with `PluginProcessMainKt` as its entry point. It supplies the separate
process classpath; this host plugin-classloader change does not switch that child
JVM to the host's Skia. No inspected dynamic plugin bundles these packages.

Re-check this audit when release artifacts change. Absence of bundled classes
does not prove every plugin's method calls are compatible with a future Skia bump.

| Plugin ID | Version | Source | Rendering classes |
| --- | --- | --- | --- |
| `ai.rever.boss.microkernel.runtime` | 1.0.26 | Store, SHA-256 verified | 692 |
| `ai.rever.boss.plugin.api` | 1.0.93 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.adminrolemanagement` | 1.3.4 | GitHub (1 JARs) | 0 |
| `ai.rever.boss.plugin.dynamic.agenthq` | 0.4.0 | GitHub (2 JARs) | 0 |
| `ai.rever.boss.plugin.dynamic.aigateway` | 1.1.7 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.analytics` | 1.0.8 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.arcade` | 0.1.30 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.bookmarks` | 2.1.10 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.codebase` | 1.6.1 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.console` | 1.2.5 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.deepseekharness` | 1.0.7 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.docker` | 1.0.6 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.downloads` | 1.1.4 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.editortab` | 1.6.8 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.flowtab` | 1.0.79 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.fluckagent` | 1.0.113 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.fluckbrowser` | 1.2.30 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.gitlog` | 1.1.3 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.gitstatus` | 1.1.4 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.jev` | 0.2.2 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.jupyternotebook` | 1.0.18 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.kubernetes` | 1.0.8 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.organisation` | 1.0.10 | GitHub (2 JARs) | 0 |
| `ai.rever.boss.plugin.dynamic.performance` | 1.2.3 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.pluginmanager` | 1.9.24 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.rolecreation` | 1.2.4 | GitHub (1 JARs) | 0 |
| `ai.rever.boss.plugin.dynamic.runconfigurations` | 1.1.3 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.screenshotshare` | 0.1.13 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.secretmanager` | 1.2.28 | GitHub (1 JARs) | 0 |
| `ai.rever.boss.plugin.dynamic.sourcecheck` | 0.1.43 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.terminal` | 1.0.11 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.terminaltab` | 2.5.101 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.toolcreator` | 0.1.9 | GitHub (2 JARs) | 0 |
| `ai.rever.boss.plugin.dynamic.toolevolver` | 0.5.8 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.topofmind` | 1.2.2 | Store, SHA-256 verified | 0 |
| `ai.rever.boss.plugin.dynamic.usersecretlist` | 1.2.8 | Store, SHA-256 verified | 0 |

## Direct rendering references on BOSS 9.5.25

A follow-up scan inspected class-file bytes for `org/jetbrains/skia/` and
`org/jetbrains/skiko/` references, excluding framework implementation classes
under those packages and `androidx/compose/`. This answers a different question
from bundling: a plugin need not bundle Skia to depend on host access to it.
The scan covered 69 available JAR artifacts (installed, downloaded store and
GitHub release copies, including duplicate versions).

| Dynamic plugin | Version | Direct reference paths |
| --- | --- | --- |
| Editor Tab | 1.6.8 | BossEditor MinimapCanvas, MinimapRenderer, FontUtils |
| Fluck Agent | 1.0.113 | FluckImageLoader image decoding, size validation and conversion to ImageBitmap |
| Terminal Tab | 2.5.101 | ImageRenderer, MCP show_image conversion, FontUtils, macOS toolbar images and Windows glass integration |
| Terminal Tab local hotfix | 2.5.102 | Same remaining paths, with the ImageRenderer direct Skia dependency removed |

The Fluck image decoder and editor minimap calls were also confirmed with
`javap -c` on the downloaded release artifacts. These are potential failures
under 9.5.25's classloader restriction; no manual UI reproduction was performed.
Error handling and feature/platform activation determine whether a blocked call
crashes the plugin, shows an error, or affects only an optional feature.

No other scanned dynamic plugin had direct references under these two package
prefixes. This bytecode scan does not detect arbitrary reflective name assembly
or prove all workflows work. The separate Terminal panel delegates through
TerminalTabPluginAPI and therefore benefits from the terminal-tab fix without
its own release. The host shared-package fix is still required to cover all
of the direct rendering paths listed here.
