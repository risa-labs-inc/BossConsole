package ai.rever.boss.recovery.verification

import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.VerificationResult
import ai.rever.boss.recovery.models.VerificationStatus
import ai.rever.boss.recovery.paths.SafePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Independent ground-truth verifier.
 * Executes build and test commands against the live workspace with strict timeout boundaries
 * and produces structured verification evidence.
 *
 * INVARIANTS:
 * - Agent statements are treated as unverified claims.
 * - Non-zero exit code produces [VerificationStatus.FAIL].
 * - Command timeout or crash produces [VerificationStatus.UNKNOWN] (never PASS).
 * - Process is forcefully terminated on timeout to prevent zombie processes.
 */
object IndependentVerifier {

    private const val MAX_OUTPUT_CAPTURE_CHARS = 4096

    /**
     * Executes a verification command in [projectRoot] and produces a ground-truth [VerificationResult].
     */
    suspend fun verify(
        command: String,
        projectRoot: File,
        agentClaim: AgentClaim? = null,
        timeoutMs: Long = 60_000L,
    ): VerificationResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val rootCanonical = SafePathResolver.canonicalRoot(projectRoot)

            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val commandList =
                if (isWindows) {
                    listOf("cmd.exe", "/c", command)
                } else {
                    listOf("sh", "-c", command)
                }

            val processBuilder =
                ProcessBuilder(commandList)
                    .directory(rootCanonical)
                    .redirectErrorStream(false)

            val process: Process
            try {
                process = processBuilder.start()
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                return@withContext VerificationResult(
                    status = VerificationStatus.UNKNOWN,
                    command = command,
                    exitCode = null,
                    stderrSnippet = "Failed to launch process: ${e.message}",
                    durationMs = duration,
                    agentClaim = agentClaim,
                    evidenceSummary = "Process execution error: ${e.message}",
                )
            }

            var stdoutText = ""
            var stderrText = ""

            val stdoutReader =
                Thread {
                    try {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            val sb = StringBuilder()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (sb.length < MAX_OUTPUT_CAPTURE_CHARS) {
                                    sb.append(line).append("\n")
                                }
                            }
                            stdoutText = sb.toString()
                        }
                    } catch (_: Exception) {}
                }

            val stderrReader =
                Thread {
                    try {
                        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                            val sb = StringBuilder()
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (sb.length < MAX_OUTPUT_CAPTURE_CHARS) {
                                    sb.append(line).append("\n")
                                }
                            }
                            stderrText = sb.toString()
                        }
                    } catch (_: Exception) {}
                }

            stdoutReader.start()
            stderrReader.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val duration = System.currentTimeMillis() - startTime

            if (!completed) {
                terminateProcessTree(process)
                stdoutReader.interrupt()
                stderrReader.interrupt()

                return@withContext VerificationResult(
                    status = VerificationStatus.UNKNOWN,
                    command = command,
                    exitCode = null,
                    stdoutSnippet = stdoutText.takeLast(1024),
                    stderrSnippet = "Execution timed out after ${timeoutMs}ms",
                    durationMs = duration,
                    agentClaim = agentClaim,
                    evidenceSummary = "Verification timed out after ${timeoutMs}ms (Process tree destroyed)",
                )
            }

            stdoutReader.join(1000)
            stderrReader.join(1000)

            val exitCode = process.exitValue()
            val status =
                if (exitCode == 0) {
                    VerificationStatus.PASS
                } else {
                    VerificationStatus.FAIL
                }

            val evidenceSummary =
                if (status == VerificationStatus.PASS) {
                    "Ground truth: Exit code 0 (Command executed successfully)"
                } else {
                    "Ground truth: Exit code $exitCode (Command failed)"
                }

            VerificationResult(
                status = status,
                command = command,
                exitCode = exitCode,
                stdoutSnippet = stdoutText.takeLast(2048),
                stderrSnippet = stderrText.takeLast(2048),
                durationMs = duration,
                agentClaim = agentClaim,
                evidenceSummary = evidenceSummary,
            )
        }

    private fun terminateProcessTree(process: Process) {
        try {
            process.descendants().forEach { handle ->
                try {
                    handle.destroyForcibly()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            process.destroyForcibly()
        } catch (_: Exception) {}

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (isWindows && process.isAlive) {
            try {
                val pid = process.pid()
                ProcessBuilder("taskkill", "/F", "/T", "/PID", pid.toString()).start().waitFor(1, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
    }
}
