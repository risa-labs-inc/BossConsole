#!/usr/bin/env bash

# Render a properties file such as `macos=13.0` as a compact app_releases
# min_os JSON object. An omitted path represents a legacy publisher and yields {}.
minimum_os_json_from_file() {
  local path="${1:-}"
  if [[ -z "$path" ]]; then
    printf '{}\n'
    return 0
  fi
  if [[ ! -f "$path" ]]; then
    echo "ERROR: minimum OS properties file not found: $path" >&2
    return 1
  fi

  local json='{}' seen='|' raw line key value
  while IFS= read -r raw || [[ -n "$raw" ]]; do
    line="${raw%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ "$line" != *=* ]]; then
      echo "ERROR: malformed minimum OS entry: $line" >&2
      return 1
    fi

    key="${line%%=*}"
    value="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"

    case "$key" in
      macos|windows|linux) ;;
      *) echo "ERROR: unknown minimum OS key: $key" >&2; return 1 ;;
    esac
    if [[ "$seen" == *"|$key|"* ]]; then
      echo "ERROR: duplicate minimum OS key: $key" >&2
      return 1
    fi
    if [[ ! "$value" =~ ^[0-9]+(\.[0-9]+){0,3}$ ]]; then
      echo "ERROR: malformed minimum OS version for $key: $value" >&2
      return 1
    fi

    json="$(jq -cn --argjson document "$json" --arg key "$key" --arg value "$value" \
      '$document + {($key): $value}')" || return 1
    seen="${seen}${key}|"
  done < "$path"

  printf '%s\n' "$json"
}
