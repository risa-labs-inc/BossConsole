package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairHint
import ai.rever.boss.ipc.proto.RepairStrategy
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepairEngineTest {
    private lateinit var dataDir: File
    private lateinit var projectRoot: File
    private lateinit var outsideDir: File
    private lateinit var snapshots: SnapshotManager

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("boss-engine-data").toFile()
        projectRoot = Files.createTempDirectory("boss-engine-project").toFile()
        outsideDir = Files.createTempDirectory("boss-engine-outside").toFile()
        snapshots = SnapshotManager(dataDir)
    }

    @AfterTest
    fun cleanup() {
        listOf(dataDir, projectRoot, outsideDir).forEach { it.deleteRecursively() }
    }

    /** Records what it was asked for instead of proposing anything. */
    private class RecordingAiClient : AiRepairClient {
        var sourceFiles: Map<String, String>? = null

        override suspend fun proposeSourceFix(
            rootCause: String,
            sourceFiles: Map<String, String>,
            stackTrace: String,
            errorMessage: String,
        ): SourceFixProposal? {
            this.sourceFiles = sourceFiles
            return null
        }

        override suspend fun proposeConfigFix(
            processId: String,
            rootCause: String,
            suggestedFix: String?,
            errorMessage: String,
        ): ConfigFixProposal? = null
    }

    private fun engine(
        aiClient: AiRepairClient? = null,
        root: File? = projectRoot,
        onRequestRestart: suspend (String, List<String>) -> Unit = { _, _ -> },
    ) = RepairEngine(
        analyzer = CrashAnalyzer(),
        snapshotManager = snapshots,
        aiClient = aiClient,
        projectRoot = root?.absolutePath,
        onRequestRestart = onRequestRestart,
    )

    /** A report whose manifest names [sourceFiles] and which forces [strategy]. */
    private fun report(
        processId: String,
        strategy: RepairStrategy,
        sourceFiles: List<String> = emptyList(),
    ): ProcessFailureReport =
        ProcessFailureReport
            .newBuilder()
            .setProcessId(processId)
            .setErrorType("ai.rever.Boom")
            .setErrorMessage("boom")
            .setConsecutiveFailures(1)
            .setManifest(
                ProcessManifest
                    .newBuilder()
                    .addAllSourceFiles(sourceFiles)
                    // A HIGH-confidence hint makes the strategy the engine runs deterministic.
                    .addRepairHints(
                        RepairHint
                            .newBuilder()
                            .setFailurePattern("Boom")
                            .setRepairStrategy(strategy)
                            .setDescription("forced by test")
                            .build(),
                    ).build(),
            ).build()

    // ---- manifest source files are confined to the project root ----

    @Test
    fun `repair source reads bound both bytes and file count`() =
        runTest {
            val files =
                (0..RepairLimits.SOURCE_COUNT).map { index ->
                    "source-$index.kt".also { name -> File(projectRoot, name).writeText("source code") }
                }
            File(projectRoot, files.first()).writeText("x".repeat(RepairLimits.SOURCE_BYTES + 1))
            val ai = RecordingAiClient()
            engine(aiClient = ai).handleFailure(
                report("bounded", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE, files),
            )
            assertEquals(files.take(RepairLimits.SOURCE_COUNT).drop(1).toSet(), ai.sourceFiles?.keys)
        }

    @Test
    fun `a manifest source file inside the project root is read`() =
        runTest {
            File(projectRoot, "src/App.kt").also { it.parentFile.mkdirs() }.writeText("inside the project")
            val ai = RecordingAiClient()

            engine(aiClient = ai).handleFailure(
                report("p1", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE, listOf("src/App.kt")),
            )

            assertEquals(mapOf("src/App.kt" to "inside the project"), ai.sourceFiles)
        }

    @Test
    fun `no project root means no source file is read, however it is named`() =
        runTest {
            // The shipped configuration: OrchestratorMain names no root, so reads are off
            // rather than confined to whatever the child's working directory happened to be.
            val inside = File(projectRoot, "src/App.kt").also { it.parentFile.mkdirs() }
            inside.writeText("inside the project")
            val ai = RecordingAiClient()

            engine(aiClient = ai, root = null).handleFailure(
                report(
                    "p0",
                    RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE,
                    listOf("src/App.kt", inside.absolutePath),
                ),
            )

            assertEquals(emptyMap(), ai.sourceFiles)
        }

    @Test
    fun `an absolute manifest source file outside the project root is not read`() =
        runTest {
            val outside = File(outsideDir, "elsewhere.txt").also { it.writeText("not the project's file") }
            val ai = RecordingAiClient()

            engine(aiClient = ai).handleFailure(
                report("p2", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE, listOf(outside.absolutePath)),
            )

            assertEquals(emptyMap(), ai.sourceFiles)
        }

    @Test
    fun `a manifest source file reached through dot-dot is not read`() =
        runTest {
            val outside = File(outsideDir, "elsewhere.txt").also { it.writeText("not the project's file") }
            val viaDotDot = "../${outsideDir.name}/${outside.name}"
            val ai = RecordingAiClient()

            engine(aiClient = ai, root = projectRoot).handleFailure(
                report("p3", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE, listOf(viaDotDot)),
            )

            assertEquals(emptyMap(), ai.sourceFiles)
        }

    @Test
    fun `a manifest source file reached through a symlink out of the project root is not read`() =
        runTest {
            val outside = File(outsideDir, "elsewhere.txt").also { it.writeText("not the project's file") }
            val link = File(projectRoot, "link")
            try {
                Files.createSymbolicLink(link.toPath(), outsideDir.toPath())
            } catch (_: Exception) {
                return@runTest // this platform will not create symlinks unprivileged
            }
            val ai = RecordingAiClient()

            engine(aiClient = ai).handleFailure(
                report("p4", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE, listOf("link/${outside.name}")),
            )

            assertEquals(emptyMap(), ai.sourceFiles)
        }

    @Test
    fun `a refused source file does not stop the readable ones`() =
        runTest {
            File(projectRoot, "Good.kt").writeText("readable")
            val outside = File(outsideDir, "elsewhere.txt").also { it.writeText("not the project's file") }
            val ai = RecordingAiClient()

            engine(aiClient = ai).handleFailure(
                report(
                    "p5",
                    RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE,
                    listOf(outside.absolutePath, "Good.kt"),
                ),
            )

            assertEquals(mapOf("Good.kt" to "readable"), ai.sourceFiles)
        }

    // ---- a state reset keeps the snapshots a rollback needs ----

    @Test
    fun `a state reset restarts the process and names its latest snapshot`() =
        runTest {
            snapshots.save("p6", "old state".toByteArray())
            Thread.sleep(SNAPSHOT_TIMESTAMP_GAP_MS)
            val newest = snapshots.save("p6", "newer state".toByteArray())
            val restarted = mutableListOf<String>()

            val outcome =
                engine(onRequestRestart = { id, _ -> restarted.add(id) })
                    .handleFailure(report("p6", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            assertEquals(listOf("p6"), restarted, "a state reset must still restart the process")
            assertEquals(RepairOutcome.StateReset("p6", newest), outcome)
        }

    @Test
    fun `a state reset leaves every snapshot in place for a later rollback`() =
        runTest {
            val first = snapshots.save("p7", "first".toByteArray())
            Thread.sleep(SNAPSHOT_TIMESTAMP_GAP_MS)
            val second = snapshots.save("p7", "second".toByteArray())

            engine().handleFailure(report("p7", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            val remaining = snapshots.listSnapshots("p7").map { it.id }
            assertEquals(setOf(first, second), remaining.toSet(), "no snapshot may be deleted by a repair")
            assertNotNull(snapshots.loadLatest("p7"), "a rollback must still have something to restore")
            assertEquals("second", snapshots.loadLatest("p7")?.decodeToString())
        }

    @Test
    fun `a state reset with no snapshot recorded is a plain restart`() =
        runTest {
            val restarted = mutableListOf<String>()

            val outcome =
                engine(onRequestRestart = { id, _ -> restarted.add(id) })
                    .handleFailure(report("p8", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            assertEquals(listOf("p8"), restarted)
            assertEquals(RepairOutcome.StateReset("p8", null), outcome)
        }

    @Test
    fun `a state reset whose restart fails is reported as failed`() =
        runTest {
            snapshots.save("p9", "state".toByteArray())

            val outcome =
                engine(onRequestRestart = { _, _ -> error("kernel unreachable") })
                    .handleFailure(report("p9", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            val failed = assertNotNull(outcome as? RepairOutcome.Failed, "got $outcome")
            assertTrue(failed.reason.contains("State reset failed"), failed.reason)
            // ...and it did not take the snapshots down with it.
            assertNotNull(snapshots.loadLatest("p9"))
        }

    @Test
    fun `an unusable project root leaves no source file readable`() =
        runTest {
            val absentRoot = File(projectRoot, "never-created")
            File(projectRoot, "Good.kt").writeText("readable")
            val ai = RecordingAiClient()

            engine(aiClient = ai, root = absentRoot).handleFailure(
                report("p10", RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE, listOf("Good.kt")),
            )

            assertEquals(emptyMap(), ai.sourceFiles)
            assertNull(AllowedRoots.of(absentRoot).resolve(File(projectRoot, "Good.kt")))
        }

    private companion object {
        /** Snapshot ordering is by millisecond timestamp, so two saves must not share one. */
        const val SNAPSHOT_TIMESTAMP_GAP_MS = 5L
    }
}
