#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../lib/minimum-os.sh"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

assert_fails() {
  if minimum_os_json_from_file "$1" >/dev/null 2>&1; then
    echo "Expected parser failure for $1" >&2
    exit 1
  fi
}

[[ "$(minimum_os_json_from_file)" == '{}' ]]

printf '# floors\nmacos=13.0\nwindows = 10.0.19041\nlinux=6\n' > "$TMP_DIR/valid.properties"
actual="$(minimum_os_json_from_file "$TMP_DIR/valid.properties")"
jq -e '. == {macos:"13.0", windows:"10.0.19041", linux:"6"}' <<< "$actual" >/dev/null

printf 'android=14\n' > "$TMP_DIR/unknown.properties"
printf 'macos=13.x\n' > "$TMP_DIR/malformed.properties"
printf 'macos=13\nmacos=14\n' > "$TMP_DIR/duplicate.properties"
printf 'macos 13\n' > "$TMP_DIR/missing-equals.properties"
assert_fails "$TMP_DIR/unknown.properties"
assert_fails "$TMP_DIR/malformed.properties"
assert_fails "$TMP_DIR/duplicate.properties"
assert_fails "$TMP_DIR/missing-equals.properties"

echo "minimum-os parser tests passed"
