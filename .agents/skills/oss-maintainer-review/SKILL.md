---
name: oss-maintainer-review
description: Harshly reviews diffs, commit messages, and documentation from an open-source maintainer perspective.
triggers:
  - "review PR"
  - "check diff"
  - "pre-commit"
---
# Maintainer Review Guidelines
- Anti-Slop Filter: Strip marketing buzzwords ('groundbreaking', 'comprehensive', 'supercharged') from PR descriptions and docstrings.
- Minimal Root README: Never bloat the root README.md with massive flag tables; keep it to a 6-line summary linking to 'docs/'.
- Git Commits: Enforce Conventional Commits ('<type>(<scope>): <summary>') under 72 characters.
- Resource Leaks: Verify every 'Socket', 'BufferedReader', and 'PrintWriter' uses Kotlin's '.use { ... }' pattern.
