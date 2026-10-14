#!/usr/bin/env bash
# Exercise only a fake executable: never launch or contact a desktop instance.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cat > "$scratch/fake-boss" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@"
exit 7
STUB
chmod +x "$scratch/fake-boss"
status=0
BOSS_BIN="$scratch/fake-boss" bash "$root/scripts/boss" mcp invoke test --args '{"text":"a b"}' > "$scratch/out" 2> "$scratch/err" || status=$?
[[ "$status" == 7 ]]
printf '%s\n' mcp invoke test --args '{"text":"a b"}' > "$scratch/expected"
cmp "$scratch/expected" "$scratch/out"
[[ ! -s "$scratch/err" ]]
status=0
BOSS_BIN='' OSTYPE=unknown bash "$root/scripts/boss" status > "$scratch/out" 2> "$scratch/err" || status=$?
[[ "$status" == 1 ]]
[[ ! -s "$scratch/out" ]]
grep -q 'binary not found' "$scratch/err"
status=0
BOSS_BIN="$scratch/missing" bash "$root/scripts/boss" status > "$scratch/out" 2> "$scratch/err" || status=$?
[[ "$status" == 1 ]]
[[ ! -s "$scratch/out" ]]
grep -q 'binary not found' "$scratch/err"
echo 'Headless launcher tests passed'
