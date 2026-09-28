#!/usr/bin/env bash
# `boss file` and `boss workspace` must carry the caller's absolute path.
#
# The app resolves a deep link against its own working directory, never the shell's
# (CLICommandHandler does File(path).absoluteFile), so a relative path reached it as
# "File not found" and opened nothing. `folder` and the bare-argument route already
# resolved; these two did not.
#
# Only a fake xdg-open is ever reached, so no desktop instance is launched.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# A directory component with a space and one with an ampersand, because those are
# the characters that break a shim by being passed unquoted somewhere, and every
# path below inherits both.
project="$scratch/a project/r&d"
mkdir -p "$project/src" "$scratch/bin"
: > "$project/src/main.kt"
: > "$project/boss.workspace.json"
: > "$project/-dash.kt"
: > "$project/we&ird.kt"
: > "$project/~notes.md"

cat > "$scratch/bin/xdg-open" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$1" > "$OPENED"
STUB
chmod +x "$scratch/bin/xdg-open"
export OPENED="$scratch/opened"

failures=0

# Print both sides on failure. A bare `[[ ... ]]` under `set -e` exits with a status
# and nothing else, so a CI failure would not say which assertion went or what it got.
assert_eq() {
    local expected="$1" actual="$2" label="$3"
    if [ "$expected" != "$actual" ]; then
        printf 'FAIL: %s\n  expected: %s\n  actual:   %s\n' "$label" "$expected" "$actual" >&2
        failures=$((failures + 1))
    fi
}

# Run the shim from inside $1 as a user would and print the percent-decoded value
# its deep link carries after $prefix.
#
# OSTYPE is forced because bash sets it with `set_if_not`, so an inherited value
# wins - which is what lets the Linux branch of open_url run under any bash. Do not
# "clean this up"; without it the shim refuses to run off Linux and macOS.
target() {
    local dir="$1"
    shift
    : > "$OPENED"
    (cd "$dir" && OSTYPE=linux-gnu PATH="$scratch/bin:$PATH" HOME="$project" bash "$root/scripts/boss" "$@")
    local link encoded hex='\x'
    link="$(cat "$OPENED")"
    if [[ "$link" != "$prefix"* ]]; then
        printf 'FAIL: link did not start with %s\n  actual: %s\n' "$prefix" "$link" >&2
        failures=$((failures + 1))
        return
    fi
    encoded="${link#"$prefix"}"
    printf '%b' "${encoded//%/$hex}"
}

prefix='boss://file?path='
assert_eq "$project/src/main.kt" "$(target "$project" file src/main.kt)" 'file, relative'
assert_eq "$project/src/main.kt" "$(target "$project" file ./src/main.kt)" 'file, dot-slash'
assert_eq "$project/src/main.kt" "$(target "$project/src" file ../src/main.kt)" 'file, dot-dot'
# HOME here contains an ampersand, which is the point: bash 5.2 reads `&` in a
# ${var/pat/rep} replacement as the matched text, so expanding the tilde that way
# corrupted the path. A literal `~notes.md` in the cwd must not be touched at all.
assert_eq "$project/src/main.kt" "$(target "$scratch" file '~/src/main.kt')" 'file, tilde (HOME contains &)'
assert_eq "$project/~notes.md" "$(target "$project" file './~notes.md')" 'file, dot-slash tilde name'
assert_eq "$project/~notes.md" "$(target "$project" file '~notes.md')" 'file, bare tilde name is not a home ref'
assert_eq "$project/src/main.kt" "$(target "$scratch" file "$project/src/main.kt")" 'file, already absolute'
assert_eq "$project/we&ird.kt" "$(target "$project" file 'we&ird.kt')" 'file, ampersand in name'
assert_eq "$project/-dash.kt" "$(target "$project" file ./-dash.kt)" 'file, leading dash in name'
# A missing file still leaves as an absolute path: reporting it is the app's job.
assert_eq "$project/src/missing.kt" "$(target "$project" file src/missing.kt)" 'file, missing but parent exists'
# Nothing to resolve against, so the `..` survives. The app rejects it at
# isValidPath, which is the correct outcome for a path that cannot exist.
assert_eq "$project/nope/../missing.kt" "$(target "$project" file nope/../missing.kt)" 'file, missing parent'

# These three pin shim output only. The app's workspaceLinkCommand reads `path` while
# every shim sends `config`, so `boss workspace` queues nothing today whatever path it
# carries (#527). Do not "fix" the parameter on one side alone.
prefix='boss://workspace?config='
assert_eq "$project/boss.workspace.json" "$(target "$project" workspace boss.workspace.json)" 'workspace, relative'
assert_eq "$project/boss.workspace.json" "$(target "$project/src" workspace ../boss.workspace.json)" 'workspace, dot-dot'
assert_eq "$project/boss.workspace.json" "$(target "$scratch" workspace "$project/boss.workspace.json")" 'workspace, already absolute'

# `folder` shares the helper now. Before, its expansion was inline and skipped the
# collapse for an absolute input, so these two disagreed about the same path.
prefix='boss://folder?path='
assert_eq "$project/src" "$(target "$project" folder src)" 'folder, relative'
assert_eq "$project/src" "$(target "$project" folder "$project/src/../src")" 'folder, absolute with dot-dot'
assert_eq "$project" "$(target "$scratch" folder '~')" 'folder, tilde'

# The bare-argument route shares the helper too, so `boss <path>` must agree with
# `boss file <path>` and `boss folder <path>` about the same input.
prefix='boss://file?path='
assert_eq "$project/src/main.kt" "$(target "$project" src/main.kt)" 'bare argument, file'
prefix='boss://folder?path='
assert_eq "$project/src" "$(target "$project" src)" 'bare argument, directory'

# Regression guard: `url` does not touch the path helper and must be unchanged.
prefix='boss://url?url='
assert_eq 'https://github.com' "$(target "$project" url https://github.com)" 'url, unchanged'

if [ "$failures" -ne 0 ]; then
    printf '%s assertion(s) failed\n' "$failures" >&2
    exit 1
fi
echo 'CLI shim path tests passed'
