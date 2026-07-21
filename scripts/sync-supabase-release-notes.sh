#!/usr/bin/env bash
#
# sync-supabase-release-notes.sh
#
# Points the update dialog's "What's new" at the curated release notes: PATCHes
# the `release_notes` column of the app_releases row for (app, version) with
# the contents of a markdown file (normally docs/release-notes/v<version>.md).
#
# The row itself is created by publish-supabase-release.sh (sync-release.yml)
# only after every installer has been uploaded to Storage, which can take
# several minutes — and the release-notes workflow runs in parallel with that.
# So a missing row is retried rather than treated as fatal, until the attempt
# budget runs out.
#
# Usage:
#   sync-supabase-release-notes.sh <app> <version> <notes_file>
#
# Example:
#   sync-supabase-release-notes.sh boss 9.2.28 docs/release-notes/v9.2.28.md
#
# Required environment:
#   SUPABASE_URL                e.g. https://api.risaboss.com
#   SUPABASE_SERVICE_ROLE_KEY   service-role key (CI secret; bypasses RLS). Never ship in the client.
#
# Optional environment:
#   SYNC_MAX_ATTEMPTS   how many times to look for the row (default 20)
#   SYNC_RETRY_DELAY    seconds between attempts (default 30)
#
# Exit codes:
#   0  notes written
#   3  row still missing after the attempt budget (retried; not an HTTP error)
#   1  hard failure — HTTP 4xx (bad key, RLS, malformed request) fails fast
#      without retrying; 5xx/transport errors retry (Cloudflare fronts this
#      Supabase instance and transient 502/503s are a known reality) and only
#      exit 1 once the budget is exhausted

set -euo pipefail

APP="${1:?usage: sync-supabase-release-notes.sh <app> <version> <notes_file>}"
VERSION="${2:?missing version}"
NOTES_FILE="${3:?missing notes_file}"

: "${SUPABASE_URL:?SUPABASE_URL must be set}"
: "${SUPABASE_SERVICE_ROLE_KEY:?SUPABASE_SERVICE_ROLE_KEY must be set}"

SUPABASE_URL="${SUPABASE_URL%/}"  # strip trailing slash
MAX_ATTEMPTS="${SYNC_MAX_ATTEMPTS:-20}"
RETRY_DELAY="${SYNC_RETRY_DELAY:-30}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required" >&2; exit 1; }
[[ -f "$NOTES_FILE" ]] || { echo "ERROR: notes file not found: $NOTES_FILE" >&2; exit 1; }

payload="$(jq -nc --rawfile notes "$NOTES_FILE" '{release_notes: $notes}')"

# URL-encode the filter values: a version with '+build' metadata would
# otherwise corrupt the PostgREST query and silently match nothing.
app_param="$(jq -rn --arg v "$APP" '$v|@uri')"
version_param="$(jq -rn --arg v "$VERSION" '$v|@uri')"
url="$SUPABASE_URL/rest/v1/app_releases?app=eq.$app_param&version=eq.$version_param"

last_kind="transient"
for (( attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ )); do
  # return=representation so the response tells us whether a row matched:
  # PATCH on a missing row is HTTP 200 with an empty array, not an error.
  # -w appends the status on its own line so HTTP errors (a 401 body is a
  # JSON object, not an array) are distinguishable from "row not there yet".
  response="$(curl -sS -w $'\n%{http_code}' -X PATCH "$url" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
    -H "Content-Type: application/json" \
    -H "Prefer: return=representation" \
    --data "$payload")" || response=""
  code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  case "$code" in
    2*)
      updated="$(jq 'if type == "array" then length else 0 end' <<<"${body:-null}" 2>/dev/null || echo 0)"
      if [[ "$updated" -ge 1 ]]; then
        echo "Updated release_notes for $APP $VERSION from $NOTES_FILE"
        exit 0
      fi
      last_kind="missing"
      reason="no app_releases row for $APP $VERSION yet"
      ;;
    4*)
      # Deterministic client error (bad key, RLS, malformed request):
      # retrying can't help, so fail fast instead of burning the budget.
      echo "ERROR: app_releases PATCH for $APP $VERSION failed (HTTP $code): $body" >&2
      exit 1
      ;;
    *)
      # 5xx or transport error — transient, retry.
      last_kind="transient"
      reason="transient failure (HTTP ${code:-none})"
      ;;
  esac
  if (( attempt < MAX_ATTEMPTS )); then
    echo "  $reason (attempt $attempt/$MAX_ATTEMPTS); retrying in ${RETRY_DELAY}s…"
    sleep "$RETRY_DELAY"
  fi
done

if [[ "$last_kind" == "missing" ]]; then
  echo "ERROR: no app_releases row for $APP $VERSION after $MAX_ATTEMPTS attempt(s)" >&2
  exit 3
fi
echo "ERROR: app_releases PATCH for $APP $VERSION kept failing transiently after $MAX_ATTEMPTS attempt(s) (last HTTP ${code:-none})" >&2
exit 1
