# Plugin Filesystem Access Security Hardening

## Overview

This document describes the security hardening implemented for plugin filesystem access in BOSS. The changes enforce a clear filesystem boundary for plugins based on host-granted capabilities, normalize and validate paths before access, and prevent path traversal attacks including equivalent/encoded/relative traversal cases.

## Security Model

### Explicit Capabilities Model

Plugins are granted access to specific filesystem roots through host-controlled mechanisms. This is not a single boundary (like user home), but an explicit set of allowed roots that can be dynamically managed.

### Allowed Roots

The following roots are explicitly granted by default:

- **Plugin Storage**: The plugin's own storage directory (always granted)
- **Current Project**: The currently selected project directory (if a project is selected)
- **User Home**: The user's home directory (default for backwards compatibility)

Additional roots can be granted through user-mediated mechanisms like FilePickerProvider.

### Boundary Enforcement

The boundary is enforced through:

- **Canonical Path Validation**: All paths are resolved to their canonical form before validation
- **Root-Based Checks**: Paths must be within at least one of the explicitly granted roots
- **Symlink Resolution**: Symlinks are resolved to their targets before boundary checks

### Path Normalization

The security utility performs comprehensive path normalization:

1. **Basic Validation**: Rejects null bytes, blank paths, and excessively long paths (>32KB)
2. **Path Normalization**: Uses `Path.normalize()` to handle `.` and `..` segments
3. **Absolute Path Conversion**: Converts relative paths to absolute paths
4. **Canonical Resolution**: Resolves symlinks and platform-specific path issues

### Traversal Prevention

Path traversal attacks are prevented through:

- **Canonical Comparison**: Uses canonical paths for boundary checks (not string comparison)
- **Segment Validation**: Validates child names don't contain path separators or traversal sequences
- **Symlink Safety**: Resolves symlinks before boundary checks to prevent symlink escapes

## Implementation

### PluginFileSystemSecurity Utility

The `PluginFileSystemSecurity` object provides centralized security validation:

```kotlin
// Initialize default allowed roots (called during plugin initialization)
PluginFileSystemSecurity.initializeDefaultRoots(
    pluginStorageDir = pluginStorageDirectory,
    currentProjectDir = currentProjectDirectory
)

// Add an explicit allowed root (e.g., from FilePickerProvider)
PluginFileSystemSecurity.addAllowedRoot(userSelectedDirectory)

// Validate and normalize a path for filesystem access
val validatedPath = PluginFileSystemSecurity.validateAndNormalizePath(
    rawPath = "/home/user/file.txt",
    operation = "readFile"
)

// Validate a child path (for create operations)
val validatedChildPath = PluginFileSystemSecurity.validateChildPath(
    parentPath = "/home/user/documents",
    childName = "newfile.txt",
    operation = "createFile"
)
```

### Integration Points

Security checks are integrated into:

1. **FileSystemDataProviderImpl**: All filesystem operations now validate paths
   - `scanDirectory` / `scanDirectoryWithDepth`
   - `directoryHasChildren`
   - `openFile`
   - `createFile` / `createFolder`
   - `delete`
   - `rename`
   - `readFile` / `writeFile`
   - `revealInFileManager`

2. **RevealInFileManager**: The reveal utility now validates paths before OS operations

3. **ScopedPluginStorageFactory**: Initializes plugin filesystem security when storage is first created

### TOCTOU Considerations

Path validation happens at the operation boundary. There is a theoretical time-of-check-to-time-of-use (TOCTOU) window between validation and the actual filesystem operation. For complete TOCTOU safety, filesystem operations would need to use handle-relative or no-follow semantics from native libraries (see boss-native-files). This implementation uses standard Java File API which follows symlinks at operation time, but the boundary check still prevents access to paths outside granted roots.

## Migration Implications

### Breaking Changes

Plugins that previously accessed files outside the granted roots will now receive `SecurityException` with a clear error message. This is intentional: unrestricted filesystem access was a security vulnerability, not a feature.

### Error Messages

When access is denied, plugins receive a clear error message:

```
Access denied: path '/etc/passwd' is outside all allowed filesystem roots.
Use FilePickerProvider for user-mediated file access, work within your project directory,
or use your plugin's storage directory.
```

### Recommended Migration Paths

Plugins that need access to specific directories should:

1. **Use FilePickerProvider**: Request the user to open files through the file picker
   ```kotlin
   val filePicker = pluginContext.filePickerProvider
   val selectedFile = filePicker?.pickFile()
   ```

2. **Use ProjectDataProvider**: Access project-specific paths
   ```kotlin
   val projectPath = pluginContext.projectPath
   // Work within the project directory
   ```

3. **Use PluginStorageFactory**: Store plugin data in the plugin's storage directory
   ```kotlin
   val storageFactory = pluginContext.pluginStorageFactory
   val pluginStorage = storageFactory?.getStorage(pluginId)
   ```

### Testing Recommendations

Plugin developers should:
1. Test all filesystem operations with paths within the granted roots
2. Verify error handling for denied access attempts
3. Use the recommended migration paths for any out-of-bounds access
4. Test with various path formats (relative, absolute, with symlinks)

## Security Benefits

### Prevented Attack Vectors

1. **Path Traversal**: Blocks `../`, encoded variants, and relative traversal
2. **Symlink Escapes**: Resolves symlinks before boundary checks
3. **Null Byte Injection**: Rejects paths with null bytes
4. **Excessive Path Length**: Prevents DoS through long paths
5. **System Directory Access**: Blocks access to sensitive system directories
6. **Sibling-Prefix Attacks**: Validates full path against all granted roots

### Backward Compatibility

The changes preserve legitimate plugin behavior for:

- Plugins that already work within the user home directory
- Standard file operations on user files
- Project-specific workflows
- Plugin storage operations
- Workspaces outside $HOME that are explicitly granted

## Limitations

### Scope

This security hardening is scoped to the `FileSystemDataProvider` interface and does not:

- Provide a full sandbox against concurrent filesystem mutation
- Replace the need for user-mediated access for sensitive operations
- Protect against vulnerabilities in plugin code itself
- Apply to other plugin interfaces (e.g., terminal, network)

### Platform Considerations

- **Windows**: Handles both forward slashes and backslashes correctly, including cross-drive paths
- **macOS**: Resolves case-insensitive filesystem issues
- **Linux**: Standard Unix path handling

### Symlink Handling

Symlinks are resolved to their targets before boundary checks. This prevents symlink escapes but means that plugins can access files through symlinks if the target is within granted roots.

### Outside $HOME Behavior

The explicit capability model supports legitimate workspaces outside $HOME when they are explicitly granted (e.g., through project selection or user-mediated file pickers). This is more flexible than a strict $HOME-only boundary.

### Future Enhancements

Potential future improvements could include:
- Plugin-specific boundary configuration
- Granular permission system for different directories
- User-configurable directory grants
- Integration with RBAC for filesystem permissions
- Native handle-relative operations for complete TOCTOU safety

## Testing

### Security Test Coverage

Comprehensive tests cover:

- Path traversal attacks (../, encoded variants)
- Path normalization and canonicalization
- Boundary enforcement (explicit granted roots)
- Symlink escape prevention
- Allowed and denied paths
- Edge cases (null bytes, excessive length, etc.)
- Platform-specific path handling
- Unicode character handling
- Provider-level operation tests (scan, open, create, read, write, delete, rename, reveal)
- Outside $HOME workspace tests
- Sibling-prefix path tests
- Windows cross-drive path tests
- Symlink-swap / TOCTOU behavior tests

### Running Tests

```bash
# Run the filesystem security tests
./gradlew :composeApp:test --tests PluginFileSystemSecurityTest

# Run the provider-level security tests
./gradlew :composeApp:test --tests FileSystemDataProviderSecurityTest

# Run all composeApp tests
./gradlew :composeApp:test
```

## References

- [AGENTS.md](../AGENTS.md) - Project architecture and workflow rules
- [FileSystemPathPolicy](../modules/boss-service-filesystem/src/main/kotlin/ai/rever/boss/service/filesystem/FileSystemPathPolicy.kt) - System path validation for RPC services
- [CLISecurityValidator](../composeApp/src/desktopMain/kotlin/ai/rever/boss/cli/CLISecurityValidator.kt) - CLI security validation patterns

## Conclusion

This security hardening significantly improves the security posture of plugin filesystem access in BOSS while maintaining backward compatibility for legitimate use cases. The explicit capability model provides flexibility for legitimate workflows outside $HOME while still preventing unauthorized access to sensitive system directories. The clear error messages and recommended migration paths help plugin developers adapt to the new security boundaries.
