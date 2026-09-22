#!/usr/bin/env bash
# Thin wrapper: the scanner lives in test-action-sha-pins.rb (Ruby stdlib
# Psych, present on GitHub ubuntu runners). Kept so the CI step's
# `bash scripts/test/test-action-sha-pins.sh` invocation stays unchanged.
set -euo pipefail
exec ruby "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/test-action-sha-pins.rb" "$@"
