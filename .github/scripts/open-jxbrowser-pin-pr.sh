#!/usr/bin/env bash
#
# Open (or update) the pull request that moves the `jxbrowser` pin in
# gradle/libs.versions.toml onto a version whose engine bundle is already
# published.
#
# The watcher builds the engine automatically but nothing bumped the pin, so the
# pin could sit a release behind a fully published engine indefinitely - 9.4.1
# was published on 2026-08-14 and bumped by hand on 2026-08-21. This closes that
# half of the loop.
#
# Deliberately NOT a step in build-chromium-branding.yml: the pin may only move
# once the engine is completely published, and the watcher already recomputes
# that state every four hours. Running here means the normal path (branding
# publishes, next watcher run opens the PR) and the catch-up path (an engine that
# was published while the pin stood still) are one code path, not two.
#
# Inputs (env):
#   VERSION            JxBrowser version whose engine is fully published (required)
#   TOML_FILE          pin location (default gradle/libs.versions.toml)
#   BASE_BRANCH        PR base (default main)
#   DRY_RUN            when true, decide and report but never push or open a PR
#   GIT_AUTHOR_NAME    commit identity (default Risa Labs)
#   GIT_AUTHOR_EMAIL   commit identity (default enterprise@risalabs.ai)
#   GH_TOKEN           token used for push and PR creation
#   GITHUB_REPOSITORY  owner/repo
#   GITHUB_OUTPUT      GitHub Actions output file (stdout outside Actions)
set -euo pipefail

TOML_FILE="${TOML_FILE:-gradle/libs.versions.toml}"
BASE_BRANCH="${BASE_BRANCH:-main}"
DRY_RUN="${DRY_RUN:-false}"
GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-Risa Labs}"
GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-enterprise@risalabs.ai}"
REPO="${GITHUB_REPOSITORY:-risa-labs-inc/BossConsole}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"

emit() { printf '%s\n' "$1" >> "$OUT"; }

# Every exit path names itself so the workflow summary can report the decision
# instead of "the step passed".
decided() {
  local action="$1" detail="${2:-}"
  echo "→ $action${detail:+: $detail}"
  emit "pin_action=$action"
  exit 0
}

required_commands=(gh jq git)
for cmd in "${required_commands[@]}"; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "ERROR: required command is unavailable: $cmd" >&2
    exit 1
  }
done

[[ -n "${VERSION:-}" ]] || {
  echo "ERROR: VERSION is required" >&2
  exit 1
}

# Anchored at both ends, and validated before VERSION reaches a branch name, a
# commit message or a jq argument. TeamDev ships three- and four-part versions
# (9.4.0, 9.4.0.1); anything else is a malformed upstream read, not a release.
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || {
  echo "ERROR: invalid JxBrowser version: $VERSION" >&2
  exit 1
}

[[ -f "$TOML_FILE" ]] || {
  echo "ERROR: version catalog is unreadable: $TOML_FILE" >&2
  exit 1
}

# `^jxbrowser = ` and nothing looser: `jxbrowser-gradle-plugin` sits on the very
# next line and tracks the Gradle plugin, which does not move with the engine.
pin_line_pattern='^jxbrowser = "'
current="$(sed -n 's/^jxbrowser = "\([^"]*\)".*/\1/p' "$TOML_FILE" | head -1)"
[[ -n "$current" ]] || {
  echo "ERROR: no jxbrowser pin found in $TOML_FILE" >&2
  exit 1
}
echo "Current pin: $current"
echo "Published engine: $VERSION"
emit "current_pin=$current"

[[ "$current" != "$VERSION" ]] || decided up_to_date "pin already on $VERSION"

# Numeric, field-by-field, and never lexical: 9.4.10 is newer than 9.4.9, and
# a string compare gets that backwards. Also the downgrade guard - a TeamDev
# metadata regression, or a backfill dispatch for an older version, must not
# walk the pin backwards.
version_gt() {
  local a="$1" b="$2" i
  local -a fa fb
  IFS='.' read -r -a fa <<< "$a"
  IFS='.' read -r -a fb <<< "$b"
  for ((i = 0; i < 4; i++)); do
    local va="${fa[i]:-0}" vb="${fb[i]:-0}"
    ((10#$va > 10#$vb)) && return 0
    ((10#$va < 10#$vb)) && return 1
  done
  return 1
}

version_gt "$VERSION" "$current" || decided not_newer "$VERSION does not supersede $current"

branch="chore/jxbrowser-$VERSION"
emit "pin_branch=$branch"

# One lookup over every state, because each state means something different:
#   open   -> the PR is already waiting; a second one would be noise
#   merged -> the pin moved and this run raced a just-merged PR
#   closed -> a human declined this bump, and reopening it every four hours
#             would override that decision
existing="$(gh pr list \
  --repo "$REPO" \
  --head "$branch" \
  --state all \
  --limit 10 \
  --json number,state,url 2>/dev/null || echo '[]')"

open_pr="$(jq -r 'first(.[] | select(.state == "OPEN") | .url) // empty' <<< "$existing")"
if [[ -n "$open_pr" ]]; then
  emit "pin_pr_url=$open_pr"
  decided pr_exists "$open_pr"
fi

closed_pr="$(jq -r 'first(.[] | select(.state == "CLOSED") | .url) // empty' <<< "$existing")"
if [[ -n "$closed_pr" ]]; then
  emit "pin_pr_url=$closed_pr"
  decided declined "$closed_pr was closed without merging"
fi

if [[ "$DRY_RUN" == "true" ]]; then
  decided would_open "$branch"
fi

# Rewrite in place, then prove it landed. A silent no-op here is the worst
# outcome available: the run goes green, the PR body claims a bump, and the diff
# is empty. The anchored pattern makes a stale-anchor miss possible in exactly
# one way (someone reformats the catalog), so it is checked rather than trusted.
git config user.name "$GIT_AUTHOR_NAME"
git config user.email "$GIT_AUTHOR_EMAIL"
git checkout -b "$branch"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
sed "s/${pin_line_pattern}[^\"]*\"/jxbrowser = \"$VERSION\"/" "$TOML_FILE" > "$tmp"
mv "$tmp" "$TOML_FILE"
trap - EXIT

rewritten="$(sed -n 's/^jxbrowser = "\([^"]*\)".*/\1/p' "$TOML_FILE" | head -1)"
[[ "$rewritten" == "$VERSION" ]] || {
  echo "ERROR: the pin rewrite did not land; $TOML_FILE still reads $rewritten" >&2
  exit 1
}
if git diff --quiet -- "$TOML_FILE"; then
  echo "ERROR: the pin rewrite produced no diff in $TOML_FILE" >&2
  exit 1
fi
# Exactly one line moved. Catches a pattern that also caught
# jxbrowser-gradle-plugin, which would ship a silent plugin downgrade.
changed="$(git diff --numstat -- "$TOML_FILE" | awk '{print $1"/"$2}')"
[[ "$changed" == "1/1" ]] || {
  echo "ERROR: expected exactly one changed line in $TOML_FILE, got $changed" >&2
  git diff -- "$TOML_FILE" >&2
  exit 1
}

git add "$TOML_FILE"
git commit -m "chore(deps): move the browser engine to JxBrowser $VERSION

The engine bundle for $VERSION is published on both sources, so the pin
can follow. Opened automatically by the JxBrowser auto-release watcher;
$current was the previous pin.

libs.versions.toml is the single source of truth here - it generates
VersionConstants.JXBROWSER_VERSION, which every engine call site reads."

git push --force-with-lease origin "$branch"

body="$(cat <<EOF
Moves the \`jxbrowser\` pin from \`$current\` to \`$VERSION\`.

The engine bundle for \`$VERSION\` is published and complete, so the pin can
follow it. Opened automatically by the JxBrowser auto-release watcher, which
builds the engine but until now left the pin bump to whoever noticed.

### Before merging

The watcher gates on the GitHub \`chromium-v$VERSION\` release carrying every
required asset. Two things it does not check, worth a glance:

- \`minimumSystemVersion\` in \`composeApp/build.gradle.kts\` tracks the engine
  bundle's macOS floor. A same-branch patch bump normally leaves it alone, but a
  Chromium branch bump has moved it before (9.4.0 took it 12.0 -> 13.0). Confirm
  with \`LSMinimumSystemVersion\` on the engine's \`BOSS.app/Contents/Info.plist\`
  plus \`LC_BUILD_VERSION\` minos on the framework and \`libtoolkit.dylib\`.
- The jar's expected Chromium build must match the engine's framework directory:
  \`unzip -p jxbrowser-$VERSION.jar com/teamdev/jxbrowser/version.info\` against
  \`Versions/\` in the bundle. A mismatch is an \`UnsatisfiedLinkError\` on first
  native load, not a build failure, so CI passing is not evidence either way.

Persisted engine pins are no longer read, so existing pins need no migration.
Developer/test overrides use the \`boss.browser.engine.version\` system property;
the native engine must still be compatible with the bundled JxBrowser library.
EOF
)"

pr_url="$(gh pr create \
  --repo "$REPO" \
  --base "$BASE_BRANCH" \
  --head "$branch" \
  --title "chore(deps): move the browser engine to JxBrowser $VERSION" \
  --body "$body")"

emit "pin_pr_url=$pr_url"
decided pr_opened "$pr_url"
