# Agent engine repositories (no GitHub App)

Deploy with the companion migration, the optimist console/provisioner and the Max host.
This feature branch does not deploy any service or modify production data.

There is **no GitHub App and no GitHub credential on the platform**. The person's own Max does the
GitHub work with the person's own GitHub sign-in (`gh`, already connected on their pod):

1. `engine_repo_create` (Max): creates a private repo under the person's account or an org they
   belong to, pushes clean `stock` and `main` snapshots of their committed Max source, protects main
   if GitHub allows it, then calls `POST /repos/link` here.
2. `engine_checkout` / `engine_build` / `engine_propose` (Max): work on `max/*` branches and open
   draft PRs with `gh`. People review and merge on GitHub.
3. `engine_import` (Max): fetches `origin/main`, builds and tests the chosen commit **from a clean
   checkout**, then `POST /builds/upload` (a one-time storage upload URL), PUTs the jar and calls
   `POST /builds/import`.

Routes (every call carries the caller's BOSS JWT; decided with `user_has_org_permission`):

| Route | Needs | Does |
|---|---|---|
| `/repos/link` | agents.evolve | Records `owner/name`, GitHub repo id, base version, whether main is protected. Same repo refreshes; a different repo is refused once builds exist; a repo another agent links is refused. |
| `/authorize` | agents.evolve | Fresh check before each Max repo operation. Returns the repo only. |
| `/builds` | agents.view | Repo and imported builds. |
| `/builds/upload` | agents.publish | One-time upload URL under `<org>/<agent>/incoming/<uuid>.jar` (≤100 MB). Known SHA-256 returns the existing build. |
| `/builds/import` | agents.publish | Reads the upload, recomputes SHA-256 (must equal the claim), checks the jar is a Max engine whose packed source is the claimed commit and whose adapter template enforces pins (`repo-engine-pin-v1`), stores it at `<org>/<agent>/<sha256>.jar`, deletes the incoming object. |
| `/builds/url` | agents.publish | Short-lived download URL for the console's publish. |

Safety that remains: the console bundles the exact stored bytes into the adapter with their SHA-256
pin; `record_agent_version_engine` binds version → build once; `require_agent_engine_pin` refuses
unpinned publications of repo-backed agents; the provisioner writes the pin into `agents.json`; the
host and adapter refuse mismatching bytes before unloading the running version.

Trust change versus the App design: the platform no longer independently proves a build came from a
CI run on main. It trusts the publisher's own Max (an `agents.publish` holder, who could publish any
persona anyway) to build a commit of main from a clean checkout; the commit packed in the jar is
verified and shown on every version, so reviewers can audit the exact source.

Tests: `deno test --config deno.json --allow-env --allow-net tests/` here, and
`supabase test db supabase/tests/agent_engine_repos_test.sql` against a disposable local stack.
No secrets are needed beyond the standard SUPABASE_URL / anon / service-role keys.
`AGENT_GITHUB_PUBLIC_SUPABASE_URL` optionally rewrites signed URLs for local stacks.

Follow-ups: sweep `incoming/` objects that were never imported; optional CI provenance (`source = 'ci'`)
if an org later wants GitHub Actions builds.
