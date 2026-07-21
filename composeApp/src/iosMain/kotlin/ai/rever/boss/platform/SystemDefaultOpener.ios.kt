package ai.rever.boss.platform

// Stub: opening a file with the system default app is a desktop feature.
// (iOS targets are currently disabled in composeApp/build.gradle.kts; this
// actual exists so re-enabling the targets doesn't break the build.)
actual fun openFileWithSystemDefault(filePath: String) {
    // No-op: terminal links only exist on desktop.
}
