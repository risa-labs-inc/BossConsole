#!/usr/bin/env bash
# 30 parallel bundle submissions by one user must produce at most the daily cap (20) of versions: the per-user advisory lock
# in marketplace_submit_bundle serialises them, so the cap count sees every earlier commit. Needs a LOCAL, disposable
# database with the marketplace v2 migration (it commits rows and removes them at the end, except
# the append-only audit rows). Usage:
#   scripts/test/test-marketplace-submit-concurrency.sh [postgres-url]    (default: the local stack on 54322)
set -euo pipefail
DB="${1:-postgresql://postgres:postgres@127.0.0.1:54322/postgres}"
case "$DB" in *127.0.0.1*|*localhost*) ;; *) echo "refusing a non-local database" >&2; exit 2 ;; esac
USER_ID=$(uuidgen | tr 'A-Z' 'a-z')
SHA=$(printf 'a%.0s' $(seq 64))
psqlq() { psql "$DB" -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }

FLAG_BEFORE=$(psqlq -c "select value::text from public.platform_flags where key = 'marketplace_bundles'")
cleanup() {
  psqlq -c "update public.platform_flags set value = '${FLAG_BEFORE:-false}'::jsonb where key = 'marketplace_bundles'" >/dev/null 2>&1 || true
  psqlq -c "update public.marketplace_listings set live_version_id = null, pending_version_id = null where publisher_id = '$USER_ID';
            delete from public.marketplace_versions where created_by = '$USER_ID';
            delete from public.marketplace_listings where publisher_id = '$USER_ID';
            delete from auth.users where id = '$USER_ID';" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup
psqlq -c "insert into auth.users (id, email, email_confirmed_at) values ('$USER_ID', 'concurrency@mkt2.test', now());
          insert into public.platform_flags (key, value) values ('marketplace_bundles', 'true'::jsonb)
              on conflict (key) do update set value = 'true'::jsonb;" >/dev/null

submit() {
  psql "$DB" -X -q -t -A -c "select public.marketplace_submit_bundle('$USER_ID', jsonb_build_object('schema', 1, 'kind', 'skill',
      'slug', 'conc-$1', 'name', 'Conc $1', 'summary', 'One line.', 'publisher_name', 'Pub', 'skill', jsonb_build_object('entry', 'SKILL.md')),
      'bundles/$USER_ID/$SHA.zip', '$SHA', 1000)" | cut -c1-60
}
export -f submit
export DB USER_ID SHA
OUT=$(mktemp)
seq 1 30 | xargs -P 30 -I{} bash -c 'submit {}' > "$OUT" || true
accepted=$(grep -c '"status"' "$OUT" || true)
refused=$(grep -c 'submission_rate_limited' "$OUT" || true)
rows=$(psqlq -c "select count(*) from public.marketplace_versions where created_by = '$USER_ID'")
rm -f "$OUT"
echo "accepted=$accepted refused=$refused version_rows=$rows"
if [ "$rows" -le 20 ] && [ "$accepted" -eq "$rows" ] && [ $((accepted + refused)) -eq 30 ]; then
  echo "ok: at most the daily cap of 20"
else
  echo "FAIL: parallel submissions beat the cap" >&2
  exit 1
fi
