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
# The shim's urlencode must percent-encode UTF-8 bytes under every bash it may
# run in: macOS /bin/bash is 3.2 and reports high bytes as negative, which a
# bare %02X prints as 16 hex digits. Test the real function text from the shim.
awk '/^urlencode\(\) \{/{found=1} found {print} found && /^\}/{exit}' "$root/scripts/boss" > "$scratch/urlencode.sh"
. "$scratch/urlencode.sh"
[[ "$(urlencode 'R&D notes.md')" == "R%26D%20notes.md" ]]
[[ "$(urlencode 'résumé.md')" == "r%C3%A9sum%C3%A9.md" ]]
[[ "$(urlencode '中文.txt')" == "%E4%B8%AD%E6%96%87.txt" ]]
echo 'Headless launcher tests passed'
