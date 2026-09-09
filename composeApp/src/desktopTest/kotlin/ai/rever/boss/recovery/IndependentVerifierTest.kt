package ai.rever.boss.recovery

import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.ClaimType
import ai.rever.boss.recovery.models.VerificationStatus
import ai.rever.boss.recovery.verification.IndependentVerifier
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndependentVerifierTest {

    private lateinit var tempProjectRoot: File
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @BeforeTest
    fun setup() {
        tempProjectRoot = kotlin.io.path.createTempDirectory("verifier-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
    }

    @Test
    fun `successful command returns status PASS and exitCode 0`() = runBlocking {
        val cmd = if (isWindows) "echo test_pass" else "echo test_pass"
        val result = IndependentVerifier.verify(cmd, tempProjectRoot)

        assertEquals(VerificationStatus.PASS, result.status)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdoutSnippet.contains("test_pass"))
        assertFalse(result.isDiscrepancy)
    }

    @Test
    fun `failing command returns status FAIL and non-zero exit code`() = runBlocking {
        val cmd = if (isWindows) "exit 1" else "exit 1"
        val result = IndependentVerifier.verify(cmd, tempProjectRoot)

        assertEquals(VerificationStatus.FAIL, result.status)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `detects discrepancy when agent claims TESTS_PASSED but ground truth is FAIL`() = runBlocking {
        val claim = AgentClaim(
            claimType = ClaimType.TESTS_PASSED,
            statement = "All 44 test suites passed successfully with zero failures"
        )
        val cmd = if (isWindows) "exit /b 2" else "exit 2"
        val result = IndependentVerifier.verify(cmd, tempProjectRoot, agentClaim = claim)

        assertEquals(VerificationStatus.FAIL, result.status)
        assertEquals(2, result.exitCode)
        assertTrue(result.isDiscrepancy)
        assertEquals(claim, result.agentClaim)
    }

    @Test
    fun `no discrepancy when agent claims TESTS_PASSED and ground truth is PASS`() = runBlocking {
        val claim = AgentClaim(
            claimType = ClaimType.TESTS_PASSED,
            statement = "All 44 test suites passed successfully"
        )
        val cmd = if (isWindows) "echo All tests passed" else "echo All tests passed"
        val result = IndependentVerifier.verify(cmd, tempProjectRoot, agentClaim = claim)

        assertEquals(VerificationStatus.PASS, result.status)
        assertEquals(0, result.exitCode)
        assertFalse(result.isDiscrepancy)
    }

    @Test
    fun `command timeout produces UNKNOWN status and terminates cleanly`() = runBlocking {
        // Sleep for 3 seconds with a 500ms timeout boundary
        val cmd = if (isWindows) "powershell -Command Start-Sleep -Milliseconds 3000" else "sleep 3"
        val result = IndependentVerifier.verify(cmd, tempProjectRoot, timeoutMs = 500L)

        assertEquals(VerificationStatus.UNKNOWN, result.status)
        assertTrue(result.evidenceSummary.contains("timed out"))
    }
}
