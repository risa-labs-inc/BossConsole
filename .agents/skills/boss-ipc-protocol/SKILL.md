---
name: boss-ipc-protocol
description: Enforces wire framing, stream separation, and exit codes for BossConsole CLI and SingleInstanceManager IPC.
triggers:
  - "SingleInstanceManager"
  - "boss mcp"
  - "boss status"
  - "CLI"
---
# IPC Wire & UNIX Discipline
- Framing: Use single-line Base64 framing: '<TOKEN> <VERB> <TOOL> <BASE64_ARGS>\n'.
- CRLF Safety: Always call '.trimEnd('\r', '\n')' and '.trim()' before Base64 decoding.
- Stream Separation: Valid JSON/data goes strictly to 'stdout'. All errors and diagnostics go strictly to 'stderr'.
- Exit Codes: Return 0 on success; return 1 on failure or when BOSS is offline.
- Cold-Start Safety: If BOSS is closed, CLI commands must fail fast in <100ms without booting the GUI.
