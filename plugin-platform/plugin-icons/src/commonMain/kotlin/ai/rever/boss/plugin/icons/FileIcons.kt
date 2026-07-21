package ai.rever.boss.plugin.icons

import ai.rever.boss.plugin.pathutils.extractFileName
import ai.rever.boss.plugin.ui.BossDarkTextSecondary
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized file icon mappings for the entire codebase.
 *
 * This provides a single source of truth for file icons (based on filename/extension)
 * used in File Tree, Dashboard, New Tab Dialog, etc.
 *
 * For code files, this delegates to [LanguageIcons] to use official brand icons.
 * For non-code files (images, documents, configs), it uses appropriate Material Icons.
 */
object FileIcons {

    // Folder icons
    val folder: ImageVector = Icons.Outlined.Folder
    val folderOpen: ImageVector = Icons.Outlined.FolderOpen

    // Document icons
    val document: ImageVector = Icons.Outlined.Description
    val pdf: ImageVector = Icons.Outlined.PictureAsPdf

    // Image icon
    val image: ImageVector = Icons.Outlined.Image

    // Config/settings icon
    val config: ImageVector = Icons.Outlined.Settings

    // Generic file icon
    val file: ImageVector = Icons.AutoMirrored.Outlined.InsertDriveFile

    // Archive icon
    val archive: ImageVector = Icons.Outlined.Archive

    // Database icon
    val database: ImageVector = Icons.Outlined.Storage

    // Lock icon (for sensitive files)
    val lock: ImageVector = Icons.Outlined.Lock

    // Audio/Video icons
    val audio: ImageVector = Icons.Outlined.MusicNote
    val video: ImageVector = Icons.Outlined.Videocam

    // Font icon
    val font: ImageVector = Icons.Outlined.FontDownload

    /**
     * Colors for non-code file types.
     */
    object Colors {
        val folder = Color(0xFF90A4AE)       // Folder gray-blue
        val document = Color(0xFF42A5F5)     // Document blue
        val pdf = Color(0xFFE53935)          // PDF red
        val image = Color(0xFF66BB6A)        // Image green
        val config = Color(0xFF8BC34A)       // Config lime
        val xml = Color(0xFFFF9800)          // XML orange
        val archive = Color(0xFF795548)      // Archive brown
        val database = Color(0xFF00ACC1)     // Database cyan
        val lock = Color(0xFFE91E63)         // Lock pink
        val audio = Color(0xFFAB47BC)        // Audio purple
        val video = Color(0xFFEF5350)        // Video red
        val font = Color(0xFF5C6BC0)         // Font indigo
        val unknown = BossDarkTextSecondary  // Default gray
    }

    /**
     * Result type containing icon and color information for a file.
     */
    data class FileIconInfo(
        val icon: ImageVector,
        val color: Color
    )

    /**
     * Get icon and color for a file based on its name.
     *
     * @param fileName The file name (can include path)
     * @return [FileIconInfo] containing the appropriate icon and color
     */
    fun forFile(fileName: String): FileIconInfo {
        val name = fileName.extractFileName()
        val extension = name.substringAfterLast('.', "").lowercase()

        // Handle special filenames first
        val specialFile = forSpecialFileName(name)
        if (specialFile != null) return specialFile

        // Handle by extension
        return when (extension) {
            // Code files - delegate to LanguageIcons
            "kt", "kts" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "java" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "scala", "sc" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "py", "pyw", "pyx", "pxd", "pxi" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "js", "mjs", "cjs" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "ts", "mts", "cts" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "jsx", "tsx" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "go" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "rs" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "rb", "erb", "rake" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "swift" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "m", "mm" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Objective-C
            "c", "h" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "cpp", "cc", "cxx", "hpp", "hxx", "hh" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "cs" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "fs", "fsx", "fsi" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // F#
            "php" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "lua" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "pl", "pm" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "r", "rmd" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "dart" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "ex", "exs" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "erl", "hrl" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Erlang
            "clj", "cljs", "cljc", "edn" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "jl" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "ml", "mli" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "zig" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "hs", "lhs" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "nim", "nims" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Nim
            "cr" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Crystal
            "f", "f90", "f95", "f03", "f08", "for" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Fortran
            "cob", "cbl" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // COBOL
            "asm", "s" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Assembly
            "sol" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Solidity
            "v" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // V
            "d" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // D
            "groovy", "gvy", "gy", "gsh" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Groovy
            "res", "resi" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // ReScript
            "rkt" -> LanguageIcons.forExtension(extension).toFileIconInfo()  // Racket

            // Web files - delegate to LanguageIcons
            "html", "htm", "xhtml" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "css", "scss", "sass", "less" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "vue" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "svelte" -> LanguageIcons.forExtension(extension).toFileIconInfo()

            // Shell scripts - delegate to LanguageIcons
            "sh", "bash", "zsh" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "ps1", "psm1", "psd1" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "bat", "cmd" -> FileIconInfo(LanguageIcons.powershell, LanguageIcons.Colors.powershell)  // Windows batch/cmd

            // Data/Config files - delegate to LanguageIcons for supported types
            "json" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "yaml", "yml" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "toml" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "md", "markdown" -> LanguageIcons.forExtension(extension).toFileIconInfo()

            // Config files - use config icon
            "xml" -> FileIconInfo(config, Colors.xml)
            "plist" -> FileIconInfo(LanguageIcons.ios, LanguageIcons.Colors.ios)  // Apple property list
            "gradle" -> FileIconInfo(LanguageIcons.gradle, LanguageIcons.Colors.gradle)
            "properties", "ini", "cfg", "conf" -> FileIconInfo(config, Colors.config)
            "env" -> FileIconInfo(lock, Colors.lock)  // Environment files often contain secrets

            // Documentation
            "txt", "log", "text" -> FileIconInfo(document, Colors.document)
            "doc", "docx", "odt" -> FileIconInfo(document, Colors.document)
            "pdf" -> FileIconInfo(pdf, Colors.pdf)
            "rtf" -> FileIconInfo(document, Colors.document)

            // Images
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "icns", "webp", "avif", "tiff", "tif" -> FileIconInfo(image, Colors.image)
            "svg" -> FileIconInfo(image, Colors.image)
            "psd", "ai", "sketch", "fig", "xd" -> FileIconInfo(image, Colors.image)

            // Audio
            "mp3", "wav", "flac", "ogg", "aac", "m4a", "wma" -> FileIconInfo(audio, Colors.audio)

            // Video
            "mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v" -> FileIconInfo(video, Colors.video)

            // Archives
            "zip", "tar", "gz", "rar", "7z", "bz2", "xz" -> FileIconInfo(archive, Colors.archive)
            "jar", "war", "ear" -> FileIconInfo(archive, LanguageIcons.Colors.java)

            // Databases
            "sql" -> LanguageIcons.forExtension(extension).toFileIconInfo()
            "db", "sqlite", "sqlite3" -> FileIconInfo(database, LanguageIcons.Colors.sqlite)

            // Fonts
            "ttf", "otf", "woff", "woff2", "eot" -> FileIconInfo(font, Colors.font)

            // Git
            "gitignore", "gitattributes", "gitmodules" -> LanguageIcons.forExtension(extension).toFileIconInfo()

            // Docker
            "dockerfile" -> LanguageIcons.forExtension(extension).toFileIconInfo()

            // Default
            else -> FileIconInfo(file, Colors.unknown)
        }
    }

    /**
     * Get icon and color for a folder.
     *
     * @param isExpanded Whether the folder is expanded
     * @return [FileIconInfo] containing the appropriate icon and color
     */
    fun forFolder(isExpanded: Boolean = false): FileIconInfo {
        return FileIconInfo(
            icon = if (isExpanded) folderOpen else folder,
            color = Colors.folder
        )
    }

    /**
     * Handle special filenames that have specific icons regardless of extension.
     */
    private fun forSpecialFileName(fileName: String): FileIconInfo? {
        val lowerName = fileName.lowercase()
        return when {
            // Docker files
            lowerName == "dockerfile" || lowerName.startsWith("dockerfile.") ->
                FileIconInfo(LanguageIcons.docker, LanguageIcons.Colors.docker)

            // Docker Compose
            lowerName == "docker-compose.yml" || lowerName == "docker-compose.yaml" ||
                lowerName == "compose.yml" || lowerName == "compose.yaml" ->
                FileIconInfo(LanguageIcons.docker, LanguageIcons.Colors.docker)

            // Package managers
            lowerName == "package.json" -> FileIconInfo(LanguageIcons.npm, LanguageIcons.Colors.npm)
            lowerName == "package-lock.json" -> FileIconInfo(LanguageIcons.npm, LanguageIcons.Colors.npm)
            lowerName == "yarn.lock" -> FileIconInfo(LanguageIcons.yarn, LanguageIcons.Colors.yarn)
            lowerName == "pnpm-lock.yaml" -> FileIconInfo(LanguageIcons.pnpm, LanguageIcons.Colors.pnpm)
            lowerName == "cargo.toml" || lowerName == "cargo.lock" ->
                FileIconInfo(LanguageIcons.cargo, LanguageIcons.Colors.rust)
            lowerName == "go.mod" || lowerName == "go.sum" ->
                FileIconInfo(LanguageIcons.go, LanguageIcons.Colors.go)
            lowerName == "requirements.txt" || lowerName == "pipfile" || lowerName == "pyproject.toml" ->
                FileIconInfo(LanguageIcons.pip, LanguageIcons.Colors.python)
            lowerName == "gemfile" || lowerName == "gemfile.lock" ->
                FileIconInfo(LanguageIcons.ruby, LanguageIcons.Colors.ruby)

            // Build files
            lowerName == "build.gradle" || lowerName == "build.gradle.kts" ||
                lowerName == "settings.gradle" || lowerName == "settings.gradle.kts" ||
                lowerName == "gradlew" || lowerName == "gradlew.bat" ||
                lowerName == "gradle.properties" ->
                FileIconInfo(LanguageIcons.gradle, LanguageIcons.Colors.gradle)
            lowerName == "pom.xml" -> FileIconInfo(LanguageIcons.maven, LanguageIcons.Colors.maven)
            lowerName == "makefile" || lowerName == "gnumakefile" ->
                FileIconInfo(config, Colors.config)

            // Git files
            lowerName == ".gitignore" || lowerName == ".gitattributes" ||
                lowerName == ".gitmodules" || lowerName == ".gitconfig" ->
                FileIconInfo(LanguageIcons.git, LanguageIcons.Colors.git)

            // Environment/secrets
            lowerName == ".env" || lowerName.startsWith(".env.") ||
                lowerName == ".secrets" || lowerName.endsWith(".pem") ||
                lowerName.endsWith(".key") ->
                FileIconInfo(lock, Colors.lock)

            // README files
            lowerName == "readme" || lowerName == "readme.md" ||
                lowerName == "readme.txt" || lowerName == "readme.rst" ->
                FileIconInfo(LanguageIcons.markdown, LanguageIcons.Colors.markdown)

            // License files
            lowerName == "license" || lowerName == "license.md" ||
                lowerName == "license.txt" || lowerName == "copying" ->
                FileIconInfo(document, Colors.document)

            // K8s files
            lowerName.endsWith(".k8s.yaml") || lowerName.endsWith(".k8s.yml") ->
                FileIconInfo(LanguageIcons.kubernetes, LanguageIcons.Colors.kubernetes)

            else -> null
        }
    }

    /**
     * Extension function to convert a Pair<ImageVector, Color> to FileIconInfo.
     */
    private fun Pair<ImageVector, Color>.toFileIconInfo(): FileIconInfo {
        return FileIconInfo(first, second)
    }
}
