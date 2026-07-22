package ai.rever.boss.updater

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * Security tests for UpdateScriptGenerator
 *
 * These tests verify that malicious inputs are properly sanitized to prevent:
 * - Command injection via shell metacharacters
 * - Path traversal attacks
 * - Script injection via newlines
 */
class UpdateScriptGeneratorSecurityTest {

    /**
     * Test that command substitution patterns are rejected
     */
    @Test
    fun `test command substitution is rejected`() {
        val maliciousPaths = listOf(
            "/tmp/file\$(rm -rf /tmp).dmg",
            "/tmp/file`whoami`.dmg",
            "/tmp/file\${malicious}.dmg"
        )

        maliciousPaths.forEach { path ->
            val exception = assertThrows<SecurityException> {
                UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = path,
                    targetAppPath = "/Applications/BOSS.app",
                    appPid = 12345
                )
            }
            assertTrue(
                exception.message?.contains("shell metacharacters") == true,
                "Should reject command substitution: $path"
            )
        }
    }

    /**
     * Test that backtick command substitution is rejected
     */
    @Test
    fun `test backtick command substitution is rejected`() {
        val maliciousPath = "/tmp/file`whoami`.dmg"

        val exception = assertThrows<SecurityException> {
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = maliciousPath,
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345
            )
        }
        assertTrue(
            exception.message?.contains("shell metacharacters") == true,
            "Should reject backtick command substitution"
        )
    }

    /**
     * Test that newline injection is rejected
     */
    @Test
    fun `test newline injection is rejected`() {
        val maliciousPaths = listOf(
            "/tmp/file.dmg\nrm -rf /tmp",
            "/tmp/file.dmg\r\nmalicious",
            "/tmp/file.dmg\rmalicious"
        )

        maliciousPaths.forEach { path ->
            val exception = assertThrows<SecurityException> {
                UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = path,
                    targetAppPath = "/Applications/BOSS.app",
                    appPid = 12345
                )
            }
            assertTrue(
                exception.message?.contains("newline") == true,
                "Should reject newline injection: ${path.replace("\n", "\\n").replace("\r", "\\r")}"
            )
        }
    }

    /**
     * Test that null byte injection is rejected
     */
    @Test
    fun `test null byte injection is rejected`() {
        val maliciousPath = "/tmp/file\u0000malicious.dmg"

        val exception = assertThrows<SecurityException> {
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = maliciousPath,
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345
            )
        }
        assertTrue(
            exception.message?.contains("null byte") == true,
            "Should reject null byte injection"
        )
    }

    /**
     * Test that Windows script generation validates MSI paths
     */
    @Test
    fun `test Windows script validation`() {
        val maliciousPath = "/tmp/file\$(malicious).msi"

        val exception = assertThrows<SecurityException> {
            UpdateScriptGenerator.generateWindowsUpdateScript(
                msiPath = maliciousPath,
                appPid = 12345
            )
        }
        assertTrue(
            exception.message?.contains("shell metacharacters") == true,
            "Windows script should also validate paths"
        )
    }

    /**
     * Test that legitimate paths with spaces work correctly
     */
    @Test
    fun `test legitimate paths with spaces work correctly`() {
        val legitimatePath = "/tmp/BOSS Updates/BOSS-8.12.14-Universal.dmg"

        // Should not throw exception
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = legitimatePath,
            targetAppPath = "/Applications/BOSS.app",
            appPid = 12345
        )

        // Verify script was generated
        assertTrue(scriptFile.exists(), "Script should be generated for legitimate path with spaces")

        // Read script and verify escaped path is used
        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("'/tmp/BOSS Updates/BOSS-8.12.14-Universal.dmg'"),
            "Script should contain properly escaped path with spaces"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that legitimate paths with single quotes are properly escaped
     */
    @Test
    fun `test paths with single quotes are properly escaped`() {
        val pathWithQuote = "/tmp/User's Files/BOSS-8.12.14.dmg"

        // Should not throw exception
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = pathWithQuote,
            targetAppPath = "/Applications/BOSS.app",
            appPid = 12345
        )

        // Verify script was generated
        assertTrue(scriptFile.exists(), "Script should be generated for path with single quote")

        // Read script and verify properly escaped
        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("'/tmp/User'\\''s Files/BOSS-8.12.14.dmg'"),
            "Script should contain properly escaped single quote using '\\'' pattern"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that script self-destructs properly
     */
    @Test
    fun `test generated script contains self-destruct command`() {
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BOSS.app",
            appPid = 12345
        )

        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("rm -f") || scriptContent.contains("del"),
            "Script should contain self-destruct command"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that generated script strips the quarantine attribute after install so
     * the relaunched app is not App-Translocated by Gatekeeper (which would break
     * future in-place updates). Regression test for the "update does not restart" bug.
     */
    @Test
    fun `test generated script strips quarantine attribute`() {
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BOSS.app",
            appPid = 12345
        )

        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("xattr -dr com.apple.quarantine '/Applications/BOSS.app'"),
            "Script should strip the quarantine attribute from the installed bundle"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that the generated script retries the relaunch if the first `open` fails,
     * so a transient LaunchServices failure does not leave the app un-restarted.
     */
    @Test
    fun `test generated script retries relaunch on failure`() {
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BOSS.app",
            appPid = 12345
        )

        val scriptContent = scriptFile.readText()
        val expectedRetryBlock = """
            open '/Applications/BOSS.app'
            if [ ${'$'}? -ne 0 ]; then
                echo "First relaunch attempt failed - retrying in 2s..."
                sleep 2
                open '/Applications/BOSS.app' || echo "Relaunch failed - please start BOSS manually"
            fi
        """.trimIndent()
        assertTrue(
            scriptContent.contains(expectedRetryBlock),
            "Script should retry the escaped app path after two seconds and provide a manual-launch fallback"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that generated script waits for PID termination
     */
    @Test
    fun `test generated script waits for PID`() {
        val testPid = 99999L
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BOSS.app",
            appPid = testPid
        )

        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("kill -0 $testPid"),
            "Script should wait for specific PID: $testPid"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that script has executable permissions (Unix)
     */
    @Test
    fun `test generated script is executable`() {
        val scriptFile = UpdateScriptGenerator.generateMacOSUpdateScript(
            dmgPath = "/tmp/update.dmg",
            targetAppPath = "/Applications/BOSS.app",
            appPid = 12345
        )

        assertTrue(scriptFile.exists(), "Script file should exist")
        assertTrue(scriptFile.canExecute(), "Script should be executable")

        // Cleanup
        scriptFile.delete()
    }

    // ==================== NEW TESTS: HARDENED VALIDATION ====================

    /**
     * Test that path traversal (..) is now rejected as hard failure
     * (Previously was just a warning)
     */
    @Test
    fun `test path traversal is rejected as error`() {
        val pathWithTraversal = "/tmp/../../../etc/passwd"

        val exception = assertThrows<SecurityException> {
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = pathWithTraversal,
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345
            )
        }
        assertTrue(
            exception.message?.contains("path traversal") == true,
            "Path traversal should be rejected with clear error message"
        )
    }

    /**
     * Test that command separators are rejected as hard failures
     * (Previously were just warnings)
     */
    @Test
    fun `test command separators are rejected as errors`() {
        val maliciousPaths = listOf(
            "/tmp/file;rm -rf /.dmg",
            "/tmp/file|malicious.dmg",
            "/tmp/file&malicious.dmg"
        )

        maliciousPaths.forEach { path ->
            val exception = assertThrows<SecurityException> {
                UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = path,
                    targetAppPath = "/Applications/BOSS.app",
                    appPid = 12345
                )
            }
            assertTrue(
                exception.message?.contains("command separator") == true,
                "Command separator should be rejected: $path"
            )
        }
    }

    /**
     * Test that Windows batch metacharacters are rejected
     * (New validation for Windows-specific attack vectors)
     */
    @Test
    fun `test Windows batch metacharacters are rejected`() {
        val windowsMetacharPaths = listOf(
            "/tmp/file%malicious%.dmg",  // Variable expansion
            "/tmp/file^malicious.dmg",   // Escape character
            "/tmp/file!malicious!.dmg"   // Delayed expansion
        )

        windowsMetacharPaths.forEach { path ->
            val exception = assertThrows<SecurityException> {
                UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = path,
                    targetAppPath = "/Applications/BOSS.app",
                    appPid = 12345
                )
            }
            assertTrue(
                exception.message?.contains("Windows batch metacharacters") == true,
                "Windows metacharacter should be rejected: $path"
            )
        }
    }

    // ==================== NEW TESTS: WINDOWS ESCAPING ====================

    /**
     * Test that Windows batch files properly escape percent signs
     */
    @Test
    fun `test Windows batch percent sign escaping`() {
        // Create a legitimate path with percent sign (unlikely but possible in temp dirs)
        // Note: Due to hardened validation, % is now rejected
        // This test verifies the rejection works correctly
        val pathWithPercent = "C:\\tmp\\file%test%.msi"

        val exception = assertThrows<SecurityException> {
            UpdateScriptGenerator.generateWindowsUpdateScript(
                msiPath = pathWithPercent,
                appPid = 12345
            )
        }
        assertTrue(
            exception.message?.contains("Windows batch metacharacters") == true,
            "Percent signs should be rejected by validation"
        )
    }

    /**
     * Test that Windows batch files properly escape double quotes
     */
    @Test
    fun `test Windows batch quote escaping with legitimate path`() {
        // Use a path without special characters that will pass validation
        val legitimatePath = "C:\\Program Files\\BOSS\\updates\\BOSS-8.12.14.msi"

        val scriptFile = UpdateScriptGenerator.generateWindowsUpdateScript(
            msiPath = legitimatePath,
            appPid = 12345
        )

        assertTrue(scriptFile.exists(), "Windows script should be generated")

        // Read script and verify path is properly quoted
        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("\"$legitimatePath\""),
            "Windows script should contain properly quoted path"
        )

        // Cleanup
        scriptFile.delete()
    }

    /**
     * Test that Windows script handles paths with spaces correctly
     */
    @Test
    fun `test Windows script handles spaces correctly`() {
        val pathWithSpaces = "C:\\Program Files\\BOSS Updates\\BOSS-8.12.14.msi"

        val scriptFile = UpdateScriptGenerator.generateWindowsUpdateScript(
            msiPath = pathWithSpaces,
            appPid = 12345
        )

        assertTrue(scriptFile.exists(), "Script should be generated for path with spaces")

        // Read script and verify quoted
        val scriptContent = scriptFile.readText()
        assertTrue(
            scriptContent.contains("\"$pathWithSpaces\""),
            "Path with spaces should be quoted"
        )

        // Cleanup
        scriptFile.delete()
    }
}
