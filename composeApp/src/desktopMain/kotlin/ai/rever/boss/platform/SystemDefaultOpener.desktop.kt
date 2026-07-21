package ai.rever.boss.platform

actual fun openFileWithSystemDefault(filePath: String) {
    FileSystemUtils.openFile(filePath)
}
