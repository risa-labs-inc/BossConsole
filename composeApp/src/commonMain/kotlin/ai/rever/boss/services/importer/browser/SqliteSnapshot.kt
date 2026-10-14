package ai.rever.boss.services.importer.browser

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteErrorCode
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Opens a browser's SQLite database safely.
 *
 * A running browser holds its databases open, and on some platforms locks them
 * outright, so reading in place either fails or blocks. Everything here works on
 * a private copy: the browser stays untouched and can keep writing while the
 * import reads.
 *
 * SQLite's online-backup API makes the private copy. Copying the main file and
 * its `-wal` sidecar separately is not safe: a running browser can checkpoint
 * between those two copies, leaving neither copy with a recently committed row.
 */
internal object SqliteSnapshot {
    private const val BACKUP_BUSY_SLEEP_MILLIS = 50
    private const val BACKUP_BUSY_RETRIES = 100
    private const val PAGES_PER_BACKUP_STEP = 100

    /**
     * Copy [source] to a temp file, run [block] against it, then delete the copy.
     * [onBackupProgress] is a deterministic concurrency seam for tests.
     */
    fun <T> read(
        source: File,
        onBackupProgress: (remaining: Int, total: Int) -> Unit = { _, _ -> },
        block: (Connection) -> T,
    ): T {
        // A private directory, not bare temp files: createTempFile is 0600 but
        // the backup destination would otherwise be created with default
        // attributes (0644 under a typical umask), and for Login Data it holds
        // usernames, origin URLs and encrypted password blobs.
        // createTempDirectory is 0700 on POSIX; on Windows it inherits the
        // parent's ACL, which is why the password path stays macOS/Linux only.
        val workDir = Files.createTempDirectory("boss-import-")
        val temp = workDir.resolve("snapshot.sqlite")

        try {
            // Explicit driver load: the host shades dependencies, so relying on
            // ServiceLoader discovery alone is fragile.
            Class.forName("org.sqlite.JDBC")

            val readOnly =
                SQLiteConfig().apply {
                    setReadOnly(true)
                }

            // Let SQLite coordinate with the live browser and copy one logical
            // database generation. Its backup API reads the main file and WAL
            // under SQLite's locks and restarts when the source changes between
            // steps. A raw file copy cannot provide that guarantee.
            DriverManager
                .getConnection("jdbc:sqlite:${source.absolutePath}", readOnly.toProperties())
                .use { sourceConnection ->
                    val sqliteConnection = sourceConnection.unwrap(SQLiteConnection::class.java)
                    val result =
                        sqliteConnection.database.backup(
                            "main",
                            temp.toAbsolutePath().toString(),
                            { remaining, total -> onBackupProgress(remaining, total) },
                            BACKUP_BUSY_SLEEP_MILLIS,
                            BACKUP_BUSY_RETRIES,
                            PAGES_PER_BACKUP_STEP,
                        )
                    if (result != SQLiteErrorCode.SQLITE_OK.code) {
                        throw SQLException("SQLite online backup failed with result code $result")
                    }
                }

            // Plain path rather than a file: URI — a Windows path contains
            // backslashes and a drive letter, which URI parsing rejects.
            return DriverManager
                .getConnection("jdbc:sqlite:${temp.toAbsolutePath()}", readOnly.toProperties())
                .use(block)
        } finally {
            runCatching { workDir.toFile().deleteRecursively() }
        }
    }
}
