#!/usr/bin/env bash
#
# publish-supabase-release.sh
#
# Publishes a desktop-app release to Supabase so clients can update via the
# Supabase-primary path (with GitHub as the automatic backup):
#   1. uploads each platform binary to Supabase Storage
#         <bucket>/<app>/<version>/<asset>
#   2. upserts a row into the `app_releases` table (which fires Supabase Realtime,
#      pushing the new release to every running client).
#
# Large installers (the macOS .dmg is well over 100 MB) exceed Cloudflare's
# ~100 MB single-request body limit — Cloudflare fronts Supabase and rejects the
# upload with HTTP 413 before it reaches Storage. Such assets are uploaded via
# the TUS resumable protocol in 6 MiB chunks instead, so no single request is
# too large.
#
# Usage:
#   publish-supabase-release.sh <app> <version> <channel> <asset_dir> <bucket>
#
# Example:
#   publish-supabase-release.sh boss 9.2.17 stable release-assets app-releases
#
# Also used for the BOSS-branded Chromium engine archives (app id "boss-chromium",
# version = JxBrowser version, .zip assets) — published by build-chromium-branding.yml
# and consumed by the client's ChromiumReleaseSource:
#   publish-supabase-release.sh boss-chromium 9.1.2 stable chromium-assets app-releases
#
# Required environment:
#   SUPABASE_URL                e.g. https://api.risaboss.com
#   SUPABASE_SERVICE_ROLE_KEY   service-role key (CI secret; bypasses RLS). Never ship in the client.
#
# Only files with known asset extensions (.dmg .msi .deb .rpm .jar .zip) in
# <asset_dir> are uploaded. Re-running for the same (app, version) upserts.

set -euo pipefail

APP="${1:?usage: publish-supabase-release.sh <app> <version> <channel> <asset_dir> <bucket>}"
VERSION="${2:?missing version}"
CHANNEL="${3:?missing channel}"
ASSET_DIR="${4:?missing asset_dir}"
BUCKET="${5:?missing bucket}"

: "${SUPABASE_URL:?SUPABASE_URL must be set}"
: "${SUPABASE_SERVICE_ROLE_KEY:?SUPABASE_SERVICE_ROLE_KEY must be set}"

SUPABASE_URL="${SUPABASE_URL%/}"  # strip trailing slash
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required" >&2; exit 1; }

# Scratch space for resumable-upload chunks; cleaned up on exit.
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

content_type_of() {
  case "$1" in
    *.dmg) echo "application/x-apple-diskimage" ;;
    *.msi) echo "application/x-msi" ;;
    *.deb) echo "application/vnd.debian.binary-package" ;;
    *.rpm) echo "application/x-rpm" ;;
    *.jar) echo "application/java-archive" ;;
    *.zip) echo "application/zip" ;;
    *)     echo "application/octet-stream" ;;
  esac
}

# --- Storage upload -----------------------------------------------------------
# Cloudflare fronts Supabase and rejects any single request body over ~100 MB
# with HTTP 413, so files above the threshold are streamed via the TUS resumable
# endpoint in fixed 6 MiB chunks. Everything else uses the simpler single POST.
# CHUNK_SIZE + slice_chunk live in lib/tus-slice.sh (sourced here) so the
# byte-slicing is unit-tested independently — see scripts/test/test-tus-slice.sh.
source "$(dirname "${BASH_SOURCE[0]}")/lib/tus-slice.sh"
RESUMABLE_THRESHOLD=$((50 * 1024 * 1024))  # Anything above 50 MB goes resumable (well under the 100 MB cap).
MAX_ATTEMPTS=4                             # Tries (1 + 3 retries) per create/PATCH, so a transient blip on any
RETRY_BACKOFF=2                            # of the ~N requests doesn't abandon the whole upload. Backoff = N*attempt s.

b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

# Last HTTP status code from a curl `-D -` header dump (handles 100-continue etc.).
http_status_of() { awk 'toupper($1) ~ /^HTTP/ {c=$2} END{print c}'; }
# First value of a (case-insensitive) header from a curl `-D -` header dump.
header_value_of() { tr -d '\r' | awk -v k="$(printf '%s' "$1" | tr 'A-Z' 'a-z'):" 'tolower($1)==k {print $2; exit}'; }

# upload_single <file> <object_path> <content_type> — plain single-request upload.
upload_single() {
  local file="$1" object_path="$2" ctype="$3" code
  code="$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "$SUPABASE_URL/storage/v1/object/$BUCKET/$object_path" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
    -H "Content-Type: $ctype" \
    -H "x-upsert: true" \
    --data-binary "@$file")" || true
  if [[ "$code" != "200" ]]; then
    echo "ERROR: storage upload failed for $(basename "$object_path") (HTTP ${code:-none})" >&2
    return 1
  fi
}

# tus_head_offset <upload_url> — prints the server's current Upload-Offset, or fails.
# Lets a retry resume from the true offset when a failed PATCH may have partially applied.
tus_head_offset() {
  local loc="$1" hdrs code
  # -I (HEAD) avoids the `-X HEAD` body-wait footgun.
  hdrs="$(curl -sS -I "$loc" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
    -H "Tus-Resumable: 1.0.0")" || return 1
  code="$(printf '%s\n' "$hdrs" | http_status_of)"
  [[ "$code" == "200" || "$code" == "204" ]] || return 1
  printf '%s\n' "$hdrs" | header_value_of Upload-Offset
}

# upload_resumable <file> <object_path> <content_type> <size> — chunked TUS upload for large files.
# create and every chunk are retried with backoff; on retry the true server offset is re-read
# (via HEAD) and the upload resumes from there, so no single blip abandons the whole transfer.
upload_resumable() {
  local file="$1" object_path="$2" ctype="$3" total="$4"
  local meta="bucketName $(b64 "$BUCKET"),objectName $(b64 "$object_path"),contentType $(b64 "$ctype"),cacheControl $(b64 '3600')"
  local body="$TMP_DIR/resp_body" chunk="$TMP_DIR/chunk.bin" attempt

  # Re-publishing the same (app, version): when an object already exists at the
  # key, the fresh tus upload's first PATCH 409s at offset 0, which is how the
  # 9.3.0 engine re-publish died. Deleting the object first clears the conflict.
  #
  # This DELETE is deliberately non-atomic: there's a brief window with no object
  # on Supabase, and if the ensuing upload fails the object is gone rather than
  # left stale-but-valid. Accepted because it only affects RE-publishes and the
  # GitHub release remains the backup download source (clients fall back to it),
  # so the blast radius is small. (DELETE of a missing object → harmless 404.)
  #
  # No metadata salting is needed to avoid a stale-upload-id collision: Supabase
  # mints a fresh upload-id version UUID on every create
  # (https://supabase.com/blog/storage-v3-resumable-uploads), so the id is
  # already unique per run — a nonce in Upload-Metadata would be a no-op.
  curl -sS -o /dev/null \
    -X DELETE "$SUPABASE_URL/storage/v1/object/$BUCKET/$object_path" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" || true

  # 1) Create the upload; capture its per-upload Location URL. x-upsert overwrites on re-runs.
  local create_hdrs code=""
  for (( attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ )); do
    create_hdrs="$(curl -sS -D - -o "$body" \
      -X POST "$SUPABASE_URL/storage/v1/upload/resumable" \
      -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
      -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
      -H "Tus-Resumable: 1.0.0" \
      -H "Upload-Length: $total" \
      -H "Upload-Metadata: $meta" \
      -H "x-upsert: true")" || true
    code="$(printf '%s\n' "$create_hdrs" | http_status_of)"
    [[ "$code" == "201" ]] && break
    if (( attempt == MAX_ATTEMPTS )); then
      echo "ERROR: resumable create failed for $object_path after $MAX_ATTEMPTS attempts (last HTTP ${code:-none})" >&2
      printf '%s\n' "$create_hdrs" >&2
      cat "$body" >&2 2>/dev/null || true   # echo error body (e.g. a file_size_limit 400) so limit failures are diagnosable
      return 1
    fi
    echo "     resumable create failed (HTTP ${code:-none}); retry $attempt/$((MAX_ATTEMPTS - 1))…" >&2
    sleep $(( RETRY_BACKOFF * attempt ))
  done

  local location; location="$(printf '%s\n' "$create_hdrs" | header_value_of Location)"
  case "$location" in
    http*) : ;;
    /*)    location="$SUPABASE_URL$location" ;;
    "")    echo "ERROR: resumable create for $object_path returned no Location header" >&2; return 1 ;;
    *)     location="$SUPABASE_URL/storage/v1/upload/resumable/$location" ;;
  esac

  # 2) PATCH successive chunks (up to CHUNK_SIZE each) until the server-reported
  # offset reaches EOF. The server's Upload-Offset is the single source of truth
  # for where to resume; because a partially-applied PATCH can leave that offset
  # on a non-chunk boundary, chunks are sliced by absolute byte offset
  # ([slice_chunk]) so ANY resumed offset is valid.
  local offset=0
  while [[ "$offset" -lt "$total" ]]; do
    slice_chunk "$file" "$offset" "$total" "$chunk" || return 1

    local newoff=""
    for (( attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ )); do
      local patch_hdrs pcode
      patch_hdrs="$(curl -sS -D - -o "$body" \
        -X PATCH "$location" \
        -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
        -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
        -H "Tus-Resumable: 1.0.0" \
        -H "Content-Type: application/offset+octet-stream" \
        -H "Upload-Offset: $offset" \
        --data-binary "@$chunk")" || true
      pcode="$(printf '%s\n' "$patch_hdrs" | http_status_of)"
      if [[ "$pcode" == "204" ]]; then
        newoff="$(printf '%s\n' "$patch_hdrs" | header_value_of Upload-Offset)"
        [[ -n "$newoff" && "$newoff" -gt "$offset" ]] && break
      fi
      if (( attempt == MAX_ATTEMPTS )); then
        echo "ERROR: resumable chunk upload for $object_path failed at offset $offset after $MAX_ATTEMPTS attempts (last HTTP ${pcode:-none})" >&2
        printf '%s\n' "$patch_hdrs" >&2
        cat "$body" >&2 2>/dev/null || true
        return 1
      fi
      echo "     chunk at offset $offset failed (HTTP ${pcode:-none}); retry $attempt/$((MAX_ATTEMPTS - 1)) after re-syncing offset…" >&2
      sleep $(( RETRY_BACKOFF * attempt ))
      # The failed PATCH may have partially applied — re-read the true offset and
      # re-slice from there. Any byte offset is valid (see slice_chunk).
      local synced; synced="$(tus_head_offset "$location")" || synced=""
      if [[ -n "$synced" && "$synced" -ge "$offset" ]]; then
        offset="$synced"
        (( offset >= total )) && { newoff="$offset"; break; }
        slice_chunk "$file" "$offset" "$total" "$chunk" || return 1
      fi
    done
    offset="$newoff"
  done
}

# prerelease = anything not on the stable channel, or a version with a pre-release suffix.
PRERELEASE="false"
if [[ "$CHANNEL" != "stable" || "$VERSION" == *-* ]]; then
  PRERELEASE="true"
fi

echo "Publishing $APP $VERSION (channel=$CHANNEL, prerelease=$PRERELEASE) to Supabase bucket '$BUCKET'"

assets_json="[]"
uploaded=0

shopt -s nullglob
for file in "$ASSET_DIR"/*.dmg "$ASSET_DIR"/*.msi "$ASSET_DIR"/*.deb "$ASSET_DIR"/*.rpm "$ASSET_DIR"/*.jar "$ASSET_DIR"/*.zip; do
  [[ -f "$file" ]] || continue
  name="$(basename "$file")"
  size="$(wc -c < "$file" | tr -d '[:space:]')"
  sha="$(sha256_of "$file")"
  ctype="$(content_type_of "$name")"
  object_path="$APP/$VERSION/$name"

  echo "  -> uploading $name ($size bytes, sha256=${sha:0:12}…)"
  # Files above the threshold would exceed Cloudflare's ~100 MB single-request
  # limit, so upload them in chunks via the resumable (TUS) endpoint instead.
  if [[ "$size" -gt "$RESUMABLE_THRESHOLD" ]]; then
    echo "     ($((size / 1024 / 1024)) MB > $((RESUMABLE_THRESHOLD / 1024 / 1024)) MB threshold — using resumable upload)"
    upload_resumable "$file" "$object_path" "$ctype" "$size" || exit 1
  else
    upload_single "$file" "$object_path" "$ctype" || exit 1
  fi

  public_url="$SUPABASE_URL/storage/v1/object/public/$BUCKET/$object_path"
  assets_json="$(jq -c \
    --arg name "$name" --arg url "$public_url" --argjson size "$size" --arg sha "$sha" \
    '. + [{name: $name, url: $url, size: $size, sha256: $sha}]' <<<"$assets_json")"
  uploaded=$((uploaded + 1))
done

if [[ "$uploaded" -eq 0 ]]; then
  echo "ERROR: no release assets (.dmg/.msi/.deb/.rpm/.jar/.zip) found in '$ASSET_DIR'" >&2
  exit 1
fi

# Optional release notes from a file if present (the release workflow writes release-body.txt).
notes=""
if [[ -f "$ASSET_DIR/release-body.txt" ]]; then
  notes="$(cat "$ASSET_DIR/release-body.txt")"
fi

row="$(jq -nc \
  --arg app "$APP" --arg version "$VERSION" --arg channel "$CHANNEL" \
  --argjson prerelease "$PRERELEASE" --arg notes "$notes" --argjson assets "$assets_json" \
  '{app: $app, version: $version, channel: $channel, prerelease: $prerelease, release_notes: $notes, assets: $assets}')"

echo "Upserting app_releases row for $APP $VERSION ($uploaded asset(s))"
# on_conflict is required: merge-duplicates alone resolves only against the
# primary key, so a re-publish of the same (app, version) 409s on the
# app_releases_app_version_key unique constraint (23505) — which is how the
# second 9.3.0 engine publish died after its asset uploads succeeded.
http_code="$(curl -sS -o "$TMP_DIR/app_releases_resp.txt" -w '%{http_code}' \
  -X POST "$SUPABASE_URL/rest/v1/app_releases?on_conflict=app,version" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
  -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Content-Type: application/json" \
  -H "Prefer: resolution=merge-duplicates,return=minimal" \
  --data "$row")"
if [[ "$http_code" != "200" && "$http_code" != "201" && "$http_code" != "204" ]]; then
  echo "ERROR: app_releases upsert failed (HTTP $http_code): $(cat "$TMP_DIR/app_releases_resp.txt")" >&2
  exit 1
fi

# Post-publish assertion: read the row back so "published" is proven, not
# assumed. This is what the in-app updater (latest-release edge fn) actually
# reads, so if the row is somehow absent — a merge-duplicates upsert that
# silently no-ops, an earlier asset upload that aborted before this point —
# the release would be invisible to clients with no obvious signal. Fail loud.
# -G + --data-urlencode so a version with URL-unsafe chars (e.g. a +build.meta
# semver suffix, where '+' would otherwise decode to space) filters correctly.
verify_code="$(curl -sS -G -o "$TMP_DIR/verify.txt" -w '%{http_code}' \
  "$SUPABASE_URL/rest/v1/app_releases" \
  --data-urlencode "app=eq.$APP" \
  --data-urlencode "version=eq.$VERSION" \
  --data-urlencode "select=version" \
  --data-urlencode "limit=1" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
  -H "apikey: $SUPABASE_SERVICE_ROLE_KEY")"
# The query already filters by version, so a non-empty array = the row exists.
# jq (a hard dep) parses it precisely rather than regex-matching the version.
if [[ "$verify_code" != "200" ]] || ! jq -e 'type == "array" and length > 0' "$TMP_DIR/verify.txt" >/dev/null 2>&1; then
  echo "ERROR: post-publish check failed — app_releases has no queryable row for $APP $VERSION (HTTP $verify_code): $(cat "$TMP_DIR/verify.txt")" >&2
  exit 1
fi

echo "Published $APP $VERSION to Supabase (Realtime will notify connected clients)."
