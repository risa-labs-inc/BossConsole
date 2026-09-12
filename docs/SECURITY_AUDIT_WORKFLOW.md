# Automated Repository Security & Code Quality Audit Workflow

This workflow provides a standardized instruction set for BossConsole AI agents to perform automated security, code-quality, and vulnerability scans across project codebases.

## Overview
By executing this workflow, the AI agent autonomously navigates the project directory, flags security vulnerabilities or unhandled runtime exceptions, and formats the findings into a clean, actionable markdown report.

## Workflow Execution Stages

### Stage 1: File Discovery & Environment Isolation
- **Scope Target**: Recursively scan all primary workspace source files (`.kt`, `.java`, `.py`, `.ts`, `.json`).
- **Boundary Rules**: Explicitly exclude ignored or sensitive local files (`.env`, `.pem`, `secrets.json`, `.gitignore`, `build/`, `node_modules/`).
- **Verification Check**: Verify that file read permissions are granted before proceeding to execution.

### Stage 2: Security & Quality Analysis
The agent evaluates the codebase against three core audit rules:

1. **Credential & Secret Exposure Check**
   - Identify hardcoded tokens, secret keys, password strings, or exposed private endpoints.
2. **Exception Handling & Fault Tolerance**
   - Flag empty or swallowed `catch` blocks, missing null checks, and unhandled async/promise rejections.
3. **Performance & Anti-Pattern Detection**
   - Highlight redundant resource allocations, inefficient loop nesting, and unclosed stream connections.

### Stage 3: Structured Report Generation
Compile all detected issues into a new file named `AUDIT_REPORT.md` in the project root using the following structure:

```markdown
# Repository Audit Summary

## Executive Summary
- **Total Files Scanned**: [Count]
- **Critical Vulnerabilities**: [Count]
- **Warnings / Code Smells**: [Count]

## Findings Breakdown

### 1. [Issue Title]
- **Severity**: [CRITICAL / HIGH / MEDIUM / LOW]
- **File Location**: `path/to/file.ext` (Line: [Line Number])
- **Description**: Brief explanation of the risk or code smell.
- **Recommended Remediation**:
  \`\`\`[language]
  // Proposed code fix or patched implementation
  \`\`\`