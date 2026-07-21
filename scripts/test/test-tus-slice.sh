#!/usr/bin/env bash
#
# Unit test for lib/tus-slice.sh slice_chunk. Runs under `set -euo pipefail`
# (the same mode publish-supabase-release.sh uses) and asserts BOTH the sliced
# bytes AND the exit status — the exit-status half is what a bytes-only test
# missed, letting a SIGPIPE (141) on non-final chunks hide inside the function.
#
# Run: bash scripts/test/test-tus-slice.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/../lib/tus-slice.sh"

TD="$(mktemp -d)"; trap 'rm -rf "$TD"' EXIT
pass=0; fail=0
ok()   { echo "  ok: $1"; pass=$((pass + 1)); }
bad()  { echo "  FAIL: $1" >&2; fail=$((fail + 1)); }

# Reference file: 15 MiB + 123 B, so there are full chunks, unaligned offsets,
# and a short final chunk.
SIZE=$((15 * 1024 * 1024 + 123))
head -c "$SIZE" /dev/urandom > "$TD/f"

# expected byte length of a slice at <offset>
want_len() { local off=$1; local l=$(( SIZE - off )); (( l > CHUNK_SIZE )) && l=$CHUNK_SIZE; echo "$l"; }

# assert slice at <offset> returns 0, writes want_len bytes, and matches a
# byte-accurate reference cut.
check() {
  local off=$1 rc got want
  # Capture status WITHOUT `|| ...` so pipefail/SIGPIPE regressions surface.
  if slice_chunk "$TD/f" "$off" "$SIZE" "$TD/c"; then rc=0; else rc=$?; fi
  want="$(want_len "$off")"
  got="$(wc -c < "$TD/c" | tr -d '[:space:]')"
  dd if="$TD/f" of="$TD/ref" bs=1M skip="$off" count="$want" iflag=skip_bytes,count_bytes 2>/dev/null \
    || dd if="$TD/f" of="$TD/ref" bs=1 skip="$off" count="$want" 2>/dev/null
  if [[ "$rc" == 0 && "$got" == "$want" ]] && cmp -s "$TD/c" "$TD/ref"; then
    ok "offset $off (rc=0, $got bytes, bytes match)"
  else
    bad "offset $off (rc=$rc got=$got want=$want)"
  fi
}

check 0                              # aligned start (full chunk)
check "$CHUNK_SIZE"                  # aligned mid (full chunk)
check $(( CHUNK_SIZE + 1 ))          # UNALIGNED — non-final chunk, the SIGPIPE-prone path
check $(( 2 * CHUNK_SIZE + 4095 ))   # deep unaligned, non-final
check $(( SIZE - 50 ))               # short final chunk
check $(( SIZE - 1 ))                # 1-byte final chunk

# Short-read must FAIL (rc != 0): offset beyond EOF can't produce want>0 bytes.
if slice_chunk "$TD/f" "$SIZE" "$(( SIZE + CHUNK_SIZE ))" "$TD/c" 2>/dev/null; then
  bad "expected non-zero rc on short read (offset at EOF with positive want)"
else
  ok "short read returns non-zero"
fi

echo "slice tests: $pass passed, $fail failed"
[[ "$fail" == 0 ]]
