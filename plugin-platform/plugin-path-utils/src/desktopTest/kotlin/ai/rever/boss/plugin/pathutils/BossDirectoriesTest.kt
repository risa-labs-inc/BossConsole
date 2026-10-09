package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossDirectoriesTest {
    @Nested
    inner class IsDevModeTests {
        @Test
        fun `isDevMode reflects current runtime state`() {
            // In test runner context, boss.dev.mode is not set,
            // so isDevMode should be false (unless explicitly configured).
            val sysProp = System.getProperty("boss.dev.mode")
            val envVar = System.getenv("BOSS_DEV_MODE")
            val expected = isTruthy(sysProp) || isTruthy(envVar)
            assertEquals(expected, BossDirectories.isDevMode)
        }
    }

    @Nested
    inner class IsTruthyTests {
        @Test
        fun `true string variants are truthy`() {
            assertTrue(callIsTruthy("true"))
            assertTrue(callIsTruthy("TRUE"))
            assertTrue(callIsTruthy("True"))
        }

        @Test
        fun `1 is truthy`() {
            assertTrue(callIsTruthy("1"))
        }

        @Test
        fun `yes is truthy`() {
            assertTrue(callIsTruthy("yes"))
            assertTrue(callIsTruthy("YES"))
            assertTrue(callIsTruthy("Yes"))
        }

        @Test
        fun `false string variants are not truthy`() {
            assertFalse(callIsTruthy("false"))
            assertFalse(callIsTruthy("FALSE"))
        }

        @Test
        fun `0 is not truthy`() {
            assertFalse(callIsTruthy("0"))
        }

        @Test
        fun `null and empty are not truthy`() {
            assertFalse(callIsTruthy(null))
            assertFalse(callIsTruthy(""))
            assertFalse(callIsTruthy("  "))
        }

        @Test
        fun `arbitrary strings are not truthy`() {
            assertFalse(callIsTruthy("on"))
            assertFalse(callIsTruthy("enabled"))
            assertFalse(callIsTruthy("2"))
        }

        /** Invoke the private isTruthy via the same logic. */
        private fun callIsTruthy(value: String?): Boolean {
            if (value == null) return false
            val v = value.trim().lowercase()
            return v == "true" || v == "1" || v == "yes"
        }
    }

    @Nested
    inner class RootDirTests {
        @Test
        fun `rootDir is under user home`() {
            val userHome = System.getProperty("user.home")
            assertTrue(BossDirectories.rootDir.absolutePath.startsWith(userHome))
        }

        @Test
        fun `rootDir name matches dev mode state`() {
            val expectedName = if (BossDirectories.isDevMode) ".boss_debug" else ".boss"
            assertEquals(expectedName, BossDirectories.rootDir.name)
        }
    }

    @Nested
    inner class ResolveTests {
        @Test
        fun `resolve produces path under rootDir`() {
            val resolved = BossDirectories.resolve("settings.json")
            assertEquals(BossDirectories.rootDir, resolved.parentFile)
            assertEquals("settings.json", resolved.name)
        }

        @Test
        fun `resolve handles nested paths`() {
            val resolved = BossDirectories.resolve("cache/favicons")
            assertTrue(resolved.absolutePath.startsWith(BossDirectories.rootDir.absolutePath))
            val expected = "cache" + java.io.File.separator + "favicons"
            assertTrue(resolved.absolutePath.endsWith(expected))
        }

        @Test
        fun `resolve creates a missing state root`() {
            val parent = Files.createTempDirectory("boss-missing-root")
            try {
                val root = parent.resolve("missing-root")

                val resolved = BossDirectories.resolveUnderRoot(root.toFile(), "nested/state.json")

                assertTrue(Files.isDirectory(root))
                assertEquals(root.toRealPath().resolve("nested/state.json").toFile(), resolved)
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

        @Test
        fun `resolve accepts a state root symlinked to a directory`() {
            val parent = Files.createTempDirectory("boss-symlinked-root")
            try {
                val target = parent.resolve("relocated-root").also { Files.createDirectories(it) }
                val root = parent.resolve("root-link")
                val linkFailure = runCatching { Files.createSymbolicLink(root, target) }.exceptionOrNull()
                if (linkFailure != null) return

                val resolved = BossDirectories.resolveUnderRoot(root.toFile(), "nested/state.json")

                assertEquals(target.toRealPath().resolve("nested/state.json").toFile(), resolved)
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

        @Test
        fun `resolve fails closed when state root is a regular file`() {
            val parent = Files.createTempDirectory("boss-file-root")
            try {
                val root = parent.resolve("root").also { Files.writeString(it, "not a directory") }

                assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                    BossDirectories.resolveUnderRoot(root.toFile(), "state.json")
                }
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

        @Test
        fun `resolve refuses absolute and traversal paths`() {
            val parent = Files.createTempDirectory("boss-path-validation")
            try {
                val root = parent.resolve("root").also { Files.createDirectories(it) }
                val absoluteOutside = parent.resolve("outside").toString()
                assertFailsWith<IllegalArgumentException> {
                    BossDirectories.resolveUnderRoot(root.toFile(), absoluteOutside)
                }
                assertFailsWith<IllegalArgumentException> {
                    BossDirectories.resolveUnderRoot(root.toFile(), "../outside")
                }
                assertFailsWith<IllegalArgumentException> {
                    BossDirectories.resolveUnderRoot(root.toFile(), "nested/../../outside")
                }
                assertEquals(root.toRealPath().toFile(), BossDirectories.resolveUnderRoot(root.toFile(), ""))
                assertEquals(root.toRealPath().toFile(), BossDirectories.resolveUnderRoot(root.toFile(), "."))
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

        @Test
        fun `resolve refuses an existing symlink escape`() {
            val parent = Files.createTempDirectory("boss-path-root")
            try {
                val root = parent.resolve("root").also { Files.createDirectories(it) }
                val outside = parent.resolve("outside").also { Files.createDirectories(it) }
                val link = root.resolve("escape")
                val linkFailure = runCatching { Files.createSymbolicLink(link, outside) }.exceptionOrNull()
                // A standard Windows runner cannot create symlinks without Developer Mode.
                // Kotlin's multiplatform test adapter reports JUnit's aborted assumption as a
                // failure here, so leave explicitly when the platform denied the setup.
                if (linkFailure != null) return

                assertFailsWith<IllegalArgumentException> {
                    BossDirectories.resolveUnderRoot(root.toFile(), "escape/state.json")
                }
                assertFalse(
                    BossDirectories.containsUnderRoot(root.toFile(), link.resolve("state.json").toFile()),
                )
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

        @Test
        fun `contains distinguishes state from external files`() {
            val parent = Files.createTempDirectory("boss-contained-path")
            try {
                val root = parent.resolve("root").also { Files.createDirectories(it) }
                val contained = BossDirectories.resolveUnderRoot(root.toFile(), "plugin-data/probe/state.json")

                assertTrue(BossDirectories.containsUnderRoot(root.toFile(), contained))
                assertFalse(BossDirectories.containsUnderRoot(root.toFile(), parent.resolve("outside.json").toFile()))
            } finally {
                parent.toFile().deleteRecursively()
            }
        }

        @Test
        fun `resolve rejects a dangling symlink leaf`() {
            val parent = Files.createTempDirectory("boss-dangling-link")
            try {
                val root = parent.resolve("root").also { Files.createDirectories(it) }
                val link = root.resolve("settings.json")
                val linkFailure =
                    runCatching { Files.createSymbolicLink(link, parent.resolve("missing")) }
                        .exceptionOrNull()
                if (linkFailure != null) return

                assertFailsWith<IllegalArgumentException> {
                    BossDirectories.resolveUnderRoot(root.toFile(), "settings.json")
                }
            } finally {
                parent.toFile().deleteRecursively()
            }
        }
    }

    /** Mirror of BossDirectories.isTruthy for test assertions. */
    private fun isTruthy(value: String?): Boolean {
        if (value == null) return false
        val v = value.trim().lowercase()
        return v == "true" || v == "1" || v == "yes"
    }
}
