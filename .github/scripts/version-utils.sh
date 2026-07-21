#!/usr/bin/env bash
# Shared version validation utilities for GitHub Actions workflows
# Source this file to use the validation functions

# =============================================================================
# Version Pattern Constants
# =============================================================================

# Prerelease types in order of precedence
readonly VERSION_PRERELEASE_TYPES="alpha|beta|rc"

# Pattern for prerelease suffix only (e.g., "beta.1", "rc.2")
readonly VERSION_PRERELEASE_SUFFIX_PATTERN="^(${VERSION_PRERELEASE_TYPES})\\.[1-9][0-9]*$"

# Pattern for full version with optional prerelease (e.g., "8.16.10", "8.16.10-beta.1")
readonly VERSION_FULL_PATTERN="^[0-9]+\\.[0-9]+\\.[0-9]+(-(${VERSION_PRERELEASE_TYPES})\\.[0-9]+)?$"

# Pattern for full version with required prerelease (e.g., "8.16.10-beta.1")
readonly VERSION_PRERELEASE_PATTERN="^[0-9]+\\.[0-9]+\\.[0-9]+-(${VERSION_PRERELEASE_TYPES})\\.[0-9]+$"

# Pattern for tag with optional 'v' prefix and optional prerelease
readonly VERSION_TAG_PATTERN="^v?[0-9]+\\.[0-9]+\\.[0-9]+(-(${VERSION_PRERELEASE_TYPES})\\.[0-9]+)?$"

# Pattern for tag with required prerelease
readonly VERSION_TAG_PRERELEASE_PATTERN="^v?[0-9]+\\.[0-9]+\\.[0-9]+-(${VERSION_PRERELEASE_TYPES})\\.[0-9]+$"

# =============================================================================
# Validation Functions
# =============================================================================

# Validates a version tag format (with optional 'v' prefix)
# Usage: validate_version_tag "v8.16.10-beta.1"
# Returns: 0 if valid, 1 if invalid
validate_version_tag() {
    local tag="$1"
    if [[ "$tag" =~ $VERSION_TAG_PATTERN ]]; then
        return 0
    fi
    return 1
}

# Validates that a tag is a prerelease version
# Usage: validate_prerelease_tag "v8.16.10-beta.1"
# Returns: 0 if valid prerelease, 1 if not
validate_prerelease_tag() {
    local tag="$1"
    if [[ "$tag" =~ $VERSION_TAG_PRERELEASE_PATTERN ]]; then
        return 0
    fi
    return 1
}

# Validates a version string (without 'v' prefix)
# Usage: validate_version "8.16.10" or "8.16.10-beta.1"
# Returns: 0 if valid, 1 if invalid
validate_version() {
    local version="$1"
    if [[ "$version" =~ $VERSION_FULL_PATTERN ]]; then
        return 0
    fi
    return 1
}

# Validates a prerelease suffix
# Usage: validate_prerelease_suffix "beta.1"
# Returns: 0 if valid, 1 if invalid
validate_prerelease_suffix() {
    local suffix="$1"
    if [[ "$suffix" =~ $VERSION_PRERELEASE_SUFFIX_PATTERN ]]; then
        return 0
    fi
    return 1
}

# Checks if a version string contains a prerelease suffix
# Usage: is_prerelease_version "8.16.10-beta.1"
# Returns: 0 if prerelease, 1 if stable
is_prerelease_version() {
    local version="$1"
    if [[ "$version" =~ -(${VERSION_PRERELEASE_TYPES})\.[0-9]+ ]]; then
        return 0
    fi
    return 1
}

# Extracts the base version (without prerelease suffix)
# Usage: base_version=$(get_base_version "8.16.10-beta.1")
# Output: "8.16.10"
get_base_version() {
    local version="$1"
    echo "${version%%-*}"
}

# Extracts the prerelease suffix from a version
# Usage: suffix=$(get_prerelease_suffix "8.16.10-beta.1")
# Output: "beta.1" or empty string if no suffix
get_prerelease_suffix() {
    local version="$1"
    if [[ "$version" == *-* ]]; then
        echo "${version#*-}"
    else
        echo ""
    fi
}

# Validates release type input
# Usage: validate_release_type "beta"
# Returns: 0 if valid (stable|alpha|beta|rc), 1 if invalid
validate_release_type() {
    local type="$1"
    case "$type" in
        stable|alpha|beta|rc|"") return 0 ;;
        *) return 1 ;;
    esac
}

# Validates prerelease number is a positive integer
# Usage: validate_prerelease_number "1"
# Returns: 0 if valid, 1 if invalid
validate_prerelease_number() {
    local num="$1"
    if [[ "$num" =~ ^[1-9][0-9]*$ ]]; then
        return 0
    fi
    return 1
}
