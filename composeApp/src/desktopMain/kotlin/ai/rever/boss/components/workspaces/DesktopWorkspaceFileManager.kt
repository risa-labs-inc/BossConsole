package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

/**
 * Desktop implementation of WorkspaceFileManager
 */
actual class WorkspaceFileManager actual constructor(
    directoryOverride: String?,
) {
    private val logger = BossLogger.forComponent("WorkspaceFileManager")
    private val workspaceDirectory: String by lazy {
        directoryOverride ?: workspaceStateDirectory().absolutePath
    }
    private val legacyMigration: Lazy<Unit> =
        lazy {
            if (directoryOverride == null) {
                try {
                    migrateLegacyWorkspaceDirectory(
                        legacyDirectory =
                            Paths
                                .get(
                                    SystemUtils.getUserHome(),
                                    "Documents",
                                    WorkspaceFileManagerCommon.LEGACY_WORKSPACE_DIRECTORY_NAME,
                                ).toFile(),
                        stateDirectory = File(workspaceDirectory),
                        onSkippedRecord = { skipped ->
                            logger.warn(
                                LogCategory.WORKSPACE,
                                "Skipped unsafe legacy workspace entry",
                                mapOf("fileName" to skipped.name),
                            )
                        },
                    )
                } catch (error: IOException) {
                    reportMigrationFailure(logger, error)
                } catch (error: UncheckedIOException) {
                    reportMigrationFailure(logger, error)
                } catch (error: IllegalArgumentException) {
                    reportMigrationFailure(logger, error)
                } catch (error: IllegalStateException) {
                    reportMigrationFailure(logger, error)
                } catch (error: SecurityException) {
                    reportMigrationFailure(logger, error)
                } catch (error: UnsupportedOperationException) {
                    reportMigrationFailure(logger, error)
                }
            }
        }

    actual fun getDefaultWorkspaceDirectory(): String = workspaceDirectory

    actual suspend fun ensureWorkspaceDirectory(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                legacyMigration.ensureInitialized()
                val dir = File(workspaceDirectory)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                dir.exists() && dir.isDirectory
            } catch (e: Exception) {
                logger.warn(LogCategory.WORKSPACE, "Failed to create workspace directory", error = e)
                false
            }
        }

    actual suspend fun saveWorkspace(
        workspace: LayoutWorkspace,
        fileName: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            saveWorkspaceBlocking(workspace, fileName)
        }

    actual fun saveWorkspaceBlocking(
        workspace: LayoutWorkspace,
        fileName: String?,
    ): String? =
        try {
            legacyMigration.ensureInitialized()
            val dir = File(workspaceDirectory)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // The ID, not the name: see WorkspaceFileManagerCommon.fileNameForId. A caller that
            // knows the Space came from a legacy path passes it explicitly.
            val actualFileName = fileName ?: WorkspaceFileManagerCommon.fileNameForId(workspace.id)

            // Never write the literal ".json": a blank id resolves to it and an explicit
            // fileName is not sanitised, so this is the last place the refusal can live. Every
            // id-less Space would share that one file.
            if (actualFileName == ".json") {
                logger.warn(
                    LogCategory.WORKSPACE,
                    "Refused to save workspace to a nameless file",
                    mapOf("workspace" to workspace.name),
                )
                return null
            }
            val filePath = getWorkspaceFilePath(actualFileName)
            val file = File(filePath)

            // Serialize workspace
            val json = WorkspaceSerializer.serialize(workspace)

            // Atomic: temp sibling + rename. This is the shutdown path, so the
            // process can die mid-write - an in-place writeText would leave a
            // truncated JSON that fails to deserialize on next launch, which is
            // strictly worse than the stale-but-valid file (#19 review).
            file.atomicWriteText(json)

            filePath
        } catch (e: Exception) {
            logger.warn(
                LogCategory.WORKSPACE,
                "Failed to save workspace to disk",
                mapOf("workspace" to workspace.name),
                error = e,
            )
            null
        }

    actual suspend fun loadWorkspace(fileName: String): LayoutWorkspace? =
        withContext(Dispatchers.IO) {
            try {
                legacyMigration.ensureInitialized()
                val filePath = getWorkspaceFilePath(fileName)
                val file = File(filePath)

                if (!file.exists()) {
                    return@withContext null
                }

                val json = file.readText()
                WorkspaceSerializer.deserialize(json)
            } catch (e: SerializationException) {
                // A Space can contain tab URLs, project paths and terminal commands. Decoder
                // messages quote the input document, so never attach one to a host log entry.
                logger.warn(
                    LogCategory.WORKSPACE,
                    "Failed to load workspace file",
                    mapOf("fileName" to fileName) + decodeFailure(e),
                )
                null
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.WORKSPACE,
                    "Failed to load workspace file",
                    mapOf("fileName" to fileName),
                    error = e,
                )
                null
            }
        }

    actual suspend fun listWorkspaces(): List<WorkspaceFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                legacyMigration.ensureInitialized()
                val dir = File(workspaceDirectory)
                if (!dir.exists() || !dir.isDirectory) {
                    return@withContext emptyList()
                }

                dir
                    .listFiles { file ->
                        // ".json" has no stem: it is what a blank id wrote on older builds, and
                        // nothing produces it any more. It is still LISTED - that file is a
                        // real Space (the last id-less import), and the load scan adopts it:
                        // mints a stable id, saves under <id>.json, and removes the nameless
                        // file. Filtering it here would orphan that Space silently.
                        file.isFile && file.name.endsWith(".json")
                    }?.map { file ->
                        WorkspaceFileInfo(
                            fileName = file.name,
                            filePath = file.absolutePath,
                            lastModified = file.lastModified(),
                            size = file.length(),
                        )
                    } ?: emptyList()
            } catch (e: Exception) {
                logger.warn(LogCategory.WORKSPACE, "Failed to list workspace files", error = e)
                emptyList()
            }
        }

    actual suspend fun deleteWorkspace(fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                legacyMigration.ensureInitialized()
                val filePath = getWorkspaceFilePath(fileName)
                val file = File(filePath)

                if (file.exists()) {
                    file.delete()
                } else {
                    false
                }
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.WORKSPACE,
                    "Failed to delete workspace file",
                    mapOf("fileName" to fileName),
                    error = e,
                )
                false
            }
        }

    actual fun getWorkspaceFilePath(fileName: String): String {
        // Every read, write and delete above builds its path here, and each of them catches and
        // logs, so a refused name surfaces as "not found" / "not saved" with a warning rather than
        // as a file outside the directory.
        require(WorkspaceFileManagerCommon.isBareFileName(fileName)) {
            "Workspace file names are bare names inside the workspace directory, got '$fileName'"
        }
        return Paths.get(workspaceDirectory, fileName).toString()
    }

    actual fun writeDocumentBlocking(
        fileName: String,
        content: String?,
    ): Boolean =
        try {
            legacyMigration.ensureInitialized()
            val file = File(getWorkspaceFilePath(fileName))
            if (content == null) {
                // Absent is success: the caller wants it gone, and it is.
                if (file.exists()) file.delete() else true
            } else {
                val dir = File(workspaceDirectory)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                // Atomic, for the reason saveWorkspaceBlocking is: this is the shutdown path, and
                // an in-place write killed halfway leaves JSON that fails to parse next launch.
                file.atomicWriteText(content)
                true
            }
        } catch (e: Exception) {
            logger.warn(
                LogCategory.WORKSPACE,
                "Failed to write workspace document",
                mapOf("fileName" to fileName, "removing" to (content == null).toString()),
                error = e,
            )
            false
        }

    actual suspend fun loadDocument(fileName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                legacyMigration.ensureInitialized()
                val file = File(getWorkspaceFilePath(fileName))
                if (file.exists()) file.readText() else null
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.WORKSPACE,
                    "Failed to read workspace document",
                    mapOf("fileName" to fileName),
                    error = e,
                )
                null
            }
        }
}

private fun reportMigrationFailure(
    logger: ComponentLogger,
    error: Exception,
): Nothing {
    logger.warn(LogCategory.WORKSPACE, "Failed to import legacy workspace records", error = error)
    // Do not expose a partial import to normal reads or writes. This storage operation fails, and
    // the absent marker requires a clean retry before a later operation can access workspaces. The
    // legacy directory remains untouched throughout.
    throw error
}

private fun Lazy<Unit>.ensureInitialized() {
    value
}

internal fun workspaceStateDirectory(resolve: (String) -> File = BossDirectories::resolve): File = resolve("workspaces")

private const val LEGACY_IMPORT_MARKER = ".legacy-documents-import-complete"

/**
 * Copy legacy Space records into the portable BOSS state root without overwriting either side.
 *
 * The old directory is retained as a rollback copy. From this release onward only [stateDirectory]
 * is read and maintained. A later cleanup can remove the legacy copy after operators have verified
 * their migration.
 */
internal fun migrateLegacyWorkspaceDirectory(
    legacyDirectory: File,
    stateDirectory: File,
    forceDirectory: (Path) -> Unit = ::forceDirectoryMetadata,
    onSkippedRecord: (File) -> Unit = {},
    copyRecord: (source: File, target: File) -> Unit = ::copyLegacyRecordAtomically,
) {
    val marker = File(stateDirectory, LEGACY_IMPORT_MARKER)
    if (Files.exists(marker.toPath(), NOFOLLOW_LINKS)) {
        require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS)) {
            "Legacy workspace import marker must be a regular file: ${marker.absolutePath}"
        }
        return
    }
    if (!Files.isDirectory(legacyDirectory.toPath(), NOFOLLOW_LINKS) ||
        legacyDirectory.canonicalFile == stateDirectory.canonicalFile
    ) {
        return
    }
    if (!stateDirectory.isDirectory && !stateDirectory.mkdirs() && !stateDirectory.isDirectory) {
        error("Could not create BOSS workspace state directory: ${stateDirectory.absolutePath}")
    }

    val legacyRecords =
        legacyDirectory.listFiles()
            ?: error("Could not list legacy workspace directory: ${legacyDirectory.absolutePath}")
    legacyRecords
        .filterNot { file -> Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) }
        .forEach(onSkippedRecord)
    val failures =
        legacyRecords
            .filter { file -> Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) }
            .mapNotNull { source ->
                val target = File(stateDirectory, source.name)
                if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) return@mapNotNull null
                runCatching { copyRecord(source, target) }
                    .exceptionOrNull()
                    ?.takeUnless { it is FileAlreadyExistsException }
            }

    if (failures.isNotEmpty()) {
        throw IllegalStateException(
            "Failed to import ${failures.size} legacy workspace record(s)",
            failures.first(),
        )
    }

    finishLegacyMigration(stateDirectory.toPath(), marker.toPath(), forceDirectory)
}

/**
 * Publish a legacy record only after its complete contents are durable in a private sibling.
 *
 * A hard link is the portable JDK primitive that gives this migration both properties it needs:
 * publication is atomic, and an existing [target] is never replaced. `ATOMIC_MOVE` cannot provide
 * the second property because providers are allowed to replace the target when that option is set.
 * The temporary and target paths are siblings, so a supported hard link never crosses a file
 * system. Providers without hard-link support fail safely: the target remains absent, the marker
 * is not written, workspace access stays unavailable for this process, and the next launch retries.
 */
internal fun copyLegacyRecordAtomically(
    source: File,
    target: File,
    deleteTemporary: (Path) -> Unit = { temporary -> Files.deleteIfExists(temporary) },
    beforePublish: (Path) -> Unit = {},
) {
    val parent = target.parentFile.toPath()
    val temporary = Files.createTempFile(parent, ".${target.name}-", ".tmp")
    try {
        if ("posix" in temporary.fileSystem.supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(temporary, setOf(OWNER_READ, OWNER_WRITE))
        }
        Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(temporary, WRITE, TRUNCATE_EXISTING).use { output ->
                input.copyTo(output)
            }
        }
        FileChannel.open(temporary, WRITE).use { channel -> channel.force(true) }
        beforePublish(temporary)
        // createLink is an atomic create-new publication: a current-state writer that wins the
        // race leaves FileAlreadyExistsException here and its bytes remain authoritative.
        Files.createLink(target.toPath(), temporary)
    } finally {
        cleanupMigrationTemporary(temporary, deleteTemporary)
    }
}

private fun cleanupMigrationTemporary(
    temporary: Path,
    deleteTemporary: (Path) -> Unit,
) {
    try {
        deleteTemporary(temporary)
    } catch (_: IOException) {
        // Cleanup must not mask a publication failure or invalidate a published record.
    } catch (_: SecurityException) {
        // Leaving an ignored .tmp orphan is safer than failing an otherwise valid publication.
    }
}

private fun forceDirectoryMetadata(directory: Path) {
    if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
        FileChannel.open(directory, READ).use { channel -> channel.force(true) }
    }
}

private fun finishLegacyMigration(
    stateDirectory: Path,
    marker: Path,
    forceDirectory: (Path) -> Unit,
) {
    // Persist every published record name before a durable marker can suppress retries. Directory
    // forcing is a durability improvement, but several POSIX-looking network and FUSE providers
    // reject directory channels. Do not let that cause later-deleted records to be resurrected.
    forceDirectoryBestEffort(stateDirectory, forceDirectory)

    val markerCreated =
        try {
            Files.createFile(marker)
            true
        } catch (_: FileAlreadyExistsException) {
            // Another BOSS process completed the same one-shot migration.
            require(Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
                "Legacy workspace import marker must be a regular file: $marker"
            }
            false
        }
    if (markerCreated) {
        forceMarkerBestEffort(marker)
    }
    forceDirectoryBestEffort(stateDirectory, forceDirectory)
}

private fun forceMarkerBestEffort(marker: Path) {
    // Only the process that created the marker opens it. This avoids following a marker path that
    // another local process could replace with a symlink during the create race.
    try {
        FileChannel.open(marker, WRITE, NOFOLLOW_LINKS).use { channel -> channel.force(true) }
    } catch (_: IOException) {
        // The marker is an empty existence flag; some providers reject explicit forcing.
    } catch (_: UnsupportedOperationException) {
        // Keep the successfully completed one-shot migration on limited providers.
    }
}

private fun forceDirectoryBestEffort(
    directory: Path,
    forceDirectory: (Path) -> Unit,
) {
    try {
        forceDirectory(directory)
    } catch (_: IOException) {
        // POSIX attribute support does not guarantee that the provider accepts directory fsync.
    } catch (_: UnsupportedOperationException) {
        // The migration remains logically complete without this additional durability barrier.
    } catch (_: SecurityException) {
        // A sandbox may permit normal state I/O while refusing a directory channel.
    }
}
