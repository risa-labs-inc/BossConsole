package ai.rever.boss.updater

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

class UpdateInstallerPathResolutionTest {
    @Test
    fun `non-translocated app path is returned without filesystem lookup`() {
        val path = "/Applications/BOSS.app"

        val resolvedPath =
            realAppPathFor(
                path = path,
                appExists = { fail("Non-translocated paths must not query the filesystem") },
                installedAppLookup = { fail("Non-translocated paths must not query Spotlight") },
            )

        assertEquals(path, resolvedPath)
    }

    @Test
    fun `translocated path resolves bundle name in Applications`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/BOSS Preview.app/Contents/MacOS/BOSS"
        val expectedPath = "/Applications/BOSS Preview.app"

        val resolvedPath =
            realAppPathFor(
                path = path,
                appExists = { it == expectedPath },
                installedAppLookup = { fail("Applications path should be preferred over Spotlight") },
            )

        assertEquals(expectedPath, resolvedPath)
    }

    @Test
    fun `bundle suffix is matched at path segment boundary`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/My.application.app/Contents/MacOS/App"
        val expectedPath = "/Applications/My.application.app"

        val resolvedPath =
            realAppPathFor(
                path = path,
                appExists = { it == expectedPath },
                installedAppLookup = { fail("Applications path should be preferred over Spotlight") },
            )

        assertEquals(expectedPath, resolvedPath)
    }

    @Test
    fun `library path preserves app substring inside bundle name`() {
        val libraryPath =
            listOf(
                "/usr/local/lib",
                "/Applications/My.application.app/Contents/runtime/Contents/Home/lib",
            ).joinToString(File.pathSeparator)

        assertEquals(
            "/Applications/My.application.app",
            macOSAppBundlePathFromLibraryPath(libraryPath),
        )
    }

    @Test
    fun `translocated path falls back to installed app lookup`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/BOSS.app"
        val spotlightPath = "/Users/test/Applications/BOSS.app"

        val resolvedPath =
            realAppPathFor(
                path = path,
                appExists = { it == spotlightPath },
                installedAppLookup = { spotlightPath },
            )

        assertEquals(spotlightPath, resolvedPath)
    }

    @Test
    fun `unresolved translocated path is preserved`() {
        val path = "/private/var/folders/xx/T/AppTranslocation/uuid/d/BOSS.app"

        val resolvedPath =
            realAppPathFor(
                path = path,
                appExists = { false },
                installedAppLookup = { null },
            )

        assertEquals(path, resolvedPath)
    }
}
