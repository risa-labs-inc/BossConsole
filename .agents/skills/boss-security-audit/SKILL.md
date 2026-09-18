---
name: boss-security-audit
description: Audits code for RBAC bypasses, secret leaks in logs, and memory/buffer vulnerabilities.
triggers:
  - "security"
  - "audit"
  - "secret"
  - "permissions"
---
# Security & Governance Checklist
- RBAC Integrity: All MCP tool invocations must route through 'McpToolRegistryImpl' to enforce roles and 'mcp-disabled-tools.json'.
- Bounded Buffering: Any stream reader ('stdin', socket lines) must enforce a hard upper limit ('MAX_REQUEST_BYTES') to prevent memory exhaustion attacks.
- Secret Sanitization: Never log or dump tool arguments from 'secret_*' tools into standard loggers or CLI command history.
- Local Loopback Auth: All IPC requests must present the 32-byte cryptographically secure session token.
