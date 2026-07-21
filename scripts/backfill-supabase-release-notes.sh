#!/usr/bin/env bash
#
# backfill-supabase-release-notes.sh
#
# One-off repair: pushes every curated docs/release-notes/v*.md into the
# matching app_releases row, so already-published versions stop showing the
# auto-generated GitHub release body (downloads table + install steps) in the
# update dialog. Versions without a Supabase row (released before the Supabase
# catalog existed) are skipped.
#
# Usage (from the repo root):
#   SUPABASE_URL=… SUPABASE_SERVICE_ROLE_KEY=… scripts/backfill-supabase-release-notes.sh [app]

set -euo pipefail

APP="${1:-boss}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOTES_DIR="$SCRIPT_DIR/../docs/release-notes"

: "${SUPABASE_URL:?SUPABASE_URL must be set}"
: "${SUPABASE_SERVICE_ROLE_KEY:?SUPABASE_SERVICE_ROLE_KEY must be set}"

updated=0
skipped=0

shopt -s nullglob
for file in "$NOTES_DIR"/v*.md; do
  # Only version-named files (skips e.g. vkeylocker-tools.md and README.md).
  [[ "$(basename "$file")" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]] || continue
  version="$(basename "$file" .md)"
  version="${version#v}"
  rc=0
  SYNC_MAX_ATTEMPTS=1 "$SCRIPT_DIR/sync-supabase-release-notes.sh" "$APP" "$version" "$file" || rc=$?
  case "$rc" in
    0) updated=$((updated + 1)) ;;
    # Exit 3 = row genuinely absent (version predates the Supabase catalog).
    3) echo "  (no row for $APP $version — skipped)"; skipped=$((skipped + 1)) ;;
    # Anything else (auth, RLS, network) would hit every version — abort
    # loudly instead of reporting a wall of "skipped" as a clean run.
    *) echo "ERROR: aborting backfill on hard failure (exit $rc) — fix the error above and re-run" >&2
       exit "$rc" ;;
  esac
done

echo "Backfill complete: $updated updated, $skipped skipped."
