# Contributing to BOSS

Thank you for contributing! BOSS is a Kotlin Multiplatform + Compose Multiplatform desktop app (macOS/Windows/Linux) with an out-of-process microkernel architecture, a plugin platform, and Supabase edge functions. This guide covers the parts that most often trip up first-time contributors.

## Before you open a PR

### 1. Fork setup: enable Actions

Forks on GitHub ship with **Actions disabled by default**. If your fork's Actions tab shows an "enable workflows" button, click it *before* opening a PR - otherwise your PR will show **zero CI runs**, and no run means no merge, regardless of the diff. (If you already opened the PR, enable Actions and then close/reopen the PR or push a commit to trigger the build.)

### 2. Use JDK 17

The build pins `jvmToolchain(17)`. Newer JDKs can crash detekt workers with confusing errors - if detekt fails before your code is ever analyzed, check `java -version` first.

### 3. The validation gate

Before pushing, run the triple gate for the modules you touched:

```bash
./gradlew :composeApp:desktopTest :composeApp:detekt :composeApp:ktlintCheck
```

(or `:boss-app-terminal:test :boss-app-terminal:detekt ...` - one module per app module you changed). CI runs the same gates; a locally-green build means a green PR. For scripts under `scripts/`, the `Shell Script Tests` CI job runs the harnesses in `scripts/test/` - a fix to a shipped script should add or extend a harness there.

Two properties of this repo's lint that differ from the defaults:

- **ktlint chain-style**: multiline call chains want each `.` on a new line; when-entries with any multiline body want braces on every entry; imports are lexicographic with `java`/`javax`/`kotlin` last. `./gradlew ktlintFormat` fixes most of these mechanically.
- **detekt thresholds are strict**: `ReturnCount > 2`, `TooManyFunctions` per object, `MaxLineLength`, `CyclomaticComplexMethod > 15`. When a fix needs more branches than a threshold allows, prefer extracting a helper over suppressing.

`-PskipLint` exists for builds that aren't the gate (CI's per-OS build jobs use it); don't use it to silence a failure you intend to ship.

### 4. One PR = one thing

The maintainers review on these lines:

- **Fixes bundle with tests.** A bug fix PR carries the regression test that pins it. If you found the bug, file the issue first (or link the existing one) - "Fixes #N" in the description is how the merge connects them.
- **Features and test-suite changes are separate PRs.** They're independently revertable and reviewers treat them independently.
- **The title should describe the whole diff.** A one-line title over a 25-file diff makes review impossible; if the diff spans multiple concerns, that's multiple PRs.
- **Don't re-implement an open PR.** Before starting, search the open PRs and issues for the area you're touching - if someone already has a green PR up, extending or reviewing theirs lands your contribution faster than racing a parallel version. The hackathon judging criteria are explicit that duplicated submissions don't count.

### 5. Architecture map (where does my change go?)

| Path | What lives there |
|---|---|
| `composeApp/` | The Compose UI application (`commonMain` = shared, `desktopMain` = JVM) |
| `modules/boss-ipc` | Proto definitions, process auth (`IpcCall`), the kernel |
| `modules/boss-service-*` | Out-of-process services (auth, settings, workspace, filesystem…) |
| `modules/boss-app-*` | Out-of-process app services (browser, editor, terminal) |
| `modules/boss-orchestrator`, `boss-mastery-*` | Self-healing orchestrator, mastery workflow engine |
| `plugin-platform/` | Host-side plugin loading/SDK (not the plugins - those live in `boss_plugins`) |
| `supabase/` | DB migrations and Deno edge functions (`plugin-store`, `passkey`, `organisation`…) - tested with `deno test` |
| `scripts/` | Shipped CLI shims (`boss`, `boss.bat`, `boss.ps1` - packaged into the app) and dev tooling |

If your change crosses a service boundary, read the module's own docs and `AGENTS.md` first - the IPC layer has deliberate invariants (authority gates, owner-scoping) that each service carries.

### 6. Test-home isolation (for tests that touch `~/.boss`)

`composeApp` test tasks point `user.home` at a fresh per-run directory. Keep that redirect: tests that read `BossDirectories.rootDir` need the same isolation or they will race a developer's real recent-files list. Don't write tests that touch the real home.

## Filing issues

Good issues carry: the file and line, the exact code that misbehaves, a reproduction or evidence, and - for security-sensitive reports - the exploit path (what can reach the vulnerable code). If you found the bug via an audit tool or agent, say so; the repo is AI-agent-friendly and judges the finding, not the finder.

## Need help?

Open the PR early with a draft marker and ask - reviewers here respond to specific questions ("is the authority gate on this RPC intentional?") faster than to general ones. The `Shell Script Tests` and `code-quality` CI jobs are your fastest pre-review signals: if those two are green, the rest is usually a diff conversation.
