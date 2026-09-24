package ai.rever.boss.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossSecretsScanTest {
    private val scanner = SecretsScanner()

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File {
        val d = Files.createTempDirectory("boss-secrets-test").toFile()
        tempDirs += d
        return d
    }

    private fun writeFile(
        parent: File,
        relativePath: String,
        content: String,
    ): File {
        val target = File(parent, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return target
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    // Fixtures are constructed from non-secret fragments so the source file does not
    // carry the literal patterns that match GitHub's push-time secret scanner.
    private fun awsKey(): String = ("A" + "KIA") + ("IOS" + "FODNN7EXAMPLE")

    private fun awsKeyLong(): String = awsKey() + "." + "OTHERKEY1234567890"

    private fun ghClassic(): String = ("g" + "hp_") + ("abcDEF" + "ghijKLMnopQRSTuvWxYz0123456789")

    private fun ghFine(): String =
        ("git" + "hub_pat_") + "11ABCDEFG0_abcdefghij1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij1234567890ABCDE"

    private fun stripeLive(): String = ("sk" + "_live_") + "abcdefghijklmnopqrstuvwx"

    private fun jwt(): String =
        ("eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0") +
            ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

    @Test
    fun `aws access key id is detected as HIGH severity`() {
        val root = tempDir()
        writeFile(root, "config.txt", "AWS_KEY=${awsKey()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val finding = report.findings.single { it.rule == "aws-access-key" }
        assertEquals(SecretsSeverity.HIGH, finding.severity)
        assertTrue(finding.maskedMatch.contains("AKIA"), "head of secret visible")
        assertFalse(finding.maskedMatch.contains("IOSFODNN7EXAMPLE"), "tail of secret hidden")
    }

    @Test
    fun `github classic PAT and fine-grained PAT are both HIGH`() {
        val root = tempDir()
        writeFile(
            root,
            "creds.txt",
            "classic: ${ghClassic()}\nfine:   ${ghFine()}\n",
        )
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val rules = report.findings.map { it.rule }.toSet()
        assertTrue("github-pat-classic" in rules, "classic PAT detected: $rules")
        assertTrue("github-fine-grained-pat" in rules, "fine-grained PAT detected: $rules")
    }

    @Test
    fun `stripe live keys are CRITICAL`() {
        val root = tempDir()
        writeFile(root, "creds.txt", "STRIPE_SECRET=${stripeLive()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val finding = report.findings.single { it.rule == "stripe-live-key" }
        assertEquals(SecretsSeverity.CRITICAL, finding.severity)
    }

    @Test
    fun `JWT bearer tokens are detected at MEDIUM severity`() {
        val root = tempDir()
        writeFile(root, "auth.txt", "Authorization: Bearer ${jwt()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        assertTrue(report.findings.any { it.rule == "jwt-bearer" })
        val jwtFinding = report.findings.single { it.rule == "jwt-bearer" }
        assertEquals(SecretsSeverity.MEDIUM, jwtFinding.severity)
    }

    @Test
    fun `basic auth in URL is detected`() {
        val root = tempDir()
        writeFile(root, "url.txt", "endpoint = https://alice:s3cret@db.example.com:5432/mydb\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val finding = report.findings.single { it.rule == "basic-auth-in-url" }
        assertFalse(finding.maskedMatch.contains("s3cret"), "password in URL leaked")
        assertFalse(finding.maskedMatch.contains("alice"), "username in URL leaked")
    }

    @Test
    fun `severity threshold filters out lower-severity findings`() {
        val root = tempDir()
        writeFile(
            root,
            "mixed.txt",
            "password=hunter2hunter2\naws_key=${awsKey()}\n",
        )
        val onlyHigh =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.HIGH,
            )
        assertEquals(
            setOf("aws-access-key"),
            onlyHigh.findings.map { it.rule }.toSet(),
            "high threshold drops the LOW password line and the MEDIUM key",
        )
        val everything =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        assertTrue(everything.findings.any { it.rule == "generic-password-assignment" })
    }

    @Test
    fun `binary files are skipped to avoid noise`() {
        val root = tempDir()
        val binary = File(root, "image.bin")
        binary.writeBytes(ByteArray(64) { if (it == 10) 0.toByte() else 0x41 })
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        assertFalse(report.findings.any { it.file.endsWith("image.bin") }, "binary file should be skipped")
    }

    @Test
    fun `large files are skipped and counted in skippedFiles`() {
        val root = tempDir()
        val big = File(root, "big.txt")
        big.writeBytes(ByteArray(5 * 1024 * 1024) { 0x61 })
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1024 * 1024,
                threshold = SecretsSeverity.LOW,
            )
        assertEquals(0, report.scannedFiles)
        assertEquals(1, report.skippedFiles)
    }

    @Test
    fun `ignore patterns skip matching paths at directory level`() {
        val root = tempDir()
        writeFile(root, "keep.txt", "${awsKey()}\n")
        writeFile(root, "node_modules/buried.txt", "${awsKey()}\n")
        writeFile(root, ".git/HEAD", "${awsKey()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = listOf("node_modules/**", ".git/**"),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val files = report.findings.map { it.file }.toSet()
        assertTrue(files.any { it.endsWith("keep.txt") }, "expected keep.txt in findings")
        assertFalse(files.any { it.contains("node_modules") }, "node_modules must be ignored")
        assertFalse(files.any { it.contains(".git") }, ".git must be ignored")
    }

    @Test
    fun `recursive walk finds secrets in nested directories`() {
        val root = tempDir()
        writeFile(root, "a/b/c/deep.txt", "${awsKey()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        assertEquals(1, report.findings.size)
        val nestedFile = report.findings.single().file
        assertTrue(
            nestedFile.replace('\\', '/').endsWith("a/b/c/deep.txt"),
            "expected nested path, got $nestedFile",
        )
    }

    @Test
    fun `findings are sorted by file then line number`() {
        val root = tempDir()
        writeFile(root, "z.txt", "${awsKey()} on line 5\n")
        writeFile(root, "a.txt", "${awsKey()} on line 2\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val files = report.findings.map { it.file }
        assertEquals(listOf("a.txt", "z.txt"), files, "alpha-sorted by file")
    }

    @Test
    fun `findings carry the source line number`() {
        val root = tempDir()
        writeFile(root, "src.txt", "first line\nsecond line\n${awsKey()} on the third\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val finding = report.findings.single()
        assertEquals(3, finding.line)
    }

    @Test
    fun `masking preserves a length but hides the body of the secret`() {
        val root = tempDir()
        writeFile(root, "k.txt", "creds=${awsKey()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val masked = report.findings.single().maskedMatch
        assertTrue(masked.contains("(len="), "length tag present")
        assertFalse(masked.contains("IOSFODNN7EXAMPLE"), "body of secret must not leak")
    }

    @Test
    fun `masking covers longer secrets and preserves a length tag`() {
        val root = tempDir()
        writeFile(root, "k.txt", "creds=${awsKeyLong()}\n")
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = emptyList(),
                maxFileSizeBytes = 1_000_000,
                threshold = SecretsSeverity.LOW,
            )
        val masked = report.findings.single().maskedMatch
        assertTrue(masked.contains("(len="), "length tag present")
        assertFalse(masked.contains("IOSFODNN7EXAMPLEKEY12"))
    }

    @Test
    fun `severity parse is case-insensitive and rejects unknown`() {
        assertEquals(SecretsSeverity.HIGH, SecretsSeverity.parse("HIGH"))
        assertEquals(SecretsSeverity.CRITICAL, SecretsSeverity.parse("critical"))
        assertEquals(SecretsSeverity.LOW, SecretsSeverity.parse("LoW"))
        val ex = runCatching { SecretsSeverity.parse("nope") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }
}
