# BOSS keylocker-tools

**Release Date**: July 28, 2025

## Summary

This release focuses on CI/CD workflow improvements and repository cleanup, including fixes to the release synchronization workflow and removal of obsolete backup files.

## Highlights

- Fixed sync-release workflow to properly authenticate asset downloads
- Cleaned up duplicate workflow backup files

## Improvements

### Workflow
- **Use PAT for asset downloads in sync-release workflow**
  - Updated sync-release workflow to use Personal Access Token for authenticated GitHub API requests
  - Improves reliability of asset downloads from BossConsole-Releases repository

- **Remove duplicate build workflow backup file**
  - Removed obsolete `.github/workflows/build_backup.yml` file (225 lines)
  - Cleaned up repository structure

## Downloads

| Platform | Download |
|----------|----------|
| macOS (Apple Silicon) | [BOSS-8.9.2-arm64.dmg](https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/keylocker-tools/BOSS-8.9.2-arm64.dmg) |
| macOS (Intel) | [BOSS-8.9.2-x64.dmg](https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/keylocker-tools/BOSS-8.9.2-x64.dmg) |
| Windows | [BOSS-8.9.2.msi](https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/keylocker-tools/BOSS-8.9.2.msi) |
| Linux (x64) | [boss_8.9.2-1_amd64.deb](https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/keylocker-tools/boss_8.9.2-1_amd64.deb) |
| Linux (ARM64) | [boss_8.9.2-1_arm64.deb](https://github.com/risa-labs-inc/BossConsole-Releases/releases/download/keylocker-tools/boss_8.9.2-1_arm64.deb) |

---

**Full Changelog**: [v8.9.1...keylocker-tools](https://github.com/risa-labs-inc/BossConsole/compare/v8.9.1...keylocker-tools)
