#!/usr/bin/env bash
#
# TUS chunk slicing for publish-supabase-release.sh, extracted into a sourceable
# unit so the real function can be tested independently of the publish flow
# (see scripts/test/test-tus-slice.sh). This file has NO side effects on source.
#
# Tooling assumption: `tail -c +N <file>` seeks to byte N for regular files and
# `head -c LEN` stops after LEN bytes — true on GNU coreutils and modern
# macOS/BSD (older BSD `head` lacked `-c`). CI runs ubuntu (GNU); local dev is
# modern macOS. Chosen over `dd` because `dd`'s efficient slicing is chunk-index
# based (skip = offset/CHUNK_SIZE) and can't address an arbitrary byte offset.

CHUNK_SIZE=$((6 * 1024 * 1024))   # 6 MiB — the only chunk size Supabase's TUS server accepts.

# slice_chunk <file> <offset> <total> <out>
# Write min(CHUNK_SIZE, total-offset) bytes of <file> starting at absolute byte
# <offset> into <out>. Byte-accurate, so a resume offset that a partially-applied
# PATCH left on a NON-chunk boundary is valid — the previous chunk-INDEX slicing
# aborted on such offsets, silently dropping the whole release from publish.
#
# Returns non-zero only if the slice is wrong (short/failed read), NOT on the
# benign SIGPIPE described below — the exit status reflects the slice so a caller
# may safely guard it with `|| return 1` without re-introducing that bug.
slice_chunk() {
  local file="$1" offset="$2" total="$3" out="$4"
  local len=$(( total - offset ))
  (( len > CHUNK_SIZE )) && len=$CHUNK_SIZE
  # head closes the pipe after LEN bytes, so on any non-final chunk `tail` is
  # still streaming and dies with SIGPIPE (exit 141). Under `set -o pipefail`
  # that would make the pipeline "fail" even though `head` wrote the full,
  # byte-correct slice. Swallow tail's status and validate the OUTPUT instead.
  { tail -c "+$(( offset + 1 ))" "$file" 2>/dev/null || true; } | head -c "$len" > "$out"
  local got; got="$(wc -c < "$out" | tr -d '[:space:]')"
  if [[ "$got" != "$len" ]]; then
    echo "ERROR: slice_chunk short read at offset $offset: got $got, want $len" >&2
    return 1
  fi
}
