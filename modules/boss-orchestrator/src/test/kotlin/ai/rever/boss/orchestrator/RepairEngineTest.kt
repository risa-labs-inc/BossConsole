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
            val second = snapshots.save("p7", "second".toByteArray())

            engine().handleFailure(report("p7", RepairStrategy.REPAIR_STRATEGY_RESET_STATE))

            val remaining = snapshots.listSnapshots("p7").map { it.id }
            assertEquals(setOf(first, second), remaining.toSet(), "no snapshot may be deleted by a repair")
            assertNotNull(snapshots.loadLatest("p7"), "a rollback must still have something to restore")
            assertEquals("second", snapshots.loadLatest("p7")?.decodeToString())
        }

    @Test
    fun `a tuned restart records its JVM args in the outcome (#978)`() =
        runTest {
            val capturedArgs = mutableListOf<List<String>>()

            val tunedReport =
                report("p9", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                    .toBuilder()
                    .addAllCurrentJvmArgs(listOf("-Xmx1024m"))
                    .build()

            val outcome =
                engine(onRequestRestart = { _, args -> capturedArgs.add(args) })
                    .handleFailure(tunedReport)

            // The 3/2 growth: 1024 -> 1536. Other flags (none here) would be preserved.
            assertEquals(listOf(listOf("-Xmx1536m")), capturedArgs)
            assertEquals(RepairOutcome.Restarted("p9", listOf("-Xmx1536m")), outcome)
        }

    @Test
    fun `a plain restart records an empty JVM-args override (#978)`() =
        runTest {
            val outcome =
                engine(onRequestRestart = { _, _ -> })
                    .handleFailure(report("p10", RepairStrategy.REPAIR_STRATEGY_RESTART))

            assertEquals(RepairOutcome.Restarted("p10", emptyList()), outcome)
        }

    // ---- the tuned heap grows each restart, capped and floored correctly (#980) ----

    @Test
    fun `a tuned restart grows the configured heap by 50 percent (#980)`() =
        runTest {
            // The follow-up review caught that `maxOf(512MB, current heap)` left a
            // 2 GB plugin at 2 GB on every "tuned" restart - the label was a lie. Each
            // restart must move the heap upward; here 2 GB -> 3 GB.
            val capturedArgs = mutableListOf<List<String>>()

            val report =
                report("p-big", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                    .toBuilder()
                    .addAllCurrentJvmArgs(listOf("-Xmx2048m"))
                    .build()

            val outcome =
                engine(onRequestRestart = { _, args -> capturedArgs.add(args) })
                    .handleFailure(report)

            assertEquals(listOf(listOf("-Xmx3072m")), capturedArgs)
            assertEquals(RepairOutcome.Restarted("p-big", listOf("-Xmx3072m")), outcome)
        }

    @Test
    fun `a tuned restart applies the floor when the configured heap is below it (#980)`() =
        runTest {
            // 256 MB * 3/2 = 384 MB, which is below the 512 MB floor; the floor wins.
            val report =
                report("p-small", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                    .toBuilder()
                    .addAllCurrentJvmArgs(listOf("-Xmx256m"))
                    .build()

            val outcome =
                engine(onRequestRestart = { _, _ -> }).handleFailure(report)

            assertEquals(RepairOutcome.Restarted("p-small", listOf("-Xmx512m")), outcome)
        }

    @Test
    fun `a tuned restart leaves args alone when no -Xmx is present (#980)`() =
        runTest {
            // Without an -Xmx to compare against, the previous code dropped to 512 MB
            // and could lower a user's heap. Returning the args unchanged means the
            // user's configured JVM keeps whatever heap it had.
            val outcome =
                engine(onRequestRestart = { _, _ -> })
                    .handleFailure(report("p-default", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED))

            assertEquals(RepairOutcome.Restarted("p-default", emptyList()), outcome)
        }

    @Test
    fun `a tuned restart leaves args alone when -Xmx is malformed (#980)`() =
        runTest {
            // An unparseable -Xmx is treated the same as no -Xmx: we don't know
            // what the user has, so we keep the args unchanged.
            val report =
                report("p-malformed", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                    .toBuilder()
                    .addAllCurrentJvmArgs(listOf("-Xmxgarbage", "-Xss2m"))
                    .build()

            val outcome =
                engine(onRequestRestart = { _, _ -> }).handleFailure(report)

            assertEquals(
                RepairOutcome.Restarted("p-malformed", listOf("-Xmxgarbage", "-Xss2m")),
                outcome,
            )
        }

    @Test
    fun `a tuned restart grows the heap on each OOM until it hits the cap (#980)`() =
        runTest {
            // The follow-up review's mutation check: the heap must visibly grow on
            // repeat OOMs (1g -> 1.5g -> 2.25g -> ...) and stop growing once it
            // reaches the cap, so a restarted-at-the-same-heap loop is impossible.
            val capturedArgs = mutableListOf<List<String>>()

            // Start at 1g and feed each outcome back as the next report's currentJvmArgs,
            // driving the ladder through 4 OOMs (1024 -> 1536 -> 2304 -> 3456 -> 5184).
            var report =
                report("p-grow", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                    .toBuilder()
                    .addAllCurrentJvmArgs(listOf("-Xmx1024m"))
                    .build()

            repeat(4) { _ ->
                val outcome =
                    engine(onRequestRestart = { _, args -> capturedArgs.add(args) })
                        .handleFailure(report)
                val args =
                    when (outcome) {
                        is RepairOutcome.Restarted -> outcome.jvmArgs
                        else -> emptyList()
                    }
                // Feed the result back so the next restart grows the previous heap.
                report =
                    report
                        .toBuilder()
                        .clearCurrentJvmArgs()
                        .addAllCurrentJvmArgs(args)
                        .build()
            }

            assertEquals(
                listOf(
                    listOf("-Xmx1536m"),
                    listOf("-Xmx2304m"),
                    listOf("-Xmx3456m"),
                    listOf("-Xmx5184m"),
                ),
                capturedArgs,
                "the heap must visibly grow on every tuned restart (1024 -> 1536 -> 2304 -> 3456 -> 5184)",
            )
        }

    @Test
    fun `a tuned restart preserves every other JVM flag in order (#980)`() =
        runTest {
            // The follow-up review's third ask: replacing -Xmx must swap the single
            // entry and leave every other flag untouched. -Xss, -D..., GC settings,
            // module flags all stay where the user put them.
            val capturedArgs = mutableListOf<List<String>>()

            val report =
                report("p-flags", RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                    .toBuilder()
                    .addAllCurrentJvmArgs(
                        listOf(
                            "-Xss2m",
                            "-Xmx512m",
                            "-Dfile.encoding=UTF-8",
                            "--add-opens=java.base/java.lang=ALL-UNNAMED",
                            "-XX:+UseG1GC",
                            "-XX:MaxGCPauseMillis=200",
                        ),
                    ).build()

            val outcome =
                engine(onRequestRestart = { _, args -> capturedArgs.add(args) })
                    .handleFailure(report)

            // 512 MB * 3/2 = 768 MB; the existing -Xmx is swapped in place (third
            // position), every other flag keeps its original slot.
            val expected =
                listOf(
                    "-Xss2m",
                    "-Xmx768m",
                    "-Dfile.encoding=UTF-8",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "-XX:+UseG1GC",
                    "-XX:MaxGCPauseMillis=200",
                )
            assertEquals(listOf(expected), capturedArgs)
            assertEquals(RepairOutcome.Restarted("p-flags", expected), outcome)
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
}
