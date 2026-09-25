package ai.rever.boss.plugin.pathutils

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Paths are built under the platform's own temp root and never created: existence is
 * injected, so every platform's rules are exercised on whichever OS runs the tests.
 */
class DownloadsDirectoryTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "boss-downloads-dir-test")
    private val home = File(root, "home").absolutePath
    private val conventional = File(home, "Downloads").absolutePath

    private fun inputs(
        osName: String,
        userDirsConfig: String? = null,
        windowsKnownFolder: String? = null,
        existing: Set<String> = emptySet(),
    ) = DownloadsDirectory.Inputs(
        osName = osName,
        userHome = home,
        userDirsConfig = userDirsConfig,
        windowsKnownFolder = windowsKnownFolder,
        isDirectory = { it in existing },
    )

    private fun under(vararg segments: String): String {
        var file = File(home)
        segments.forEach { file = File(file, it) }
        return file.absolutePath
    }

    @Nested
    inner class Windows {
        @Test
        fun `the known folder wins when it is a real directory`() {
            val moved = File(root, "moved-downloads").absolutePath

            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Windows 11", windowsKnownFolder = moved, existing = setOf(moved, conventional)),
                )

            assertEquals(moved, resolved)
        }

        @Test
        fun `a known folder that does not exist is ignored`() {
            val stale = File(root, "deleted-downloads").absolutePath

            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Windows 11", windowsKnownFolder = stale, existing = setOf(conventional)),
                )

            assertEquals(conventional, resolved)
        }

        @Test
        fun `a blank known folder falls back to the profile Downloads folder`() {
            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Windows 11", windowsKnownFolder = "   ", existing = setOf(conventional)),
                )

            assertEquals(conventional, resolved)
        }

        @Test
        fun `an unavailable known folder falls back to the profile Downloads folder`() {
            val resolved = DownloadsDirectory.resolve(inputs("Windows 11", existing = setOf(conventional)))

            assertEquals(conventional, resolved)
        }
    }

    @Nested
    inner class Linux {
        @Test
        fun `a localised XDG download dir written against HOME is used`() {
            val localised = under("Téléchargements")

            val resolved =
                DownloadsDirectory.resolve(
                    inputs(
                        "Linux",
                        userDirsConfig = "XDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"",
                        existing = setOf(localised),
                    ),
                )

            assertEquals(localised, resolved)
        }

        @Test
        fun `the braced HOME form is expanded too`() {
            val localised = under("Descargas")

            val resolved =
                DownloadsDirectory.resolve(
                    inputs(
                        "Linux",
                        userDirsConfig = "XDG_DOWNLOAD_DIR=\"\${HOME}/Descargas\"",
                        existing = setOf(localised),
                    ),
                )

            assertEquals(localised, resolved)
        }

        @Test
        fun `an absolute XDG download dir is used as written`() {
            val elsewhere = File(root, "data/downloads").absolutePath

            val resolved =
                DownloadsDirectory.resolve(
                    inputs(
                        "Linux",
                        userDirsConfig = "XDG_DOWNLOAD_DIR=\"$elsewhere\"",
                        existing = setOf(elsewhere),
                    ),
                )

            assertEquals(elsewhere, resolved)
        }

        @Test
        fun `commented and unrelated entries are ignored`() {
            val config =
                """
                # XDG_DOWNLOAD_DIR="${'$'}HOME/Commented"
                XDG_MUSIC_DIR="${'$'}HOME/Music"
                """.trimIndent()

            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Linux", userDirsConfig = config, existing = setOf(conventional, under("Commented"))),
                )

            assertEquals(conventional, resolved)
        }

        @Test
        fun `the last assignment wins`() {
            val second = under("Second")
            val config =
                """
                XDG_DOWNLOAD_DIR="${'$'}HOME/First"
                XDG_DOWNLOAD_DIR="${'$'}HOME/Second"
                """.trimIndent()

            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Linux", userDirsConfig = config, existing = setOf(under("First"), second)),
                )

            assertEquals(second, resolved)
        }

        @Test
        fun `a blank last assignment disables the entry instead of reviving an earlier one`() {
            // xdg-user-dirs records a disabled directory as an empty value.
            val config =
                """
                XDG_DOWNLOAD_DIR="${'$'}HOME/First"
                XDG_DOWNLOAD_DIR=""
                """.trimIndent()

            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Linux", userDirsConfig = config, existing = setOf(conventional, under("First"))),
                )

            assertEquals(conventional, resolved)
        }

        @Test
        fun `an XDG download dir naming the home folder itself falls back`() {
            val resolved =
                DownloadsDirectory.resolve(
                    inputs(
                        "Linux",
                        userDirsConfig = "XDG_DOWNLOAD_DIR=\"\$HOME/\"",
                        existing = setOf(conventional, home),
                    ),
                )

            assertEquals(conventional, resolved)
        }

        @Test
        fun `dot segments that lead back to the home folder fall back too`() {
            listOf("\$HOME/.", "\$HOME/sub/..").forEach { value ->
                // A real filesystem reports these as existing directories: they are home.
                val asWritten = File(value.replace("\$HOME", home)).absolutePath
                val resolved =
                    DownloadsDirectory.resolve(
                        inputs(
                            "Linux",
                            userDirsConfig = "XDG_DOWNLOAD_DIR=\"$value\"",
                            existing = setOf(conventional, home, asWritten),
                        ),
                    )

                assertEquals(conventional, resolved, "for XDG_DOWNLOAD_DIR=\"$value\"")
            }
        }

        @Test
        fun `a relative XDG download dir is read against home, not the working directory`() {
            val relative = under("Relative")
            val resolved =
                DownloadsDirectory.resolve(
                    inputs("Linux", userDirsConfig = "XDG_DOWNLOAD_DIR=\"Relative\"", existing = setOf(relative)),
                )

            assertEquals(relative, resolved)
        }

        @Test
        fun `an XDG download dir that no longer exists falls back`() {
            val resolved =
                DownloadsDirectory.resolve(
                    inputs(
                        "Linux",
                        userDirsConfig = "XDG_DOWNLOAD_DIR=\"\$HOME/Gone\"",
                        existing = setOf(conventional),
                    ),
                )

            assertEquals(conventional, resolved)
        }

        @Test
        fun `no config at all falls back`() {
            val resolved = DownloadsDirectory.resolve(inputs("Linux", existing = setOf(conventional)))

            assertEquals(conventional, resolved)
        }
    }

    @Nested
    inner class EveryPlatform {
        @Test
        fun `macOS uses the home Downloads folder`() {
            val resolved = DownloadsDirectory.resolve(inputs("Mac OS X", existing = setOf(conventional)))

            assertEquals(conventional, resolved)
        }

        @Test
        fun `a missing Downloads folder resolves to the conventional path, not the home folder`() {
            // The in-process provider used to hand back the home folder here, so a plugin
            // saving a file dropped it loose in the user's home directory. Both write paths
            // create missing parents, so naming the conventional folder is safe.
            val resolved = DownloadsDirectory.resolve(inputs("Windows 11", existing = emptySet()))

            assertEquals(conventional, resolved)
            assertNotEquals(home, resolved)
        }

        @Test
        fun `the resolved path carries platform separators`() {
            // The out-of-process proxy concatenated user.home + "/Downloads", which on Windows
            // produced C:\Users\someone/Downloads and compared unequal to every host-built path.
            val resolved = DownloadsDirectory.resolve(inputs("Windows 11", existing = setOf(conventional)))

            assertEquals(resolved, File(resolved).absolutePath)
            if (File.separatorChar == '\\') {
                assertFalse(resolved.contains('/'), "expected no forward slashes in $resolved")
            }
        }
    }
}
