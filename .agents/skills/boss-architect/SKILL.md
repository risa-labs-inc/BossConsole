---
name: boss-architect
description: Use when planning new features, plugins, or architectural modifications in BossConsole.
triggers:
  - "plan"
  - "architect"
  - "design"
  - "add feature"
---
# BossConsole Architectural Rules
- Native JVM First: Never propose Node/Electron dependencies or single-threaded event loop models.
- Preserve KMP Boundaries: Keep common logic in 'commonMain'; isolate desktop JVM runtime hooks (sockets, JNI, desktop Compose) in 'desktopMain'.
- Token Efficiency: Any tool listing or schema inspection must support filtering or targeted queries to prevent LLM context dilution.
- Headless First: Any new action must be capable of running headlessly over IPC without prompting desktop modal dialogs.
