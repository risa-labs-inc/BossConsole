# Threading and Coroutines Best Practices

> **CRITICAL**: Proper threading is essential for responsive UI and preventing freezes. This document covers threading patterns, common pitfalls, and best practices learned from production issues.

## Table of Contents

- [Core Principles](#core-principles)
- [Dispatcher Reference](#dispatcher-reference)
- [Compose-Specific Patterns](#compose-specific-patterns)
- [Common Patterns](#common-patterns)
- [Anti-Patterns to Avoid](#anti-patterns-to-avoid)
- [State Management & Thread Safety](#state-management--thread-safety)
- [Exception Handling](#exception-handling)
- [JxBrowser-Specific Patterns](#jxbrowser-specific-patterns)
- [Testing & Debugging](#testing--debugging)
- [Code Review Checklist](#code-review-checklist)

---

## Core Principles

### The Five Rules

1. **Never block the UI thread** - No `Thread.sleep()`, blocking I/O, or computations >16ms
2. **Use the right dispatcher** - Match the work type to the appropriate dispatcher
3. **Prefer structured concurrency** - Use `coroutineScope` over `GlobalScope`
4. **Use `delay()` not `Thread.sleep()`** - Non-blocking delays in coroutines
5. **Handle exceptions explicitly** - Coroutine exceptions can be silent killers

### Frame Budget

| Target FPS | Frame Budget | User Perception |
|------------|--------------|-----------------|
| 60 FPS | 16ms | Smooth |
| 30 FPS | 33ms | Acceptable |
| < 30 FPS | > 33ms | Noticeable lag |
| < 10 FPS | > 100ms | Feels broken |

**Rule of thumb**: Any operation >16ms should be off the UI thread.

---

## Dispatcher Reference

### Quick Reference Table

| Dispatcher | Use For | Thread Pool | Examples |
|------------|---------|-------------|----------|
| `Dispatchers.Main` | UI updates only | Single thread | State changes, recomposition |
| `Dispatchers.IO` | I/O operations | 64+ threads | File, network, database, browser cleanup |
| `Dispatchers.Default` | CPU-bound work | CPU cores | Parsing, sorting, encryption |
| `Dispatchers.Unconfined` | Immediate execution | Caller's thread | Rarely needed, avoid |

### When to Use Each

#### `Dispatchers.Main`
```kotlin
// ✅ UI state updates
withContext(Dispatchers.Main) {
    isLoading = false
    errorMessage = result.errorOrNull()?.message
}

// ✅ Quick synchronous operations (<16ms)
onClick = { viewModel.toggleSelection() }
```

#### `Dispatchers.IO`
```kotlin
// ✅ File operations
withContext(Dispatchers.IO) {
    val content = file.readText()
    val parsed = Json.decodeFromString<Config>(content)
}

// ✅ Network calls
withContext(Dispatchers.IO) {
    val response = httpClient.get(url)
}

// ✅ Database operations
withContext(Dispatchers.IO) {
    database.insert(entity)
}

// ✅ Resource cleanup (browser, streams, connections)
CoroutineScope(Dispatchers.IO).launch {
    browser.close()
}
```

#### `Dispatchers.Default`
```kotlin
// ✅ Heavy computation
withContext(Dispatchers.Default) {
    val sorted = largeList.sortedBy { it.score }
    val filtered = sorted.filter { it.isValid }
}

// ✅ JSON parsing of large payloads
withContext(Dispatchers.Default) {
    Json.decodeFromString<LargeResponse>(jsonString)
}
```

---

## Compose-Specific Patterns

### LaunchedEffect

Use for side effects that should run when a key changes:

```kotlin
@Composable
fun BrowserTab(url: String) {
    // ✅ Runs when url changes, cancels previous if still running
    LaunchedEffect(url) {
        // Already on Main, but can switch dispatchers
        withContext(Dispatchers.IO) {
            loadUrl(url)
        }
    }
}
```

### rememberCoroutineScope

Use for event-driven coroutines (clicks, callbacks):

```kotlin
@Composable
fun SaveButton(onSave: suspend () -> Unit) {
    val scope = rememberCoroutineScope()

    Button(onClick = {
        // ✅ Launch coroutine from click handler
        scope.launch {
            withContext(Dispatchers.IO) {
                onSave()
            }
        }
    }) {
        Text("Save")
    }
}
```

### DisposableEffect for Cleanup

```kotlin
@Composable
fun TerminalView(terminalId: String) {
    val terminal = remember { Terminal() }

    DisposableEffect(terminalId) {
        terminal.start()

        onDispose {
            // ✅ Cleanup runs on composition disposal
            // For heavy cleanup, launch on IO
            CoroutineScope(Dispatchers.IO).launch {
                terminal.dispose()
            }
        }
    }
}
```

### produceState for Async Data Loading

```kotlin
@Composable
fun UserProfile(userId: String) {
    val userState by produceState<User?>(initialValue = null, userId) {
        // Runs on Default dispatcher, updates state on Main
        value = withContext(Dispatchers.IO) {
            userRepository.getUser(userId)
        }
    }

    userState?.let { UserCard(it) } ?: LoadingSpinner()
}
```

---

## Common Patterns

### Pattern 1: Background Resource Disposal

**Problem**: Resource cleanup blocks UI thread

```kotlin
// ❌ BAD - Blocks UI for 50ms+
fun dispose() {
    browserViewState?.dispose()
    Thread.sleep(50)  // UI FROZEN!
    browser?.close()
}

// ✅ GOOD - Returns immediately, cleanup happens async
fun dispose() {
    if (!isDisposed) {
        isDisposed = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                browserViewState?.dispose()
                delay(50)  // Non-blocking wait for RPC queue
                browser?.close()
            } catch (e: Exception) {
                println("Dispose error: ${e.message}")
            }
        }
    }
}
```

**Reference**: `Fluck.kt:336-357`

### Pattern 2: Async Data Loading with UI Updates

```kotlin
// ✅ Load data on IO, update UI on Main
fun loadBookmarks() {
    viewModelScope.launch {
        _isLoading.value = true

        val result = withContext(Dispatchers.IO) {
            bookmarkRepository.getAll()
        }

        // Back on Main automatically
        _bookmarks.value = result
        _isLoading.value = false
    }
}
```

### Pattern 3: Debounced Search

```kotlin
private var searchJob: Job? = null

fun onSearchQueryChanged(query: String) {
    searchJob?.cancel()  // Cancel previous search
    searchJob = viewModelScope.launch {
        delay(300)  // Debounce - wait for user to stop typing

        withContext(Dispatchers.IO) {
            val results = searchRepository.search(query)
            withContext(Dispatchers.Main) {
                _searchResults.value = results
            }
        }
    }
}
```

### Pattern 4: Parallel Operations

```kotlin
// ✅ Run independent operations in parallel
suspend fun loadDashboard(): Dashboard = coroutineScope {
    val userDeferred = async(Dispatchers.IO) { userRepo.getUser() }
    val statsDeferred = async(Dispatchers.IO) { statsRepo.getStats() }
    val notificationsDeferred = async(Dispatchers.IO) { notificationRepo.getUnread() }

    // All three load in parallel, await all
    Dashboard(
        user = userDeferred.await(),
        stats = statsDeferred.await(),
        notifications = notificationsDeferred.await()
    )
}
```

### Pattern 5: Timeout with Fallback

```kotlin
suspend fun fetchWithTimeout(): Result {
    return withTimeoutOrNull(5000) {
        withContext(Dispatchers.IO) {
            api.fetch()
        }
    } ?: Result.Timeout
}
```

---

## Anti-Patterns to Avoid

### 1. Thread.sleep() in Any Context

```kotlin
// ❌ NEVER
Thread.sleep(100)

// ✅ ALWAYS use delay() in coroutines
delay(100)
```

### 2. GlobalScope for Fire-and-Forget

```kotlin
// ❌ BAD - No structured concurrency, hard to cancel, memory leaks
GlobalScope.launch {
    doWork()
}

// ✅ GOOD - Tied to lifecycle, cancellable
viewModelScope.launch {
    doWork()
}

// ✅ OK for true fire-and-forget cleanup (document why!)
// Example: Browser disposal after tab close
CoroutineScope(Dispatchers.IO).launch {
    // Intentionally not tied to any lifecycle
    // Tab is already gone, just need cleanup to complete
    browser.close()
}
```

### 3. Blocking Calls in Coroutines

```kotlin
// ❌ BAD - Blocks the coroutine thread
suspend fun loadData() {
    val data = blockingHttpCall()  // Blocks!
}

// ✅ GOOD - Use suspending version or wrap in IO
suspend fun loadData() = withContext(Dispatchers.IO) {
    blockingHttpCall()
}
```

### 4. Catching CancellationException

```kotlin
// ❌ BAD - Swallows cancellation, breaks structured concurrency
try {
    suspendingWork()
} catch (e: Exception) {
    // CancellationException caught here!
}

// ✅ GOOD - Rethrow CancellationException
try {
    suspendingWork()
} catch (e: CancellationException) {
    throw e  // Let cancellation propagate
} catch (e: Exception) {
    handleError(e)
}
```

### 5. Updating UI State from Wrong Thread

```kotlin
// ❌ BAD - May crash or cause race conditions
CoroutineScope(Dispatchers.IO).launch {
    val data = loadData()
    _state.value = data  // Wrong thread!
}

// ✅ GOOD - Update state on Main
CoroutineScope(Dispatchers.IO).launch {
    val data = loadData()
    withContext(Dispatchers.Main) {
        _state.value = data
    }
}
```

---

## State Management & Thread Safety

### MutableStateFlow (Thread-Safe)

```kotlin
// ✅ StateFlow is thread-safe for updates
private val _count = MutableStateFlow(0)
val count: StateFlow<Int> = _count.asStateFlow()

fun increment() {
    _count.update { it + 1 }  // Atomic update
}
```

### Mutex for Complex State

```kotlin
private val mutex = Mutex()
private var complexState = ComplexState()

suspend fun updateState(transform: (ComplexState) -> ComplexState) {
    mutex.withLock {
        complexState = transform(complexState)
    }
}
```

### Atomic Operations

```kotlin
private val isDisposed = AtomicBoolean(false)

fun dispose() {
    if (isDisposed.compareAndSet(false, true)) {
        // Only runs once, even if called from multiple threads
        performDisposal()
    }
}
```

---

## Exception Handling

### CoroutineExceptionHandler

```kotlin
val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    println("Coroutine failed: ${throwable.message}")
    // Log to crash reporting service
}

CoroutineScope(Dispatchers.IO + exceptionHandler).launch {
    riskyOperation()
}
```

### SupervisorJob for Independent Children

```kotlin
// If one child fails, others continue
val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

scope.launch { task1() }  // Failure here...
scope.launch { task2() }  // ...doesn't cancel this
scope.launch { task3() }  // ...or this
```

### Try-Catch in Coroutines

```kotlin
viewModelScope.launch {
    try {
        val result = withContext(Dispatchers.IO) {
            api.fetchData()
        }
        _data.value = result
    } catch (e: CancellationException) {
        throw e  // Always rethrow!
    } catch (e: IOException) {
        _error.value = "Network error: ${e.message}"
    } catch (e: Exception) {
        _error.value = "Unexpected error: ${e.message}"
    }
}
```

---

## JxBrowser-Specific Patterns

JxBrowser uses internal RPC (Remote Procedure Call) requiring careful threading.

### Browser Disposal

```kotlin
// ✅ Correct disposal pattern
CoroutineScope(Dispatchers.IO).launch {
    try {
        // 1. Dispose view state first
        browserViewState?.let { disposeBrowserViewState(it) }

        // 2. Wait for RPC queue to drain
        // Without this delay, browser.close() tears down RPC
        // while messages are still pending → NullPointerException
        delay(50)

        // 3. Now safe to close browser
        browser?.let { disposeBrowser(it) }
    } catch (e: Exception) {
        println("Browser disposal error: ${e.message}")
    }
}
```

### Engine Shutdown

```kotlin
// In main.kt shutdown hook
Runtime.getRuntime().addShutdownHook(Thread {
    try {
        val engine = FluckEngine.currentEngine
        if (engine != null && !engine.isClosed) {
            engine.close()  // Releases profile lock files
        }
    } catch (e: Exception) {
        println("Engine shutdown error: ${e.message}")
    }
})
```

**Reference**: `main.kt:91-103`, `FluckEngine.kt`

---

## Testing & Debugging

### Finding Threading Issues

```bash
# Find Thread.sleep() calls (should be rare/zero)
git grep "Thread.sleep"

# Find GlobalScope usage (review each case)
git grep "GlobalScope"

# Find blocking calls
git grep "runBlocking"
git grep "blockingGet"
```

### Manual Testing Checklist

- [ ] Close multiple browser tabs rapidly - should be instant
- [ ] Switch between tabs quickly - no lag
- [ ] Open large files - UI stays responsive
- [ ] Network operations - loading indicators work, no freezes
- [ ] App quit - clean shutdown, no hanging

### Profiling Tools

1. **IntelliJ Profiler**: CPU sampling to find hot spots
2. **Compose Performance**: Enable recomposition highlighting
3. **Android Studio Profiler**: Works for desktop too (limited)

### IntelliJ Inspections to Enable

- "Inappropriate blocking method call"
- "Possibly blocking call in non-blocking context"
- "Redundant coroutine context"
- "Unused coroutine scope"

---

## Code Review Checklist

### Must Check

- [ ] No `Thread.sleep()` calls
- [ ] No blocking I/O on Main dispatcher
- [ ] Resource cleanup uses `Dispatchers.IO`
- [ ] `CancellationException` is rethrown, not swallowed
- [ ] State updates happen on Main thread

### Should Check

- [ ] Heavy computation uses `Dispatchers.Default`
- [ ] Parallel operations use `async` where beneficial
- [ ] Timeouts on network operations
- [ ] Error handling doesn't silently fail
- [ ] Cleanup code in `DisposableEffect` or `onDispose`

### Watch For

- [ ] Large `delay()` values (>1000ms) - usually a code smell
- [ ] Nested `launch` blocks - consider restructuring
- [ ] Missing cancellation handling in loops
- [ ] StateFlow updates from wrong dispatcher

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────────────┐
│                    THREADING QUICK REFERENCE                 │
├─────────────────────────────────────────────────────────────┤
│  UI Updates        → Dispatchers.Main                       │
│  File/Network/DB   → Dispatchers.IO                         │
│  Heavy Compute     → Dispatchers.Default                    │
│  Delays            → delay() NOT Thread.sleep()             │
│  Cleanup           → CoroutineScope(Dispatchers.IO).launch  │
├─────────────────────────────────────────────────────────────┤
│  ❌ Thread.sleep()                                          │
│  ❌ GlobalScope (unless documented why)                     │
│  ❌ Catching CancellationException                          │
│  ❌ Blocking calls without withContext(IO)                  │
├─────────────────────────────────────────────────────────────┤
│  ✅ viewModelScope.launch { }                               │
│  ✅ withContext(Dispatchers.IO) { }                         │
│  ✅ try/catch with CancellationException rethrow            │
│  ✅ StateFlow for thread-safe state                         │
└─────────────────────────────────────────────────────────────┘
```

---

## Further Reading

- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Compose Side Effects](https://developer.android.com/jetpack/compose/side-effects)
- [Structured Concurrency](https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency)
