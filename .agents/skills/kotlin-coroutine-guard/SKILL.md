---
name: kotlin-coroutine-guard
description: Enforces Kotlin coroutine cancellation safety, non-blocking UI rules, and dispatcher scoping in BossConsole.
triggers:
  - "coroutine"
  - "Dispatchers"
  - "async"
  - "withContext"
---
# Kotlin Concurrency Invariants
- Never Swallow Cancellation: Always rethrow 'CancellationException' when catching 'Throwable' or 'Exception'.
- Dispatcher Offloading: Disk I/O, network calls, and IPC socket handling must run on 'Dispatchers.IO'.
- No Main Blocking: Never run 'runBlocking' on 'Dispatchers.Main'.
- UI Thread Hopping: Any mutation of Compose UI state must switch via 'withContext(Dispatchers.Main)'.
