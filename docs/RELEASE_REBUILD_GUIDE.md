# Release Rebuild Guide - Issue #111 Fix

## Problem Summary

The v8.12.18 release was built with a version mismatch:
- **DMG/MSI/JAR filename**: `BOSS-8.12.18-Universal.dmg` (correct)
- **App version inside**: `8.12.2` (incorrect - stale VersionConstants.kt)

This caused users who updated to v8.12.18 to actually receive v8.12.2, resulting in downgrades.

## Root Cause

The `generateVersionConstants` Gradle task was not running reliably before compilation, causing stale VersionConstants.kt to be embedded in release artifacts.

## Fixes Applied

1. **Build System (composeApp/build.gradle.kts)**:
   - Added `outputs.upToDateWhen { false }` to force VersionConstants regeneration on every build
   - Prevents Gradle from caching stale generated files

2. **CI/CD Verification (.github/workflows/release.yml)**:
   - Added version verification steps after each platform build
   - Compares generated VersionConstants.kt against expected version
   - Fails build if mismatch detected

3. **Runtime Verification (VersionVerifier.kt)**:
   - Added startup check that compares VersionConstants against version.properties
   - Logs warning if mismatch detected
   - Helps detect build issues in development

4. **Downgrade Protection (UpdateInstaller.kt)**:
   - Added version extraction from artifact filenames
   - Prevents installation if downloaded version < current version
   - Protects users from Issue #111 scenario

## Release Rebuild Process

### Prerequisites

- GitHub CLI (`gh`) installed and authenticated
- Admin access to BossConsole-Releases repository
- All fixes from this PR merged to main branch

### Step 1: Delete Bad v8.12.18 Release

```bash
# List current releases to verify v8.12.18 exists
gh release list --repo risa-labs-inc/BossConsole-Releases

# Delete the v8.12.18 release (keeps the tag)
gh release delete v8.12.18 --repo risa-labs-inc/BossConsole-Releases --yes

# Delete the v8.12.18 tag
git push --delete origin v8.12.18
```

### Step 2: Verify Local Repository

```bash
# Ensure you're on main branch with latest code
git checkout main
git pull origin main

# Verify version.properties
cat version.properties | grep "app.version="
# Should show: app.version=8.12.19 (or whatever the correct version is)

# Clean build directory
./gradlew clean
```

### Step 3: Test Build Locally

```bash
# Force regeneration of VersionConstants
./gradlew generateVersionConstants

# Verify generated file
cat composeApp/build/generated/source/version/ai/rever/boss/utils/VersionConstants.kt
# Should match version.properties: MAJOR = 8, MINOR = 12, PATCH = 19

# Test compilation
./gradlew compileKotlinDesktop

# Build platform-specific packages for verification
./gradlew packageDmg  # macOS
# OR
./gradlew packageMsi  # Windows
# OR
./gradlew packageDistributionForCurrentOS  # Linux
```

### Step 4: Trigger Release Build

**Option A: Manual Tag (Recommended)**

```bash
# Create and push v8.12.18 tag
git tag v8.12.18
git push origin v8.12.18

# This triggers the release workflow automatically
```

**Option B: Workflow Dispatch**

```bash
# Trigger release workflow manually
gh workflow run release.yml --repo risa-labs-inc/BOSS-Kotlin \
  -f version_increment=patch \
  -f create_release=true
```

### Step 5: Monitor CI/CD Build

```bash
# Watch workflow progress
gh run list --workflow=release.yml --repo risa-labs-inc/BOSS-Kotlin

# View logs for specific run
gh run view <run-id> --log --repo risa-labs-inc/BOSS-Kotlin
```

**Critical checkpoints:**
1. ✅ `generateVersionConstants` task runs
2. ✅ "Verify Built Version" steps pass for all platforms
3. ✅ Version inside artifacts matches tag version
4. ✅ Release created successfully

### Step 6: Verify Release Artifacts

```bash
# Download and verify artifacts
gh release download v8.12.18 --repo risa-labs-inc/BossConsole-Releases

# macOS: Mount DMG and check version
hdiutil attach BOSS-8.12.18-Universal.dmg
# Check BOSS.app version in Info.plist or About dialog

# Windows: Extract MSI and check version
# Use 7-Zip or similar to inspect MSI contents

# Linux: Run JAR and check version
java -jar BOSS-8.12.18.jar --version
```

### Step 7: Test Update Flow

```bash
# Install v8.12.17 (or earlier)
# Launch app
# Check for updates
# Verify update to v8.12.18 works correctly
# Verify installed version is actually 8.12.18 (not 8.12.2!)
```

## Verification Checklist

- [ ] v8.12.18 release deleted from GitHub
- [ ] v8.12.18 tag deleted from repository
- [ ] Local repository on main branch with latest fixes
- [ ] VersionConstants.kt generated with correct version (8.12.19)
- [ ] Local build compiles successfully
- [ ] CI/CD workflow triggered (via tag or dispatch)
- [ ] All platform builds pass version verification
- [ ] Release created with correct artifacts
- [ ] Downloaded artifacts verified to contain correct version
- [ ] Update flow tested from older version
- [ ] No more downgrades occur

## Prevention

These fixes ensure Issue #111 cannot happen again:

1. **Build-time**: VersionConstants always regenerated
2. **CI/CD**: Version mismatches caught before release
3. **Runtime**: Version inconsistencies logged
4. **Installation**: Downgrades blocked

## Troubleshooting

**Problem**: CI/CD fails with "VERSION MISMATCH DETECTED"

**Solution**:
- Check version.properties in repository
- Ensure generateVersionConstants task ran
- Verify Gradle cache isn't causing issues: `./gradlew clean build`

**Problem**: Can't delete release (permission denied)

**Solution**:
- Verify GitHub CLI authentication: `gh auth status`
- Ensure you have admin access to BossConsole-Releases repository
- Try using GitHub web UI as alternative

**Problem**: Users still reporting downgrade

**Solution**:
- Verify they're updating FROM a version older than 8.12.18
- Check their current version: likely they had the bad 8.12.18 (actually 8.12.2)
- They need to update to the NEW v8.12.18 release

## Related Issues

- Issue #111: Update Issue (version downgrade)
- PR #XXX: Fix for Issue #111 (this PR)

## Contact

For questions about this process, contact the development team or reference:
- CLAUDE.md - Project development guidelines
- .github/workflows/release.yml - Release workflow details
- composeApp/build.gradle.kts - Build configuration
