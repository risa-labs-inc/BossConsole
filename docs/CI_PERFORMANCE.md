# CI performance baseline and changes

Measured on 2026-09-15 (Pacific time), from the eight latest completed
`Build & Test` runs in a 100-run Actions API snapshot. Seven succeeded and one
failed. These are mixed commits and cache states, not a controlled benchmark.

| Job | Queue median (range), seconds | Execution median (range), seconds |
| --- | --- | --- |
| Linux build/test | 2 (2-3) | 450 (274-634) |
| Windows build/test | 2 (2-3) | 635.5 (297-776) |
| macOS build/test | 141 (6-522) | 493.5 (234-795) |
| Compatibility | 2.5 (2-3) | 213 (66-323) |
| Code quality | 2 (2-3) | 130.5 (69-272) |
| Database | 2 (1-2) | 107.5 (99-117) |

Queue is job `started_at - created_at`; execution is
`completed_at - started_at`. Dependency wait happens before a dependent job is
created and is not runner queue time. Workflow `run_started_at` is not a useful
measure of runner availability. The API does not distinguish capacity wait from
all other scheduling delays, so macOS capacity pressure is an inference.

Run IDs: [35035075258](https://github.com/risa-labs-inc/BossConsole/actions/runs/35035075258)
(failure), [35035357670](https://github.com/risa-labs-inc/BossConsole/actions/runs/35035357670),
[35036042335](https://github.com/risa-labs-inc/BossConsole/actions/runs/35036042335),
[35036571228](https://github.com/risa-labs-inc/BossConsole/actions/runs/35036571228),
[35036630915](https://github.com/risa-labs-inc/BossConsole/actions/runs/35036630915),
[35038233314](https://github.com/risa-labs-inc/BossConsole/actions/runs/35038233314),
[35040853535](https://github.com/risa-labs-inc/BossConsole/actions/runs/35040853535),
[35043626080](https://github.com/risa-labs-inc/BossConsole/actions/runs/35043626080).

Workflow wall time was 318-1,113 seconds, median 745. The critical path ended
with Windows in four runs, macOS in three, and Linux in one, followed by the
summary job. The version prerequisite took 5-8 seconds plus 1-3 seconds of queue.

## Changes

- Cancel superseded PR runs independently in Build & Test, Native file boundaries,
  and Authenticated IPC boundaries. Push and manual runs use unique run IDs and
  cannot cancel each other or PR runs. Workflow and check names remain unchanged,
  including the workflow-run-based review authorization flow.
- Run the native-file push matrix on integration branches (`main`, `develop`,
  `dev`), with feature-branch validation on PRs. The initial push and opening of
  this PR produced native-file runs 35044732899 and 35044764465 for the same head,
  consuming two five-job matrices. Keep the PR merge-tree validation and the
  post-merge push validation; feature branches can still use manual dispatch
  before opening a PR.
  Both workflows retain push coverage for `main`, `develop`, and `dev`; their
  PR target lists are `main` and `dev`. `develop` is retained for compatibility,
  not removed merely because it is absent from the current branch inventory.
- Submit `test build` in one Gradle invocation on every OS, plus
  `createExecutableJar` on Linux. Retain `--continue`, the Windows daemon/parallel
  settings, and always-uploaded test reports. Only lint remains delegated to the
  existing authoritative code-quality job via `-PskipLint`.
- Combine the three compatibility tasks into one invocation; combine dependency
  reporting with static analysis. Keep the separate buildSrc test invocation
  because it targets a different build.
- Start code quality and compatibility without waiting for unused version
  outputs. Database tests still require successful BOSS AI contract checks.
- Include script tests and version-check in the summary success condition; no
  failure or skipped check is converted into a passing summary.

## Evidence and expected impact

In run 35038233314, logs show distinct uncached configuration passes for `test`,
`build`, and `createExecutableJar`; compatibility likewise configured three
times. Consolidation removes seven Gradle invocations across the workflow
(four across the OS matrix, two compatibility, one code quality). It does not
claim all test-step time as savings: much of it performs necessary JVM tests.
Local Java 17 dry runs selected an identical union of 1,375 tasks before and
after, including `composeApp:desktopTest` and the three plugin ABI test tasks.

Expected savings are configuration/startup overhead and obsolete PR runner work.
No percentage or fixed wall-time reduction is promised: compiler work and macOS
queue variance dominate. The invocation reduction and graph equivalence are
observed; hosted execution savings require post-change runs and matched cache
states. Cancellation will only save work when a PR is updated before completion.

## Reviewed but retained

- **Caching:** `setup-gradle` already caches Gradle User Home/build outputs;
  configuration and build caching are enabled in `gradle.properties`. Different
  task requests prevent reuse within the original job. Do not persist plaintext
  configuration-cache files: configuration may capture credentials. No new cache
  mechanism, encryption secret, cache trust boundary, or production secret access
  is introduced. Forks retain normal `pull_request` secret restrictions.
  The pinned Gradle 9.7.1 dependency report uses a cached report model; the
  combined quality invocation in run 35044850572 stored its configuration cache
  successfully. An older Gradle dependency-report incompatibility is not evidence
  that this invocation discards the lint configuration cache.
- **Change selection:** broad build/test coverage remains on every PR. Existing
  native/security workflows already have path selection. Expanding it safely
  needs coverage of build logic, dependency catalogs, shared modules, scripts and
  resource inputs; docs-only shortcuts can also miss fixture or embedded-resource
  changes. No check is skipped for speed in this change.
- **Overlap and platforms:** JVM regressions overlap with dedicated native jobs,
  but Graal native execution and old-glibc coverage are distinct and retained.
  No OS or architecture is removed. Integration pushes and PR validation cover
  different trees, so both remain. Only redundant native-file feature pushes
  are removed, aligning this workflow with the main build's branch policy.
- **Artifacts/releases:** the existing Linux JAR and test reports remain. PR
  outputs are not reused in privileged release jobs. Release version mutation,
  signing, notarization, environment approval and publication remain unchanged.
  [Release 34931474764](https://github.com/risa-labs-inc/BossConsole/actions/runs/34931474764)
  spent 157s preparing the version, 949s executing macOS packaging (9s queue),
  and 133s creating the release. All five platform jobs are dependencies of
  publication; optional ARM jobs still must finish before publication evaluates
  their results. Splitting that dependency or reusing unsigned PR artifacts needs
  separate correctness work, not an incidental CI optimization.
- **Capacity:** no paid runner capacity or repository protection/settings changes.
  If macOS queueing remains high after obsolete runs are cancelled, collect a
  longer queue distribution before proposing a capacity change with cost.
- **Related PRs:** open PR title/search inspection found no active CI/build speed
  change. PR #613 is an audit workflow document template, not these CI workflows.

## Validation and rollback

Validate modified workflows with `actionlint`; compare the union of dry-run task
graphs with `test build createExecutableJar -PskipLint --continue --dry-run`.
Inspect hosted Linux/macOS/Windows tests and static analysis before merging.
Use job timestamps and step durations to separate queue effects from execution.
Rollback is a revert of this change; no migration, secret rotation, cache purge,
repository settings change or release rollback is needed.
