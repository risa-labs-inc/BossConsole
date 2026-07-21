package ai.rever.boss.platform

/**
 * Open a file with the operating system's default application
 * (Finder/Explorer "open" behavior). Used by the terminal link
 * SYSTEM_DEFAULT open mode.
 *
 * @param filePath Absolute path to the file to open
 */
expect fun openFileWithSystemDefault(filePath: String)
