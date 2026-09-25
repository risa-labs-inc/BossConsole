# CI validation and the PR 1472 queue investigation

## Validation policy

`build.yml`, `authenticated-ipc.yml`, `native-file-boundaries.yml`,
`filesystem-limits.yml`, and `edge-functions.yml` validate pull requests and
post-merge pushes to `main`; `build.yml` also runs on pushes to `dev` (see below). PRs targeting `main`, `dev`, or `develop` are covered;
filesystem limits retains its existing coverage of PRs to any branch. Existing
path filters on the specialized workflows are unchanged. The required Build &
Test workflow has no path filter, including for documentation-only PRs.

A push to `develop`, and to `dev` in every workflow except `build.yml`, does not
start a second validation alongside the branch's open integration PR. **Without an
open PR, `develop` has no automatic push validation.** Open a PR or use
`workflow_dispatch` to validate it. This is an event policy, not an API lookup that
conditionally skips required jobs. Main pushes still validate the actual merged
tree, and manual runs remain available on any branch.

`build.yml` does run on pushes to `dev`, on the Linux legs only. That run exists to
seed the shared Gradle caches: only pushes write the shared caches;
pull requests and manual runs restore from their base or default branch but never
write (`cache-read-only` is true for every event except `push`), because PR- and
dispatch-scoped caches can't be read by any other run and, at about 1 GB each, kept the
repository over GitHub's 10 GB cache cap and evicted the shared entries. Before
this change 14 of 20 sampled PR runs of `build-test (ubuntu-latest)` started from
an empty Gradle User Home (313 of 313 tasks executed, about 830 s) against about
256-594 s (about 430 s) when a cache was restored. The dev push run skips macOS and Windows, so
it adds no load to the hosted runners the original routing change protected.

Only superseded runs of the same PR are cancelled. Main push, dev push and manual
runs keep unique run-id concurrency groups, so a newer commit cannot cancel work
needed for release validation. Filesystem resource limits and Edge Functions now
use the same PR cancellation policy as the other three workflows.

PRs that don't target `main` skip the macOS and Windows legs of `build.yml`,
`authenticated-ipc.yml`, `native-file-boundaries.yml`, and `filesystem-limits.yml`
and gate on the Linux legs. PRs into `main`, pushes to `main`, and
`workflow_dispatch` runs keep the full matrix, so `main`'s required
`build-test (macos-latest)` and `build-test (windows-latest)` contexts are still
reported where they are required. A macOS-only or Windows-only break on `dev`
surfaces on the next `dev` to `main` PR; run `workflow_dispatch` on a branch to
check macOS or Windows sooner.

All jobs, Gradle tasks, path filters, runner labels, artifacts, and dependencies
are retained. In particular, the following live `main` branch-protection contexts
were checked through the GitHub API on 2026-09-22:

- `version-check`
- `build-test (ubuntu-latest)`
- `build-test (macos-latest)`
- `build-test (windows-latest)`
- `code-quality`
- `compatibility-test`
- `🧪 Database (pgTAP)`
- `🐚 Shell Script Tests`

`dev` returned "Branch not protected" and the repository rulesets list was empty
at inspection. No branch protections are changed by this patch. Release,
publishing, and Chromium branding workflows are untouched, including the
Chromium x64 branding job's `macos-15-large` runner.

## Evidence: duplicate work and queue time

At head `bc9aa3b2fad66db2c6da59797887470f6ade29ec`, PR 1472 caused both
[push run 35687918500](https://github.com/risa-labs-inc/BossConsole/actions/runs/35687918500)
and [PR run 35687920435](https://github.com/risa-labs-inc/BossConsole/actions/runs/35687920435).
The workflow subscribed to pushes on `dev` and PRs targeting `main`. Its
concurrency key used the PR number for one and a unique run id for the other,
so neither suppressed the duplicate. Both ran the Linux and Windows builds,
quality, compatibility, shell, BOSS AI, and database jobs.

GitHub job timestamps (UTC, 2026-09-22):

| Job | Created | Started | Completed | Queue / execution |
| --- | --- | --- | --- | --- |
| PR macOS [106618653017](https://github.com/risa-labs-inc/BossConsole/actions/runs/35687920435/job/106618653017) | 04:43:03 | 05:00:36 | 05:04:02 | 17m33s / 3m26s |
| PR Linux 106618652970 | 04:43:03 | 04:43:08 | 04:47:39 | 5s / 4m31s |
| PR Windows 106618652987 | 04:43:03 | 04:43:08 | 04:47:39 | 5s / 4m31s |
| Push macOS [106618650756](https://github.com/risa-labs-inc/BossConsole/actions/runs/35687918500/job/106618650756) | 04:43:03 | unassigned | still queued at 05:08 | over 25m / none |

The queued job has `runner_name: ""`. GitHub returned `started_at` equal to
`created_at` even while `status` was `queued`; that timestamp is not evidence
of execution. Queue time here is measured from job creation, not workflow
creation, so it excludes the `version-check` dependency.

A repository snapshot completed at 05:08:29 UTC examined 31 queued workflow
runs and found 28 jobs still queued, all labelled `macos-latest`. This supports
runner scheduling as the immediate delay; it does not establish whether the
limiting factor was organization concurrency, other repositories, or GitHub's
capacity. There is no evidence here that switching macOS images is faster.
GitHub documents the available labels in its
[runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

The routing change removes one of the two full validation runs for a `dev` to
`main` update: one macOS build per update instead of two, and the same reduction
for each matching specialized matrix. Cancelling superseded filesystem runs
also removes obsolete macOS demand. This does not guarantee a queue-time bound,
change organization quotas, or cancel already queued runs using old YAML.

Reproduce the evidence with `gh api`:

```sh
gh api repos/risa-labs-inc/BossConsole/actions/runs/35687920435/jobs
gh api repos/risa-labs-inc/BossConsole/actions/runs/35687918500/jobs
gh api repos/risa-labs-inc/BossConsole/branches/main/protection/required_status_checks
gh run list -R risa-labs-inc/BossConsole --status queued --limit 100
```

## PR 935 replay: intermediate regressions, no final content change

[PR 935](https://github.com/risa-labs-inc/BossConsole/pull/935) was squash-merged
as `6c3e1186dfe47616ff4d441292a3f399d5d9b3ef` at 04:04:05 UTC on 2026-09-22.
[PR 1472](https://github.com/risa-labs-inc/BossConsole/pull/1472) then replayed
53 commits on top of `01120ac82799d5e4a0904820fad4f644c8f28b08`, which already
contains that squash. Commit subjects describe the original fixes, but some
actual replay diffs undo the reviewed state before subsequent commits restore it:

| Replay commit | Regression in that intermediate tree | Restoration |
| --- | --- | --- |
| [fc379ccc](https://github.com/risa-labs-inc/BossConsole/commit/fc379cccb5b4441ac21624beba2552f6f5677fd0) | `AtomicFileWrite` catches permission-hardening failures and publishes anyway, replacing fail-closed owner-only persistence with best effort. | `d092bc17` |
| [3ba91dfa](https://github.com/risa-labs-inc/BossConsole/commit/3ba91dfa672bf2cc55a2e7ab36b92b620cef9299) | Settings persistence similarly swallows POSIX permission failures. | `179a0ba9` |
| [32ad5569](https://github.com/risa-labs-inc/BossConsole/commit/32ad5569c636daea2cfe25a7202718c6d2580a61) | Assigns loaded dismissals over startup dismissals, removes their regression tests, and removes `RecentBrowserPagesManager.flushPendingSaves` while `ShutdownSequence` still calls it. The latter is an unresolved reference by source inspection. | Dismissal merge in `3c5a764c`; flush in `efea1918`; awaitable dismissal save in `bc9aa3b2`. |
| [bdbd926d](https://github.com/risa-labs-inc/BossConsole/commit/bdbd926dff6619fc9fb7c3400e0be5518e0d6854) | Removes `UserDataStorage.flushPendingSaves` while shutdown still calls it; downgrades the logout test from the public save path to a test-only delegate. | Public-path test in `4285c325`; flush in `16a4af64`. |
| [517d9717](https://github.com/risa-labs-inc/BossConsole/commit/517d9717d1161cfc2cfa9f14e514b7589b985644) | Reintroduces separate search grouping and deletes three producer/layout contract tests from the already-reviewed search fix. | `9f89d223`, then formatting in `0d7b47f2`. |

These are **intermediate replay regressions, not defects remaining at PR 1472's
inspected head**. No historical Kotlin builds were run for this investigation.
The final head `bc9aa3b2` and recorded base `01120ac82` have the identical Git
tree `320ba59f78a7cab0168338e5382e875bf2fd2928`; the API reports zero changed files.
Replaying the batch added history and CI work, not the features its PR body claims.

`main` subsequently advanced to `5dd0b064f` with the tab-drag fix in PR 1473.
A two-endpoint comparison of that main against `dev` shows the fix absent from
the older dev snapshot; it is not a merge regression. `git merge-tree` produced
`2193413797a56bb09f0bb13c08dc56603496a668`, identical to that main tree, proving
the current merge preserves PR 1473. Recheck these facts if either branch moves.

```sh
git diff --exit-code 01120ac82 bc9aa3b2
git rev-list --count 01120ac82..bc9aa3b2
git show fc379ccc -- composeApp/src/commonMain/kotlin/ai/rever/boss/utils/AtomicFileWrite.kt
git merge-tree --write-tree 5dd0b064f bc9aa3b2
git rev-parse '5dd0b064f^{tree}'
```

For the next integration, start from current `main` and bring over only changes
not included in the previous squash. Do not cherry-pick a prefix of this replay
as a fresh security fix. This CI patch does not rewrite or merge either branch.

## Validation of this change

- Actionlint 1.7.12 passes on all five edited workflows (embedded shell/Python
  lint disabled; job steps are unchanged).
- Parsed-YAML comparisons against `bc9aa3b2` confirm structurally identical
  job definitions, unchanged path filters, all eight live required
  contexts, and no filter on the required workflow.
- Sixty routing scenarios cover PRs to main/dev/develop, pushes, manual runs,
  and one validation event per dev-to-main/develop-to-dev update. PR base
  filtering also admits fork PRs. Cancellation remains PR-only.
  (This records the earlier routing change. Since `build.yml` gained the dev push
  trigger for cache seeding, an update into `dev` produces two `build.yml` runs:
  the PR run and the ubuntu-only post-merge seeding run.)
- Every unedited workflow is byte-identical to the base, including release,
  publishing, and Chromium branding. `git diff --check` passes.

Routing is validated locally; GitHub scheduling and platform execution are
validated by the new PR's checks. Existing queued runs are not retroactively
changed by this patch.
