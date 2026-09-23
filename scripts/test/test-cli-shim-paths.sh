#!/usr/bin/env bash
# `boss file` and `boss workspace` must carry the caller's absolute path: the
# app resolves a deep link against its own working directory, never the
# shell's, so a relative path reached it as "File not found" and opened nothing.
# Only a fake xdg-open is ever reached; no desktop instance is launched.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
project="$scratch/project"
mkdir -p "$scratch/bin" "$project/src"
: > "$project/src/main.kt"
: > "$project/boss.workspace.json"
cat > "$scratch/bin/xdg-open" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$1" > "$OPENED"
STUB
chmod +x "$scratch/bin/xdg-open"
export OPENED="$scratch/opened"

# Run the shim from inside $1 as a user would, with the remaining arguments,
# and print the percent-decoded value the deep link carries after $prefix.
target() {
    local dir="$1"
    shift
    : > "$OPENED"
    (cd "$dir" && OSTYPE=linux-gnu PATH="$scratch/bin:$PATH" HOME="$project" bash "$root/scripts/boss" "$@")
    local link
    link="$(cat "$OPENED")"
    [[ "$link" == "$prefix"* ]]
    local encoded="${link#"$prefix"}" hex='\x'
    printf '%b\n' "${encoded//%/$hex}"
}

prefix='boss://file?path='
[[ "$(target "$project" file src/main.kt)" == "$project/src/main.kt" ]]
[[ "$(target "$project" file ./src/main.kt)" == "$project/src/main.kt" ]]
[[ "$(target "$project/src" file ../src/main.kt)" == "$project/src/main.kt" ]]
[[ "$(target "$scratch" file '~/src/main.kt')" == "$project/src/main.kt" ]]
[[ "$(target "$scratch" file "$project/src/main.kt")" == "$project/src/main.kt" ]]
# A missing file still leaves as an absolute path: reporting it is the app's job.
[[ "$(target "$project" file src/missing.kt)" == "$project/src/missing.kt" ]]

prefix='boss://workspace?config='
[[ "$(target "$project" workspace boss.workspace.json)" == "$project/boss.workspace.json" ]]
[[ "$(target "$project/src" workspace ../boss.workspace.json)" == "$project/boss.workspace.json" ]]
[[ "$(target "$scratch" workspace "$project/boss.workspace.json")" == "$project/boss.workspace.json" ]]

echo 'CLI shim path tests passed'
