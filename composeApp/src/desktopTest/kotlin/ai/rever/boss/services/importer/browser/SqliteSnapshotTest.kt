package ai.rever.boss.services.importer.browser

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteSnapshotTest {
    @Test
    fun `includes committed rows that exist only in the WAL`() {
        withWalDatabase { source, writer ->
            writer.createStatement().use { statement ->
                statement.executeUpdate("INSERT INTO entries(value) VALUES ('wal-only')")
            }

            val values = SqliteSnapshot.read(source.toFile(), block = ::readValues)

            assertEquals(listOf("wal-only"), values)
        }
    }

    @Test
    fun `online backup restarts when the browser writes between steps`() {
        withWalDatabase { source, writer ->
            writer.autoCommit = false
            writer.prepareStatement("INSERT INTO payload(content) VALUES (randomblob(4096))").use { insert ->
                repeat(600) { insert.addBatch() }
                insert.executeBatch()
            }
            writer.commit()
            writer.autoCommit = true

            var wroteDuringBackup = false
            var sawIncompleteStep = false
            val values =
                SqliteSnapshot.read(
                    source.toFile(),
                    onBackupProgress = { remaining, _ ->
                        if (remaining > 0) sawIncompleteStep = true
                        if (remaining > 0 && !wroteDuringBackup) {
                            writer.createStatement().use { statement ->
                                statement.executeUpdate("INSERT INTO entries(value) VALUES ('during-backup')")
                                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                            }
                            wroteDuringBackup = true
                        }
                    },
                    block = ::readValues,
                )

            assertTrue(sawIncompleteStep, "fixture must span more than one backup step")
            assertTrue(wroteDuringBackup)
            assertEquals(listOf("during-backup"), values)
        }
    }

    @Test
    fun `temporary snapshot is removed after the reader fails`() {
        val tempRoot = Files.createTempDirectory("sqlite-snapshot-cleanup-")
        val source = tempRoot.resolve("source.sqlite")
        val before = bossImportDirectories()

        try {
            createConnection(source.toString()).use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE entries(value TEXT)") }
            }

            assertFailsWith<IllegalStateException> {
                SqliteSnapshot.read(source.toFile()) { error("reader failed") }
            }

            assertEquals(before, bossImportDirectories())
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }

    private fun withWalDatabase(block: (java.nio.file.Path, Connection) -> Unit) {
        val directory = Files.createTempDirectory("sqlite-snapshot-source-")
        val source = directory.resolve("source.sqlite")
        try {
            createConnection(source.toString()).use { writer ->
                writer.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA wal_autocheckpoint=0")
                    statement.execute("CREATE TABLE entries(value TEXT)")
                    statement.execute("CREATE TABLE payload(content BLOB)")
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                }
                block(source, writer)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun createConnection(path: String): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:$path")
    }

    private fun readValues(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT value FROM entries ORDER BY rowid").use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString(1))
                }
            }
        }

    private fun bossImportDirectories(): Set<String> {
        val root = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(root).use { paths ->
            paths
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("boss-import-") }
                .map { it.toAbsolutePath().normalize().toString() }
                .toList()
                .toSet()
        }
    }
}
