#!/usr/bin/env bash
# Repeatable decision-table tests for check-jxbrowser-release.sh.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
detector="$repo_root/.github/scripts/check-jxbrowser-release.sh"
assets_file="$repo_root/.github/chromium-required-assets.txt"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
mock_bin="$test_dir/bin"
mkdir -p "$mock_bin"

cat > "$mock_bin/curl" <<'MOCK_CURL'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"maven-metadata.xml"* ]]; then
  if [[ -n "${MOCK_METADATA+x}" ]]; then
    printf '%s\n' "$MOCK_METADATA"
  else
    printf '<metadata><versioning><release>%s</release></versioning></metadata>\n' \
      "${MOCK_VERSION:-9.4.0}"
  fi
elif [[ -n "${MOCK_MISSING_ARTIFACT:-}" && "$*" == *"/${MOCK_MISSING_ARTIFACT}/"* ]]; then
  exit 22
elif [[ "${MOCK_ARTIFACT_READY:-true}" != "true" ]]; then
  exit 22
elif [[ "$*" != *"--head"* ]]; then
  echo "Artifact probes must use HEAD" >&2
  exit 64
elif [[ "$*" == *"jxbrowser-win64-arm"* ]]; then
  echo "Optional Windows ARM64 must not block dispatch" >&2
  exit 65
fi
MOCK_CURL

cat > "$mock_bin/gh" <<'MOCK_GH'
#!/usr/bin/env bash
set -euo pipefail
case "$1 $2" in
  "api --paginate")
    [[ "${MOCK_RELEASE_API_EXIT:-0}" == "0" ]] || exit "$MOCK_RELEASE_API_EXIT"
    printf '%s\n' "${MOCK_RELEASE_PAGES:-[[]]}"
    ;;
  "run list")
    printf '%s\n' "${MOCK_RUN_LIST:-[]}"
    ;;
  *)
    echo "Unexpected mock gh call: $*" >&2
    exit 2
    ;;
esac
MOCK_GH
chmod +x "$mock_bin/curl" "$mock_bin/gh"

complete_release='{
  "id": 1,
  "tag_name": "chromium-v9.4.0",
  "draft": false,
  "assets": [
    {"name":"boss-chromium-macos-arm64.zip"},
    {"name":"boss-chromium-macos-x64.zip"},
    {"name":"boss-chromium-windows-x64.zip"},
    {"name":"boss-chromium-linux-x64.zip"},
    {"name":"boss-chromium-linux-arm64.zip"}
  ]
}'
partial_release='{
  "id": 2,
  "tag_name": "chromium-v9.4.0",
  "draft": false,
  "assets": [
    {"name":"boss-chromium-macos-arm64.zip"},
    {"name":"boss-chromium-macos-x64.zip"},
    {"name":"boss-chromium-windows-x64.zip"},
    {"name":"boss-chromium-linux-x64.zip"}
  ]
}'
draft_release='{
  "id": 3,
  "tag_name": "chromium-v9.4.0",
  "draft": true,
  "assets": []
}'
complete_pages="[[$complete_release]]"
partial_pages="[[$partial_release]]"
draft_pages="[[$draft_release]]"
public_and_draft_pages="[[$complete_release,$draft_release]]"

assert_output() {
  local output="$1"
  local expected="$2"
  if ! grep -Fqx "$expected" "$output"; then
    echo "Expected '$expected' in $output" >&2
    cat "$output" >&2
    exit 1
  fi
}

run_watch_case() {
  local name="$1"
  local expected_dispatch="$2"
  local expected_reason="$3"
  local artifact_ready="$4"
  local release_pages="$5"
  local run_list="$6"
  local output="$test_dir/$name.output"
  local log="$test_dir/$name.log"

  if ! PATH="$mock_bin:$PATH" \
    TARGET_VERSION=9.4.0 \
    MOCK_ARTIFACT_READY="$artifact_ready" \
    MOCK_RELEASE_PAGES="$release_pages" \
    MOCK_RUN_LIST="$run_list" \
    REQUIRED_ASSETS_FILE="$assets_file" \
    GITHUB_OUTPUT="$output" \
    bash "$detector" > "$log" 2>&1; then
    echo "FAILED: $name" >&2
    cat "$log" >&2
    exit 1
  fi

  assert_output "$output" "latest_version=9.4.0"
  assert_output "$output" "should_dispatch=$expected_dispatch"
  assert_output "$output" "reason=$expected_reason"
  echo "✓ $name"
}

run_preflight_case() {
  local name="$1"
  local force_rebuild="$2"
  local release_pages="$3"
  local expected_build="$4"
  local expected_mode="$5"
  local expected_reason="$6"
  local output="$test_dir/$name.output"
  local log="$test_dir/$name.log"

  if ! PATH="$mock_bin:$PATH" \
    CHECK_MODE=preflight \
    TARGET_VERSION=9.4.0 \
    FORCE_REBUILD="$force_rebuild" \
    MOCK_RELEASE_PAGES="$release_pages" \
    SUPABASE_SERVICE_ROLE_KEY=test-key \
    REQUIRED_ASSETS_FILE="$assets_file" \
    GITHUB_OUTPUT="$output" \
    bash "$detector" > "$log" 2>&1; then
    echo "FAILED: $name" >&2
    cat "$log" >&2
    exit 1
  fi

  assert_output "$output" "should_build=$expected_build"
  assert_output "$output" "release_mode=$expected_mode"
  assert_output "$output" "reason=$expected_reason"
  echo "✓ $name"
}

run_watch_case \
  published_complete false release_already_published true \
  "$complete_pages" '[]'

run_watch_case \
  published_complete_with_stale_draft false release_already_published true \
  "$public_and_draft_pages" '[]'
assert_output "$test_dir/published_complete_with_stale_draft.output" 'stale_draft=true'

run_watch_case \
  artifact_not_ready false artifact_not_ready false \
  '[[]]' '[]'
assert_output "$test_dir/artifact_not_ready.output" 'blocked_artifact=jxbrowser'

run_watch_case \
  active_run false release_run_active true \
  '[[]]' \
  '[{"displayTitle":"BOSS Chromium for JxBrowser 9.4.0","status":"in_progress","conclusion":""}]'

run_watch_case \
  release_missing true release_missing true \
  '[[]]' '[]'

run_watch_case \
  public_release_repair true release_assets_incomplete true \
  "$partial_pages" '[]'

run_watch_case \
  draft_release_repair true release_draft_incomplete true \
  "$draft_pages" '[]'

run_watch_case \
  repeated_failures false release_repeatedly_failing true \
  '[[]]' \
  '[
    {"displayTitle":"BOSS Chromium for JxBrowser 9.4.0","status":"completed","conclusion":"failure"},
    {"displayTitle":"BOSS Chromium for JxBrowser 9.4.0","status":"completed","conclusion":"cancelled"}
  ]'

metadata_output="$test_dir/metadata.output"
PATH="$mock_bin:$PATH" \
  TARGET_VERSION='' \
  MOCK_VERSION=9.4.0.1 \
  MOCK_RELEASE_PAGES='[[]]' \
  MOCK_RUN_LIST='[]' \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$metadata_output" \
  bash "$detector" > "$test_dir/metadata.log" 2>&1
assert_output "$metadata_output" 'latest_version=9.4.0.1'
echo "✓ four_component_metadata_version"

if PATH="$mock_bin:$PATH" \
  TARGET_VERSION='' \
  MOCK_METADATA='<metadata><versioning/></metadata>' \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$test_dir/missing-metadata.output" \
  bash "$detector" > "$test_dir/missing-metadata.log" 2>&1; then
  echo "Expected missing release metadata to fail" >&2
  exit 1
fi
echo "✓ missing_metadata_version"

if PATH="$mock_bin:$PATH" \
  TARGET_VERSION='9.4; echo unsafe' \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$test_dir/invalid.output" \
  bash "$detector" > "$test_dir/invalid.log" 2>&1; then
  echo "Expected invalid version to fail" >&2
  exit 1
fi
echo "✓ invalid_version"

if PATH="$mock_bin:$PATH" \
  CHECK_MODE=invalid \
  TARGET_VERSION=9.4.0 \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$test_dir/invalid-mode.output" \
  bash "$detector" > "$test_dir/invalid-mode.log" 2>&1; then
  echo "Expected an invalid check mode to fail" >&2
  exit 1
fi
echo "✓ invalid_check_mode"

if PATH="$mock_bin:$PATH" \
  TARGET_VERSION=9.4.0 \
  MOCK_RELEASE_API_EXIT=1 \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$test_dir/unreadable.output" \
  bash "$detector" > "$test_dir/unreadable.log" 2>&1; then
  echo "Expected an unreadable releases repository to fail" >&2
  exit 1
fi
echo "✓ unreadable_releases_repo"

run_preflight_case \
  preflight_complete false "$complete_pages" \
  false none release_already_published

run_preflight_case \
  preflight_force_rebuild true "$complete_pages" \
  true repair_public force_rebuild

run_preflight_case \
  preflight_public_repair false "$partial_pages" \
  true repair_public release_assets_incomplete

run_preflight_case \
  preflight_draft_repair false "$draft_pages" \
  true replace_draft release_draft_incomplete

run_preflight_case \
  preflight_new_release false '[[]]' \
  true new release_missing

if PATH="$mock_bin:$PATH" \
  CHECK_MODE=preflight \
  TARGET_VERSION=9.4.0 \
  MOCK_ARTIFACT_READY=false \
  MOCK_RELEASE_PAGES='[[]]' \
  SUPABASE_SERVICE_ROLE_KEY=test-key \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$test_dir/preflight-artifact.output" \
  bash "$detector" > "$test_dir/preflight-artifact.log" 2>&1; then
  echo "Expected preflight to reject an unavailable mandatory artifact" >&2
  exit 1
fi
echo "✓ preflight_artifact_not_ready"

if PATH="$mock_bin:$PATH" \
  CHECK_MODE=preflight \
  TARGET_VERSION=9.4.0 \
  MOCK_RELEASE_PAGES='[[]]' \
  REQUIRED_ASSETS_FILE="$assets_file" \
  GITHUB_OUTPUT="$test_dir/missing-secret.output" \
  bash "$detector" > "$test_dir/missing-secret.log" 2>&1; then
  echo "Expected a missing Supabase service key to fail preflight" >&2
  exit 1
fi
echo "✓ preflight_missing_supabase_secret"

parser_fixture="$test_dir/asset-parser.txt"
printf 'first.zip\n\nsecond.zip' > "$parser_fixture"
# shellcheck source=.github/scripts/chromium-release-utils.sh
source "$repo_root/.github/scripts/chromium-release-utils.sh"
load_chromium_required_assets "$parser_fixture"
[[ "${#CHROMIUM_REQUIRED_ASSETS[@]}" == "2" ]]
[[ "${CHROMIUM_REQUIRED_ASSETS[1]}" == "second.zip" ]]
echo "✓ blank_line_and_missing_newline_asset_list"

if verify_chromium_release_assets 'first.zip' 'Test release' > "$test_dir/verify-assets.log" 2>&1; then
  echo "Expected release asset verification to reject a missing asset" >&2
  exit 1
fi
echo "✓ missing_release_asset"


for module in jxbrowser-compose jxbrowser-swing jxbrowser-kotlin; do
  export MOCK_MISSING_ARTIFACT="$module"
  run_watch_case "missing-$module" false artifact_not_ready true '[[]]' '[]'
  assert_output "$test_dir/missing-$module.output" "blocked_artifact=$module"
  if PATH="$mock_bin:$PATH" CHECK_MODE=preflight TARGET_VERSION=9.4.0 MOCK_RELEASE_PAGES='[[]]' SUPABASE_SERVICE_ROLE_KEY=test-key REQUIRED_ASSETS_FILE="$assets_file" GITHUB_OUTPUT="$test_dir/preflight-$module.output" bash "$detector" > "$test_dir/preflight-$module.log" 2>&1; then
    echo "FAILED: preflight accepted absent $module" >&2
    exit 1
  fi
  assert_output "$test_dir/preflight-$module.output" "blocked_artifact=$module"
  echo "✓ preflight missing $module rejected"
  unset MOCK_MISSING_ARTIFACT
done

echo "All JxBrowser release automation tests passed."
