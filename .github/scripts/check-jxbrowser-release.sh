#!/usr/bin/env bash
#
# Resolve JxBrowser/Chromium release state for the scheduled watcher or the
# branding workflow preflight. Both paths intentionally share this logic.
#
# Inputs (env):
#   CHECK_MODE       watch (default) or preflight
#   TARGET_VERSION   optional override in watch mode; required by preflight
#   FORCE_REBUILD    preflight-only boolean
#   SOURCE_REPO      repository containing the branding workflow
#   RELEASES_REPO    repository containing published Chromium releases
#   SOURCE_TOKEN     optional token for reading workflow runs (defaults to GH_TOKEN)
#   RELEASES_TOKEN   optional token for reading releases (defaults to GH_TOKEN)
#   REQUIRED_ASSETS_FILE newline-delimited list of required release assets
#   GITHUB_OUTPUT    GitHub Actions output file (stdout outside Actions)
set -euo pipefail

CHECK_MODE="${CHECK_MODE:-watch}"
META_URL="${META_URL:-https://europe-maven.pkg.dev/jxbrowser/releases/com/teamdev/jxbrowser/jxbrowser/maven-metadata.xml}"
ARTIFACT_BASE_URL="${ARTIFACT_BASE_URL:-https://europe-maven.pkg.dev/jxbrowser/releases/com/teamdev/jxbrowser}"
SOURCE_REPO="${SOURCE_REPO:-risa-labs-inc/BossConsole}"
RELEASES_REPO="${RELEASES_REPO:-risa-labs-inc/BossConsole-Releases}"
WORKFLOW_FILE="${WORKFLOW_FILE:-build-chromium-branding.yml}"
REQUIRED_ASSETS_FILE="${REQUIRED_ASSETS_FILE:-.github/chromium-required-assets.txt}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.github/scripts/chromium-release-utils.sh
source "$SCRIPT_DIR/chromium-release-utils.sh"

gh_source() {
  local token="${SOURCE_TOKEN:-${GH_TOKEN:-}}"
  if [[ -n "$token" ]]; then
    GH_TOKEN="$token" gh "$@"
  else
    gh "$@"
  fi
}

gh_releases() {
  local token="${RELEASES_TOKEN:-${GH_TOKEN:-}}"
  if [[ -n "$token" ]]; then
    GH_TOKEN="$token" gh "$@"
  else
    gh "$@"
  fi
}

emit() { printf '%s\n' "$1" >> "$OUT"; }
noop() {
  local reason="$1"
  echo "→ $reason"
  emit "should_dispatch=false"
  emit "reason=$reason"
  exit 0
}

probe_required_artifacts() {
  local artifact artifact_url
  # Metadata can lead platform artifacts briefly. HEAD is intentional: a range
  # request could download an entire Chromium JAR if an intermediary ignored it.
  # Windows ARM64 is optional in the release matrix, so it is not a hard gate.
  #
  # jxbrowser-compose and jxbrowser-swing are on the desktop compile classpath
  # (composeApp/build.gradle.kts), and jxbrowser-kotlin is pulled in transitively
  # by jxbrowser-compose. All three publish as their own Maven modules, so their
  # propagation can lag the platform binaries. Probing only the binaries let
  # JxBrowser 9.5.1 dispatch while those modules were still unresolvable, and the
  # branding build failed at `desktopCompileClasspath` with "Could not find
  # com.teamdev.jxbrowser:jxbrowser-compose:9.5.1" (BossConsole#667). Gate on the
  # compile-classpath modules too, not just the binaries.
  local artifacts=(
    jxbrowser
    jxbrowser-compose
    jxbrowser-swing
    jxbrowser-kotlin
    jxbrowser-linux64
    jxbrowser-linux64-arm
    jxbrowser-mac
    jxbrowser-mac-arm
    jxbrowser-win64
  )
  for artifact in "${artifacts[@]}"; do
    artifact_url="$ARTIFACT_BASE_URL/$artifact/$latest/$artifact-$latest.jar"
    if ! curl -fsSL --retry 3 --retry-all-errors --max-time 30 \
      --head --output /dev/null "$artifact_url"; then
      echo "::warning::JxBrowser $latest is in metadata, but $artifact is not resolvable yet"
      emit "blocked_artifact=$artifact"
      if [[ "$CHECK_MODE" == "watch" ]]; then
        noop "artifact_not_ready"
      fi
      echo "ERROR: required JxBrowser artifact is unavailable: $artifact" >&2
      exit 1
    fi
  done
}

case "$CHECK_MODE" in
  watch|preflight) ;;
  *)
    echo "ERROR: invalid check mode: $CHECK_MODE" >&2
    exit 1
    ;;
esac

required_commands=(gh jq curl)
for cmd in "${required_commands[@]}"; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "ERROR: required command is unavailable: $cmd" >&2
    exit 1
  }
done

load_chromium_required_assets "$REQUIRED_ASSETS_FILE"
required_assets=("${CHROMIUM_REQUIRED_ASSETS[@]}")

latest="${TARGET_VERSION:-}"
if [[ -z "$latest" && "$CHECK_MODE" == "watch" ]]; then
  metadata="$(curl -fsSL --retry 3 --retry-all-errors --max-time 30 "$META_URL")"
  latest="$(sed -n 's:.*<release>\([^<]*\)</release>.*:\1:p' <<< "$metadata")"
  latest="${latest%%$'\n'*}"
fi

[[ -n "$latest" ]] || {
  echo "ERROR: JxBrowser release version is unavailable" >&2
  exit 1
}
[[ "$latest" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || {
  echo "ERROR: invalid JxBrowser release version: $latest" >&2
  exit 1
}

emit "latest_version=$latest"
echo "JxBrowser candidate: $latest"

if [[ "$CHECK_MODE" == "preflight" && -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ]]; then
  echo "ERROR: SUPABASE_SERVICE_ROLE_KEY is required for Chromium releases" >&2
  exit 1
fi

tag="chromium-v$latest"
# REST pagination has no fixed release window and returns draft metadata to the
# cross-repository token. An unreadable repository fails this command loudly.
release_pages="$(gh_releases api \
  --paginate \
  --slurp \
  "repos/$RELEASES_REPO/releases?per_page=100")"
public_release="$(jq -c --arg tag "$tag" \
  'first(.[][] | select(.tag_name == $tag and .draft == false)) // empty' \
  <<< "$release_pages")"
draft_release="$(jq -c --arg tag "$tag" \
  'first(.[][] | select(.tag_name == $tag and .draft == true)) // empty' \
  <<< "$release_pages")"

release_state="absent"
missing_asset=""
if [[ -n "$public_release" ]]; then
  release_state="public_complete"
  for asset in "${required_assets[@]}"; do
    if ! jq -e --arg asset "$asset" \
      '.assets[]? | select(.name == $asset)' <<< "$public_release" >/dev/null; then
      release_state="public_incomplete"
      missing_asset="$asset"
      break
    fi
  done
elif [[ -n "$draft_release" ]]; then
  release_state="draft"
fi
if [[ -n "$public_release" && -n "$draft_release" ]]; then
  emit "stale_draft=true"
fi

if [[ "$CHECK_MODE" == "preflight" && "$release_state" == "public_complete" && "${FORCE_REBUILD:-false}" != "true" ]]; then
  emit "should_build=false"
  emit "release_mode=none"
  emit "reason=release_already_published"
  echo "$tag is already complete; nothing to rebuild."
  exit 0
fi
if [[ "$CHECK_MODE" == "watch" && "$release_state" == "public_complete" ]]; then
  noop "release_already_published"
fi

# Automated dispatch and manual/forced builds both fail fast while TeamDev's
# mandatory platform artifacts are still propagating.
probe_required_artifacts

if [[ "$CHECK_MODE" == "preflight" ]]; then
  case "$release_state" in
    public_complete)
      emit "should_build=true"
      emit "release_mode=repair_public"
      emit "reason=force_rebuild"
      echo "Force rebuild will replace public assets in-place after the mirror succeeds."
      ;;
    public_incomplete)
      emit "should_build=true"
      emit "release_mode=repair_public"
      emit "reason=release_assets_incomplete"
      echo "::warning::$tag is missing $missing_asset and will be repaired"
      ;;
    draft)
      emit "should_build=true"
      emit "release_mode=replace_draft"
      emit "reason=release_draft_incomplete"
      echo "::warning::$tag has an incomplete draft and will be replaced"
      ;;
    absent)
      emit "should_build=true"
      emit "release_mode=new"
      emit "reason=release_missing"
      ;;
  esac
  exit 0
fi

dispatch_reason="release_missing"
case "$release_state" in
  public_incomplete)
    dispatch_reason="release_assets_incomplete"
    echo "::warning::$tag is missing $missing_asset; a repair build is required"
    ;;
  draft)
    dispatch_reason="release_draft_incomplete"
    echo "::warning::$tag exists as a draft; a repair build is required"
    ;;
esac

# The branding workflow exposes the version in run-name, making these checks
# deterministic without downloading run logs or inspecting event payloads. The
# per-version workflow concurrency group remains the duplicate-dispatch backstop
# if an extremely busy repository ever pushes a matching run beyond this window.
run_title="BOSS Chromium for JxBrowser $latest"
runs_json="$(gh_source run list \
  --repo "$SOURCE_REPO" \
  --workflow "$WORKFLOW_FILE" \
  --limit 100 \
  --json displayTitle,status,conclusion,createdAt)"
active_status="$(jq -r --arg title "$run_title" \
  'first(.[] | select(.displayTitle == $title and .status != "completed") | .status) // empty' \
  <<< "$runs_json")"
if [[ -n "$active_status" ]]; then
  echo "Matching branding run is already $active_status"
  noop "release_run_active"
fi

# The pause below predates probe_required_artifacts: before the probe was
# added (BossConsole#678 / commit 8dafac58), Maven propagation gaps let
# runs dispatch with `jxbrowser-compose` / `-swing` / `-kotlin` still
# unresolvable, and the build failed at `desktopCompileClasspath`. The probe
# now blocks those dispatches. A failure pair that BOTH predate the most
# recent commit touching this script is by definition a pre-probe failure,
# and a probe that passes NOW means the underlying cause is gone - trust
# the probe and recover. A failure pair where one or both post-date the
# latest script change still blocks, so a real workflow bug surfaces.
# `stale_pause_recovered` is a distinct reason so the autorelease workflow's
# "Close recovered blocker issues" step closes #747 automatically.
probe_change_ts="$(git log -1 --format=%ct -- "$SCRIPT_DIR/check-jxbrowser-release.sh" 2>/dev/null || echo 0)"
recent_failure_state="$(jq -r --arg title "$run_title" '
  [.[] | select(.displayTitle == $title and .status == "completed")][0:2]
  | if length == 2 and all(.[];
      .conclusion == "failure"
      or .conclusion == "timed_out"
      or .conclusion == "startup_failure"
      or .conclusion == "cancelled")
    then {
        count: 2,
        most_recent_ts: (try ((.[0].createdAt | fromdateiso8601)) catch null)
      }
    else {count: 0}
    end
' <<< "$runs_json")"
recent_failure_count="$(jq -r '.count' <<< "$recent_failure_state")"
if [[ "$recent_failure_count" == "2" ]]; then
  most_recent_ts="$(jq -r '.most_recent_ts // empty' <<< "$recent_failure_state")"
  # Both must be present: the latest script commit timestamp (so we know the
  # probe change time) and the failure timestamp. A missing createdAt means
  # the caller is using an older API surface and the old blocking path stands.
  if [[ -n "$most_recent_ts" && "$most_recent_ts" != "null" \
        && "$probe_change_ts" -gt 0 \
        && "$most_recent_ts" -lt "$probe_change_ts" ]]; then
    echo "::notice::The two most recent $run_title runs predate the probe gate; recovering"
    emit "should_dispatch=true"
    emit "reason=stale_pause_recovered"
    echo "✓ JxBrowser $latest is ready (recovering from pre-probe failures) and requires a BOSS Chromium release"
    exit 0
  fi
  echo "::warning::The two most recent $run_title runs failed; automatic retries are paused"
  noop "release_repeatedly_failing"
fi

emit "should_dispatch=true"
emit "reason=$dispatch_reason"
echo "✓ JxBrowser $latest is ready and requires a BOSS Chromium release"
