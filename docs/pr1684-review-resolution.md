# Release PR #1684 review resolution

## Confirmed runtime blocker

Repeated reflective calls to a plugin-defined class failed on JDK 17 when the JVM
generated an accessor. `jdk.internal.reflect` now resolves parent-first, like the
other platform runtime packages. This does not reopen arbitrary host-library or
host-implementation fallback. The regression test packages its own fixture class,
checks that the plugin owns it, then invokes its constructor and method 100 times.
The test fails without the fix and passes with it.

## Main review findings

| Finding | Resolution |
| --- | --- |
| Duplicate host MCP provider registration | Removed the repeated download-history and workspace-portability registrations. |
| Download history exposes credential-bearing URLs by default | Kept the truthful read-only declaration, but classified this sensitive read alongside `secret_get` in the approval-requiring catalog. Default policy is ASK; explicit operator policies and trust still apply. A governed invocation test denies the request and checks that neither the signed URL nor local path is returned. |
| Unreadable child directory discards every search result | Skip inaccessible child directories and count them. Filename indexing retains a visible partial-results warning, including on cache reuse. Content search posts the warning and discovery logs it. Root, budget, and unreadable ignore-policy failures remain fatal. |
| Eager discovery cost | Retained the bounded complete walk so budget and ignore-policy failures are discovered before results are returned. Added a 120-module, 12,000-source-file scale probe with diagnostic timing. This is not a streaming-search optimization or a guarantee for the 250,000-file limit. |
| Tab search reads UI state from a worker | Snapshot liveness and immutable metadata on Main, then score and sort off Main. A real-tab test checks which thread reads its title. |
| Workspace restore policy and documentation disagree | Corrected the documentation and added actionable recovery text. All-or-nothing restore is retained: silently omitting saved tabs is not an acceptable recovery policy. An explicit partial-restore workflow remains separate work. |
| Missing Chromium checksums can poison the version cache | Mark hashless platform entries as incomplete and do not cache incomplete or empty lists. Checksum verification remains mandatory; GitHub is still a byte-download fallback, not authority for an unverified version. |
| Log overflow and shutdown races | Use an atomic bounded FIFO drop-oldest operation. Fence dispatcher creation against stop, and prevent a retired dispatcher from consuming another generation. Listener callbacks remain outside locks; an uncooperative in-flight callback is not forcibly terminated. |

The PowerShell quote scanner also now shares the single-quote delimiter set with
the path quoter, including mixed typographic quote pairs.

## Rush Hour removed from the host release

At the maintainer's request, removed the built-in Rush Hour game, Compose UI,
tab registration, three MCP tools, feature tests, and host-specific documentation.
Games belong in a separately delivered plugin or Arcade package, not BossConsole
host code. This change removes the host implementation; it does not claim to
have migrated the game to a plugin. The implementation remains in Git history.
Original implementation: Kanak Khandelwal (`Kanakk23`), PR #1012; maintainer
consolidation: PR #1531.

## Validation

- Before Rush Hour removal: full host and plugin-loader suites, 7,255 cases,
  zero failures, five skipped; both modules' ktlint and detekt passed.
- After removal: rebuilt host, 793 affected host tests and 182 plugin-loader
  tests passed; both modules' ktlint and detekt passed.
- Restarted the debug app from this isolated worktree. Its live MCP endpoint
  advertised 186 tools with Rush Hour absent. Sixty consecutive `plugins_list`
  calls, workspace/tab listings, performance metrics, ping, tool discovery,
  and a fresh initialization all succeeded without the reflection failure.

## Limits and separate follow-ups

- No production release catalog, platform artifact parity, grants, or deployment
  was changed or certified by these local tests.
- The eager walk remains bounded but still pays full discovery cost per content
  search. Streaming plus trustworthy completion metadata needs a separate API design.
- The review's smaller observations about cold-start URL queue capacity,
  hidden filename indexing, expanded git status, and the
  pre-existing PowerShell subprocess timeout ordering are not changed here.
- Five incompatible installed debug plugins and the malformed Flow plugin tool
  schema belong to separately versioned plugins and are not fixed by this host patch.
- Build and test work uses a separate worktree from the running debug process;
  replacing a JAR underneath a live JVM invalidates manual smoke-test evidence.
