# Plugin Filesystem Security

## Overview

BOSS implements scoped filesystem security for plugins to prevent unauthorized access to sensitive system files and directories. The security model is **per-instance and capability-based**, not process-global, ensuring that each plugin's filesystem access is properly isolated.

## Security Model

### Capability-Based Authorization

Each plugin receives a **scoped `FileSystemDataProvider`** instance with explicit allowed roots:

- **Plugin Storage Directory**: `~/.boss/plugin-data/{pluginId}/` - Always granted for the plugin's own persistent data
- **Current Project Directory**: The currently selected project path (if a project is open)
- **User Home Directory**: `~/.boss` and user home - Default for backwards compatibility
- **Downloads Directory**: Resolved with symlink/junction handling for relocated Downloads folders

### Key Design Principles

1. **No Global State**: Security boundaries are per-instance, not process-global. Each plugin gets its own scoped provider.
2. **Explicit Roots**: Access is granted only to explicitly allowed directories, not universal `$HOME` access.
3. **Symlink Safety**: Uses real path resolution to prevent symlink escape attacks, especially on Windows with junctions.
4. **Path Normalization**: All paths are canonicalized before validation to handle relative paths and platform separators.
5. **TOCTOU Awareness**: Validation happens at the operation boundary, though complete TOCTOU safety requires native handle-relative operations.

## Implementation

### ScopedFileSystemDataProvider

The `ScopedFileSystemDataProvider` wrapper binds a plugin's filesystem access to specific roots:

```kotlin
class ScopedFileSystemDataProvider(
    private val pluginId: String,
    private val pluginStorageDir: File,
    private val currentProjectDir: File?,
    private val delegate: FileSystemDataProvider,
) : FileSystemDataProvider
```

This is similar to `ScopedPluginStorageFactory` and `DownloadCenterProviderImpl.forPlugin` - identity is bound once at construction.

### FileSystemDataProviderImpl

The base implementation enforces security boundaries through:

- **Path Validation**: `validatePath()` normalizes and checks paths against allowed roots
- **Child Path Validation**: `validateChildPath()` prevents traversal in child names
- **Symlink Resolution**: Uses `toRealPath()` and nearest-existing-ancestor pattern
- **Safe Recursive Delete**: Walks tree explicitly to avoid following symlinks outside boundary

### RevealInFileManager

The shared reveal utility supports optional scoped validation:

```kotlin
fun revealInFileManager(
    path: String,
    allowedRoots: Set<File>? = null,
): Result<Unit>
```

- When `allowedRoots` is provided: validates path is within roots (plugin-originated reveals)
- When `allowedRoots` is null: skips validation (host-initiated reveals, trusted)

### Windows Explorer Security

The Windows Explorer binary resolution is protected against binary planting attacks:

```kotlin
internal fun windowsExplorer(
    systemRoot: String? = System.getenv("SystemRoot"),
    isFile: (String) -> Boolean = { File(it).isFile },
): String
```

- Uses `%SystemRoot%\explorer.exe` when valid
- Falls back to bare `explorer.exe` only when SystemRoot is invalid
- Protects against `explorer.exe` placed in application or current directory

## Security Boundary Enforcement

### Path Validation

All filesystem operations go through `validatePath()` which:

1. Rejects null bytes and excessively long paths (DoS prevention)
2. Normalizes paths to canonical form
3. Resolves symlinks using nearest-existing-ancestor pattern
4. Checks against all allowed roots using component-aware path comparison

### Traversal Prevention

- **Path-level**: Uses `Path.startsWith()` which is component-aware to prevent sibling-prefix attacks
- **Child-level**: `validateChildPath()` rejects path separators and traversal sequences in child names
- **Real-path comparison**: Symlinks are resolved before boundary checks

### Downloads Directory Handling

The Downloads directory gets special handling because users may relocate it:

- **Symlink/junction resolution**: Uses `realPath()` to resolve Windows junctions
- **Outside-home support**: Allows Downloads moved to another drive or XDG directory
- **Root admission**: Downloads at filesystem root is refused (would admit whole drive)
- **Dangling link handling**: Refuses unresolvable paths rather than I/O failure

## Limitations

### TOCTOU (Time-of-Check-to-Time-of-Use)

The implementation validates at the operation boundary, but there is a theoretical TOCTOU window between validation and the actual filesystem operation. Complete TOCTOU safety would require native handle-relative operations (see `boss-native-files`).

This is acceptable for the current threat model because:
- The boundary check still prevents access to paths outside granted roots
- Operations that could be exploited (e.g., recursive delete) include additional validation during execution
- The primary attack vectors (path traversal, symlink escape) are prevented

### Recursive Delete Symlink Safety

Recursive delete walks the tree explicitly and validates each child before deletion, preventing symlink escape attacks. However, this is not complete TOCTOU protection - a determined attacker could potentially swap a symlink between validation and deletion.

## Migration Impact

Existing plugins that accessed files outside the granted roots will now receive `SecurityException` with clear error messages. This is intentional: unrestricted filesystem access was a security vulnerability, not a feature.

Plugins that need access to specific directories should:
1. Use `FilePickerProvider` for user-mediated file access
2. Work within project-specific paths provided by `ProjectDataProvider`
3. Use their plugin's storage directory via `PluginStorageFactory`

## Testing

Security tests are deterministic and use scoped provider instances with explicit allowed roots, avoiding global state dependencies. Tests cover:

- Allowed root access
- Denial outside allowed roots
- Sibling-prefix/path-boundary attacks
- Relative path rejection
- Child traversal rejection
- Symlink/junction child escape
- Allowed root outside `$HOME`
- Windows cross-drive behavior (with platform skips)
- Empty/no-capability state
- Security behavior at the actual FileSystemDataProvider operation seam

## Related Components

- `TrackingPluginContext`: Wires the scoped provider for each plugin
- `ScopedPluginStorageFactory`: Similar pattern for plugin storage scoping
- `DownloadCenterProviderImpl.forPlugin`: Similar pattern for download center scoping
- `BossDirectories`: Provides plugin data directory paths
