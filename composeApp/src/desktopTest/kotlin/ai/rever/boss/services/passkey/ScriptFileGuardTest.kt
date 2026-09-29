package ai.rever.boss.services.passkey

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the script-name boundary of the passkey executors. A name like
 * `../evil.swift` must be refused before any process starts — the refusal is
 * pure name validation, so neither a script directory nor a `swift`/
 * `powershell` binary needs to exist for it to fire.
 */
class ScriptFileGuardTest {
    @Test
    fun `executeSwiftFile refuses a traversal name before touching the filesystem`() {
        // getSwiftFilesDirectory() throws IllegalStateException when no script
        // directory exists; an IllegalArgumentException here proves the name
        // check ran first, ahead of any directory resolution or process start.
        assertFailsWith<IllegalArgumentException> {
            SwiftScriptExecutor.executeSwiftFile("../evil.swift")
        }
        assertFailsWith<IllegalArgumentException> {
            SwiftScriptExecutor.executeSwiftFile("subdir/evil.swift")
        }
        assertFailsWith<IllegalArgumentException> {
            SwiftScriptExecutor.executeSwiftFile("..\\evil.swift")
        }
    }

    @Test
    fun `executePowerShellScript refuses a traversal name before resolving its directory`() {
        assertFailsWith<IllegalArgumentException> {
            PowerShellExecutor.executePowerShellScript("../evil.ps1")
        }
        assertFailsWith<IllegalArgumentException> {
            PowerShellExecutor.executePowerShellScript("a..b.ps1")
        }
    }

    @Test
    fun `requireSimpleName accepts plain script names`() {
        ScriptFileGuard.requireSimpleName("auth.swift")
        ScriptFileGuard.requireSimpleName("hello-world_v2.ps1")
        ScriptFileGuard.requireSimpleName("Check.swift")
    }

    @Test
    fun `requireSimpleName rejects separators traversal and empty names`() {
        val badNames =
            listOf("..", "../x.swift", "a/b.swift", "a\\b.swift", "..x.swift", "", "with space.swift", "/abs.swift")
        for (name in badNames) {
            assertFailsWith<IllegalArgumentException>("expected refusal for '$name'") {
                ScriptFileGuard.requireSimpleName(name)
            }
        }
    }

    @Test
    fun `resolveInside returns a file under the script directory`() {
        val dir = Files.createTempDirectory("script-guard").toFile()
        try {
            val resolved = ScriptFileGuard.resolveInside(dir, "ok.swift")
            val expected =
                dir
                    .toPath()
                    .toRealPath()
                    .resolve("ok.swift")
                    .toFile()
            assertEquals(expected, resolved)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolveInside refuses a symlink that escapes the script directory`() {
        val root = Files.createTempDirectory("script-guard-root").toFile()
        val scriptsDir = File(root, "scripts").apply { mkdirs() }
        val outside = File(root, "outside.swift").apply { writeText("print(1)") }
        try {
            // Windows without SeCreateSymbolicLinkPrivilege cannot create a
            // symlink; nothing left to prove on such a host.
            runCatching {
                Files.createSymbolicLink(File(scriptsDir, "link.swift").toPath(), outside.toPath())
            }.getOrElse { return }
            ScriptFileGuard.requireSimpleName("link.swift")
            assertFailsWith<IllegalArgumentException> {
                ScriptFileGuard.resolveInside(scriptsDir, "link.swift")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveInside keeps a legitimately contained file`() {
        val dir = Files.createTempDirectory("script-guard").toFile()
        try {
            File(dir, "real.swift").writeText("print(1)")
            val resolved = ScriptFileGuard.resolveInside(dir, "real.swift")
            assertTrue(resolved.toPath().startsWith(dir.toPath().toRealPath()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
